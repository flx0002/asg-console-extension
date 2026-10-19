/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.alibaba.higress.sdk.model.WasmPluginInstance;
import com.alibaba.higress.sdk.model.WasmPluginInstanceScope;
import com.alibaba.higress.sdk.service.WasmPluginInstanceService;
import com.asg.console.extension.constant.AsgPluginConstants;

import lombok.extern.slf4j.Slf4j;

/**
 * 把生效内容合规词库下发到 {@code ai-prompt-guard} 全局 WasmPlugin CR——这是「同步到网关」的真正含义：
 * 附录 A 词库被写成数据面可执行的拦截/免责规则，而非仅版本管理。
 *
 * <p>映射（与 {@code ai-prompt-guard} wasm 的 camelCase schema 严格同源，前端提示注入页亦写同结构）：
 * <ul>
 *   <li>{@code mode=block} 小类 → {@code keywordRules=[{name:"<code> <name>", keywords, patterns, enabled:true}]}</li>
 *   <li>{@code mode=advisory} 小类 → {@code advisoryRules=[{name:"<code> <name>", keywords, advice, enabled:true}]}</li>
 * </ul>
 *
 * <p>整库覆盖 {@code keywordRules}/{@code advisoryRules} 两个键，<b>保留</b> CR 其余配置
 * （{@code denyRules/allowRules/detectMode/checkRequest/checkResponse/rejectMessage}）不被清空。
 * 词库为空即跳过下发（绝不用空数组覆盖现有数据面规则）。失败为「尽力而为」：词库已落库为权威源，
 * CR 同步失败仅告警，可用 {@code POST /v1/compliance-kb/sync-gateway} 重试。
 */
@Slf4j
@Service
public class KbComplianceGatewaySync {

    private static final String KEY_KEYWORD_RULES = "keywordRules";
    private static final String KEY_ADVISORY_RULES = "advisoryRules";

    private WasmPluginInstanceService wasmPluginInstanceService;

    @Resource
    public void setWasmPluginInstanceService(WasmPluginInstanceService wasmPluginInstanceService) {
        this.wasmPluginInstanceService = wasmPluginInstanceService;
    }

    /**
     * 同步词库到 {@code ai-prompt-guard} 网关 CR。
     *
     * @return true 表示已成功写入 CR；false 表示跳过（词库为空 / CR 未部署）
     */
    public boolean sync(ComplianceKbBundle bundle) {
        if (bundle == null || bundle.isEmpty()) {
            log.warn("Active compliance KB has no categories, skip gateway sync (CR keywordRules/advisoryRules unchanged)");
            return false;
        }
        List<Map<String, Object>> keywordRules = new ArrayList<>();
        List<Map<String, Object>> advisoryRules = new ArrayList<>();
        toRules(bundle, keywordRules, advisoryRules);
        if (keywordRules.isEmpty() && advisoryRules.isEmpty()) {
            log.warn("Compliance KB produced no rules, skip gateway sync to avoid wiping CR");
            return false;
        }
        WasmPluginInstance instance = wasmPluginInstanceService.query(
            WasmPluginInstanceScope.GLOBAL, null, AsgPluginConstants.AI_PROMPT_GUARD, false);
        if (instance == null) {
            log.warn("Global WasmPlugin '{}' not found, skip gateway sync", AsgPluginConstants.AI_PROMPT_GUARD);
            return false;
        }
        Map<String, Object> configurations = instance.getConfigurations();
        if (configurations == null) {
            configurations = new HashMap<>(4);
            instance.setConfigurations(configurations);
        }
        // 整库覆盖两个键，其余键（denyRules/allowRules/detectMode/check*/rejectMessage）原地保留
        configurations.put(KEY_KEYWORD_RULES, keywordRules);
        configurations.put(KEY_ADVISORY_RULES, advisoryRules);
        wasmPluginInstanceService.addOrUpdate(instance);
        log.info("Compliance KB synced to '{}' WasmPlugin CR: keywordRules={}, advisoryRules={}",
            AsgPluginConstants.AI_PROMPT_GUARD, keywordRules.size(), advisoryRules.size());
        return true;
    }

    /** 遍历分组小类，按 mode 分流到 keywordRules（拦截）/ advisoryRules（免责）。 */
    private void toRules(ComplianceKbBundle bundle, List<Map<String, Object>> keywordRules,
        List<Map<String, Object>> advisoryRules) {
        if (bundle.getSections() == null) {
            return;
        }
        for (ComplianceKbBundle.Section s : bundle.getSections()) {
            if (s == null || s.getCategories() == null) {
                continue;
            }
            for (ComplianceKbBundle.Category c : s.getCategories()) {
                if (c == null || StringUtils.isBlank(c.getName())) {
                    continue;
                }
                String name = ruleName(c);
                if (ComplianceKbBundle.isAdvisory(c)) {
                    Map<String, Object> m = new HashMap<>(4);
                    m.put("name", name);
                    m.put("keywords", c.getKeywords() == null ? new ArrayList<String>() : c.getKeywords());
                    m.put("advice", c.getAdvice() == null ? "" : c.getAdvice());
                    m.put("enabled", Boolean.TRUE);
                    advisoryRules.add(m);
                } else {
                    Map<String, Object> m = new HashMap<>(4);
                    m.put("name", name);
                    m.put("keywords", c.getKeywords() == null ? new ArrayList<String>() : c.getKeywords());
                    m.put("patterns", c.getPatterns() == null ? new ArrayList<String>() : c.getPatterns());
                    m.put("enabled", Boolean.TRUE);
                    keywordRules.add(m);
                }
            }
        }
    }

    /** 规则名 = {@code "<code> <name>"}（与前端 appaLabel 及现网 CR 既有命名一致，便于启停映射）。 */
    private static String ruleName(ComplianceKbBundle.Category c) {
        String code = StringUtils.trimToEmpty(c.getCode());
        return code.isEmpty() ? c.getName().trim() : code + " " + c.getName().trim();
    }
}
