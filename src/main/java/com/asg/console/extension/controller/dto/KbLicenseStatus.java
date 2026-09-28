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
import java.util.List;

import lombok.Data;

/**
 * KB 授权状态视图（脱敏）。不含原始凭证/签名体，仅展示校验结果与绑定信息。
 * {@code deviceFingerprint} 为本机 ESN（设备标识），供运维在厂商授权工具里绑定设备用；
 * {@code esn} 为授权文件内绑定的 ESN（有效授权时两者相等）。
 */
@Data
public class KbLicenseStatus {

    /** valid / expired / invalid / absent。 */
    private String status;
    /** 是否允许 KB 更新（status=valid 且未过期且含 ai_kb_update 功能位）。 */
    private boolean canUpdate;
    private String licenseId;
    private String subject;
    private List<String> features;
    /** 授权功能位（含各自签发/过期时间），供产品级授权页展示。 */
    private List<FunctionView> functions;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    /** 校验失败原因（status!=valid 时展示）。 */
    private String reason;
    private String source;
    private LocalDateTime activatedAt;
    /** 本机设备 ESN（授权绑定用，可复制到厂商授权工具）。 */
    private String deviceFingerprint;

    // ===== 全量对齐字段（展示用，对齐 TEG 授权）=====
    /** 授权文件内绑定的 ESN。 */
    private String esn;
    /** 是否校验设备 ESN（对齐 TEG checkSn，默认 true）。 */
    private boolean checkSn = true;
    /** formal（正式/永久） / temporary（临时）。 */
    private String licenseType;
    private String productName;
    private String productVersion;
    private String companyName;
    private String contractNo;
    private String licenseModel;
    private Integer licenseValue;
    private String iegCustomerId;
    private Integer iegAuthorizedCount;

    /**
     * 单个授权功能位视图：名称 + 各自签发/过期时间 + 后端计算的有效期标志。
     * {@code active} 由后端按当前时间与 endTime 判定（空 endTime=永久→true），
     * 前端直接展示，避免时区/序列化格式导致的误判。
     */
    @Data
    public static class FunctionView {
        private String name;
        /** 签发时间（功能位自身）。 */
        private LocalDateTime issuedAt;
        /** 过期时间（功能位自身，空=永久）。 */
        private LocalDateTime expiresAt;
        /** 该功能位当前是否有效（未过期且时间合法）。 */
        private boolean active;

        public FunctionView() {
        }

        public FunctionView(String name, LocalDateTime issuedAt, LocalDateTime expiresAt) {
            this.name = name;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
        }

        public FunctionView(String name, LocalDateTime issuedAt, LocalDateTime expiresAt, boolean active) {
            this.name = name;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
            this.active = active;
        }
    }
}
