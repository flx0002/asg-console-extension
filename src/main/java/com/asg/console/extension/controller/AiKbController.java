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

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.higress.sdk.exception.ValidationException;
import com.asg.console.extension.controller.dto.KbLicenseStatus;
import com.asg.console.extension.controller.exception.AuthException;
import com.asg.console.extension.controller.dto.KbMeta;
import com.asg.console.extension.controller.dto.KbOnlineSetting;
import com.asg.console.extension.controller.dto.Response;
import com.asg.console.extension.model.AiKbLicense;
import com.asg.console.extension.model.AiKbVersion;
import com.asg.console.extension.service.AiKbService;
import com.asg.console.extension.service.KbLicenseService;
import com.asg.console.extension.service.KbSettingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 分类知识库（KB）管理端点：查看（脱敏）/ 离线上传更新 / 在线拉取更新 / 回滚 /
 * 授权状态与离线导入。
 *
 * <p>查看类端点只返回 {@link KbMeta}（分类数/域名数/哈希/版本），**不含任何域名明文**；
 * 更新类端点受授权门控与厂商验签双重保护，无授权或验签失败即拒绝。
 */
@Slf4j
@Tag(name = "AiKb", description = "AI classification knowledge base: view (masked) / update / rollback / license")
@RestController
@RequestMapping("/v1/ai-kb")
public class AiKbController {

    private AiKbService kbService;
    private KbLicenseService licenseService;
    private KbSettingService settingService;

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 操作人兜底值：请求无有效登录会话时使用。
     */
    private static final String OPERATOR_CONSOLE = "console";

    /** 控制台会话服务类型名（属 console 应用，未随 SDK 暴露到本扩展编译期，故反射调用）。 */
    private static final String SESSION_SERVICE_CLASS = "com.alibaba.higress.console.service.SessionService";

    @Resource
    public void setKbService(AiKbService kbService) {
        this.kbService = kbService;
    }

    @Resource
    public void setLicenseService(KbLicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @Resource
    public void setSettingService(KbSettingService settingService) {
        this.settingService = settingService;
    }

    @GetMapping("/versions")
    @Operation(summary = "List KB versions (newest first, masked metadata only)")
    public ResponseEntity<Response<List<KbMeta>>> listVersions() {
        return ResponseEntity.ok(Response.success(kbService.listVersions()));
    }

    @GetMapping("/active")
    @Operation(summary = "Get the active KB version (masked metadata only)")
    public ResponseEntity<Response<KbMeta>> getActive() {
        return ResponseEntity.ok(Response.success(kbService.getActiveMeta()));
    }

    @PostMapping("/import")
    @Operation(summary = "Import an encrypted .wnt KB update package offline (license-gated + signtool-verified)")
    public ResponseEntity<Response<KbMeta>> importOffline(@RequestBody Map<String, String> body,
        HttpServletRequest servletRequest) {
        // 操作人取自控制台登录会话（会话 Cookie 解析出的登录用户名），不再信任可被任意伪造的 X-Operator 客户端头。
        String operator = resolveOperator(servletRequest);
        // 非法/损坏更新包、无有效授权等属业务级拒绝：统一以 200 + success=false + 可读 message 返回，
        // 前端 unwrap 检出 success=false 后给出可读提示，避免「服务器内部错误 500」把异常栈暴露给用户。
        // bundle 为 .wnt 容器（外层 signtool 头保真 + 内层我方 AES-256-GCM 保机密）的 Base64；
        // 服务层校签+解密后走统一去重/加密落库/网关同步，链路不传输明文。
        try {
            if (body == null) {
                throw new ValidationException("request body is required");
            }
            KbMeta meta = kbService.importWntBundle(body.get("bundle"), AiKbVersion.SRC_OFFLINE, operator);
            return ResponseEntity.ok(Response.success(meta));
        } catch (ValidationException | AuthException e) {
            log.warn("KB offline import rejected: {}", e.getMessage());
            return ResponseEntity.ok(Response.failure(e.getMessage()));
        }
    }

    @PostMapping("/online-update")
    @Operation(summary = "Pull the latest KB from the online server and apply it (license-gated + verified)")
    public ResponseEntity<Response<KbMeta>> onlineUpdate(HttpServletRequest servletRequest) {
        // 操作人取自控制台登录会话（会话 Cookie 解析出的登录用户名），不再信任可被任意伪造的 X-Operator 客户端头。
        String operator = resolveOperator(servletRequest);
        // 在线通道失败（服务器不可达、无有效授权、更新包非法等）属业务级拒绝，
        // 统一以 200 + success=false + 可读 message 返回，前端 unwrap 检出后给出准确提示，
        // 既避免「服务器内部错误 500」裸栈，也避免通用 HTTP 状态码掩盖真实原因。
        try {
            return ResponseEntity.ok(Response.success(kbService.onlineUpdate(operator)));
        } catch (ValidationException | AuthException e) {
            log.warn("KB online update rejected: {}", e.getMessage());
            return ResponseEntity.ok(Response.failure(e.getMessage()));
        }
    }

    @GetMapping("/online-setting")
    @Operation(summary = "Get KB online-update server setting (page url + env default + effective + configured)")
    public ResponseEntity<Response<KbOnlineSetting>> getOnlineSetting() {
        return ResponseEntity.ok(Response.success(settingService.getOnlineSetting()));
    }

    @PostMapping("/online-setting")
    @Operation(summary = "Persist the KB online-update server base url (empty clears, falls back to env)")
    public ResponseEntity<Response<KbOnlineSetting>> updateOnlineSetting(@RequestBody Map<String, String> body) {
        if (body == null || body.get("url") == null) {
            throw new ValidationException("url is required");
        }
        // 地址非法（非 http/https 绝对地址）属业务级拒绝，转 400 + 可读 message，与在线更新保持一致。
        try {
            return ResponseEntity.ok(Response.success(settingService.updateOnlineSetting(body.get("url"))));
        } catch (ValidationException e) {
            log.warn("KB online-setting rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Response.failure(e.getMessage()));
        }
    }

    @PostMapping("/rollback/{versionNo}")
    @Operation(summary = "Roll back the active KB to a stored version")
    public ResponseEntity<Response<KbMeta>> rollback(@PathVariable("versionNo") Long versionNo,
        HttpServletRequest servletRequest) {
        // 操作人取自控制台登录会话（会话 Cookie 解析出的登录用户名），不再信任可被任意伪造的 X-Operator 客户端头。
        return ResponseEntity.ok(Response.success(kbService.rollback(versionNo, resolveOperator(servletRequest))));
    }

    @PostMapping("/sync-gateway")
    @Operation(summary = "Re-sync the active KB categories to the ai-shadow-detect gateway plugin")
    public ResponseEntity<Response<Boolean>> syncGateway() {
        return ResponseEntity.ok(Response.success(kbService.syncActiveToGateway()));
    }

    @GetMapping("/license")
    @Operation(summary = "Get KB license status (masked)")
    public ResponseEntity<Response<KbLicenseStatus>> licenseStatus() {
        return ResponseEntity.ok(Response.success(licenseService.getStatus()));
    }

    @PostMapping("/license/import")
    @Operation(summary = "Import an offline license file")
    public ResponseEntity<Response<KbLicenseStatus>> importLicense(@RequestBody Map<String, String> body) {
        if (body == null || body.get("rawLicense") == null) {
            throw new ValidationException("rawLicense is required");
        }
        // 返回本次导入尝试的结果（非回读旧库）：非授权文件被拒时不会误显旧有效授权为“成功”。
        return ResponseEntity.ok(
            Response.success(licenseService.importLicense(body.get("rawLicense"), AiKbLicense.SRC_OFFLINE)));
    }

    /**
     * 解析 KB 操作人 = 控制台登录用户名。
     *
     * <p>控制台的登录鉴权（会话 Cookie / Authorization 头）由 console 应用的
     * {@code com.alibaba.higress.console.service.SessionService} 承担；该类型未随 Higress SDK 暴露到
     * 本扩展的编译期，故经 Spring 容器反射调用其 {@code validateSession(request)} 取当前登录用户。
     * KB 端点未被 console 的鉴权切面（仅匹配 {@code com.alibaba.higress.console.controller..*Controller}）覆盖，
     * 无法依赖其预置的会话上下文，因此在此显式校验会话。无有效登录会话时回退 {@link #OPERATOR_CONSOLE}。
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
            log.debug("KB operator: no resolvable console session user, falling back to {}", OPERATOR_CONSOLE, e);
        }
        return OPERATOR_CONSOLE;
    }
}
