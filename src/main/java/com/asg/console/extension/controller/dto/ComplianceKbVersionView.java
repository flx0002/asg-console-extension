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

import com.asg.console.extension.model.ComplianceKbVersion;

import lombok.Data;

/**
 * 内容合规词库版本脱敏视图（对应前端 {@code ComplianceKbVersion}）。不含 bundle 明文，仅元信息。
 */
@Data
public class ComplianceKbVersionView {

    private Long id;
    private Long versionNo;
    private String label;
    private Integer categoryCount;
    private Integer keywordCount;
    private String kbHash;
    private String status;
    private String source;
    private String operator;
    private LocalDateTime createdAt;
    /** 当前 active 是否已成功下发网关（{@code kbHash == gatewaySyncedHash}）。 */
    private boolean gatewaySynced;
    /** 本次导入与当前 active 内容相同、未产生新版本时为 true。 */
    private boolean unchanged;

    public static ComplianceKbVersionView from(ComplianceKbVersion v) {
        ComplianceKbVersionView d = new ComplianceKbVersionView();
        d.setId(v.getId());
        d.setVersionNo(v.getVersionNo());
        d.setLabel(v.getLabel());
        d.setCategoryCount(v.getCategoryCount());
        d.setKeywordCount(v.getKeywordCount());
        d.setKbHash(v.getKbHash());
        d.setStatus(v.getStatus());
        d.setSource(v.getSource());
        d.setOperator(v.getOperator());
        d.setCreatedAt(v.getCreatedAt());
        d.setGatewaySynced(v.getKbHash() != null && v.getKbHash().equals(v.getGatewaySyncedHash()));
        return d;
    }
}
