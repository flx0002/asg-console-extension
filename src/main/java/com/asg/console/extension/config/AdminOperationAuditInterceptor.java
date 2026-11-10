/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.alibaba.fastjson.JSONObject;
import com.asg.console.extension.service.AuditChainService;

/**
 * Fallback audit for every console write operation and export action.
 *
 * <p>Registering one interceptor instead of editing each controller keeps new endpoints covered
 * automatically. Endpoints that already write a richer, business-specific audit entry
 * (AiShadowServiceImpl / AiShadowCaptureConfigServiceImpl) call {@link #markWritten()} so this
 * interceptor skips them and the same operation is not recorded twice.</p>
 *
 * <p>Request bodies are intentionally not captured: reading the stream here would require a
 * wrapping filter. The audited tuple is method + path + status + client ip + operator, and
 * business parameters stay in the existing fine-grained entries.</p>
 */
public class AdminOperationAuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminOperationAuditInterceptor.class);
    private static final String WRITTEN_ATTR = "asg.adminAuditWritten";
    private static final String ADMIN_SESSION = "admin_ops";
    private static final String RECORD_TYPE = "admin_operation";
    private static final String OPERATOR_CONSOLE = "console";
    /** High-frequency data-plane batch upload; auditing it would flood the table. */
    private static final String DATA_PLANE_REPORT_PATH = "/v1/ai-shadow/detect-events";

    private final AuditChainService auditChainService;

    public AdminOperationAuditInterceptor(AuditChainService auditChainService) {
        this.auditChainService = auditChainService;
    }

    /** Mark the current request as already audited. No-op outside a web request (scheduled jobs). */
    public static void markWritten() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.setAttribute(WRITTEN_ATTR, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
        Object handler, Exception ex) {
        if (!(handler instanceof HandlerMethod) || isWritten(request)) {
            return;
        }
        String method = request.getMethod();
        String path = request.getRequestURI();
        boolean write = "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method);
        boolean export = "GET".equals(method) && path.contains("/export");
        if ((!write && !export) || !path.startsWith("/v1/") || path.startsWith(DATA_PLANE_REPORT_PATH)) {
            return;
        }
        try {
            long timestampMs = System.currentTimeMillis();
            String eventId = timestampMs + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            String operator = resolveOperator(request);
            JSONObject entry = new JSONObject();
            entry.put("event_id", eventId);
            entry.put("timestamp_ms", timestampMs);
            entry.put("session_id", ADMIN_SESSION);
            entry.put("record_types", Collections.singletonList(RECORD_TYPE));
            entry.put("blocked", false);
            entry.put("identity_trusted", true);
            entry.put("user_id", operator);
            entry.put("user_name", operator);
            entry.put("action", method + " " + path);
            entry.put("source", "admin_http");
            entry.put("http_method", method);
            entry.put("http_path", path);
            entry.put("http_status", response.getStatus());
            // X-Forwarded-For is caller-controlled; keep the socket address as the
            // auditable client_ip and store the header separately for reference only.
            entry.put("client_ip", request.getRemoteAddr());
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isEmpty()) {
                entry.put("forwarded_for", forwarded);
            }
            if (request.getQueryString() != null) {
                entry.put("query_string", request.getQueryString());
            }
            if (ex != null) {
                entry.put("error", ex.getClass().getSimpleName());
            }
            auditChainService.writeAuditLog(entry.toJSONString(), eventId, ADMIN_SESSION,
                timestampMs, operator, true, null);
        } catch (Exception e) {
            // Auditing must never break the business request (same contract as writeAdminAudit).
            log.error("Failed to write fallback admin audit for {} {}", method, path, e);
        }
    }

    private static boolean isWritten(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(WRITTEN_ATTR));
    }

    /** Same identity source as AiShadowServiceImpl.resolveOperator: Basic auth user, else console. */
    private static String resolveOperator(HttpServletRequest request) {
        try {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Basic ")) {
                String decoded = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
                int separator = decoded.indexOf(':');
                String user = separator > 0 ? decoded.substring(0, separator) : decoded;
                return user.isEmpty() ? OPERATOR_CONSOLE : user;
            }
        } catch (Exception e) {
            log.debug("Failed to resolve operator identity", e);
        }
        return OPERATOR_CONSOLE;
    }

}
