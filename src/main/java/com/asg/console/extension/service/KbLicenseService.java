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
import com.asg.console.extension.kb.LicenseInfo;

/**
 * KB 授权（license）服务：导入/激活授权、查询状态、更新门控。
 *
 * <p>门控语义：{@link #canUpdate()} 为 true 时才允许应用 KB 更新；否则更新入口拒绝，
 * 检测继续使用最后一次有效库。离线授权文件与在线激活都经同一校验路径。
 */
public interface KbLicenseService {

    /** 当前授权状态（脱敏，含本机指纹；实时复核过期）。 */
    KbLicenseStatus getStatus();

    /**
     * 导入并校验授权凭证。
     *
     * @param rawLicense 授权文件原始内容
     * @param source     offline / online
     * @return 校验结果；无论通过与否都会落库记录最后一次尝试
     */
    LicenseInfo importLicense(String rawLicense, String source);

    /** 是否允许 KB 更新（授权有效 ∧ 未过期 ∧ 含 ai_kb_update）。 */
    boolean canUpdate();
}
