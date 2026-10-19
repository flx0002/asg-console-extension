/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import lombok.Data;

/**
 * 内容合规词库版本条目（BE-011）。镜像 {@link AiKbVersion} 的「at-rest 加密 + 审计签名 + 版本切换」模型，
 * 但库内容为 GB/T 45654 附录 A 词库（非机密域名清单），统计维度为小类数 / 关键词数。
 *
 * <p>传输信封为厂商 {@code .wnt} 容器（外层 signtool ECC 头校签保真 + 内层我方 AES-256-GCM 保机密）；
 * {@code bundleCipher} 为落库密钥的 AES-256-GCM 密文（Base64），{@code signature} 为 {@code .wnt}
 * 头部 raw {@code r‖s}（Base64，审计展示）。同一时刻至多一行 {@code status=active}。
 */
@Data
@Entity
@Table(name = "compliance_kb_version",
    uniqueConstraints = @UniqueConstraint(name = "uk_compliance_kb_version_no", columnNames = "version_no"),
    indexes = {
        @Index(name = "idx_compliance_kb_version_status", columnList = "status"),
        @Index(name = "idx_compliance_kb_version_no", columnList = "version_no")
    })
public class ComplianceKbVersion {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_INACTIVE = "inactive";

    /** 更新来源：online / offline / rollback / builtin。 */
    public static final String SRC_ONLINE = "online";
    public static final String SRC_OFFLINE = "offline";
    public static final String SRC_ROLLBACK = "rollback";
    public static final String SRC_BUILTIN = "builtin";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 单调递增的词库版本号。 */
    @Column(name = "version_no", nullable = false)
    private Long versionNo;

    /** 版本标签。 */
    @Column(name = "label", length = 255)
    private String label;

    /** 变更说明。 */
    @Column(name = "changelog", length = 1024)
    private String changelog;

    /** AES-256-GCM 加密后的整库 bundle（Base64）。 */
    @Column(name = "bundle_cipher", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String bundleCipher;

    /** {@code .wnt} 头部 raw r‖s 签名（Base64），仅供审计展示。 */
    @Column(name = "signature", nullable = false, length = 1024)
    private String signature;

    /** 签名算法标识。 */
    @Column(name = "sig_algorithm", nullable = false, length = 32)
    private String sigAlgorithm;

    /** 明文 bundle 的 SHA-256（hex），完整性校验与去重用。 */
    @Column(name = "kb_hash", nullable = false, length = 64)
    private String kbHash;

    /** 小类数（脱敏统计）。 */
    @Column(name = "category_count")
    private Integer categoryCount;

    /** 关键词数（脱敏统计）。 */
    @Column(name = "keyword_count")
    private Integer keywordCount;

    /** active / inactive。 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** online / offline / rollback / builtin。 */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    /** 操作者。 */
    @Column(name = "operator", length = 64)
    private String operator;

    /** 最近一次成功下发到网关 CR 的内容哈希。 */
    @Column(name = "gateway_synced_hash", length = 64)
    private String gatewaySyncedHash;

    /** 最近一次成功同步到网关的时间。 */
    @Column(name = "gateway_synced_at")
    private LocalDateTime gatewaySyncedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
