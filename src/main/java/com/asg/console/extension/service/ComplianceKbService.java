/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

import java.util.List;

import com.asg.console.extension.controller.dto.ComplianceKbContent;
import com.asg.console.extension.controller.dto.ComplianceKbLicense;
import com.asg.console.extension.controller.dto.ComplianceKbVersionView;

/**
 * 内容合规词库（BE-011）服务：导入（离线/在线）、版本查询、生效内容、回滚、网关同步、授权状态。
 *
 * <p>整体镜像 {@link AiKbService} 流水线（校签解密 → 结构校验 → 去重 → at-rest 加密落库 →
 * 版本切换 → 网关同步），但门控功能位为 {@code content_compliance_kb}
 * （{@link KbLicenseService#canUpdateCompliance()}），数据面载体为 {@code ai-prompt-guard}。
 */
public interface ComplianceKbService {

    /**
     * 导入并应用一个 {@code .wnt} 加密签名的词库更新包（离线上传与在线拉取共用的唯一入口）。
     * 流水线：授权门控 → 校签 + 解密 → 解析校验 → 加密落库 → 版本切换 → 网关同步。
     */
    ComplianceKbVersionView importWntBundle(String base64Wnt, String source, String operator);

    /** 在线拉取最新词库并导入（受授权门控 + 校签）。 */
    ComplianceKbVersionView onlineUpdate(String operator);

    /** 版本历史（新→旧，脱敏）。 */
    List<ComplianceKbVersionView> listVersions();

    /** 当前生效版本脱敏视图；无则 null。 */
    ComplianceKbVersionView getActiveVersion();

    /** 当前生效词库的完整内容（供前端展示）；无则 null。 */
    ComplianceKbContent getContent();

    /** 回滚到指定版本号。 */
    ComplianceKbVersionView rollback(Long versionNo, String operator);

    /** 把当前 active 词库下发到 ai-prompt-guard 网关 CR；true 表示已写入。 */
    boolean syncActiveToGateway();

    /** 内容合规词库更新授权状态（content_compliance_kb 功能位）。 */
    ComplianceKbLicense licenseStatus();
}
