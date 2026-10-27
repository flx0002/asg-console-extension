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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import com.asg.console.extension.model.AiShadowActionRequest;
import com.asg.console.extension.model.AiShadowAuthorizedDomain;
import com.asg.console.extension.model.AiShadowDetectedAccess;
import com.asg.console.extension.model.AiShadowDnsPolicy;
import com.asg.console.extension.model.AiShadowEntry;
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

    private static final String PROMETHEUS_QUERY_PATH = "/api/v1/query";
    private static final String INPUT_TOKEN_METRIC = "route_upstream_model_consumer_metric_input_token";
    private static final String OUTPUT_TOKEN_METRIC = "route_upstream_model_consumer_metric_output_token";
    private static final String AI_SHADOW_DETECT_METRIC_PREFIX = "ai_shadow_detect_category_";

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
    public final List<AiShadowStatus> getStatus() {
        PaginatedResult<AiRoute> aiRoutes = aiRouteService.list(null);
        if (aiRoutes == null || CollectionUtils.isEmpty(aiRoutes.getData())) {
            return Collections.emptyList();
        }

        Map<String, Map<String, PrometheusMetricData>> prometheusData = queryPrometheusMetrics();

        List<AiShadowStatus> result = new ArrayList<>();
        for (AiRoute aiRoute : aiRoutes.getData()) {
            String routeResourceName = buildRouteResourceName(aiRoute.getName());
            try {
                AiShadowStatus status = buildAiShadowStatus(aiRoute.getName(), routeResourceName, prometheusData);
                result.add(status);
            } catch (Exception e) {
                log.error("Error building shadow AI status for route: {}", aiRoute.getName(), e);
                result.add(AiShadowStatus.builder()
                    .routeName(aiRoute.getName())
                    .mode(MODE_MONITORING)
                    .authEnabled(false)
                    .authorizedConsumers(Collections.emptyList())
                    .aiShadowList(Collections.emptyList())
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
        Map<String, Map<String, PrometheusMetricData>> prometheusData = queryPrometheusMetrics();

        return buildAiShadowStatus(routeName, routeResourceName, prometheusData);
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
        Map<String, Map<String, PrometheusMetricData>> prometheusData) {

        WasmPluginInstance keyAuthInstance = wasmPluginInstanceService.query(
            WasmPluginInstanceScope.ROUTE, routeResourceName, BuiltInPluginName.KEY_AUTH, true);

        boolean authEnabled = keyAuthInstance != null && Boolean.TRUE.equals(keyAuthInstance.getEnabled());
        // Determine mode based on identify_only config:
        // - enabled=true + identify_only=false 鈫?enforcement mode (reject unauthenticated)
        // - enabled=true + identify_only=true  鈫?monitoring mode (identify but don't reject)
        // - enabled=false                      鈫?monitoring mode (legacy, can't identify consumers)
        boolean identifyOnly = false;
        if (authEnabled && keyAuthInstance.getConfigurations() != null) {
            Object identifyOnlyObj = keyAuthInstance.getConfigurations().get(
                AsgPluginConstants.IDENTIFY_ONLY);
            if (identifyOnlyObj instanceof Boolean) {
                identifyOnly = (Boolean) identifyOnlyObj;
            }
        }
        String mode = (authEnabled && !identifyOnly) ? MODE_ENFORCEMENT : MODE_MONITORING;

        List<String> authorizedConsumers = getAuthorizedConsumers(routeResourceName);

        List<AiShadowEntry> aiShadowList = buildAiShadowEntries(routeResourceName, authorizedConsumers, authEnabled, prometheusData);

        return AiShadowStatus.builder()
            .routeName(routeName)
            .mode(mode)
            .authEnabled(authEnabled)
            .authorizedConsumers(authorizedConsumers)
            .aiShadowList(aiShadowList)
            .build();
    }

    private List<String> getAuthorizedConsumers(String routeResourceName) {
        Map<WasmPluginInstanceScope, String> targets = MapUtil.of(WasmPluginInstanceScope.ROUTE, routeResourceName);
        AllowList allowList = consumerService.getAllowList(targets);
        if (allowList == null || CollectionUtils.isEmpty(allowList.getConsumerNames())) {
            return Collections.emptyList();
        }
        // Filter out "none" - it's not a real consumer, just a placeholder for unauthenticated access
        return allowList.getConsumerNames().stream()
            .filter(name -> !CONSUMER_NONE.equals(name))
            .collect(Collectors.toList());
    }

    private List<AiShadowEntry> buildAiShadowEntries(String routeResourceName, List<String> authorizedConsumers,
        boolean authEnabled, Map<String, Map<String, PrometheusMetricData>> prometheusData) {

        // Collect all metric data matching this route (prefix match, because the Prometheus
        // ai_route label may include provider info, e.g. "ai-route-nex-agi-nex-n2-pro.internal"
        // while routeResourceName is "ai-route-nex.internal")
        Map<String, PrometheusMetricData> routeMetricData = new HashMap<>();
        String routePrefix = CommonKey.AI_ROUTE_PREFIX;
        for (Map.Entry<String, Map<String, PrometheusMetricData>> entry : prometheusData.entrySet()) {
            String prometheusRoute = entry.getKey();
            if (matchesRoute(routeResourceName, prometheusRoute)) {
                entry.getValue().forEach(routeMetricData::putIfAbsent);
            }
        }

        List<AiShadowEntry> entries = new ArrayList<>();
        for (Map.Entry<String, PrometheusMetricData> entry : routeMetricData.entrySet()) {
            PrometheusMetricData data = entry.getValue();

            // All accesses through AI routes are shadow AI except authorized users.
            // "none" consumer means unauthenticated access - always shadow AI.
            // Specific consumers are authorized only if they're in the allow list.
            boolean isAuthorized = !CONSUMER_NONE.equals(data.consumer)
                && authorizedConsumers.contains(data.consumer);

            // IR-003: authorized accesses are removed from the shadow AI list.
            if (isAuthorized) {
                continue;
            }

            entries.add(AiShadowEntry.builder()
                .consumer(data.consumer)
                .model(data.model)
                .inputTokens(data.inputTokens)
                .outputTokens(data.outputTokens)
                .requestCount(data.requestCount)
                .authorized(false)
                .build());
        }

        return entries;
    }

    /**
     * Check if a Prometheus ai_route label value matches the expected route resource name.
     * The ai_route label may include additional provider info, e.g.
     * "ai-route-nex-agi-nex-n2-pro.internal" matches "ai-route-nex.internal".
     */
    private static boolean matchesRoute(String routeResourceName, String prometheusRoute) {
        if (StringUtils.isEmpty(routeResourceName) || StringUtils.isEmpty(prometheusRoute)) {
            return false;
        }
        // Exact match
        if (routeResourceName.equals(prometheusRoute)) {
            return true;
        }
        // Prefix match: the Prometheus route may have extra provider suffix before ".internal"
        // e.g. "ai-route-nex-agi-nex-n2-pro.internal" starts with "ai-route-nex-"
        // and routeResourceName is "ai-route-nex.internal"
        String routePrefix = routeResourceName.replace(
            HigressConstants.INTERNAL_RESOURCE_NAME_SUFFIX, "");
        return prometheusRoute.startsWith(routePrefix + "-")
            || prometheusRoute.startsWith(routePrefix + ".");
    }

    private Map<String, Map<String, PrometheusMetricData>> queryPrometheusMetrics() {
        Map<String, Map<String, PrometheusMetricData>> result = new HashMap<>();

        try {
            Map<String, PrometheusMetricData> inputData = queryMetric(INPUT_TOKEN_METRIC);
            Map<String, PrometheusMetricData> outputData = queryMetric(OUTPUT_TOKEN_METRIC);

            for (Map.Entry<String, PrometheusMetricData> entry : inputData.entrySet()) {
                String key = entry.getKey();
                PrometheusMetricData data = entry.getValue();

                PrometheusMetricData outputMetric = outputData.get(key);
                if (outputMetric != null) {
                    data.outputTokens = outputMetric.outputTokens;
                }

                String routeKey = data.route;
                result.computeIfAbsent(routeKey, k -> new HashMap<>()).put(key, data);
            }

            for (Map.Entry<String, PrometheusMetricData> entry : outputData.entrySet()) {
                String key = entry.getKey();
                PrometheusMetricData data = entry.getValue();
                String routeKey = data.route;
                if (!inputData.containsKey(key)) {
                    result.computeIfAbsent(routeKey, k -> new HashMap<>()).put(key, data);
                }
            }
        } catch (Exception e) {
            log.error("Error querying Prometheus metrics", e);
        }

        return result;
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

                if (INPUT_TOKEN_METRIC.equals(metricName)) {
                    metricData.inputTokens = tokenValue;
                    metricData.requestCount = tokenValue > 0 ? 1L : 0L;
                } else if (OUTPUT_TOKEN_METRIC.equals(metricName)) {
                    metricData.outputTokens = tokenValue;
                    if (tokenValue > 0) {
                        metricData.requestCount = 1L;
                    }
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
    public List<AiShadowDetectedAccess> getDetectedAccessesInWindow(Long startTime, Long endTime) {
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
            List<Object[]> rows = detectEventRepository.aggregateByDomain(start / 1000L, end / 1000L);
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
    public Map<String, Object> getDetectedStats(Long startTime, Long endTime) {
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
            List<Object[]> rows = detectEventRepository.aggregateByDomain(start / 1000L, end / 1000L);
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
        stats.put("degraded", degraded);
        return stats;
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
    public Map<String, Object> getDetectedTrend(Long startTime, Long endTime) {
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
            List<Object[]> rows = detectEventRepository.countByBucketAndStatus(startSeconds, endSeconds, stepSeconds);
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
            List<Object[]> rows = detectEventRepository.aggregateByDomain(window[0] / 1000L, window[1] / 1000L);
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
                return "浜戠SaaS AI";
            case "api_integrated_ai":
                return "API闆嗘垚AI";
            case "embedded_ai":
                return "宓屽叆寮廇I";
            case "local_deployed_ai":
                return "鏈湴閮ㄧ讲AI";
            case "ai_agent":
                return "AI Agent";
            default:
                return category;
        }
    }

    private static class PrometheusMetricData {

        String route;
        String consumer;
        String model;
        long inputTokens;
        long outputTokens;
        long requestCount;

        PrometheusMetricData(String route, String consumer, String model) {
            this.route = route;
            this.consumer = consumer;
            this.model = model;
        }
    }
}
