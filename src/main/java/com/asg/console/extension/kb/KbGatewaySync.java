/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import com.alibaba.higress.sdk.model.WasmPluginInstance;
import com.alibaba.higress.sdk.model.WasmPluginInstanceScope;
import com.alibaba.higress.sdk.service.WasmPluginInstanceService;
import com.asg.console.extension.constant.AsgPluginConstants;

import lombok.extern.slf4j.Slf4j;

/**
 * 把 active KB 的分类库同步写入 {@code ai-shadow-detect} 全局 WasmPlugin CR 的
 * {@code categories} 字段——网关强制阻断与 dns-policy（继续从 CR 读）由此统一取自 KB。
 *
 * <p>只覆盖 {@code categories} 一个键，保留 CR 其余配置（blocked_code / blocked_message /
 * enable_body_feature / mode 等）。KB 无有效分类时跳过下发，绝不用空值覆盖 CR。
 *
 * <p>失败为「尽力而为」：KB 已落库为权威源，CR 同步失败仅告警，可用
 * {@code POST /v1/ai-kb/sync-gateway} 重试。
 */
@Slf4j
@Service
public class KbGatewaySync {

    private static final String KEY_CATEGORIES = "categories";

    private WasmPluginInstanceService wasmPluginInstanceService;

    @Resource
    public void setWasmPluginInstanceService(WasmPluginInstanceService wasmPluginInstanceService) {
        this.wasmPluginInstanceService = wasmPluginInstanceService;
    }

    /**
     * 同步 KB 分类到网关 CR。
     *
     * @return true 表示已成功写入 CR；false 表示跳过（无分类 / CR 未部署）
     */
    public boolean syncCategories(KbBundle bundle) {
        List<Map<String, Object>> categories = KbCategoryMapper.toGatewayCategories(bundle);
        if (categories == null) {
            log.warn("Active KB has no valid categories, skip gateway sync (CR categories unchanged)");
            return false;
        }
        WasmPluginInstance instance = wasmPluginInstanceService.query(
            WasmPluginInstanceScope.GLOBAL, null, AsgPluginConstants.AI_SHADOW_DETECT, false);
        if (instance == null) {
            log.warn("Global WasmPlugin '{}' not found, skip gateway sync", AsgPluginConstants.AI_SHADOW_DETECT);
            return false;
        }
        Map<String, Object> configurations = instance.getConfigurations();
        if (configurations == null) {
            configurations = new HashMap<>(2);
            instance.setConfigurations(configurations);
        }
        configurations.put(KEY_CATEGORIES, categories);
        wasmPluginInstanceService.addOrUpdate(instance);
        log.info("KB synced to '{}' WasmPlugin CR: categories={}", AsgPluginConstants.AI_SHADOW_DETECT,
            categories.size());
        return true;
    }
}
