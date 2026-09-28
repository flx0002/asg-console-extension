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

import lombok.Data;

/**
 * AI 分类知识库（KB）版本条目（IR-028 版本快照模式复用）。
 *
 * <p>整库以「加密 + 签名」的单一 bundle 存储：{@code bundleCipher} 为 AES-256-GCM
 * 密文（magic "ASGKB1" + 12B IV + ciphertext，Base64），明文永不出现在任何列中，
 * 满足「授权用户也不可见明文」。{@code signature} 为厂商私钥对明文 bundle JSON 的
 * 签名，更新时用内置公钥验签；验签或授权任一不过即拒绝应用。
 *
 * <p>同一时刻至多一行 {@code status=active}；更新即插入新 active 行并将旧行降级为
 * inactive，回滚即把某历史行重新置为 active。
 */
@Data
@Entity
@Table(name = "ai_kb_version", indexes = {
    @Index(name = "idx_kb_version_status", columnList = "status"),
    @Index(name = "idx_kb_version_no", columnList = "version_no")
})
public class AiKbVersion {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_INACTIVE = "inactive";

    /** 更新来源：online（在线拉取）/ offline（离线上传）/ rollback（回滚）/ builtin（出厂内置）。 */
    public static final String SRC_ONLINE = "online";
    public static final String SRC_OFFLINE = "offline";
    public static final String SRC_ROLLBACK = "rollback";
    public static final String SRC_BUILTIN = "builtin";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 单调递增的库版本号（如 20260922001），用于展示与比较。 */
    @Column(name = "version_no", nullable = false)
    private Long versionNo;

    /** 版本标签/说明（不含域名明文，可安全展示）。 */
    @Column(name = "label", length = 255)
    private String label;

    /** 变更说明。 */
    @Column(name = "changelog", length = 1024)
    private String changelog;

    /** AES-256-GCM 加密后的整库 bundle（Base64）。 */
    @Column(name = "bundle_cipher", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String bundleCipher;

    /** 厂商签名（Base64），对明文 bundle JSON 字节签名。 */
    @Column(name = "signature", nullable = false, length = 1024)
    private String signature;

    /** 签名算法标识：rsa-sha256 / ed25519 / sm2。 */
    @Column(name = "sig_algorithm", nullable = false, length = 32)
    private String sigAlgorithm;

    /** 明文 bundle 的 SHA-256（hex），用于完整性校验与脱敏展示指纹。 */
    @Column(name = "kb_hash", nullable = false, length = 64)
    private String kbHash;

    /** 库内分类数（脱敏统计，供 Console 展示，不含域名）。 */
    @Column(name = "category_count")
    private Integer categoryCount;

    /** 库内域名总数（脱敏统计）。 */
    @Column(name = "domain_count")
    private Integer domainCount;

    /** active / inactive。 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** online / offline / rollback / builtin。 */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    /** 操作者（会话用户名，缺省 unknown）。 */
    @Column(name = "operator", length = 64)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
