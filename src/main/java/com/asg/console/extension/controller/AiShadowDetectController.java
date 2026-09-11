/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.controller;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
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
import com.asg.console.extension.controller.dto.DetectEventReportRequest;
import com.asg.console.extension.controller.dto.DnsPolicyResponse;
import com.asg.console.extension.controller.dto.DnsPolicyUpdateRequest;
import com.asg.console.extension.controller.dto.PageResult;
import com.asg.console.extension.controller.dto.Response;
import com.asg.console.extension.controller.exception.AuthException;
import com.asg.console.extension.model.AiShadowDetectEvent;
import com.asg.console.extension.model.AiShadowDnsPolicy;
import com.asg.console.extension.service.AiShadowDetectEventService;
import com.asg.console.extension.service.AiShadowDnsPolicyService;
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

    private AiShadowDetectEventService detectEventService;
    private AiShadowDnsPolicyService dnsPolicyService;
    private WasmPluginInstanceService wasmPluginInstanceService;

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
    public void setWasmPluginInstanceService(WasmPluginInstanceService wasmPluginInstanceService) {
        this.wasmPluginInstanceService = wasmPluginInstanceService;
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
        checkCollectorToken(token);
        if (request == null || request.getEvents() == null || request.getEvents().isEmpty()) {
            throw new ValidationException("events must not be empty");
        }
        List<AiShadowDetectEvent> events = new ArrayList<>(request.getEvents().size());
        for (DetectEventReportRequest.DetectEvent source : request.getEvents()) {
            if (StringUtils.isBlank(source.getDomain())) {
                throw new ValidationException("domain is required for each event");
            }
            AiShadowDetectEvent event = new AiShadowDetectEvent();
            event.setDetectType(StringUtils.defaultIfBlank(source.getDetectType(), DEFAULT_DETECT_TYPE));
            event.setDomain(source.getDomain().trim().toLowerCase());
            event.setCategory(source.getCategory());
            event.setRiskLevel(source.getRiskLevel());
            event.setStatus(StringUtils.defaultIfBlank(source.getStatus(), DEFAULT_STATUS));
            event.setSource(StringUtils.defaultIfBlank(source.getSource(), DEFAULT_SOURCE));
            event.setSrcIp(source.getSrcIp());
            event.setSessionId(source.getSessionId());
            event.setDetail(source.getDetail());
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
     * Query detect events for the management console, with filters and paging.
     */
    @GetMapping("/detect-events")
    @Operation(summary = "Query shadow AI detect events")
    public ResponseEntity<Response<PageResult<AiShadowDetectEvent>>> queryEvents(
        @RequestParam(value = "domain", required = false) String domain,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "category", required = false) String category,
        @RequestParam(value = "riskLevel", required = false) String riskLevel,
        @RequestParam(value = "source", required = false) String source,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size) {
        Page<AiShadowDetectEvent> result =
            detectEventService.query(domain, status, category, riskLevel, source, page, size);
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
        checkCollectorToken(token);
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
        AiShadowDnsPolicy policy = dnsPolicyService.updatePolicy(request.getMode(), request.getAuthorizedDomains());
        return ResponseEntity.ok(Response.success(toPolicyResponse(policy)));
    }

    private void checkCollectorToken(String token) {
        if (StringUtils.isBlank(collectorToken) || !collectorToken.equals(token)) {
            throw new AuthException("Invalid collector token");
        }
    }

    private DnsPolicyResponse toPolicyResponse(AiShadowDnsPolicy policy) {
        List<String> domains = new ArrayList<>();
        if (StringUtils.isNotBlank(policy.getAuthorizedDomains())) {
            domains = Arrays.stream(policy.getAuthorizedDomains().split(",")).filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
        }
        DnsPolicyResponse response = new DnsPolicyResponse();
        response.setMode(policy.getMode());
        response.setAuthorizedDomains(domains);
        // IR-001 alignment: expose the gateway AI domain category library so the
        // bypass collector classifies with the same categories/risk levels.
        response.setCategories(loadGatewayCategories());
        return response;
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
}
