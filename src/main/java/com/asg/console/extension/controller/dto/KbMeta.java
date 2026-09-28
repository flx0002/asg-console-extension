/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.controller.dto;

import java.time.LocalDateTime;

import com.asg.console.extension.model.AiKbVersion;

import lombok.Data;

/**
 * KB 版本脱敏视图。**不含任何域名明文**，仅暴露分类数/域名数/哈希/版本/签名算法等元信息，
 * 满足「授权用户也不可见明文」——Console 只能看到统计与指纹，无法导出域名清单。
 */
@Data
public class KbMeta {

    private Long id;
    private Long versionNo;
    private String label;
    private String changelog;
    private String sigAlgorithm;
    /** 明文 bundle 的 SHA-256 指纹（完整性校验/展示用，不可逆推域名）。 */
    private String kbHash;
    private Integer categoryCount;
    private Integer domainCount;
    private String status;
    private String source;
    private String operator;
    private LocalDateTime createdAt;

    public static KbMeta from(AiKbVersion v) {
        KbMeta m = new KbMeta();
        m.setId(v.getId());
        m.setVersionNo(v.getVersionNo());
        m.setLabel(v.getLabel());
        m.setChangelog(v.getChangelog());
        m.setSigAlgorithm(v.getSigAlgorithm());
        m.setKbHash(v.getKbHash());
        m.setCategoryCount(v.getCategoryCount());
        m.setDomainCount(v.getDomainCount());
        m.setStatus(v.getStatus());
        m.setSource(v.getSource());
        m.setOperator(v.getOperator());
        m.setCreatedAt(v.getCreatedAt());
        return m;
    }
}
