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
import javax.persistence.Id;
import javax.persistence.Table;

import lombok.Data;

/**
 * AI 分类知识库授权（license）状态（单行表，id=1）。
 *
 * <p>记录最后一次成功导入/激活的授权凭证与其校验结果。授权门控更新：
 * {@code status=valid} 且未过期时允许应用 KB 更新；否则更新入口禁用，
 * 检测仍使用最后一次有效库。原始凭证 {@code rawLicense} 仅存 Base64 密文/签名体，
 * 不含私钥。支持离线文件与在线激活两种来源。
 */
@Data
@Entity
@Table(name = "ai_kb_license")
public class AiKbLicense {

    public static final String STATUS_VALID = "valid";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_INVALID = "invalid";
    public static final String STATUS_ABSENT = "absent";

    public static final String SRC_OFFLINE = "offline";
    public static final String SRC_ONLINE = "online";

    /** 授权类型：正式（永久有效）。 */
    public static final String TYPE_FORMAL = "formal";
    /** 授权类型：临时（有到期时间）。 */
    public static final String TYPE_TEMPORARY = "temporary";

    @Id
    private Long id = 1L;

    /** 授权编号。 */
    @Column(name = "license_id", length = 128)
    private String licenseId;

    /** 授权主体（客户/项目名）。 */
    @Column(name = "subject", length = 255)
    private String subject;

    /** 绑定的设备 ESN（空=不绑定设备）。对齐 TEG 的 SN 校验语义。 */
    @Column(name = "device_fingerprint", length = 128)
    private String deviceFingerprint;

    /** 授权绑定的设备 ESN（同 device_fingerprint，保留显式字段以对齐 TEG 授权结构）。 */
    @Column(name = "esn", length = 128)
    private String esn;

    /** 是否校验设备 ESN（对齐 TEG checkSn，默认 true）；false=不绑定设备。 */
    @Column(name = "check_sn", nullable = false)
    private boolean checkSn = true;

    /** 授权类型：formal（正式/永久） / temporary（临时）。 */
    @Column(name = "license_type", length = 32)
    private String licenseType;

    /** 授权产品名称（如 工业防火墙 / ASG）。 */
    @Column(name = "product_name", length = 128)
    private String productName;

    @Column(name = "product_version", length = 64)
    private String productVersion;

    /** 授权公司/客户名。 */
    @Column(name = "company_name", length = 255)
    private String companyName;

    /** 合同号。 */
    @Column(name = "contract_no", length = 128)
    private String contractNo;

    /** 授权型号。 */
    @Column(name = "license_model", length = 255)
    private String licenseModel;

    /** 授权点数。 */
    @Column(name = "license_value")
    private Integer licenseValue;

    /** IEG 客户标识。 */
    @Column(name = "ieg_customer_id", length = 128)
    private String iegCustomerId;

    /** IEG 授权数量。 */
    @Column(name = "ieg_authorized_count")
    private Integer iegAuthorizedCount;

    /** 授权功能位（逗号分隔名称），KB 更新需含 ai_kb_update。 */
    @Column(name = "features", length = 512)
    private String features;

    /** 授权功能位（含各自签发/过期时间）JSON，形如 [{"name","createTime","endTime"}]。 */
    @Column(name = "function_items", columnDefinition = "TEXT")
    private String functionItems;

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** 原始授权文件内容（Base64），用于审计与再校验。 */
    @Column(name = "raw_license", columnDefinition = "TEXT")
    private String rawLicense;

    /** valid / expired / invalid / absent。 */
    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_ABSENT;

    /** 最近一次校验失败原因（status!=valid 时展示）。 */
    @Column(name = "reason", length = 512)
    private String reason;

    /** offline / online。 */
    @Column(name = "source", length = 16)
    private String source;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;
}
