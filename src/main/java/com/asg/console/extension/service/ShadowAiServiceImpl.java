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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.alibaba.higress.sdk.constant.CommonKey;
import com.alibaba.higress.sdk.constant.HigressConstants;
import com.alibaba.higress.sdk.constant.plugin.BuiltInPluginName;
import com.alibaba.higress.sdk.exception.BusinessException;
import com.alibaba.higress.sdk.model.CommonPageQuery;
import com.alibaba.higress.sdk.model.PaginatedResult;
import com.asg.console.extension.model.ShadowAiActionRequest;
import com.asg.console.extension.model.ShadowAiDetectedAccess;
import com.asg.console.extension.model.ShadowAiDnsPolicy;
import com.asg.console.extension.model.ShadowAiEntry;
import com.asg.console.extension.model.ShadowAiModeRequest;
import com.asg.console.extension.model.ShadowAiStatus;
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
public class ShadowAiServiceImpl implements ShadowAiService {

    private static final String MODE_MONITORING = "monitoring";
    private static final String MODE_ENFORCEMENT = "enforcement";
    private static final String ACTION_AUTHORIZE = "authorize";
    private static final String ACTION_BLOCK = "block";
    private static final String CONSUMER_NONE = "none";

    private static final String PROMETHEUS_QUERY_PATH = "/api/v1/query";
    private static final String PROMETHEUS_RANGE_PATH = "/api/v1/query_range";
    private static final String INPUT_TOKEN_METRIC = "route_upstream_model_consumer_metric_input_token";
    private static final String OUTPUT_TOKEN_METRIC = "route_upstream_model_consumer_metric_output_token";
    private static final String SHADOW_AI_DETECT_METRIC_PREFIX = "shadow_ai_detect_category_";

    /** PromQL for the hourly detection trend: per-status increase of the detect counter over each 1h bucket. */
    private static final String SHADOW_AI_TREND_QUERY =
        "sum by (status) (increase(shadow_ai_detect_category_domain_risk_status_requests[1h]))";
    /** Bypass collector exposes its own counter with a different name and labels. */
    private static final String SHADOW_AI_BYPASS_TREND_QUERY =
        "sum by (status) (increase(shadow_ai_detect_bypass_requests_total[1h]))";
    /** Stable legend ordering for trend series. */
    private static final List<String> TREND_STATUS_ORDER = Arrays.asList("blocked", "allowed", "monitored");

    /** Fixed virtual session holding all shadow AI admin (authorization) operations. */
    private static final String ADMIN_OPS_SESSION_ID = "admin_ops";
    /** Record type tag for admin operations, queryable via the audit chain recordType filter. */
    private static final String ADMIN_OPS_RECORD_TYPE = "admin_operation";
    /** Fallback operator when the console request carries no resolvable identity. */
    private static final String OPERATOR_CONSOLE = "console";

    private final WasmPluginInstanceService wasmPluginInstanceService;
    private final ConsumerService consumerService;
    private final AiRouteService aiRouteService;
    private final String prometheusBaseUrl;
    private final AuditChainService auditChainService;
    private final ShadowAiDnsPolicyService dnsPolicyService;

    public ShadowAiServiceImpl(WasmPluginInstanceService wasmPluginInstanceService,
        ConsumerService consumerService, AiRouteService aiRouteService, String prometheusBaseUrl,
        AuditChainService auditChainService, ShadowAiDnsPolicyService dnsPolicyService) {
        this.wasmPluginInstanceService = wasmPluginInstanceService;
        this.consumerService = consumerService;
        this.aiRouteService = aiRouteService;
        this.prometheusBaseUrl = StringUtils.defaultIfBlank(prometheusBaseUrl,
            "http://higress-console-prometheus.higress-system:9090/prometheus");
        this.auditChainService = auditChainService;
        this.dnsPolicyService = dnsPolicyService;
    }

    @Override
    public final List<ShadowAiStatus> getStatus() {
        PaginatedResult<AiRoute> aiRoutes = aiRouteService.list(null);
        if (aiRoutes == null || CollectionUtils.isEmpty(aiRoutes.getData())) {
            return Collections.emptyList();
        }

        Map<String, Map<String, PrometheusMetricData>> prometheusData = queryPrometheusMetrics();

        List<ShadowAiStatus> result = new ArrayList<>();
        for (AiRoute aiRoute : aiRoutes.getData()) {
            String routeResourceName = buildRouteResourceName(aiRoute.getName());
            try {
                ShadowAiStatus status = buildShadowAiStatus(aiRoute.getName(), routeResourceName, prometheusData);
                result.add(status);
            } catch (Exception e) {
                log.error("Error building shadow AI status for route: {}", aiRoute.getName(), e);
                result.add(ShadowAiStatus.builder()
                    .routeName(aiRoute.getName())
                    .mode(MODE_MONITORING)
                    .authEnabled(false)
                    .authorizedConsumers(Collections.emptyList())
                    .shadowAiList(Collections.emptyList())
                    .build());
            }
        }
        return result;
    }

    @Override
    public final ShadowAiStatus getStatus(String routeName) {
        AiRoute aiRoute = aiRouteService.query(routeName);
        if (aiRoute == null) {
            return null;
        }

        String routeResourceName = buildRouteResourceName(routeName);
        Map<String, Map<String, PrometheusMetricData>> prometheusData = queryPrometheusMetrics();

        return buildShadowAiStatus(routeName, routeResourceName, prometheusData);
    }

    @Override
    public final ShadowAiStatus setMode(ShadowAiModeRequest request) {
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
    public final ShadowAiStatus performAction(ShadowAiActionRequest request) {
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

    private ShadowAiStatus buildShadowAiStatus(String routeName, String routeResourceName,
        Map<String, Map<String, PrometheusMetricData>> prometheusData) {

        WasmPluginInstance keyAuthInstance = wasmPluginInstanceService.query(
            WasmPluginInstanceScope.ROUTE, routeResourceName, BuiltInPluginName.KEY_AUTH, true);

        boolean authEnabled = keyAuthInstance != null && Boolean.TRUE.equals(keyAuthInstance.getEnabled());
        // Determine mode based on identify_only config:
        // - enabled=true + identify_only=false → enforcement mode (reject unauthenticated)
        // - enabled=true + identify_only=true  → monitoring mode (identify but don't reject)
        // - enabled=false                      → monitoring mode (legacy, can't identify consumers)
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

        List<ShadowAiEntry> shadowAiList = buildShadowAiEntries(routeResourceName, authorizedConsumers, authEnabled, prometheusData);

        return ShadowAiStatus.builder()
            .routeName(routeName)
            .mode(mode)
            .authEnabled(authEnabled)
            .authorizedConsumers(authorizedConsumers)
            .shadowAiList(shadowAiList)
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

    private List<ShadowAiEntry> buildShadowAiEntries(String routeResourceName, List<String> authorizedConsumers,
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

        List<ShadowAiEntry> entries = new ArrayList<>();
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

            entries.add(ShadowAiEntry.builder()
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
    public List<ShadowAiDetectedAccess> getDetectedAccesses() {
        List<ShadowAiDetectedAccess> result = new ArrayList<>();
        // IR-003: accesses to authorized domains are removed from the shadow AI list.
        List<String> authorizedDomains = loadAuthorizedDomains();
        try {
            // After adding stats_tags, Prometheus exports the metric as:
            // shadow_ai_detect_category_domain_risk_status_requests{category="...", domain="...", risk="...", status="..."}
            String queryUrl = prometheusBaseUrl + PROMETHEUS_QUERY_PATH
                + "?query=" + java.net.URLEncoder.encode("shadow_ai_detect_category_domain_risk_status_requests", "UTF-8");
            HttpURLConnection connection = (HttpURLConnection)new URL(queryUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                log.warn("Prometheus query returned non-200 status: {} for shadow AI detect metric", responseCode);
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

                long requestCount = 0;
                if (value != null && value.size() >= 2) {
                    try {
                        requestCount = Long.parseLong(value.getString(1));
                    } catch (NumberFormatException e) {
                        log.warn("Failed to parse request count: {}", value.getString(1));
                    }
                }

                // Parse metric using Prometheus labels (new format with "." separator)
                // Format: shadow_ai_detect_requests{category="saas_ai", domain="www_deepseek_com", risk="high", status="allowed"}
                String category = metric.getString("category");
                String domain = metric.getString("domain");
                String riskLevel = metric.getString("risk");
                String status = metric.getString("status");
                if (status == null) {
                    status = "allowed";
                }

                // Restore dots and hyphens that were sanitized in metric values
                domain = restoreDomainFromMetric(domain);

                if (isDomainAuthorized(domain, authorizedDomains)) {
                    continue;
                }

                result.add(ShadowAiDetectedAccess.builder()
                    .sni(domain)
                    .category(category)
                    .categoryLabel(getCategoryLabel(category))
                    .riskLevel(riskLevel)
                    .status(status)
                    .requestCount(requestCount)
                    .build());
            }
        } catch (Exception e) {
            log.error("Error querying shadow AI detect metrics", e);
        }
        return result;
    }

    /**
     * Restore domain name from metric value.
     * In Wasm plugin, dots and hyphens are replaced with underscores in metric names.
     * We try to restore them by matching against known domains in the config.
     */
    private static String restoreDomainFromMetric(String metricDomain) {
        // Common AI service domains - used to restore dots/hyphens from underscored metric names
        String[] knownDomains = {
            "api.openai.com", "chat.openai.com", "api.anthropic.com", "claude.ai",
            "gemini.google.com", "deepseek.com", "api.deepseek.com", "chat.deepseek.com",
            "www.deepseek.com", "mistral.ai", "api.mistral.ai", "coze.com", "www.coze.com",
            "doubao.com", "www.doubao.com", "kimi.moonshot.cn", "chatglm.cn", "tongyi.aliyun.com",
            "yiyan.baidu.com", "chat.zhipu.ai", "api.groq.com", "copilot.github.com", "api.githubcopilot.com",
            "cursor.sh", "codeium.com", "windsurf.com", "notion.so",
            "models.openclaw.ai", "api.openclaw.ai", "langchain.com", "winclaw.ai"
        };

        // Convert known domains to metric format and compare
        for (String known : knownDomains) {
            String metricFormat = known.replace(".", "_").replace("-", "_");
            if (metricFormat.equals(metricDomain)) {
                return known;
            }
        }

        // Fallback: try heuristic restoration (replace first few underscores with dots)
        // This is imperfect but better than showing underscores
        return metricDomain;
    }

    @Override
    public List<Map<String, Object>> getDetectedTrend(int hours) {
        // Clamp the lookback window: 1h ~ 7d, default 24h.
        int window = Math.min(Math.max(hours, 1), 168);
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            long end = System.currentTimeMillis() / 1000L;
            long start = end - window * 3600L;
            // Two sources: gateway-side WasmPlugin counter and bypass collector counter.
            // They are separate Prometheus metrics, so aggregate by (status, timestamp).
            Map<String, Long> counts = new HashMap<>();
            Map<String, Long> timestamps = new HashMap<>();
            mergeTrendSeries(counts, timestamps, queryRange(SHADOW_AI_TREND_QUERY, start, end, 3600));
            mergeTrendSeries(counts, timestamps, queryRange(SHADOW_AI_BYPASS_TREND_QUERY, start, end, 3600));
            if (counts.isEmpty()) {
                return result;
            }
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd HH:00").withZone(ZoneId.systemDefault());
            for (Map.Entry<String, Long> e : counts.entrySet()) {
                Map<String, Object> p = new HashMap<>();
                p.put("time", fmt.format(Instant.ofEpochSecond(timestamps.get(e.getKey()))));
                p.put("status", e.getKey().substring(0, e.getKey().indexOf('|')));
                p.put("count", e.getValue());
                result.add(p);
            }
            // Stable ordering: status (blocked/allowed/monitored first), then time ascending.
            result.sort((a, b) -> {
                int sa = TREND_STATUS_ORDER.indexOf(a.get("status"));
                int sb = TREND_STATUS_ORDER.indexOf(b.get("status"));
                if (sa != sb) {
                    return Integer.compare(sa < 0 ? Integer.MAX_VALUE : sa, sb < 0 ? Integer.MAX_VALUE : sb);
                }
                return String.valueOf(a.get("time")).compareTo(String.valueOf(b.get("time")));
            });
        } catch (Exception e) {
            log.error("Error querying shadow AI detection trend", e);
        }
        return result;
    }

    /** Accumulate one range-query series array into the (status|ts -> count) aggregation maps. */
    private void mergeTrendSeries(Map<String, Long> counts, Map<String, Long> timestamps, JSONArray series) {
        if (series == null) {
            return;
        }
        for (int i = 0; i < series.size(); i++) {
            JSONObject item = series.getJSONObject(i);
            String status = item.getJSONObject("metric").getString("status");
            JSONArray values = item.getJSONArray("values");
            if (StringUtils.isEmpty(status) || values == null) {
                continue;
            }
            for (int j = 0; j < values.size(); j++) {
                JSONArray point = values.getJSONArray(j);
                if (point == null || point.size() < 2) {
                    continue;
                }
                long ts = point.getLongValue(0);
                long count = (long) parsePromValue(point.getString(1));
                String key = status + "|" + ts;
                counts.merge(key, count, Long::sum);
                timestamps.putIfAbsent(key, ts);
            }
        }
    }

    /**
     * Prometheus range query: evaluates the PromQL at each step over [start, end]
     * and returns the raw result array (one series per label combination).
     */
    private JSONArray queryRange(String query, long start, long end, long stepSeconds) {
        try {
            String queryUrl = prometheusBaseUrl + PROMETHEUS_RANGE_PATH + "?query="
                + java.net.URLEncoder.encode(query, "UTF-8")
                + "&start=" + start + "&end=" + end + "&step=" + stepSeconds;
            HttpURLConnection connection = (HttpURLConnection)new URL(queryUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(15000);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                log.warn("Prometheus query_range returned non-200 status: {}", responseCode);
                return null;
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
                log.warn("Prometheus query_range was not successful");
                return null;
            }
            JSONObject data = response.getJSONObject("data");
            return data == null ? null : data.getJSONArray("result");
        } catch (Exception e) {
            log.error("Error querying Prometheus range: {}", query, e);
            return null;
        }
    }

    private static double parsePromValue(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Load the authorized domain list from the DNS detection policy
     * (shadow_ai_dns_policy). Failures degrade to an empty list so the
     * detected list stays complete when the policy cannot be read.
     */
    private List<String> loadAuthorizedDomains() {
        try {
            if (dnsPolicyService == null) {
                return Collections.emptyList();
            }
            ShadowAiDnsPolicy policy = dnsPolicyService.getPolicy();
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
     * Normalize a domain for authorization matching: metric label values may
     * carry underscores where the Wasm plugin sanitized dots/hyphens.
     */
    private static String normalizeDomain(String domain) {
        if (StringUtils.isEmpty(domain)) {
            return "";
        }
        String normalized = domain.trim().toLowerCase()
            .replace('_', '.').replace('-', '.');
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
            WasmPluginInstanceScope.GLOBAL, null, AsgPluginConstants.SHADOW_AI_DETECT, false);

        if (instance == null) {
            instance = wasmPluginInstanceService.createEmptyInstance(AsgPluginConstants.SHADOW_AI_DETECT);
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
    public Map<String, Object> getAuthorizedDomains() {
        Map<String, Object> result = new HashMap<>();
        List<String> domains = new ArrayList<>();
        String mode = MODE_MONITORING;
        try {
            ShadowAiDnsPolicy policy = dnsPolicyService.getPolicy();
            if (policy != null) {
                if (StringUtils.isNotEmpty(policy.getMode())) {
                    mode = policy.getMode();
                }
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
        return result;
    }

    @Override
    public Map<String, Object> updateAuthorizedDomains(String mode, List<String> addDomains,
        List<String> removeDomains) {
        ShadowAiDnsPolicy policy = dnsPolicyService.getPolicy();
        String currentMode = policy != null && StringUtils.isNotEmpty(policy.getMode())
            ? policy.getMode()
            : MODE_MONITORING;

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

        Map<String, Object> result = new HashMap<>();
        result.put("mode", targetMode);
        result.put("domains", merged);
        return result;
    }

    @Override
    public String getDetectMode() {
        WasmPluginInstance instance = wasmPluginInstanceService.query(
            WasmPluginInstanceScope.GLOBAL, null, AsgPluginConstants.SHADOW_AI_DETECT, false);

        if (instance != null && instance.getConfigurations() != null) {
            Object mode = instance.getConfigurations().get("mode");
            if (mode instanceof String) {
                return (String) mode;
            }
        }
        return MODE_MONITORING;
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
            entry.put("source", "shadow_ai_console");
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
