/*
 * Copyright (c) 2022-2025 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.asg.console.extension.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.alibaba.higress.sdk.constant.CommonKey;
import com.alibaba.higress.sdk.constant.HigressConstants;
import com.alibaba.higress.sdk.constant.plugin.BuiltInPluginName;
import com.alibaba.higress.sdk.exception.BusinessException;
import com.alibaba.higress.sdk.exception.ResourceConflictException;
import com.alibaba.higress.sdk.model.CommonPageQuery;
import com.alibaba.higress.sdk.model.PaginatedResult;
import com.asg.console.extension.model.AiConsumerUsage;
import com.asg.console.extension.model.AiConsumerUsageReport;
import com.asg.console.extension.model.AiShadowActionRequest;
import com.asg.console.extension.model.AiShadowAuthorizedDomain;
import com.asg.console.extension.model.AiShadowBatchActionRequest;
import com.asg.console.extension.model.AiShadowDetectedAccess;
import com.asg.console.extension.model.AiShadowDnsPolicy;
import com.asg.console.extension.model.AiShadowModeRequest;
import com.asg.console.extension.model.AiShadowStatus;
import com.asg.console.extension.repository.AiShadowAuthorizedDomainRepository;
import com.asg.console.extension.repository.AiShadowDetectEventRepository;
import com.alibaba.higress.sdk.model.WasmPluginInstance;
import com.alibaba.higress.sdk.model.WasmPluginInstanceScope;
import com.alibaba.higress.sdk.model.ai.AiRoute;
import com.alibaba.higress.sdk.model.consumer.AllowList;
import com.alibaba.higress.sdk.model.consumer.AllowListOperation;
import com.alibaba.higress.sdk.service.WasmPluginInstanceService;
import com.asg.console.extension.constant.AsgPluginConstants;
import com.alibaba.higress.sdk.service.ai.AiRouteService;
import com.alibaba.higress.sdk.model.consumer.Consumer;
import com.alibaba.higress.sdk.service.consumer.ConsumerService;
import com.alibaba.higress.sdk.util.MapUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.asg.console.extension.context.HttpContext;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AiShadowServiceImpl implements AiShadowService {

    private static final String MODE_MONITORING = "monitoring";
    private static final String MODE_ENFORCEMENT = "enforcement";
    private static final int SET_ENABLED_MAX_RETRY = 5;
    private static final String ACTION_AUTHORIZE = "authorize";
    private static final String ACTION_BLOCK = "block";
    private static final String CONSUMER_NONE = "none";

    /**
     * 默认拒绝的占位项。
     * <p>
     * key-auth 在 allow 为空时会提前 return（main.go:311-317、:355-358），连 no_key 都不上报，
     * 等于插件在该路由完全不介入——既无身份也不产生事件。写入这个永不匹配的名字让 allow 非空，
     * 插件就会真正进入判定流程；由于没有真实消费者叫这个名字，效果正是「默认拒绝」。
     * 它不是调用方，展示层必须过滤。
     */
    private static final String DEFAULT_DENY_SENTINEL = "__asg_default_deny__";

    private static final String PROMETHEUS_QUERY_PATH = "/api/v1/query";
    private static final String INPUT_TOKEN_METRIC = "route_upstream_model_consumer_metric_input_token";
    private static final String OUTPUT_TOKEN_METRIC = "route_upstream_model_consumer_metric_output_token";
    private static final String AI_SHADOW_DETECT_METRIC_PREFIX = "ai_shadow_detect_category_";

    /**
     * 请求数指标。histogram 的 {@code _count} 等于被计量到的调用次数——这是 Prometheus 里
     * 唯一能当「请求数」用的指标。曾经用「token>0 ? 1 : 0」冒充它，那是把「一次调用」
     * 和「一次调用里有 token」混为一谈，数字恒定偏小，所以那个字段必须从真指标取，而不是删掉。
     */
    private static final String LLM_REQUEST_METRIC = "route_upstream_model_consumer_metric_llm_duration_count";

    /** 事件表里拦截/放行的两种处置值。 */
    private static final String STATUS_BLOCKED = "blocked";
    private static final String STATUS_MONITORED = "monitored";

    /** 准入拒绝原因的前缀，后缀是被拒的消费者名（见 key-auth/main.go 的 reason 构造）。 */
    private static final String REASON_NOT_IN_ALLOW_PREFIX = "not_in_allow:";
    private static final String REASON_NO_KEY = "no_key";
    private static final String REASON_UNKNOWN_KEY = "unknown_key";

    /**
     * Stable legend ordering for trend series. 事件表还会带 alerted / masked，
     * 漏在列表外会被 indexOf=-1 推到最末，图例看起来像事后追加的。
     */
    private static final List<String> TREND_STATUS_ORDER =
        Arrays.asList("blocked", "alerted", "monitored", "masked", "allowed");

    /** Default lookback when the caller supplies no window (kept at the historical 24h). */
    private static final long DEFAULT_TREND_WINDOW_MS = 24L * 3600L * 1000L;
    /** Hard cap on the lookback so a bad caller cannot trigger a multi-year scan. */
    private static final long MAX_TREND_WINDOW_MS = 31L * 86400L * 1000L;
    /** Spans up to two days stay on hourly buckets; wider spans switch to daily buckets. */
    private static final long HOURLY_MAX_SPAN_MS = 2L * 86400L * 1000L;
    private static final String GRANULARITY_HOUR = "hour";
    private static final String GRANULARITY_DAY = "day";

    /** Fixed virtual session holding all shadow AI admin (authorization) operations. */
    private static final String ADMIN_OPS_SESSION_ID = "admin_ops";
    /** Record type tag for admin operations, queryable via the audit chain recordType filter. */
    private static final String ADMIN_OPS_RECORD_TYPE = "admin_operation";
    /** Fallback operator when the console request carries no resolvable identity. */
    private static final String OPERATOR_CONSOLE = "console";
    /** 授权域名的匹配方式：当前实现对子域采用后缀匹配，前端据此提示"含子域"。 */
    private static final String MATCH_TYPE_SUFFIX = "suffix";
    /** 授权来源：控制台手动添加 / 从检测结果一键授权 / 策略中已存在但无元数据。 */
    private static final String SOURCE_MANUAL = "manual";
    private static final String SOURCE_DETECTED = "detected";
    private static final String SOURCE_IMPORTED = "imported";

    private final WasmPluginInstanceService wasmPluginInstanceService;
    private final ConsumerService consumerService;
    private final AiRouteService aiRouteService;
    private final String prometheusBaseUrl;
    private final AuditChainService auditChainService;
    private final AiShadowDnsPolicyService dnsPolicyService;
    private final AiShadowDetectEventRepository detectEventRepository;
    private final AiShadowAuthorizedDomainRepository authorizedDomainRepository;

    public AiShadowServiceImpl(WasmPluginInstanceService wasmPluginInstanceService,
        ConsumerService consumerService, AiRouteService aiRouteService, String prometheusBaseUrl,
        AuditChainService auditChainService, AiShadowDnsPolicyService dnsPolicyService,
        AiShadowDetectEventRepository detectEventRepository,
        AiShadowAuthorizedDomainRepository authorizedDomainRepository) {
        this.wasmPluginInstanceService = wasmPluginInstanceService;
        this.consumerService = consumerService;
        this.aiRouteService = aiRouteService;
        this.prometheusBaseUrl = StringUtils.defaultIfBlank(prometheusBaseUrl,
            "http://higress-console-prometheus.higress-system:9090/prometheus");
        this.auditChainService = auditChainService;
        this.dnsPolicyService = dnsPolicyService;
        this.detectEventRepository = detectEventRepository;
        this.authorizedDomainRepository = authorizedDomainRepository;
    }

    @Override
    public final List<AiShadowStatus> getStatus(boolean includeMetrics) {
        // includeMetrics 已无作用：aiShadowList 不再填充，用量改由 getConsumerUsage 提供。
        // 保留参数只为不破坏既有调用方签名。
        PaginatedResult<AiRoute> aiRoutes = aiRouteService.list(null);
        if (aiRoutes == null || CollectionUtils.isEmpty(aiRoutes.getData())) {
            return Collections.emptyList();
        }

        List<AiShadowStatus> result = new ArrayList<>();
        for (AiRoute aiRoute : aiRoutes.getData()) {
            String routeResourceName = buildRouteResourceName(aiRoute.getName());
            try {
                WasmPluginInstance guard = ensureGuard(routeResourceName);
                result.add(buildAiShadowStatus(aiRoute.getName(), routeResourceName, guard));
            } catch (Exception e) {
                log.error("Error building shadow AI status for route: {}", aiRoute.getName(), e);
                result.add(AiShadowStatus.builder()
                    .routeName(aiRoute.getName())
                    .mode(MODE_MONITORING)
                    .authEnabled(false)
                    .managed(false)
                    .defaultDeny(true)
                    .authorizedConsumers(Collections.emptyList())
                    .build());
            }
        }
        return result;
    }

    @Override
    public final AiShadowStatus getStatus(String routeName) {
        AiRoute aiRoute = aiRouteService.query(routeName);
        if (aiRoute == null) {
            return null;
        }

        String routeResourceName = buildRouteResourceName(routeName);
        return buildAiShadowStatus(routeName, routeResourceName, ensureGuard(routeResourceName));
    }

    /**
     * 消费者用量与拦截画像。用「消费者」作为唯一对齐键把两套数据拼起来：
     * Prometheus 给 ai_consumer，事件表给 reason。
     * <p>
     * 不遍历 {@code aiRouteService.list()}：真实流量走的是普通 Ingress（实测 demo-llm-route
     * 有 384+124 次调用），根本不在 AI Route 列表里，遍历列表会让页面只看到 AI Route 上的
     * 测试流量——那正是「这页面只有 demo-llm 有数据」的真正原因。Prometheus 的 ai_route
     * 标签本身就是 Ingress 资源名，直接拿它查允许名单即可。
     */
    @Override
    public final AiConsumerUsageReport getConsumerUsage(Long startTime, Long endTime) {
        // 复用既有窗口解析：null/<=0 走默认、上限 31 天、end 不越过当前时间。
        // 自己再写一套的话，同目录两个接口对同一个 startTime=0 会给出不同答案。
        long[] window = resolveWindow(startTime, endTime);
        long start = window[0];
        long end = window[1];

        Map<String, ConsumerAggregate> aggregates = new LinkedHashMap<>();
        boolean usageAvailable = pingPrometheus();

        if (usageAvailable) {
            for (PrometheusMetricData data : queryMetric(LLM_REQUEST_METRIC).values()) {
                aggregateOf(aggregates, data).requestCount += data.requestCount;
            }
            for (PrometheusMetricData data : queryMetric(INPUT_TOKEN_METRIC).values()) {
                aggregateOf(aggregates, data).inputTokens += data.inputTokens;
            }
            for (PrometheusMetricData data : queryMetric(OUTPUT_TOKEN_METRIC).values()) {
                aggregateOf(aggregates, data).outputTokens += data.outputTokens;
            }
        } else {
            log.warn("Prometheus unreachable at {}, consumer usage will be reported as unavailable",
                prometheusBaseUrl);
        }

        // 被拦的请求不产生任何计量，只存在于事件表。所以这里必须能「补行」：
        // 一个人只在事件里出现、不在 Prometheus 里出现，恰恰说明他每一次都被拦住了。
        for (Object[] row : detectEventRepository.countAdmissionByReason(start / 1000L, end / 1000L)) {
            String reason = row[0] == null ? null : String.valueOf(row[0]);
            String status = row[1] == null ? null : String.valueOf(row[1]);
            long count = row[2] == null ? 0L : ((Number)row[2]).longValue();
            String consumer = consumerFromReason(reason);
            if (consumer == null || count <= 0) {
                continue;
            }
            ConsumerAggregate aggregate = aggregates.get(consumer);
            if (aggregate == null) {
                aggregate = newConsumerAggregate(consumer);
                aggregates.put(consumer, aggregate);
            }
            if (STATUS_BLOCKED.equals(status)) {
                aggregate.blockedCount += count;
            } else if (STATUS_MONITORED.equals(status)) {
                aggregate.monitoredCount += count;
            }
        }

        // 授权是逐路由判定的：实测王五在 demo-llm-route 的允许名单里，却不在
        // ai-route-demo-llm.internal 的名单里，而后者是监控模式，他照样被放行。
        // 把「调用过但没被授权」的路由列出来，这就是可执行的越权证据。
        Map<String, List<String>> allowListCache = new HashMap<>();
        List<AiConsumerUsage> consumers = new ArrayList<>();
        long requestTotal = 0L;
        long inputTotal = 0L;
        long outputTotal = 0L;
        long blockedTotal = 0L;
        long monitoredTotal = 0L;
        long unauthenticatedRequests = 0L;
        int identifiedCount = 0;
        int authorizedCount = 0;

        for (ConsumerAggregate aggregate : aggregates.values()) {
            List<String> unauthorizedRoutes = new ArrayList<>();
            for (String route : aggregate.routes) {
                List<String> allowed = allowListCache.computeIfAbsent(route, this::authorizedConsumersOf);
                if (!allowed.contains(aggregate.consumer)) {
                    unauthorizedRoutes.add(route);
                }
            }
            // 三个条件缺一不可：有身份、确实调用过路由（否则没有判定依据）、调用过的每条路由都授权了他。
            // 只在事件表出现的人（每次都被拦、从未放行）routes 为空，不能因为「没查出未授权路由」
            // 就被判成已授权——那会和同一行里 blockedCount>0 直接矛盾。
            boolean authorized = !aggregate.unauthenticated
                && !aggregate.routes.isEmpty()
                && unauthorizedRoutes.isEmpty();

            requestTotal += aggregate.requestCount;
            inputTotal += aggregate.inputTokens;
            outputTotal += aggregate.outputTokens;
            blockedTotal += aggregate.blockedCount;
            monitoredTotal += aggregate.monitoredCount;
            if (aggregate.unauthenticated) {
                unauthenticatedRequests += aggregate.requestCount;
            } else {
                identifiedCount++;
                if (authorized) {
                    authorizedCount++;
                }
            }

            consumers.add(AiConsumerUsage.builder()
                .consumer(aggregate.consumer)
                .unauthenticated(aggregate.unauthenticated)
                .requestCount(aggregate.requestCount)
                .inputTokens(aggregate.inputTokens)
                .outputTokens(aggregate.outputTokens)
                .models(new ArrayList<>(aggregate.models))
                .routes(new ArrayList<>(aggregate.routes))
                .unauthorizedRoutes(unauthorizedRoutes)
                .authorized(authorized)
                .blockedCount(aggregate.blockedCount)
                .monitoredCount(aggregate.monitoredCount)
                .build());
        }

        consumers.sort((left, right) -> Long.compare(
            right.getRequestCount() == null ? 0L : right.getRequestCount(),
            left.getRequestCount() == null ? 0L : left.getRequestCount()));

        return AiConsumerUsageReport.builder()
            .requestCount(requestTotal)
            .inputTokens(inputTotal)
            .outputTokens(outputTotal)
            .totalTokens(inputTotal + outputTotal)
            .consumerCount(identifiedCount)
            .authorizedCount(authorizedCount)
            .unauthenticatedRequestCount(unauthenticatedRequests)
            .blockedCount(blockedTotal)
            .monitoredCount(monitoredTotal)
            .consumers(consumers)
            .usageAvailable(usageAvailable)
            .usageScope("cumulative")
            .eventWindowHours((int)((end - start) / 3600000L))
            .build();
    }

    private ConsumerAggregate aggregateOf(Map<String, ConsumerAggregate> aggregates,
        PrometheusMetricData data) {
        String consumer = StringUtils.isEmpty(data.consumer) ? CONSUMER_NONE : data.consumer;
        ConsumerAggregate aggregate = aggregates.get(consumer);
        if (aggregate == null) {
            aggregate = newConsumerAggregate(consumer);
            aggregates.put(consumer, aggregate);
        }
        if (StringUtils.isNotEmpty(data.route)) {
            aggregate.routes.add(data.route);
        }
        if (StringUtils.isNotEmpty(data.model)) {
            aggregate.models.add(data.model);
        }
        return aggregate;
    }

    private static ConsumerAggregate newConsumerAggregate(String consumer) {
        ConsumerAggregate aggregate = new ConsumerAggregate();
        aggregate.consumer = consumer;
        aggregate.unauthenticated = CONSUMER_NONE.equals(consumer);
        return aggregate;
    }

    /**
     * 把事件表里的 reason 还原成「是谁」。
     * <ul>
     *   <li>{@code not_in_allow:<名>} → 该消费者，被拒的正是他</li>
     *   <li>{@code no_key} / {@code unknown_key} → 没有人可归属，算未认证</li>
     *   <li>其余（如 off_channel 属于绕道面）→ 与消费者无关，返回 null 由调用方跳过</li>
     * </ul>
     */
    private static String consumerFromReason(String reason) {
        if (StringUtils.isEmpty(reason)) {
            return null;
        }
        if (reason.startsWith(REASON_NOT_IN_ALLOW_PREFIX)) {
            String name = reason.substring(REASON_NOT_IN_ALLOW_PREFIX.length());
            return StringUtils.isEmpty(name) ? CONSUMER_NONE : name;
        }
        if (REASON_NO_KEY.equals(reason) || REASON_UNKNOWN_KEY.equals(reason)) {
            return CONSUMER_NONE;
        }
        return null;
    }

    private List<String> authorizedConsumersOf(String route) {
        try {
            return getAuthorizedConsumers(route);
        } catch (Exception e) {
            // 读不到允许名单时退化成「按未授权展示」，不能整页 500：
            // 页面打不开的代价比「个别行的授权状态可能标错」大得多。
            log.warn("Failed to read consumer allow list for route {}: {}", route, e.getMessage());
            return Collections.emptyList();
        }
    }

    private boolean pingPrometheus() {
        HttpURLConnection connection = null;
        try {
            String queryUrl = prometheusBaseUrl + PROMETHEUS_QUERY_PATH + "?query=1";
            connection = (HttpURLConnection)new URL(queryUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            return connection.getResponseCode() == 200;
        } catch (Exception e) {
            log.warn("Prometheus probe failed: {}", e.getMessage());
            return false;
        } finally {
            // 只取状态码不读流，不 disconnect 会把连接留在 keep-alive 池里；
            // 这个接口每 30s 被轮询一次，泄漏会累积。
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Override
    public final AiShadowStatus setMode(AiShadowModeRequest request) {
        String routeName = request.getRouteName();
        String mode = request.getMode();

        if (StringUtils.isEmpty(routeName)) {
            throw new IllegalArgumentException("routeName cannot be empty.");
        }
        if (!MODE_MONITORING.equals(mode) && !MODE_ENFORCEMENT.equals(mode)) {
            throw new IllegalArgumentException("mode must be either 'monitoring' or 'enforcement'.");
        }

        AiRoute aiRoute = aiRouteService.query(routeName);
        if (aiRoute == null) {
            throw new BusinessException("AI route not found: " + routeName);
        }

        String routeResourceName = buildRouteResourceName(routeName);
        boolean enableAuth = MODE_ENFORCEMENT.equals(mode);

        // Keep key-auth enabled in both monitoring and enforcement modes.
        // In monitoring mode, identify_only=true is set so valid consumers are still
        // identified (x-mse-consumer header) but unauthenticated requests are not rejected.
        // In enforcement mode, identify_only=false so unauthenticated requests are rejected.
        AllowList allowList = AllowList.forTarget(WasmPluginInstanceScope.ROUTE, routeResourceName)
            .authEnabled(true)
            .build();

        consumerService.updateAllowList(AllowListOperation.TOGGLE_ONLY, allowList);

        // Set identify_only configuration on the route-level key-auth instance.
        boolean identifyOnly = MODE_MONITORING.equals(mode);
        updateKeyAuthIdentifyOnly(routeResourceName, identifyOnly);

        writeAdminAudit("set_mode", routeName, null, mode);

        return getStatus(routeName);
    }

    /**
     * Update the identify_only configuration on the route-level key-auth plugin instance.
     * This is used to switch between monitoring mode (identify_only=true) and
     * enforcement mode (identify_only=false).
     */
    private void updateKeyAuthIdentifyOnly(String routeResourceName, boolean identifyOnly) {
        WasmPluginInstance keyAuthInstance = wasmPluginInstanceService.query(
            WasmPluginInstanceScope.ROUTE, routeResourceName, BuiltInPluginName.KEY_AUTH, true);

        if (keyAuthInstance == null) {
            log.warn("key-auth instance not found for route: {}, skip setting identify_only", routeResourceName);
            return;
        }

        Map<String, Object> configurations = keyAuthInstance.getConfigurations();
        if (configurations == null) {
            configurations = new HashMap<>(2);
            keyAuthInstance.setConfigurations(configurations);
        }

        configurations.put(AsgPluginConstants.IDENTIFY_ONLY, identifyOnly);
        wasmPluginInstanceService.addOrUpdate(keyAuthInstance);
    }

    @Override
    public final AiShadowStatus performAction(AiShadowActionRequest request) {
        String routeName = request.getRouteName();
        String consumerName = request.getConsumerName();
        String action = request.getAction();

        if (StringUtils.isEmpty(routeName)) {
            throw new IllegalArgumentException("routeName cannot be empty.");
        }
        if (StringUtils.isEmpty(consumerName)) {
            throw new IllegalArgumentException("consumerName cannot be empty.");
        }
        if (!ACTION_AUTHORIZE.equals(action) && !ACTION_BLOCK.equals(action)) {
            throw new IllegalArgumentException("action must be either 'authorize' or 'block'.");
        }

        AiRoute aiRoute = aiRouteService.query(routeName);
        if (aiRoute == null) {
            throw new BusinessException("AI route not found: " + routeName);
        }

        String routeResourceName = buildRouteResourceName(routeName);
        AllowListOperation operation = ACTION_AUTHORIZE.equals(action) ? AllowListOperation.ADD : AllowListOperation.REMOVE;

        // Authorize/block actions only modify the allow list, they do NOT change
        // the monitoring/enforcement mode. The mode is controlled independently
        // via setMode().
        WasmPluginInstance keyAuthInstance = wasmPluginInstanceService.query(
            WasmPluginInstanceScope.ROUTE, routeResourceName, BuiltInPluginName.KEY_AUTH, true);
        boolean currentAuthEnabled = keyAuthInstance != null && Boolean.TRUE.equals(keyAuthInstance.getEnabled());

        List<String> consumerNames;
        if (CONSUMER_NONE.equals(consumerName)) {
            if (ACTION_AUTHORIZE.equals(action)) {
                consumerNames = consumerService.list(new CommonPageQuery()).getData().stream()
                    .map(Consumer::getName)
                    .filter(name -> !CONSUMER_NONE.equals(name))
                    .collect(Collectors.toList());
            } else {
                consumerNames = Collections.emptyList();
            }
        } else {
            consumerNames = Collections.singletonList(consumerName);
        }

        AllowList allowList = AllowList.forTarget(WasmPluginInstanceScope.ROUTE, routeResourceName)
            .consumerNames(consumerNames)
            .authEnabled(currentAuthEnabled)
            .build();

        consumerService.updateAllowList(operation, allowList);

        writeAdminAudit(action, routeName, consumerName, null);

        return getStatus(routeName);
    }

    private AiShadowStatus buildAiShadowStatus(String routeName, String routeResourceName,
        WasmPluginInstance keyAuthInstance) {

        boolean authEnabled = keyAuthInstance != null && Boolean.TRUE.equals(keyAuthInstance.getEnabled());
        // 模式由 identify_only 决定：false 才是真拦截，true 只记录。实例缺失或未启用时降级为检测。
        boolean identifyOnly = false;
        if (authEnabled && keyAuthInstance.getConfigurations() != null) {
            Object identifyOnlyObj = keyAuthInstance.getConfigurations().get(AsgPluginConstants.IDENTIFY_ONLY);
            if (identifyOnlyObj instanceof Boolean) {
                identifyOnly = (Boolean)identifyOnlyObj;
            }
        }
        String mode = (authEnabled && !identifyOnly) ? MODE_ENFORCEMENT : MODE_MONITORING;

        List<String> authorizedConsumers = getAuthorizedConsumers(routeResourceName);

        return AiShadowStatus.builder()
            .routeName(routeName)
            .resourceName(routeResourceName)
            .mode(mode)
            .authEnabled(authEnabled)
            .managed(authEnabled)
            .defaultDeny(authorizedConsumers.isEmpty())
            .authorizedConsumers(authorizedConsumers)
            .build();
    }

    private List<String> getAuthorizedConsumers(String routeResourceName) {
        Map<WasmPluginInstanceScope, String> targets = MapUtil.of(WasmPluginInstanceScope.ROUTE, routeResourceName);
        AllowList allowList = consumerService.getAllowList(targets);
        if (allowList == null || CollectionUtils.isEmpty(allowList.getConsumerNames())) {
            return Collections.emptyList();
        }
        // "none" 是未认证访问的占位，哨兵代表「默认拒绝」，两者都不是真实调用方，必须过滤掉，
        // 否则页面会把它们当成可授权对象显示出来。
        return allowList.getConsumerNames().stream()
            .filter(name -> !CONSUMER_NONE.equals(name))
            .filter(name -> !DEFAULT_DENY_SENTINEL.equals(name))
            .collect(Collectors.toList());
    }

    /**
     * 读取路由的 key-auth 守卫，不满足「已纳管 + 默认拒绝」就就地修正。
     * <p>
     * 三条不变式：① 实例存在且启用；② 没有真实消费者时名单必须带占位哨兵；③ 有真实消费者时不能带哨兵。
     * 状态已经合规时不写任何东西，因此可以放在查询路径上做对账。
     */
    private WasmPluginInstance ensureGuard(String routeResourceName) {
        WasmPluginInstance instance = queryGuard(routeResourceName);
        if (instance == null) {
            // 新建路由自动纳管：检测模式 + 默认拒绝。复用 setMode 的同一套写入，不另开路径。
            writeQuietly(routeResourceName, () -> {
                consumerService.updateAllowList(AllowListOperation.REPLACE,
                    routeAllowList(routeResourceName, Collections.singletonList(DEFAULT_DENY_SENTINEL)));
                updateKeyAuthIdentifyOnly(routeResourceName, true);
                // 审计跟着写入走：写失败还记一条「已纳管」，审计就和事实不符了。
                writeAdminAudit("auto_manage", routeResourceName, null, MODE_MONITORING);
            });
            log.info("auto-managed route {} with default-deny allow list", routeResourceName);
            return queryGuard(routeResourceName);
        }

        if (!Boolean.TRUE.equals(instance.getEnabled())) {
            // 实例在但被禁用，等同于未纳管。只启用，不动名单与模式。
            // 这里不能 return：TOGGLE_ONLY 只改开关、不补名单，直接返回会让页面显示
            // 「默认拒绝」而插件仍然提前放行——那正是这次要消灭的假管控。
            writeQuietly(routeResourceName, () -> consumerService.updateAllowList(
                AllowListOperation.TOGGLE_ONLY, routeAllowList(routeResourceName, null)));
            log.info("re-enabled key-auth guard for route {}", routeResourceName);
            instance = queryGuard(routeResourceName);
            if (instance == null) {
                return null;
            }
        }

        List<String> rawAllow = rawAllowOf(instance);
        List<String> realConsumers = rawAllow.stream()
            .filter(name -> !DEFAULT_DENY_SENTINEL.equals(name))
            .filter(name -> !CONSUMER_NONE.equals(name))
            .collect(Collectors.toList());

        if (realConsumers.isEmpty() && !rawAllow.contains(DEFAULT_DENY_SENTINEL)) {
            writeQuietly(routeResourceName, () -> consumerService.updateAllowList(
                AllowListOperation.REPLACE,
                routeAllowList(routeResourceName, Collections.singletonList(DEFAULT_DENY_SENTINEL))));
            log.info("allow list of route {} was empty, applied default-deny", routeResourceName);
            return queryGuard(routeResourceName);
        }
        if (!realConsumers.isEmpty() && rawAllow.contains(DEFAULT_DENY_SENTINEL)) {
            writeQuietly(routeResourceName, () -> consumerService.updateAllowList(
                AllowListOperation.REPLACE, routeAllowList(routeResourceName, realConsumers)));
            log.info("route {} now has authorized consumers, dropped default-deny placeholder",
                routeResourceName);
            return queryGuard(routeResourceName);
        }
        return instance;
    }

    /**
     * 对账写入不该让整个查询失败：K8s 写入可能因并发 resourceVersion 冲突而报错，
     * 此时页面仍应显示可读的状态（下次轮询会再对一次），而不是把路由降级成「未纳管」。
     */
    private void writeQuietly(String routeResourceName, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("failed to reconcile key-auth guard for route {}, will retry on next query",
                routeResourceName, e);
        }
    }

    private WasmPluginInstance queryGuard(String routeResourceName) {
        return wasmPluginInstanceService.query(WasmPluginInstanceScope.ROUTE, routeResourceName,
            BuiltInPluginName.KEY_AUTH, true);
    }

    private static AllowList routeAllowList(String routeResourceName, List<String> consumerNames) {
        return AllowList.forTarget(WasmPluginInstanceScope.ROUTE, routeResourceName)
            .consumerNames(consumerNames)
            .authEnabled(true)
            .build();
    }

    private static List<String> rawAllowOf(WasmPluginInstance instance) {
        if (instance == null || instance.getConfigurations() == null) {
            return Collections.emptyList();
        }
        Object allow = instance.getConfigurations().get(AsgPluginConstants.ALLOW);
        if (!(allow instanceof Collection)) {
            return Collections.emptyList();
        }
        return ((Collection<?>)allow).stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .collect(Collectors.toList());
    }

    @Override
    public final List<String> listConsumerNames() {
        PaginatedResult<Consumer> consumers = consumerService.list(new CommonPageQuery());
        if (consumers == null || CollectionUtils.isEmpty(consumers.getData())) {
            return Collections.emptyList();
        }
        // "none" 是未认证桶，不是可授权的调用方，不能出现在选择列表里。
        return consumers.getData().stream()
            .map(Consumer::getName)
            .filter(StringUtils::isNotBlank)
            .filter(name -> !CONSUMER_NONE.equals(name))
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }


    @Override
    public final AiShadowStatus replaceAuthorizedConsumers(AiShadowBatchActionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null.");
        }
        String routeName = request.getRouteName();
        if (StringUtils.isEmpty(routeName)) {
            throw new IllegalArgumentException("routeName cannot be empty.");
        }
        AiRoute aiRoute = aiRouteService.query(routeName);
        if (aiRoute == null) {
            throw new BusinessException("AI route not found: " + routeName);
        }

        List<String> consumers = request.getConsumerNames() == null ? Collections.emptyList()
            : request.getConsumerNames().stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .filter(name -> !CONSUMER_NONE.equals(name) && !DEFAULT_DENY_SENTINEL.equals(name))
                .distinct()
                .collect(Collectors.toList());

        // 一次替换成最终名单：空名单落哨兵即「默认拒绝」，非空即精确等于页面勾选结果。
        List<String> target = consumers.isEmpty() ? Collections.singletonList(DEFAULT_DENY_SENTINEL) : consumers;
        String routeResourceName = buildRouteResourceName(routeName);
        consumerService.updateAllowList(AllowListOperation.REPLACE,
            routeAllowList(routeResourceName, target));

        writeAdminAudit("replace_consumers", routeName, String.join(",", consumers), null);

        return getStatus(routeName);
    }


    private Map<String, PrometheusMetricData> queryMetric(String metricName) {
        Map<String, PrometheusMetricData> result = new HashMap<>();

        try {
            String queryUrl = prometheusBaseUrl + PROMETHEUS_QUERY_PATH + "?query=" + metricName;
            HttpURLConnection connection = (HttpURLConnection)new URL(queryUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                log.warn("Prometheus query returned non-200 status: {} for metric: {}", responseCode, metricName);
                return result;
            }

            StringBuilder responseBody = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBody.append(line);
                }
            }

            JSONObject response = JSON.parseObject(responseBody.toString());
            if (!"success".equals(response.getString("status"))) {
                log.warn("Prometheus query was not successful for metric: {}", metricName);
                return result;
            }

            JSONObject data = response.getJSONObject("data");
            if (data == null) {
                return result;
            }

            JSONArray results = data.getJSONArray("result");
            if (results == null) {
                return result;
            }

            for (int i = 0; i < results.size(); i++) {
                JSONObject item = results.getJSONObject(i);
                JSONObject metric = item.getJSONObject("metric");
                JSONArray value = item.getJSONArray("value");

                String route = metric.getString("ai_route");
                String consumer = metric.getString("ai_consumer");
                String model = metric.getString("ai_model");

                long tokenValue = 0;
                if (value != null && value.size() >= 2) {
                    try {
                        tokenValue = Long.parseLong(value.getString(1));
                    } catch (NumberFormatException e) {
                        log.warn("Failed to parse token value: {}", value.getString(1));
                    }
                }

                String key = route + "|" + consumer + "|" + model;
                PrometheusMetricData metricData = result.computeIfAbsent(key,
                    k -> new PrometheusMetricData(route, consumer, model));

                // 同一个 (route, consumer, model) 在每个 Pod 上各有一条 series：控制台是 2 副本，
                // 用 `=` 覆盖只会留下最后读到的那条，Token 用量恒定少算一半（实测 716 vs 1432）。
                if (INPUT_TOKEN_METRIC.equals(metricName)) {
                    metricData.inputTokens += tokenValue;
                } else if (OUTPUT_TOKEN_METRIC.equals(metricName)) {
                    metricData.outputTokens += tokenValue;
                } else if (LLM_REQUEST_METRIC.equals(metricName)) {
                    metricData.requestCount += tokenValue;
                }
            }
        } catch (Exception e) {
            log.error("Error querying Prometheus metric: {}", metricName, e);
        }

        return result;
    }

    private static String buildRouteResourceName(String routeName) {
        return CommonKey.AI_ROUTE_PREFIX + routeName + HigressConstants.INTERNAL_RESOURCE_NAME_SUFFIX;
    }

    @Override
    public List<AiShadowDetectedAccess> getDetectedAccessesInWindow(Long startTime, Long endTime, String surface) {
        // 数据源改为事件表：Prometheus 指标缺 risk 标签（riskLevel 恒为 null，"严重/高风险"
        // 永远算不出来），且只保留 6h。事件表由 Wasm 插件逐条上报，是流量的真相源。
        List<AiShadowDetectedAccess> result = new ArrayList<>();
        // 口径：全量。已授权域名不再从列表里剔除，只用 authorized 标记出来。
        // 原实现（IR-003）把授权当作「从检测视图里隐藏」，结果同一页面出现两套口径：
        // KPI 与列表剔除授权域名、趋势与事件明细不剔除，用户一授权就看到
        // 「趋势有数据、KPI 全 0」。授权语义应该是「已放行，但仍在监控」，
        // 隐藏流量会让授权变成一次无法复核的操作。
        List<String> authorizedDomains = loadAuthorizedDomains();
        long[] window = resolveWindow(startTime, endTime);
        long start = window[0];
        long end = window[1];
        try {
            List<Object[]> rows =
                detectEventRepository.aggregateByDomain(start / 1000L, end / 1000L, StringUtils.defaultString(surface));
            for (Object[] row : rows) {
                if (row == null || row.length < 6 || row[0] == null || row[1] == null) {
                    continue;
                }
                String domain = String.valueOf(row[0]);
                String category = row[5] == null ? null : String.valueOf(row[5]);
                String status = rankToValue(STATUS_BY_RANK, row[3]);
                result.add(AiShadowDetectedAccess.builder()
                    .sni(domain)
                    .category(category)
                    .categoryLabel(category == null ? null : getCategoryLabel(category))
                    .riskLevel(rankToValue(RISK_BY_RANK, row[2]))
                    // null 表示该域名的所有事件 status 都不在已知枚举内（FIELD 全为 0）。
                    // 兜底成 monitored 而非 allowed：把「未知」显示成「已放行」会掩盖风险，
                    // monitored 的语义是「已记录未处置」，方向上更保守。
                    .status(status == null ? "monitored" : status)
                    .requestCount(((Number)row[1]).longValue())
                    .lastSeen(row[4] == null ? null : ((Number)row[4]).longValue() * 1000L)
                    .authorized(isDomainAuthorized(domain, authorizedDomains))
                    .build());
            }
        } catch (Exception e) {
            log.error("Error aggregating shadow AI detected accesses", e);
        }
        return result;
    }

    /** FIELD() 在 SQL 里返回的严重度序号（1 起）到实际取值的映射，0 表示该列全为未知值。 */
    private static final String[] RISK_BY_RANK = {null, "critical", "high", "medium", "low"};
    private static final String[] STATUS_BY_RANK =
        {null, "blocked", "alerted", "monitored", "masked", "allowed"};

    private static String rankToValue(String[] table, Object rank) {
        if (rank == null) {
            return null;
        }
        int idx = ((Number)rank).intValue();
        return (idx > 0 && idx < table.length) ? table[idx] : null;
    }

    @Override
    public Map<String, Object> getDetectedStats(Long startTime, Long endTime, String surface) {
        long[] window = resolveWindow(startTime, endTime);
        long start = window[0];
        long end = window[1];
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("start", start);
        stats.put("end", end);
        long total = 0L;
        long critical = 0L;
        long high = 0L;
        long blocked = 0L;
        long domainCount = 0L;
        // 已授权域名单独累计，供前端在「检测总量」旁注明「其中已授权 N 条」。
        // 口径与列表、趋势统一为全量：三者对同一个窗口必然得出同一个合计数。
        long authorizedTotal = 0L;
        long authorizedDomainCount = 0L;
        boolean degraded = false;
        try {
            List<String> authorizedDomains = loadAuthorizedDomains();
            List<Object[]> rows =
                detectEventRepository.aggregateByDomain(start / 1000L, end / 1000L, StringUtils.defaultString(surface));
            for (Object[] row : rows) {
                if (row == null || row.length < 9 || row[0] == null || row[1] == null) {
                    continue;
                }
                long count = ((Number)row[1]).longValue();
                domainCount++;
                total += count;
                critical += numberOrZero(row[6]);
                high += numberOrZero(row[7]);
                blocked += numberOrZero(row[8]);
                if (isDomainAuthorized(String.valueOf(row[0]), authorizedDomains)) {
                    authorizedDomainCount++;
                    authorizedTotal += count;
                }
            }
        } catch (Exception e) {
            // degraded=true 时前端显示告警，而不是把 0 当成"没有风险"
            log.error("Error aggregating shadow AI detection stats", e);
            degraded = true;
        }
        stats.put("total", total);
        stats.put("critical", critical);
        stats.put("high", high);
        stats.put("blocked", blocked);
        stats.put("domainCount", domainCount);
        stats.put("authorizedTotal", authorizedTotal);
        stats.put("authorizedDomainCount", authorizedDomainCount);
        // 跨页提示：本页只覆盖一面（检测页=绕道，管控页=准入违规），另一面有多少条要显式说出来。
        // 这两个数取全量表，不受 surface 过滤影响，否则就变成了「自己跟自己比」。
        // 口径变更点：准入违规同样是影子AI，不能因为不在本页就当作不存在。
        long admissionTotal = 0L;
        long shadowTotal = 0L;
        try {
            for (Object[] row : detectEventRepository.countBySurface(start / 1000L, end / 1000L)) {
                if (row == null || row.length < 2 || row[0] == null) {
                    continue;
                }
                long cnt = numberOrZero(row[1]);
                if ("route".equals(String.valueOf(row[0]))) {
                    admissionTotal = cnt;
                } else if ("shadow".equals(String.valueOf(row[0]))) {
                    shadowTotal = cnt;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to aggregate event count by surface", e);
        }
        stats.put("admissionTotal", admissionTotal);
        stats.put("shadowTotal", shadowTotal);
        stats.put("degraded", degraded);
        return stats;
    }

    @Override
    public Map<String, Object> getAdmissionEvents(Long startTime, Long endTime) {
        long[] window = resolveWindow(startTime, endTime);
        long start = window[0];
        long end = window[1];
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("start", start);
        result.put("end", end);
        long total = 0L;
        long blocked = 0L;
        Set<String> domains = new HashSet<>();
        List<Map<String, Object>> list = new ArrayList<>();
        boolean degraded = false;
        try {
            for (Object[] row : detectEventRepository.aggregateAdmission(start / 1000L, end / 1000L)) {
                if (row == null || row.length < 5 || row[0] == null) {
                    continue;
                }
                long count = numberOrZero(row[3]);
                String domain = String.valueOf(row[0]);
                String status = String.valueOf(row[2]);
                total += count;
                domains.add(domain);
                if ("blocked".equals(status)) {
                    blocked += count;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("domain", domain);
                item.put("reason", StringUtils.trimToNull(String.valueOf(row[1])));
                item.put("status", StringUtils.trimToNull(status));
                item.put("count", count);
                item.put("lastSeen", row[4] == null ? null : ((Number)row[4]).longValue() * 1000L);
                list.add(item);
            }
        } catch (Exception e) {
            // 聚合失败必须与「窗口内没有准入违规」区分开，否则页面会显示一切正常。
            log.error("Error aggregating admission events", e);
            degraded = true;
        }
        result.put("total", total);
        result.put("blocked", blocked);
        result.put("domainCount", domains.size());
        result.put("list", list);
        result.put("degraded", degraded);
        return result;
    }

    private static long numberOrZero(Object value) {
        return value == null ? 0L : ((Number)value).longValue();
    }

    /**
     * 归一化查询窗口，返回 {start, end}（epoch 毫秒）。列表、KPI、趋势三条查询必须共用这一份
     * 归一化逻辑：任何一处口径不同，就会出现「明细有数据、卡片是 0」的自相矛盾画面。
     * <ul>
     *   <li>end 不越界到未来——未来的区间只会得到空桶；</li>
     *   <li>start 缺省回退到默认窗口；</li>
     *   <li>超长窗口按上限截断，避免一次查全表；</li>
     *   <li>end 必须严格大于 start，否则 SQL 的 {@code BETWEEN} 语义会退化成空集。</li>
     * </ul>
     */
    private static long[] resolveWindow(Long startTime, Long endTime) {
        long now = System.currentTimeMillis();
        long end = (endTime == null || endTime <= 0L) ? now : Math.min(endTime, now);
        long start = (startTime == null || startTime <= 0L) ? end - DEFAULT_TREND_WINDOW_MS : startTime;
        if (end - start > MAX_TREND_WINDOW_MS) {
            start = end - MAX_TREND_WINDOW_MS;
        }
        if (end <= start) {
            end = start + 1L;
        }
        return new long[] {start, end};
    }

    @Override
    public Map<String, Object> getDetectedTrend(Long startTime, Long endTime, String surface) {
        // The window never runs into the future: a future end only yields empty buckets.
        long[] window = resolveWindow(startTime, endTime);
        long start = window[0];
        long end = window[1];
        // Granularity follows the span (same rule as the behaviour dashboard): <=2d hourly, else daily.
        boolean hourly = (end - start) <= HOURLY_MAX_SPAN_MS;
        long stepSeconds = hourly ? 3600L : 86400L;
        // Bucket edges come from the caller. The console aligns the window to its own local
        // midnight; re-aligning here would use the JVM zone, and the container runs in UTC.
        long windowStart = start;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("start", windowStart);
        result.put("end", end);
        result.put("granularity", hourly ? GRANULARITY_HOUR : GRANULARITY_DAY);
        // Distinguishes "Prometheus unreachable" from "no detections": both yield an empty
        // list, and the console fills an all-zero axis, which would hide the outage.
        result.put("degraded", false);
        List<Map<String, Object>> points = new ArrayList<>();
        try {
            long startSeconds = windowStart / 1000L;
            long endSeconds = end / 1000L;
            // 事件表由 Wasm 插件（key-auth / ai-pii-guard）逐条上报，是流量的真相源，且自带
            // risk_level / status / domain；Prometheus 计数器缺 risk 标签、会随 Pod 重启归零、
            // 并受 6h retention 限制，导致 KPI / 趋势 / 明细三处口径对不上。此处统一到事件表。
            List<Object[]> rows = detectEventRepository.countByBucketAndStatus(startSeconds, endSeconds, stepSeconds,
                StringUtils.defaultString(surface));
            for (Object[] row : rows) {
                if (row == null || row.length < 3 || row[0] == null || row[1] == null || row[2] == null) {
                    continue;
                }
                long bucketIdx = ((Number)row[0]).longValue();
                Map<String, Object> p = new HashMap<>();
                // Milliseconds: the console formats the label itself, per granularity.
                p.put("timestamp", (startSeconds + bucketIdx * stepSeconds) * 1000L);
                p.put("status", String.valueOf(row[1]));
                p.put("count", ((Number)row[2]).longValue());
                points.add(p);
            }
            // Stable ordering: status (blocked/allowed/monitored first), then time ascending.
            points.sort((a, b) -> {
                int sa = TREND_STATUS_ORDER.indexOf(a.get("status"));
                int sb = TREND_STATUS_ORDER.indexOf(b.get("status"));
                if (sa != sb) {
                    return Integer.compare(sa < 0 ? Integer.MAX_VALUE : sa, sb < 0 ? Integer.MAX_VALUE : sb);
                }
                return Long.compare((Long)a.get("timestamp"), (Long)b.get("timestamp"));
            });
        } catch (Exception e) {
            // 聚合失败时置 degraded：前端会先渲染告警再铺零轴，
            // 避免把"查询挂了"显示成"窗口内没有检测到"。
            log.error("Error aggregating shadow AI detection trend", e);
            result.put("degraded", true);
        }
        result.put("list", points);
        return result;
    }

    /**
     * Load the authorized domain list from the DNS detection policy
     * (ai_shadow_dns_policy). Failures degrade to an empty list so the
     * detected list stays complete when the policy cannot be read.
     */
    private List<String> loadAuthorizedDomains() {
        try {
            if (dnsPolicyService == null) {
                return Collections.emptyList();
            }
            AiShadowDnsPolicy policy = dnsPolicyService.getPolicy();
            if (policy == null || StringUtils.isBlank(policy.getAuthorizedDomains())) {
                return Collections.emptyList();
            }
            return Arrays.stream(policy.getAuthorizedDomains().split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to load authorized domains, keep detected list unfiltered", e);
            return Collections.emptyList();
        }
    }

    /**
     * 归一化域名用于授权匹配。
     * <p>
     * 只把下划线当成点：授权列表里存在从旧 Prometheus 标签还原出来的历史条目
     * （{@code chat_example_com} 这类），保留这条替换是为了让它们仍能被解释成
     * {@code chat.example.com}。
     * <p>
     * 连字符必须保留：{@code -} 是合法域名字符（{@code demo-llm.local}），把它也换成点会让
     * 授权 {@code abc.com} 连带覆盖 {@code abc-com} / {@code abc_com}，属于过度匹配 —— 现在
     * 它只影响「已授权」标记的准确性，一旦用于放行判断就是越权。
     */
    private static String normalizeDomain(String domain) {
        if (StringUtils.isEmpty(domain)) {
            return "";
        }
        String normalized = domain.trim().toLowerCase().replace('_', '.');
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Whether a detected domain is covered by the authorized domain list
     * (exact match or subdomain).
     */
    private static boolean isDomainAuthorized(String domain, List<String> authorizedDomains) {
        if (CollectionUtils.isEmpty(authorizedDomains)) {
            return false;
        }
        String normalized = normalizeDomain(domain);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String authorized : authorizedDomains) {
            String normalizedAuthorized = normalizeDomain(authorized);
            if (!normalizedAuthorized.isEmpty()
                && (normalized.equals(normalizedAuthorized) || normalized.endsWith("." + normalizedAuthorized))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void setDetectMode(String mode) {
        if (!MODE_MONITORING.equals(mode) && !MODE_ENFORCEMENT.equals(mode)) {
            throw new IllegalArgumentException("mode must be either 'monitoring' or 'enforcement'.");
        }

        WasmPluginInstance instance = wasmPluginInstanceService.query(
            WasmPluginInstanceScope.GLOBAL, null, AsgPluginConstants.AI_SHADOW_DETECT, false);

        if (instance == null) {
            instance = wasmPluginInstanceService.createEmptyInstance(AsgPluginConstants.AI_SHADOW_DETECT);
            instance.setGlobalTarget();
            instance.setEnabled(true);
        }

        Map<String, Object> configurations = instance.getConfigurations();
        if (configurations == null) {
            configurations = new HashMap<>();
        }
        configurations.put("mode", mode);
        instance.setConfigurations(configurations);

        wasmPluginInstanceService.addOrUpdate(instance);

        writeAdminAudit("set_detect_mode", null, null, mode);
    }

    @Override
    public Map<String, Object> getAuthorizedDomains(Long startTime, Long endTime) {
        Map<String, Object> result = new HashMap<>();
        List<String> domains = new ArrayList<>();
        // R9: unify mode source with GET /detect-mode (WasmPlugin config is authoritative)
        String mode = getDetectMode();
        LocalDateTime policyUpdatedAt = null;
        try {
            AiShadowDnsPolicy policy = dnsPolicyService.getPolicy();
            if (policy != null) {
                policyUpdatedAt = policy.getUpdatedAt();
                // domain list still comes from the DNS policy table
                if (StringUtils.isNotBlank(policy.getAuthorizedDomains())) {
                    domains = Arrays.stream(policy.getAuthorizedDomains().split(",")).map(String::trim)
                        .filter(StringUtils::isNotBlank).collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            // Degrade to the default view instead of failing the console page.
            log.warn("Failed to load DNS policy for authorized domains view", e);
        }
        result.put("mode", mode);
        result.put("domains", domains);
        // 元数据表只补展示字段，权威域名集合仍是 DNS policy：缺行时按「导入」呈现，
        // 不影响授权判定，因此 collector 读的 dns-policy 契约保持不变。
        result.put("items", buildAuthorizedDomainItems(domains, policyUpdatedAt, startTime, endTime));
        return result;
    }

    private List<Map<String, Object>> buildAuthorizedDomainItems(List<String> domains, LocalDateTime fallbackTime,
                                                                 Long startTime, Long endTime) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (CollectionUtils.isEmpty(domains)) {
            return items;
        }
        Map<String, AiShadowAuthorizedDomain> meta = new HashMap<>();
        try {
            for (AiShadowAuthorizedDomain row : authorizedDomainRepository.findAllByOrderByCreatedAtAsc()) {
                meta.put(row.getDomain(), row);
            }
        } catch (Exception e) {
            log.warn("Failed to load authorized domain metadata", e);
        }
        // 窗口内命中统计：让「看起来授权了、实际一条也没覆盖」的空转条目在界面上无处藏身。
        // 统计失败时给 null（前端显示 -）而不是 0 —— 0 的语义是「确认没有流量」，
        // 与「没查到」完全不同，混为一谈会让人误判这条授权该删。
        Map<String, Long> hitCounts = new HashMap<>();
        Map<String, List<String>> hitDomainLists = new HashMap<>();
        boolean statsOk = true;
        try {
            long[] window = resolveWindow(startTime, endTime);
            // 只算 shadow 面：授权域名的作用是让 ai-shadow-detect 对该域名不再上报（S1 白名单），
            // 它不改变 key-auth 的准入判定。命中数若把准入违规算进来，用户会看到
            // 「这条授权覆盖 12 条」但检测页只有 3 条——那是两个 tab 的口径又打架了。
            List<Object[]> rows =
                detectEventRepository.aggregateByDomain(window[0] / 1000L, window[1] / 1000L, "shadow");
            for (String domain : domains) {
                long hits = 0L;
                List<String> matched = new ArrayList<>();
                for (Object[] row : rows) {
                    if (row == null || row.length < 2 || row[0] == null || row[1] == null) {
                        continue;
                    }
                    String detected = String.valueOf(row[0]);
                    // 用与运行时完全相同的判定函数、对单个条目求值，保证「界面显示的命中」
                    // 就是「实际会被标记为已授权的流量」，不另写一套匹配逻辑。
                    if (isDomainAuthorized(detected, Collections.singletonList(domain))) {
                        hits += ((Number)row[1]).longValue();
                        matched.add(detected);
                    }
                }
                hitCounts.put(domain, hits);
                hitDomainLists.put(domain, matched);
            }
        } catch (Exception e) {
            log.warn("Failed to compute authorized domain hit counts", e);
            statsOk = false;
        }
        long fallbackMillis = toEpochMillis(fallbackTime);
        for (String domain : domains) {
            AiShadowAuthorizedDomain row = meta.get(domain);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("domain", domain);
            // isDomainAuthorized 采用后缀匹配，授权 example.com 会同时覆盖 api.example.com
            item.put("matchType", MATCH_TYPE_SUFFIX);
            item.put("note", row == null ? null : row.getNote());
            item.put("source", row == null ? SOURCE_IMPORTED : row.getSource());
            item.put("createdBy", row == null ? null : row.getCreatedBy());
            item.put("createdAt", row == null ? fallbackMillis : toEpochMillis(row.getCreatedAt()));
            item.put("hitCount", statsOk ? hitCounts.get(domain) : null);
            item.put("hitDomains", statsOk ? hitDomainLists.get(domain) : null);
            items.add(item);
        }
        return items;
    }

    /**
     * LocalDateTime → epoch 毫秒。用 JVM 默认时区解释，是因为 JDBC 驱动默认
     * {@code connectionTimeZone=LOCAL}：TIMESTAMP 列读出时已被转成 JVM 时区的字面值，
     * 这里再用同一时区解释，两次转换相互抵消。
     * <p>
     * 现网实测（容器 TZ=UTC、MySQL 会话时区 SYSTEM=CST）：DB 里 {@code 09:53:46} 对应
     * 接口返回 {@code 1793066026910}，与 {@code UNIX_TIMESTAMP()} 结果一致，无偏移。
     * <p>
     * 风险：一旦有人把连接串固定成 {@code connectionTimeZone=Asia/Shanghai}，或给 JVM 加
     * {@code -Duser.timezone}，这个抵消关系就断了，展示时间会整体偏 8 小时。改动此处前先复核。
     */
    private static long toEpochMillis(LocalDateTime time) {
        return time == null ? 0L : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Override
    public Map<String, Object> updateAuthorizedDomains(String mode, List<String> addDomains,
        List<String> removeDomains, String source) {
        AiShadowDnsPolicy policy = dnsPolicyService.getPolicy();
        // R9: unify mode source with GET /detect-mode (WasmPlugin config is authoritative)
        String currentMode = getDetectMode();

        // Merge: keep existing order, normalize new entries, skip duplicates.
        List<String> merged = new ArrayList<>();
        if (policy != null && StringUtils.isNotBlank(policy.getAuthorizedDomains())) {
            Arrays.stream(policy.getAuthorizedDomains().split(",")).map(String::trim)
                .filter(StringUtils::isNotBlank).forEach(merged::add);
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(addDomains)) {
            for (String raw : addDomains) {
                if (StringUtils.isBlank(raw)) {
                    continue;
                }
                String domain = raw.trim().toLowerCase();
                if (!merged.contains(domain)) {
                    merged.add(domain);
                    added.add(domain);
                }
            }
        }
        if (CollectionUtils.isNotEmpty(removeDomains)) {
            for (String raw : removeDomains) {
                if (StringUtils.isBlank(raw)) {
                    continue;
                }
                String domain = raw.trim().toLowerCase();
                if (merged.remove(domain)) {
                    removed.add(domain);
                }
            }
        }

        String targetMode = StringUtils.isNotEmpty(mode) ? mode : currentMode;
        dnsPolicyService.updatePolicy(targetMode, merged);

        // Each effective change is audited, mirroring the consumer authorization trail.
        for (String domain : added) {
            writeAdminAudit("authorize_domain", null, domain, null);
        }
        for (String domain : removed) {
            writeAdminAudit("deauthorize_domain", null, domain, null);
        }

        syncAuthorizedDomainMetadata(added, removed, source);

        Map<String, Object> result = new HashMap<>();
        result.put("mode", targetMode);
        result.put("domains", merged);
        // 写接口不携带时间窗口，命中数只能按默认窗口算；调用方（控制台）随后会用
        // 页面当前窗口重新拉取 GET /authorized-domains，因此这里的 hitCount 只是一次
        // 过渡值，不用于最终展示。
        result.put("items", buildAuthorizedDomainItems(merged, null, null, null));
        return result;
    }

    /**
     * 维护授权域名的展示元数据。权威域名集合写在 dns policy 里，这里只补 source / 操作人 / 时间，
     * 因此写失败也不影响授权生效（记 warn 后继续，不回滚已生效的策略）。
     */
    private void syncAuthorizedDomainMetadata(List<String> added, List<String> removed, String source) {
        String origin = StringUtils.isBlank(source) ? SOURCE_MANUAL : source.trim().toLowerCase(Locale.ROOT);
        if (!SOURCE_DETECTED.equals(origin)) {
            origin = SOURCE_MANUAL;
        }
        String operator = resolveOperator();
        for (String domain : added) {
            try {
                AiShadowAuthorizedDomain row = authorizedDomainRepository.findByDomain(domain).orElse(null);
                if (row == null) {
                    row = new AiShadowAuthorizedDomain();
                    row.setDomain(domain);
                    row.setCreatedAt(LocalDateTime.now());
                }
                // 已存在（例如由「导入」转为显式授权）时保留首次授权时间，只刷新来源与操作人
                row.setSource(origin);
                row.setCreatedBy(operator);
                authorizedDomainRepository.save(row);
            } catch (Exception e) {
                log.warn("Failed to persist authorized domain metadata for {}", domain, e);
            }
        }
        for (String domain : removed) {
            try {
                authorizedDomainRepository.findByDomain(domain)
                    .ifPresent(row -> authorizedDomainRepository.delete(row));
            } catch (Exception e) {
                log.warn("Failed to drop authorized domain metadata for {}", domain, e);
            }
        }
    }

    @Override
    public String getDetectMode() {
        WasmPluginInstance instance = wasmPluginInstanceService.query(
            WasmPluginInstanceScope.GLOBAL, null, AsgPluginConstants.AI_SHADOW_DETECT, false);

        if (instance != null && instance.getConfigurations() != null) {
            Object mode = instance.getConfigurations().get("mode");
            if (mode instanceof String) {
                return (String) mode;
            }
        }
        return MODE_MONITORING;
    }

    @Override
    public void setEnabled(boolean enabled) {
        // Rapid toggles can fire concurrent PUTs that read the same WasmPlugin CR resourceVersion;
        // the losing update throws ResourceConflictException (surfaced as HTTP 500). The write is an
        // idempotent boolean set (enabled = !defaultConfigDisable), so re-query the fresh version and
        // retry instead of failing the whole request.
        int attempts = 0;
        while (true) {
            WasmPluginInstance instance = wasmPluginInstanceService.query(
                WasmPluginInstanceScope.GLOBAL, null, AsgPluginConstants.AI_SHADOW_DETECT, false);
            if (instance == null) {
                // No global instance yet: materialize a default one so the switch persists.
                instance = wasmPluginInstanceService.createEmptyInstance(AsgPluginConstants.AI_SHADOW_DETECT);
                instance.setGlobalTarget();
                Map<String, Object> configurations = new HashMap<>();
                configurations.put("mode", MODE_MONITORING);
                instance.setConfigurations(configurations);
            }
            // WasmPluginInstance.enabled maps to CR spec.defaultConfigDisable (enabled = !disable).
            instance.setEnabled(enabled);
            try {
                wasmPluginInstanceService.addOrUpdate(instance);
                break;
            } catch (ResourceConflictException e) {
                if (++attempts >= SET_ENABLED_MAX_RETRY) {
                    throw e;
                }
                try {
                    Thread.sleep(50L * attempts);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        writeAdminAudit(enabled ? "enable_shadow_ai" : "disable_shadow_ai", null, null, null);
    }

    @Override
    public boolean getEnabled() {
        WasmPluginInstance instance = wasmPluginInstanceService.query(
            WasmPluginInstanceScope.GLOBAL, null, AsgPluginConstants.AI_SHADOW_DETECT, false);
        if (instance == null) {
            // Feature defaults to enabled until explicitly disabled.
            return true;
        }
        return !Boolean.FALSE.equals(instance.getEnabled());
    }

    /**
     * Write an admin operation entry to the audit chain (IR-003).
     *
     * <p>All shadow AI authorization operations (setMode / performAction / setDetectMode)
     * are recorded under the fixed virtual session {@link #ADMIN_OPS_SESSION_ID} so they
     * can be queried via the audit chain API, e.g.
     * {@code GET /v1/audit-chain/logs?sessionId=admin_ops&recordType=admin_operation}.
     * The entry mirrors the Wasm plugin AuditLogEntry field names (event_id, timestamp_ms,
     * session_id, record_types, user_id, identity_trusted) so existing queries work.</p>
     *
     * @param action the operation performed (set_mode / authorize / block / set_detect_mode)
     * @param routeName target AI route, may be null for global operations
     * @param consumerName target consumer, may be null
     * @param mode target mode, may be null
     */
    private void writeAdminAudit(String action, String routeName, String consumerName, String mode) {
        try {
            long timestampMs = System.currentTimeMillis();
            String operator = resolveOperator();
            String eventId = timestampMs + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);

            JSONObject entry = new JSONObject();
            entry.put("event_id", eventId);
            entry.put("timestamp_ms", timestampMs);
            entry.put("session_id", ADMIN_OPS_SESSION_ID);
            entry.put("record_types", Collections.singletonList(ADMIN_OPS_RECORD_TYPE));
            entry.put("blocked", false);
            entry.put("identity_trusted", true);
            entry.put("user_id", operator);
            entry.put("user_name", operator);
            entry.put("action", action);
            entry.put("source", "ai_shadow_console");
            if (StringUtils.isNotEmpty(routeName)) {
                entry.put("route_name", routeName);
            }
            if (StringUtils.isNotEmpty(consumerName)) {
                entry.put("consumer_name", consumerName);
            }
            if (StringUtils.isNotEmpty(mode)) {
                entry.put("mode", mode);
            }

            auditChainService.writeAuditLog(entry.toJSONString(), eventId, ADMIN_OPS_SESSION_ID,
                timestampMs, operator, true, null);
        } catch (Exception e) {
            log.error("Failed to write admin audit log for action: {}", action, e);
        }
    }

    /**
     * Best-effort resolution of the console operator from the current request:
     * Basic auth header username, falling back to {@link #OPERATOR_CONSOLE}.
     */
    private static String resolveOperator() {
        try {
            HttpContext context = HttpContext.getCurrent();
            if (context != null && context.getRequest() != null) {
                String auth = context.getRequest().getHeader("Authorization");
                if (StringUtils.isNotBlank(auth) && auth.startsWith("Basic ")) {
                    String decoded = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
                    int separator = decoded.indexOf(':');
                    return separator > 0 ? decoded.substring(0, separator) : decoded;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to resolve operator identity, falling back to {}", OPERATOR_CONSOLE, e);
        }
        return OPERATOR_CONSOLE;
    }

    private static String getCategoryLabel(String category) {
        if (category == null) {
            return "";
        }
        switch (category) {
            case "saas_ai":
                return "云端SaaS AI";
            case "api_integrated_ai":
                return "API集成AI";
            case "embedded_ai":
                return "嵌入式AI";
            case "local_deployed_ai":
                return "本地部署AI";
            case "ai_agent":
                return "AI Agent";
            default:
                return category;
        }
    }

    private static class ConsumerAggregate {

        String consumer;
        boolean unauthenticated;
        long requestCount;
        long inputTokens;
        long outputTokens;
        long blockedCount;
        long monitoredCount;
        final Set<String> routes = new LinkedHashSet<>();
        final Set<String> models = new LinkedHashSet<>();
    }

    private static class PrometheusMetricData {

        String route;
        String consumer;
        String model;
        long requestCount;
        long inputTokens;
        long outputTokens;

        PrometheusMetricData(String route, String consumer, String model) {
            this.route = route;
            this.consumer = consumer;
            this.model = model;
        }
    }
}
