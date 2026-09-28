/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.controller;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.higress.sdk.exception.ValidationException;
import com.asg.console.extension.controller.dto.KbLicenseStatus;
import com.asg.console.extension.controller.dto.KbMeta;
import com.asg.console.extension.controller.dto.Response;
import com.asg.console.extension.kb.KbOnlineClient;
import com.asg.console.extension.model.AiKbLicense;
import com.asg.console.extension.model.AiKbVersion;
import com.asg.console.extension.service.AiKbService;
import com.asg.console.extension.service.KbLicenseService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 分类知识库（KB）管理端点：查看（脱敏）/ 离线上传更新 / 在线拉取更新 / 回滚 /
 * 授权状态与导入/在线激活。
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
    private KbOnlineClient onlineClient;

    @Resource
    public void setKbService(AiKbService kbService) {
        this.kbService = kbService;
    }

    @Resource
    public void setLicenseService(KbLicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @Resource
    public void setOnlineClient(KbOnlineClient onlineClient) {
        this.onlineClient = onlineClient;
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
    @Operation(summary = "Import a KB update bundle offline (license-gated + signature-verified)")
    public ResponseEntity<Response<KbMeta>> importOffline(@RequestBody Map<String, String> body,
        @RequestHeader(value = "X-Operator", required = false) String operator) {
        if (body == null) {
            throw new ValidationException("request body is required");
        }
        KbMeta meta = kbService.importBundle(body.get("bundle"), body.get("signature"), body.get("sigAlgorithm"),
            body.get("label"), body.get("changelog"), AiKbVersion.SRC_OFFLINE, operator);
        return ResponseEntity.ok(Response.success(meta));
    }

    @PostMapping("/online-update")
    @Operation(summary = "Pull the latest KB from the online server and apply it (license-gated + verified)")
    public ResponseEntity<Response<KbMeta>> onlineUpdate(
        @RequestHeader(value = "X-Operator", required = false) String operator) {
        return ResponseEntity.ok(Response.success(kbService.onlineUpdate(operator)));
    }

    @PostMapping("/rollback/{versionNo}")
    @Operation(summary = "Roll back the active KB to a stored version")
    public ResponseEntity<Response<KbMeta>> rollback(@PathVariable("versionNo") Long versionNo,
        @RequestHeader(value = "X-Operator", required = false) String operator) {
        return ResponseEntity.ok(Response.success(kbService.rollback(versionNo, operator)));
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
        licenseService.importLicense(body.get("rawLicense"), AiKbLicense.SRC_OFFLINE);
        return ResponseEntity.ok(Response.success(licenseService.getStatus()));
    }

    @PostMapping("/license/activate")
    @Operation(summary = "Activate the license online (report device fingerprint, fetch signed license)")
    public ResponseEntity<Response<KbLicenseStatus>> activateOnline() {
        String rawLicense = onlineClient.activateLicense();
        licenseService.importLicense(rawLicense, AiKbLicense.SRC_ONLINE);
        return ResponseEntity.ok(Response.success(licenseService.getStatus()));
    }
}
