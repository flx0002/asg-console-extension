/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.controller;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.higress.sdk.exception.ValidationException;
import com.asg.console.extension.controller.dto.ComplianceKbContent;
import com.asg.console.extension.controller.dto.ComplianceKbLicense;
import com.asg.console.extension.controller.dto.ComplianceKbVersionView;
import com.asg.console.extension.controller.dto.Response;
import com.asg.console.extension.controller.exception.AuthException;
import com.asg.console.extension.model.ComplianceKbVersion;
import com.asg.console.extension.service.ComplianceKbService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;

/**
 * 内容合规词库（BE-011）管理端点：查看（完整词库内容）/ 离线上传更新 / 在线拉取更新 / 回滚 /
 * 同步网关 / 授权状态。端点前缀 {@code /v1/compliance-kb/}，与 ai-kb 完全独立，逐一匹配前端
 * {@code services/compliance-kb.ts} 契约。
 *
 * <p>{@code /content} 返回附录 A 词库完整内容（国标公开、不涉密）；更新类端点受
 * {@code content_compliance_kb} 授权门控与厂商验签双重保护，无授权或验签失败即拒绝。
 */
@Slf4j
@Tag(name = "ComplianceKb",
    description = "Content compliance knowledge base (GB/T 45654 App. A): view / update / rollback / gateway sync / license")
@RestController
@RequestMapping("/v1/compliance-kb")
public class ComplianceKbController {

    private ComplianceKbService complianceKbService;

    @Autowired
    private ApplicationContext applicationContext;

    /** 操作人兜底值：请求无有效登录会话时使用。 */
    private static final String OPERATOR_CONSOLE = "console";

    /** 控制台会话服务类型名（属 console 应用，未随 SDK 暴露到本扩展编译期，故反射调用）。 */
    private static final String SESSION_SERVICE_CLASS = "com.alibaba.higress.console.service.SessionService";

    @Resource
    public void setComplianceKbService(ComplianceKbService complianceKbService) {
        this.complianceKbService = complianceKbService;
    }

    @GetMapping("/content")
    @Operation(summary = "Get the active compliance KB full content (GB/T 45654 App. A categories/keywords/regex)")
    public ResponseEntity<Response<ComplianceKbContent>> content() {
        return ResponseEntity.ok(Response.success(complianceKbService.getContent()));
    }

    @GetMapping("/versions")
    @Operation(summary = "List compliance KB versions (newest first, masked metadata only)")
    public ResponseEntity<Response<List<ComplianceKbVersionView>>> listVersions() {
        return ResponseEntity.ok(Response.success(complianceKbService.listVersions()));
    }

    @GetMapping("/active")
    @Operation(summary = "Get the active compliance KB version (masked metadata only)")
    public ResponseEntity<Response<ComplianceKbVersionView>> getActive() {
        return ResponseEntity.ok(Response.success(complianceKbService.getActiveVersion()));
    }

    @PostMapping("/import")
    @Operation(summary = "Import an encrypted .wnt compliance KB package offline (license-gated + signtool-verified)")
    public ResponseEntity<Response<ComplianceKbVersionView>> importOffline(@RequestBody Map<String, String> body,
        HttpServletRequest servletRequest) {
        String operator = resolveOperator(servletRequest);
        // 非法/损坏更新包、无有效授权等属业务级拒绝：统一以 200 + success=false + 可读 message 返回，
        // 前端 unwrap 检出 success=false 后给出可读提示，避免 500 裸栈。
        try {
            if (body == null) {
                throw new ValidationException("request body is required");
            }
            ComplianceKbVersionView view =
                complianceKbService.importWntBundle(body.get("bundle"), ComplianceKbVersion.SRC_OFFLINE, operator);
            return ResponseEntity.ok(Response.success(view));
        } catch (ValidationException | AuthException e) {
            log.warn("Compliance KB offline import rejected: {}", e.getMessage());
            return ResponseEntity.ok(Response.failure(e.getMessage()));
        }
    }

    @PostMapping("/online-update")
    @Operation(summary = "Pull the latest compliance KB from the online server and apply it (license-gated + verified)")
    public ResponseEntity<Response<ComplianceKbVersionView>> onlineUpdate(HttpServletRequest servletRequest) {
        String operator = resolveOperator(servletRequest);
        try {
            return ResponseEntity.ok(Response.success(complianceKbService.onlineUpdate(operator)));
        } catch (ValidationException | AuthException e) {
            log.warn("Compliance KB online update rejected: {}", e.getMessage());
            return ResponseEntity.ok(Response.failure(e.getMessage()));
        }
    }

    @PostMapping("/rollback/{versionNo}")
    @Operation(summary = "Roll back the active compliance KB to a stored version")
    public ResponseEntity<Response<ComplianceKbVersionView>> rollback(@PathVariable("versionNo") Long versionNo,
        HttpServletRequest servletRequest) {
        return ResponseEntity.ok(
            Response.success(complianceKbService.rollback(versionNo, resolveOperator(servletRequest))));
    }

    @PostMapping("/sync-gateway")
    @Operation(summary = "Re-sync the active compliance KB to the ai-prompt-guard gateway plugin")
    public ResponseEntity<Response<Boolean>> syncGateway() {
        return ResponseEntity.ok(Response.success(complianceKbService.syncActiveToGateway()));
    }

    @GetMapping("/license")
    @Operation(summary = "Get compliance KB update license status (content_compliance_kb feature bit)")
    public ResponseEntity<Response<ComplianceKbLicense>> licenseStatus() {
        return ResponseEntity.ok(Response.success(complianceKbService.licenseStatus()));
    }

    /**
     * 解析操作人 = 控制台登录用户名（会话 Cookie 解析）。与 {@code AiKbController} 同机制：
     * console 的 {@code SessionService} 未随 SDK 暴露到编译期，故反射调用其 {@code validateSession(request)}。
     * KB 端点未被 console 鉴权切面覆盖，无有效会话时回退 {@link #OPERATOR_CONSOLE}。
     */
    private String resolveOperator(HttpServletRequest request) {
        if (request == null) {
            return OPERATOR_CONSOLE;
        }
        try {
            Class<?> sessionServiceType = Class.forName(SESSION_SERVICE_CLASS);
            Object sessionService = applicationContext.getBean(sessionServiceType);
            Method validateSession = sessionServiceType.getMethod("validateSession", HttpServletRequest.class);
            Object user = validateSession.invoke(sessionService, request);
            if (user != null) {
                Object name = user.getClass().getMethod("getName").invoke(user);
                if (name != null && StringUtils.isNotBlank(name.toString())) {
                    return name.toString();
                }
            }
        } catch (Exception e) {
            log.debug("Compliance KB operator: no resolvable console session user, falling back to {}",
                OPERATOR_CONSOLE, e);
        }
        return OPERATOR_CONSOLE;
    }
}
