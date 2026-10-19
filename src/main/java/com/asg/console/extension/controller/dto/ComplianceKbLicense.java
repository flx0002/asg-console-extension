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

import lombok.Data;

/**
 * 内容合规词库更新授权状态（对应前端 {@code ComplianceKbLicense}）。
 *
 * <p>独立反映 {@code content_compliance_kb} 功能位：{@code canUpdate} 为该位存在且未过期的实时判定；
 * {@code featureName} 为后端目录解析的权威显示名；{@code expiresAt} 为该位自身到期时间（空=永久）；
 * {@code reason} 为不可更新时的人类可读原因。
 */
@Data
public class ComplianceKbLicense {

    private boolean canUpdate;
    private String featureName;
    private LocalDateTime expiresAt;
    private String reason;
}
