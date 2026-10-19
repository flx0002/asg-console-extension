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

import lombok.Data;

/**
 * 内容合规词库 bundle（明文结构，GB/T 45654-2025 附录 A）。与前端内置基线
 * {@code appA_rules.json} 同构：{@code standard/appendix/generatedBy/categoryTotal/keywordTotal/sections[]}。
 *
 * <p>序列化为 JSON 后由厂商 signtool 私钥签名、内层我方 AES-256-GCM 加密（{@code .wnt} 容器），
 * 落库再经 {@link KbCrypto} at-rest 加密；明文仅在内存匹配/下发路径出现。字段全部 camelCase，
 * 与 {@link com.asg.console.extension.kb.ComplianceKbBundle} 的 fastjson 解析、前端
 * {@code interfaces/compliance-kb.ts} 严格一致。
 */
@Data
public class ComplianceKbBundle {

    /** 标准号（如 GB/T 45654-2025）。 */
    private String standard;

    /** 附录标识（如 A）。 */
    private String appendix;

    /** 生成来源标注（可空）。 */
    private String generatedBy;

    /** 小类总数（脱敏统计，随包声明）。 */
    private Integer categoryTotal;

    /** 关键词总数（脱敏统计，随包声明）。 */
    private Integer keywordTotal;

    /** 大类分组。 */
    private List<Section> sections = new ArrayList<>();

    /** 单个大类（A.1/A.2/... 或按国标分组）。 */
    @Data
    public static class Section {
        /** 分组编号（如 A.1）。 */
        private String section;
        /** 分组标题。 */
        private String title;
        /** 对应标准条款。 */
        private String clause;
        /** 该分组下的小类。 */
        private List<Category> categories = new ArrayList<>();
    }

    /** 单个风险小类。 */
    @Data
    public static class Category {
        /** 小类编码（如 A.1-a）。 */
        private String code;
        /** 小类名称。 */
        private String name;
        /** block（拦截） / advisory（免责不拦）。 */
        private String mode;
        /** 免责提示语（仅 advisory 使用）。 */
        private String advice;
        /** 关键词列表。 */
        private List<String> keywords = new ArrayList<>();
        /** 正则表达式列表。 */
        private List<String> patterns = new ArrayList<>();
        /** 该小类词条数。 */
        private Integer keywordCount;
        /** 正/负样本（前端「每类测试」用）。 */
        private Sample sample;
    }

    /** 命中/良性样本对。 */
    @Data
    public static class Sample {
        private String positive;
        private String negative;
    }

    /** 是否空库（无任何小类）——空库不下发、不落 active。 */
    public boolean isEmpty() {
        return categoryCount() == 0;
    }

    /** 小类总数（实算，脱敏统计兜底）。 */
    public int categoryCount() {
        int n = 0;
        if (sections != null) {
            for (Section s : sections) {
                if (s != null && s.getCategories() != null) {
                    n += s.getCategories().size();
                }
            }
        }
        return n;
    }

    /** 关键词总数（实算，优先用各类 keywordCount 之和）。 */
    public int keywordCount() {
        int n = 0;
        if (sections != null) {
            for (Section s : sections) {
                if (s == null || s.getCategories() == null) {
                    continue;
                }
                for (Category c : s.getCategories()) {
                    if (c == null) {
                        continue;
                    }
                    if (c.getKeywordCount() != null) {
                        n += c.getKeywordCount();
                    } else if (c.getKeywords() != null) {
                        n += c.getKeywords().size();
                    }
                }
            }
        }
        return n;
    }

    /** 是否免责（advisory）类。 */
    public static boolean isAdvisory(Category c) {
        return c != null && "advisory".equalsIgnoreCase(c.getMode());
    }
}
