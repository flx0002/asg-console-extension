/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 授权校验结果（不含私钥/签名原文）。字段对齐工业防火墙（TEG）授权：设备 ESN、公司/产品、
 * 授权类型（临时/正式）、授权型号/点数、IEG 客户与授权数、功能位、生效/到期时间等。
 */
@Data
public class LicenseInfo {

    /** KB 更新所需的功能位。 */
    public static final String FEATURE_KB_UPDATE = "ai_kb_update";

    /** 授权类型：正式（永久有效）。 */
    public static final String TYPE_FORMAL = "formal";
    /** 授权类型：临时（有到期时间）。 */
    public static final String TYPE_TEMPORARY = "temporary";

    // ===== 核心校验字段 =====
    private String licenseId;
    /** 授权主体（客户/项目名）。 */
    private String subject;
    /** 授权绑定的设备 ESN（与本机 ESN 比对）。 */
    private String esn;
    /** 是否校验设备 ESN（对齐 TEG checkSn；默认 true）。false=该授权不绑定设备，跳过 ESN 比对。 */
    private boolean checkSn = true;
    /** 兼容旧字段：设备绑定标识，取值同 {@link #esn}。 */
    private String deviceFingerprint;
    /** 功能位名称列表（由 {@link #functionItems} 派生的功能位 ID，兼容落库/展示/门控）。 */
    private List<String> features = new ArrayList<>();
    /** 功能位（含各自签发/过期时间），对齐平台「授权功能项」逐位有效期。 */
    private List<FunctionItem> functionItems = new ArrayList<>();
    /** 生效时间（载荷 createTime）。 */
    private LocalDateTime issuedAt;
    /** 到期时间（载荷 endTime；正式授权可为空=永久）。 */
    private LocalDateTime expiresAt;

    // ===== 全量对齐字段（展示/审计用）=====
    private String version;
    private String productName;
    private String productVersion;
    private String companyName;
    private String contractNo;
    /** formal（正式/永久） / temporary（临时）。 */
    private String licenseType;
    private String licenseModel;
    private String licenseName;
    private Integer licenseValue;
    private String iegCustomerId;
    private Integer iegAuthorizedCount;
    private String endCustomerAcceptName;
    private String customerAcceptCompanyEmail;
    private String customerAcceptTelephone;

    /** 是否有效（验签通过 ∧ 未过期 ∧ ESN 匹配 ∧ 含所需功能位）。 */
    private boolean valid;
    /** 无效原因（valid=false 时展示）。 */
    private String reason;

    /** 单个授权功能位：稳定的功能位 ID + 各自的签发/过期时间（显示名称不入库，由后端目录按 ID 解析）。 */
    @Data
    public static class FunctionItem {
        /** 功能位唯一 ID（如 {@code ai_kb_update}），与厂商授权目录一一对应，作为身份/门控/签名依据。 */
        private String id;
        /** 签发时间（载荷内原始字符串，可空）。 */
        private String createTime;
        /** 过期时间（载荷内原始字符串，空/缺省=永久）。 */
        private String endTime;

        public FunctionItem() {
        }

        public FunctionItem(String id, String createTime, String endTime) {
            this.id = id;
            this.createTime = createTime;
            this.endTime = endTime;
        }

        public LocalDateTime parseCreate() {
            return DatLicenseVerifier.parseTime(createTime);
        }

        public LocalDateTime parseEnd() {
            return DatLicenseVerifier.parseTime(endTime);
        }
    }

    public boolean hasFeature(String feature) {
        return findFunction(feature) != null;
    }

    /** 按功能位 ID 查找（trim 后精确匹配）。 */
    public FunctionItem findFunction(String feature) {
        if (feature == null || functionItems == null) {
            return null;
        }
        for (FunctionItem fi : functionItems) {
            if (fi != null && fi.getId() != null && fi.getId().trim().equals(feature)) {
                return fi;
            }
        }
        return null;
    }

    /** 是否正式（永久）授权。 */
    public boolean isFormal() {
        return TYPE_FORMAL.equalsIgnoreCase(licenseType) || "正式".equals(licenseType);
    }

    public static LicenseInfo invalid(String reason) {
        LicenseInfo info = new LicenseInfo();
        info.setValid(false);
        info.setReason(reason);
        return info;
    }
}
