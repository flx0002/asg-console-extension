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
import java.util.List;

import com.alibaba.fastjson.annotation.JSONField;

import lombok.Data;

/**
 * AI 分类知识库 bundle（明文结构）。序列化为 JSON 后由厂商私钥签名、AES-256-GCM
 * 加密落库；明文仅存在于内存匹配路径，永不入列、不出接口（脱敏展示见 {@link KbMeta}）。
 *
 * <p>字段与 bypass-guard 的 CategoryRule / WasmPlugin categories 同构，便于 P2 直接
 * 下发：{@code name/label/riskLevel/domains/suffixes}。
 */
@Data
public class KbBundle {

    /** 库版本号（与 AiKbVersion.versionNo 对应）。 */
    private long kbVersion;

    /** 生成时间（ISO-8601 字符串）。 */
    private String generatedAt;

    /** 分类条目。 */
    private List<Category> categories = new ArrayList<>();

    /** 单个分类。字段以 fastjson @JSONField 映射为 snake_case，与网关 CR / bypass 同源。 */
    @Data
    public static class Category {
        @JSONField(name = "name")
        private String name;
        @JSONField(name = "label")
        private String label;
        /** high / medium / critical / low。 */
        @JSONField(name = "risk_level")
        private String riskLevel;
        @JSONField(name = "domains")
        private List<String> domains = new ArrayList<>();
        @JSONField(name = "suffixes")
        private List<String> suffixes = new ArrayList<>();
        /** 请求体特征关键字（网关 body-feature 检测用），与 CR 同源。 */
        @JSONField(name = "body_features")
        private List<String> bodyFeatures = new ArrayList<>();
        /** 路径匹配模式（网关 path 检测用），与 CR 同源。 */
        @JSONField(name = "path_patterns")
        private List<String> pathPatterns = new ArrayList<>();
    }

    /** 域名总数（脱敏统计用）。 */
    public int domainTotal() {
        int n = 0;
        if (categories != null) {
            for (Category c : categories) {
                n += (c.getDomains() == null ? 0 : c.getDomains().size());
                n += (c.getSuffixes() == null ? 0 : c.getSuffixes().size());
            }
        }
        return n;
    }

    /** 分类数（脱敏统计用）。 */
    public int categoryTotal() {
        return categories == null ? 0 : categories.size();
    }
}
