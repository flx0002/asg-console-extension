/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

import com.asg.console.extension.controller.dto.KbLicenseStatus;

/**
 * KB 授权（license）服务：导入授权、查询状态、更新门控。
 *
 * <p>授权唯一来源为离线导入 {@code .dat}（在线激活已移除）。门控语义：{@link #canUpdate()}
 * 为 true 时才允许应用 KB 更新；否则更新入口拒绝，检测继续使用最后一次有效库。
 */
public interface KbLicenseService {

    /** 当前授权状态（脱敏，含本机指纹；实时复核过期）。 */
    KbLicenseStatus getStatus();

    /**
     * 导入并校验授权凭证，返回**本次导入尝试**的状态（而非回读旧库）。
     *
     * <p>安全约束：仅验签通过的真实 {@code .dat}（含过期/ESN 不符/缺功能位）才覆盖落库；
     * 非授权文件/篡改/错签名一律拒绝且**不改动既有授权**（防止坏文件摧毁有效授权）。
     *
     * @param rawLicense 授权文件原始内容
     * @param source     固定 offline（在线激活已移除）
     */
    KbLicenseStatus importLicense(String rawLicense, String source);

    /** 是否允许 KB 更新（授权有效 ∧ 未过期 ∧ 含 ai_kb_update）。 */
    boolean canUpdate();
}
