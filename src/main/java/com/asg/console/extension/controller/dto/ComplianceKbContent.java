/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.controller.dto;

import java.util.ArrayList;
import java.util.List;

import com.asg.console.extension.kb.ComplianceKbBundle;

import lombok.Data;

/**
 * 内容合规词库「当前生效词库完整内容」视图（对应前端 {@code ComplianceKbContent}）。
 *
 * <p>与 {@link ComplianceKbBundle} 同构，另附带生效版本的 {@code versionNo}/{@code kbHash}。
 * 词库内容本身（标准/附录/分组/小类/关键词/正则/样本）非机密（附录 A 为公开国标），故可全量返回前端展示；
 * 机密性约束仅适用于 ai-kb 的域名清单，本词库不涉密。
 */
@Data
public class ComplianceKbContent {

    /** 生效版本的业务版本号。 */
    private Long versionNo;

    /** 生效 bundle 明文 SHA-256 指纹。 */
    private String kbHash;

    private String standard;
    private String appendix;
    private String generatedBy;
    private Integer categoryTotal;
    private Integer keywordTotal;

    /** 大类分组（含小类、关键词、正则、样本）。 */
    private List<ComplianceKbBundle.Section> sections = new ArrayList<>();

    /** 由 bundle + 版本元信息构造。 */
    public static ComplianceKbContent of(ComplianceKbBundle bundle, Long versionNo, String kbHash) {
        ComplianceKbContent c = new ComplianceKbContent();
        c.setVersionNo(versionNo);
        c.setKbHash(kbHash);
        if (bundle != null) {
            c.setStandard(bundle.getStandard());
            c.setAppendix(bundle.getAppendix());
            c.setGeneratedBy(bundle.getGeneratedBy());
            c.setCategoryTotal(bundle.getCategoryTotal() != null ? bundle.getCategoryTotal() : bundle.categoryCount());
            c.setKeywordTotal(bundle.getKeywordTotal() != null ? bundle.getKeywordTotal() : bundle.keywordCount());
            c.setSections(bundle.getSections() == null ? new ArrayList<>() : bundle.getSections());
        }
        return c;
    }
}
