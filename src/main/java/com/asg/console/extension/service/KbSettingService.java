/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

import com.asg.console.extension.controller.dto.KbOnlineSetting;

/**
 * KB 运行时设置服务：在线更新服务器地址的读取/持久化与生效解析。
 *
 * <p>生效优先级：页面持久化值（{@code ai_kb_setting.online_url}）优先；为空回退部署期
 * 环境变量 {@code ASG_KB_ONLINE_URL}。地址在每次在线更新时即时解析，改后无需重启。
 */
public interface KbSettingService {

    /** 当前在线更新设置（页面值 + env 默认 + 生效地址 + 是否已配置）。 */
    KbOnlineSetting getOnlineSetting();

    /**
     * 持久化在线更新服务器基址（trim 后存单行 id=1）；传空=清除页面值（回退 env）。
     *
     * @throws com.alibaba.higress.sdk.exception.ValidationException 地址非法（非空且非 http/https 绝对地址）
     */
    KbOnlineSetting updateOnlineSetting(String url);

    /** 生效地址（页面值优先，回退 env）；两者皆空返回空串。 */
    String effectiveOnlineUrl();
}
