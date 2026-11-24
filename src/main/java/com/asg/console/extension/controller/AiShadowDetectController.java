/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.asg.console.extension.aop.AllowAnonymous;
import com.asg.console.extension.constant.AsgPluginConstants;
import com.asg.console.extension.controller.dto.CaptureConfigResponse;
import com.asg.console.extension.controller.dto.CaptureConfigUpdateRequest;
import com.asg.console.extension.controller.dto.CaptureNic;
import com.asg.console.extension.controller.dto.CaptureNicsResponse;
import com.asg.console.extension.controller.dto.DetectEventReportRequest;
import com.asg.console.extension.controller.dto.DnsPolicyResponse;
import com.asg.console.extension.controller.dto.DnsPolicyUpdateRequest;
import com.asg.console.extension.controller.dto.PageResult;
import com.asg.console.extension.controller.dto.Response;
import com.asg.console.extension.model.AiChannelChangeEvent;
import com.asg.console.extension.model.AiShadowCaptureConfig;
import com.asg.console.extension.model.AiShadowDetectEvent;
import com.asg.console.extension.model.AiShadowDnsPolicy;
import com.asg.console.extension.service.AiShadowCaptureConfigService;
import com.asg.console.extension.service.AiShadowDetectEventService;
import com.asg.console.extension.service.AiShadowDnsPolicyService;
import com.asg.console.extension.service.AiShadowService;
import com.alibaba.higress.sdk.exception.ValidationException;
import com.alibaba.higress.sdk.model.WasmPluginInstance;
import com.alibaba.higress.sdk.model.WasmPluginInstanceScope;
import com.alibaba.higress.sdk.service.WasmPluginInstanceService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * Shadow AI detection APIs: event reporting from security detection components
 * (persisted to MySQL and correlated with the audit chain), query for the
 * management console, and DNS detection policy control (IR-004 / IR-025).
 */
@RestController("AiShadowDetectController")
@RequestMapping("/v1/ai-shadow")
@Tag(name = "Shadow AI Detect APIs")
@Slf4j
public class AiShadowDetectController {

    public static final String DEFAULT_DETECT_TYPE = "dns_ai_shadow";
    public static final String DEFAULT_SOURCE = "dns";
    public static final String DEFAULT_STATUS = "allowed";

    /** Surface of our own channel: access control and disposition (route tab). */
    public static final String SURFACE_ROUTE = "route";
    /** Surface of shadow AI discovery: AI traffic outside registered channels (traffic tab). */
    public static final String SURFACE_SHADOW = "shadow";
    /** Only shadow verdicts are reported today; non_shadow never produces an event. */
    public static final String DEFAULT_VERDICT = "shadow";

    /**
     * Authoritative detectType → surface mapping (整改方案 2.3).
     *
     * <p>It serves two purposes at once: it validates a reported surface, and it fills the surface
     * in when the reporter does not send one. The latter is not a nicety — key-auth's
     * {@code unauthorized_access} events do not carry the field yet, and those are the only rows
     * the route tab has.
     */
    private static final Map<String, String> SURFACE_BY_DETECT_TYPE;

    static {
        Map<String, String> mapping = new HashMap<>();
        // Shadow discovery: gateway-side channel misses, bypass guard, DNS collector.
        mapping.put("gateway_ai_access", SURFACE_SHADOW);
        mapping.put("gateway_unauthorized_access", SURFACE_SHADOW);
        mapping.put("bypass_ai_shadow", SURFACE_SHADOW);
        mapping.put(DEFAULT_DETECT_TYPE, SURFACE_SHADOW);
        // Own-channel disposition: authentication, content inspection, quota.
        mapping.put("unauthorized_access", SURFACE_ROUTE);
        mapping.put("dlp_leak", SURFACE_ROUTE);
        mapping.put("quota_reject", SURFACE_ROUTE);
        SURFACE_BY_DETECT_TYPE = Collections.unmodifiableMap(mapping);
    }

    private AiShadowDetectEventService detectEventService;
    private AiShadowDnsPolicyService dnsPolicyService;
    private AiShadowCaptureConfigService captureConfigService;
    private AiShadowService aiShadowService;
    private WasmPluginInstanceService wasmPluginInstanceService;
    private ApplicationEventPublisher eventPublisher;

    @Value("${asg.collector-token:wnt-asg-collector-2026}")
    private String collectorToken;

    @Resource
    public void setDetectEventService(AiShadowDetectEventService detectEventService) {
        this.detectEventService = detectEventService;
    }

    @Resource
    public void setDnsPolicyService(AiShadowDnsPolicyService dnsPolicyService) {
        this.dnsPolicyService = dnsPolicyService;
    }

    @Resource
    public void setCaptureConfigService(AiShadowCaptureConfigService captureConfigService) {
        this.captureConfigService = captureConfigService;
    }

    @Resource
    public void setAiShadowService(AiShadowService aiShadowService) {
        this.aiShadowService = aiShadowService;
    }

    @Resource
    public void setWasmPluginInstanceService(WasmPluginInstanceService wasmPluginInstanceService) {
        this.wasmPluginInstanceService = wasmPluginInstanceService;
    }

    @Resource
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Report detection events from security detection components. Internal
     * endpoint, protected by the collector token header.
     */
    @PostMapping("/detect-events")
    @AllowAnonymous
    @Operation(summary = "Report shadow AI detect events")
    public ResponseEntity<Response<Integer>> reportEvents(
        @RequestHeader(value = "X-Collector-Token", required = false) String token,
        @RequestBody DetectEventReportRequest request, HttpServletRequest servletRequest) {
        if (!isCollectorTokenValid(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 总开关关闭期间不落库任何事件：影子AI的四个上报方（detect/key-auth/pii/bypass）
        // 共用本端点，这是唯一写入口；关闭即整条判定链停止记录，含 surface=route 准入事件。
        // 返回 200 + 0 而非拒绝：上报是 best-effort，4xx 只会让插件刷错误日志并重试。
        // isFeatureEnabled() 刻意 fail-open：读不到状态时继续记录，绝不静默丢审计。
        if (!isFeatureEnabled()) {
            int dropped = request == null || request.getEvents() == null ? 0 : request.getEvents().size();
            log.info("Shadow AI disabled, dropped {} detect events", dropped);
            return ResponseEntity.ok(Response.success(0));
        }
        if (request == null || request.getEvents() == null || request.getEvents().isEmpty()) {
            throw new ValidationException("events must not be empty");
        }
        List<AiShadowDetectEvent> events = new ArrayList<>(request.getEvents().size());
        for (DetectEventReportRequest.DetectEvent source : request.getEvents()) {
            if (StringUtils.isBlank(source.getDomain())) {
                throw new ValidationException("domain is required for each event");
            }
            AiShadowDetectEvent event = new AiShadowDetectEvent();
            String detectType = StringUtils.defaultIfBlank(source.getDetectType(), DEFAULT_DETECT_TYPE);
            String surface = resolveSurface(source, detectType);
            if (surface == null) {
                // resolveSurface already logged why; one bad event must not take the batch down.
                continue;
            }
            event.setDetectType(detectType);
            event.setDomain(source.getDomain().trim().toLowerCase());
            event.setCategory(source.getCategory());
            event.setRiskLevel(source.getRiskLevel());
            event.setStatus(StringUtils.defaultIfBlank(source.getStatus(), DEFAULT_STATUS));
            event.setSource(StringUtils.defaultIfBlank(source.getSource(), DEFAULT_SOURCE));
            event.setSrcIp(source.getSrcIp());
            event.setSessionId(resolveSessionId(source));
            event.setDetail(source.getDetail());
            event.setSurface(surface);
            event.setVerdict(StringUtils.defaultIfBlank(source.getVerdict(), DEFAULT_VERDICT));
            event.setReason(reasonOf(source));
            event.setRouteHint(StringUtils.trimToNull(source.getRouteHint()));
            if (source.getEventTime() != null) {
                event.setEventTime(
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(source.getEventTime()), ZoneId.systemDefault()));
            }
            events.add(event);
        }
        List<AiShadowDetectEvent> saved = detectEventService.saveEvents(events);
        return ResponseEntity.ok(Response.success(saved.size()));
    }

    /**
     * Reason behind the verdict, taken from the top-level field or, failing that, from the JSON
     * detail blob.
     *
     * <p>The fallback is not redundant: key-auth reports {@code detail={"reason":"unknown_key",...}}
     * and sends no top-level reason. Without it, every route event would carry a null reason while
     * the backfilled history carries one — the same column meaning different things depending on
     * when the row was written. The gateway's detail is plain text
     * ({@code feature=... matched=...}), so the parse must tolerate non-JSON.
     */
    private static String reasonOf(DetectEventReportRequest.DetectEvent source) {
        String reason = StringUtils.trimToNull(source.getReason());
        if (reason != null) {
            return reason;
        }
        String detail = StringUtils.trimToNull(source.getDetail());
        if (detail == null || !detail.startsWith("{")) {
            return null;
        }
        try {
            JSONObject parsed = JSON.parseObject(detail);
            return parsed == null ? null : StringUtils.trimToNull(parsed.getString("reason"));
        } catch (Exception e) {
            // A detail that looks like JSON but is not: the verdict is already safe, so the reason
            // being absent is a display gap, not a data integrity problem.
            return null;
        }
    }

    /**
     * Resolves the surface of one reported event, or returns null when the event must be dropped.
     *
     * <p>Two different situations must not be conflated:
     *
     * <ul>
     *   <li><b>Field absent</b> — not an error. The reporter may predate the field (key-auth does
     *       not send it), so the detectType mapping fills it in. Dropping those would empty the
     *       route tab, which is the opposite of the intent.</li>
     *   <li><b>Field contradicts detectType</b> — an error. Persisting it would file the event
     *       under the wrong tab, and a wrong-but-present surface is harder to notice than a
     *       missing one.</li>
     * </ul>
     *
     * <p>An unmapped detectType defaults to shadow: a new detector that nobody has classified yet
     * should surface in the discovery view rather than vanish from every view.
     */
    private String resolveSurface(DetectEventReportRequest.DetectEvent source, String detectType) {
        String expected = SURFACE_BY_DETECT_TYPE.getOrDefault(detectType, SURFACE_SHADOW);
        String reported = StringUtils.trimToNull(source.getSurface());
        if (reported == null) {
            return expected;
        }
        if (!SURFACE_ROUTE.equals(reported) && !SURFACE_SHADOW.equals(reported)) {
            log.warn("Dropping detect event with unknown surface={} detectType={} domain={}", reported, detectType,
                source.getDomain());
            return null;
        }
        if (!expected.equals(reported)) {
            log.warn("Dropping detect event whose surface={} contradicts detectType={} (expected {}) domain={}",
                reported, detectType, expected, source.getDomain());
            return null;
        }
        return reported;
    }

    /**
     * Query detect events for the management console, with filters and paging.
     * startTime/endTime (epoch millis) narrow the result to one trend bucket when
     * the user drills down from the detection trend chart.
     */
    @GetMapping("/detect-events")
    @Operation(summary = "Query shadow AI detect events")
    public ResponseEntity<Response<PageResult<AiShadowDetectEvent>>> queryEvents(
        @RequestParam(value = "domain", required = false) String domain,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "category", required = false) String category,
        @RequestParam(value = "riskLevel", required = false) String riskLevel,
        @RequestParam(value = "source", required = false) String source,
        @RequestParam(value = "surface", required = false) String surface,
        @RequestParam(value = "reason", required = false) String reason,
        @RequestParam(value = "srcIp", required = false) String srcIp,
        @RequestParam(value = "startTime", required = false) Long startTime,
        @RequestParam(value = "endTime", required = false) Long endTime,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size) {
        Page<AiShadowDetectEvent> result = detectEventService.query(domain, status, category, riskLevel, source,
            surface, reason, srcIp, startTime, endTime, page, size);
        // IR-025/S5: attach weak audit-chain links (domain handling audit +
        // same-source host aggregation) for bypass/dns events.
        detectEventService.attachAuditLinks(result.getContent());
        PageResult<AiShadowDetectEvent> pageResult =
            new PageResult<>(result.getContent(), result.getTotalElements(), result.getNumber(), result.getSize());
        return ResponseEntity.ok(Response.success(pageResult));
    }

    /**
     * Get the DNS detection policy. Called by the DNS collector to fetch the
     * current mode and authorized domains.
     */
    @GetMapping("/dns-policy")
    @AllowAnonymous
    @Operation(summary = "Get DNS shadow AI detection policy")
    public ResponseEntity<Response<DnsPolicyResponse>> getDnsPolicy(
        @RequestHeader(value = "X-Collector-Token", required = false) String token) {
        if (!isCollectorTokenValid(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(Response.success(toPolicyResponse(dnsPolicyService.getPolicy())));
    }

    /**
     * Update the DNS detection policy (monitoring / enforcement mode switch
     * and authorized domains).
     */
    @PutMapping("/dns-policy")
    @Operation(summary = "Update DNS shadow AI detection policy")
    public ResponseEntity<Response<DnsPolicyResponse>> updateDnsPolicy(@RequestBody DnsPolicyUpdateRequest request) {
        if (request == null) {
            throw new ValidationException("request body is required");
        }
        // 走 AiShadowService 而不是 dnsPolicyService 直写：前者会对 diff 写管理审计
        // （authorize_domain / deauthorize_domain），后者只落库不留痕。
        AiShadowDnsPolicy policy = aiShadowService.updateDnsPolicy(request.getMode(), request.getAuthorizedDomains());
        // 这是授权域名的第二条写入口（采集器/策略同步用），与控制台那条同样要触发派生；
        // 漏掉这里就会出现「某条路径改了授权，网关不生效」的间歇性不一致。
        eventPublisher.publishEvent(new AiChannelChangeEvent(this, "dns-policy"));
        return ResponseEntity.ok(Response.success(toPolicyResponse(policy)));
    }

    /**
     * Get the bypass capture configuration. Read by both the management UI and
     * the bypass collector's policy poll. {@code interfaces == null} means
     * never configured — the collector keeps its local yaml; an empty list
     * means capture is explicitly off.
     */
    @GetMapping("/capture/config")
    @AllowAnonymous
    @Operation(summary = "Get bypass capture configuration")
    public ResponseEntity<Response<CaptureConfigResponse>> getCaptureConfig() {
        return ResponseEntity.ok(Response.success(toCaptureConfigResponse(captureConfigService.getConfig())));
    }

    /**
     * Update the bypass capture configuration (capture interfaces and
     * per-interface reinjection control ports). The collector hot-reloads the
     * new selection in its next policy poll cycle (≤30s).
     */
    @PutMapping("/capture/config")
    @Operation(summary = "Update bypass capture configuration")
    public ResponseEntity<Response<CaptureConfigResponse>> updateCaptureConfig(
        @RequestBody CaptureConfigUpdateRequest request) {
        if (request == null) {
            throw new ValidationException("request body is required");
        }
        AiShadowCaptureConfig config =
            captureConfigService.updateConfig(request.getInterfaces(), request.getControlInterfaces());
        return ResponseEntity.ok(Response.success(toCaptureConfigResponse(config)));
    }

    /**
     * Probe the bypass collector for its physical NIC inventory (link state and
     * speed). Returns agentOnline=false instead of an error when the collector
     * is unreachable so the capture page degrades to a clear offline state.
     */
    @GetMapping("/capture/nics")
    @Operation(summary = "Probe bypass collector NIC inventory")
    public ResponseEntity<Response<CaptureNicsResponse>> getCaptureNics() {
        List<CaptureNic> nics = captureConfigService.probeNics();
        CaptureNicsResponse body = new CaptureNicsResponse();
        body.setAgentOnline(nics != null);
        body.setNics(nics == null ? Collections.emptyList() : nics);
        return ResponseEntity.ok(Response.success(body));
    }

    private CaptureConfigResponse toCaptureConfigResponse(AiShadowCaptureConfig config) {
        CaptureConfigResponse response = new CaptureConfigResponse();
        response.setInterfaces(parseCaptureInterfaces(config.getCaptureInterfaces()));
        response.setControlInterfaces(parseControlInterfaces(config.getControlInterfaces()));
        response.setUpdatedAt(config.getUpdatedAt());
        return response;
    }

    /** null stays null (unconfigured) so the collector can tell it apart from []. */
    private List<String> parseCaptureInterfaces(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(raw.split(",")).filter(StringUtils::isNotBlank).collect(Collectors.toList());
    }

    private Map<String, String> parseControlInterfaces(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Collections.emptyMap();
        }
        JSONObject json = JSON.parseObject(raw);
        Map<String, String> controls = new LinkedHashMap<>();
        for (String key : json.keySet()) {
            controls.put(key, StringUtils.defaultString(json.getString(key)));
        }
        return controls;
    }

    /**
     * A missing/invalid collector token is an authentication failure (401), not a server
     * error. AuthException is an unannotated RuntimeException that Spring maps to 500,
     * which hides the real cause from collectors and monitoring.
     */
    private boolean isCollectorTokenValid(String token) {
        return StringUtils.isNotBlank(collectorToken) && collectorToken.equals(token);
    }

    private DnsPolicyResponse toPolicyResponse(AiShadowDnsPolicy policy) {
        List<String> domains = new ArrayList<>();
        if (StringUtils.isNotBlank(policy.getAuthorizedDomains())) {
            domains = Arrays.stream(policy.getAuthorizedDomains().split(",")).filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
        }
        DnsPolicyResponse response = new DnsPolicyResponse();
        response.setMode(policy.getMode());
        response.setEnabled(isFeatureEnabled());
        response.setAuthorizedDomains(domains);
        // IR-001 alignment: expose the gateway AI domain category library so the
        // bypass collector classifies with the same categories/risk levels.
        response.setCategories(loadGatewayCategories());
        return response;
    }

    /**
     * Whether the shadow AI feature is currently enabled, read from the
     * ai-shadow-detect global plugin instance (enabled = !defaultConfigDisable).
     * Defaults to true when the instance is absent or the check fails, matching
     * the product default (feature on until explicitly turned off).
     */
    private boolean isFeatureEnabled() {
        try {
            WasmPluginInstance instance = wasmPluginInstanceService.query(
                WasmPluginInstanceScope.GLOBAL, null, AsgPluginConstants.AI_SHADOW_DETECT, false);
            if (instance == null) {
                return true;
            }
            return !Boolean.FALSE.equals(instance.getEnabled());
        } catch (Exception e) {
            log.warn("Failed to read shadow AI enable state, defaulting to enabled", e);
            return true;
        }
    }

    /**
     * Load the AI domain category library from the ai-shadow-detect global plugin
     * configuration. Each item: {name, label, risk_level, domains, suffixes, ...}.
     * Returns null (collector falls back to its local library) on any failure.
     */
    private List<Map<String, Object>> loadGatewayCategories() {
        try {
            WasmPluginInstance instance = wasmPluginInstanceService.query(
                WasmPluginInstanceScope.GLOBAL, null, AsgPluginConstants.AI_SHADOW_DETECT, false);
            if (instance == null || instance.getConfigurations() == null) {
                return null;
            }
            Object raw = instance.getConfigurations().get("categories");
            if (raw == null) {
                return null;
            }
            JSONArray arr = JSON.parseArray(JSON.toJSONString(raw));
            if (arr == null || arr.isEmpty()) {
                return null;
            }
            List<Map<String, Object>> categories = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                if (item != null && StringUtils.isNotBlank(item.getString("name"))) {
                    categories.add(item);
                }
            }
            return categories.isEmpty() ? null : categories;
        } catch (Exception e) {
            log.warn("Failed to load gateway AI categories, bypass collector will use its local library", e);
            return null;
        }
    }

    /**
     * Session id for one reported detect event, resolved centrally for every reporter
     * (ai-shadow-detect, key-auth, bypass collector and any future source).
     *
     * <p>A reported id wins as-is: the bypass collector already sanitizes business session ids
     * and emits ids like {@code bp:<hash>:<nano>}, so rewriting characters here would break
     * stage-1 data. Only the length is bounded, because the column is varchar(128) and an
     * oversized external header would otherwise fail the whole batch.
     *
     * <p>When the reporter sends no id, derive an access-session id from
     * {@code srcIp x main domain x 30min bucket}. It must be deterministic: Console runs two
     * replicas, and the same session must never get two ids. The fixed bucket only bounds the id
     * lifetime; the idle split is finished by the display-layer fold (LAG over event_time), so a
     * session crossing a bucket boundary yields two alerts rather than merging across days.
     *
     * <p>Package-private for unit tests.
     */
    static String resolveSessionId(DetectEventReportRequest.DetectEvent source) {
        String reported = normalizeSessionId(source.getSessionId());
        if (!reported.isEmpty()) {
            return reported;
        }
        String srcIp = firstIp(source.getSrcIp());
        String domain = registeredDomain(normalizeDomain(source.getDomain()));
        long eventTime = source.getEventTime() != null ? source.getEventTime() : System.currentTimeMillis();
        long bucket = eventTime / (30L * 60 * 1000);
        return "cx:" + hash8(srcIp + "|" + domain + "|" + bucket) + ":" + bucket;
    }

    /** Trim, drop control characters, bound to varchar(128). Never rewrites structural chars. */
    private static String normalizeSessionId(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.trim().replaceAll("[\\p{Cntrl}]", "");
        if (v.length() > 128) {
            int end = 128;
            // varchar(128) counts UTF-16 chars; never leave a dangling high surrogate for JDBC.
            if (Character.isHighSurrogate(v.charAt(end - 1))) {
                end--;
            }
            v = v.substring(0, end);
        }
        return v;
    }

    /** x-forwarded-for may carry a chain; the gateway plugins report only the first hop. */
    private static String firstIp(String raw) {
        if (raw == null) {
            return "unknown";
        }
        int comma = raw.indexOf(',');
        String v = (comma >= 0 ? raw.substring(0, comma) : raw).trim();
        return v.isEmpty() ? "unknown" : v;
    }

    private static String normalizeDomain(String raw) {
        if (raw == null) {
            return "unknown";
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        int colon = v.indexOf(':');
        if (colon >= 0) {
            v = v.substring(0, colon);
        }
        while (v.endsWith(".")) {
            v = v.substring(0, v.length() - 1);
        }
        return v.isEmpty() ? "unknown" : v;
    }

    /** Last two labels only, mirroring the bypass collector (no public suffix table). */
    private static String registeredDomain(String domain) {
        String[] parts = domain.split("\\.");
        if (parts.length <= 2) {
            return domain;
        }
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private static String hash8(String key) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated for every JRE; keep event reporting alive on stripped runtimes.
            digest = String.valueOf(key.hashCode()).getBytes(StandardCharsets.UTF_8);
        }
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder(16);
        for (int i = 0; i < 8 && i < digest.length; i++) {
            int v = digest[i] & 0xff;
            out.append(hex[v >>> 4]).append(hex[v & 0x0f]);
        }
        return out.toString();
    }
}
