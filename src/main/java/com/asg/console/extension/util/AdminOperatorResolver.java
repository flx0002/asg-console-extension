/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.util;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the console operator (login user) for audit records.
 *
 * <p>The console front-end authenticates via an HttpOnly session cookie (see
 * {@code SessionServiceImpl} in the base console), so neither {@code getRemoteUser()} nor a
 * Basic header is present on normal UI requests.  The authoritative answer comes from the
 * console's own {@code SessionService.validateSession()}, which understands both the session
 * cookie and Basic credentials.  Because the extension module has no compile-time dependency
 * on the console classes, the lookup is done through the Spring context with reflection.</p>
 *
 * <p>Resolution order:</p>
 * <ol>
 *   <li>{@code SessionService.validateSession(request)} — cookie login (front-end) and
 *       Basic login (curl / API clients) with <em>verified</em> credentials;</li>
 *   <li>raw Basic header username — best-effort so that failed-auth attempts still carry
 *       an attributable name;</li>
 *   <li>{@code request.getRemoteUser()} — container-managed auth (rare in this stack);</li>
 *   <li>{@code console} as last resort.</li>
 * </ol>
 */
public final class AdminOperatorResolver {

    private static final Logger log = LoggerFactory.getLogger(AdminOperatorResolver.class);
    private static final String OPERATOR_CONSOLE = "console";
    /** Console {@code SessionService} interface — looked up reflectively at runtime. */
    private static final String SESSION_SERVICE_CLASS = "com.alibaba.higress.console.service.SessionService";

    private static volatile ApplicationContext applicationContext;

    private AdminOperatorResolver() {
    }

    /** Wire the Spring context so the console SessionService can be looked up at runtime. */
    public static void setApplicationContext(ApplicationContext ctx) {
        applicationContext = ctx;
    }

    /** Resolve the operator from the current thread-bound request ({@link RequestContextHolder}). */
    public static String resolve() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                return resolve(attrs.getRequest());
            }
        } catch (Exception e) {
            log.debug("No request context available for operator resolution", e);
        }
        return OPERATOR_CONSOLE;
    }

    /**
     * Client IP of the current request, taken from the socket address ({@code getRemoteAddr()}).
     * X-Forwarded-For is caller-controlled and intentionally ignored — the console ingress is
     * rewritten by DNAT so the socket address already carries the real client IP.
     */
    public static String currentClientIp() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null && attrs.getRequest() != null) {
                return attrs.getRequest().getRemoteAddr();
            }
        } catch (Exception e) {
            log.debug("No request context available for client IP resolution", e);
        }
        return "";
    }

    /** Resolve the operator from an explicit request. */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return OPERATOR_CONSOLE;
        }
        String viaSession = resolveViaSessionService(request);
        if (viaSession != null) {
            return viaSession;
        }
        String viaBasic = resolveViaBasicHeader(request);
        if (viaBasic != null) {
            return viaBasic;
        }
        try {
            String remoteUser = request.getRemoteUser();
            if (remoteUser != null && !remoteUser.isEmpty()) {
                return remoteUser;
            }
        } catch (Exception ignored) {
            // container auth not configured — expected
        }
        return OPERATOR_CONSOLE;
    }

    // ------------------------------------------------------------------
    // SessionService.validateSession — cookie + verified Basic
    // ------------------------------------------------------------------

    private static String resolveViaSessionService(HttpServletRequest request) {
        try {
            ApplicationContext ctx = applicationContext;
            if (ctx == null) {
                return null;
            }
            Class<?> svcClass = Class.forName(SESSION_SERVICE_CLASS);
            Object svc = ctx.getBean(svcClass);
            if (svc == null) {
                return null;
            }
            Method validate = svcClass.getMethod("validateSession", HttpServletRequest.class);
            Object user = validate.invoke(svc, request);
            if (user == null) {
                return null;
            }
            Object name = user.getClass().getMethod("getName").invoke(user);
            if (name instanceof String && !((String) name).isEmpty()) {
                return (String) name;
            }
        } catch (Exception e) {
            log.debug("SessionService-based operator resolution failed", e);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Raw Basic header — best-effort (no credential verification)
    // ------------------------------------------------------------------

    private static String resolveViaBasicHeader(HttpServletRequest request) {
        try {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Basic ")) {
                String decoded =
                    new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
                int sep = decoded.indexOf(':');
                String user = sep > 0 ? decoded.substring(0, sep) : decoded;
                return user.isEmpty() ? null : user;
            }
        } catch (Exception e) {
            log.debug("Basic header operator resolution failed", e);
        }
        return null;
    }
}
