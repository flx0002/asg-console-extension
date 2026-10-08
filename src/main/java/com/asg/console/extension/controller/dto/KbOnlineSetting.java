/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.controller.dto;

import lombok.Data;

/**
 * KB 在线更新设置视图。区分「页面持久化值」与「部署 env 默认值」，并给出解析后的
 * 生效地址与是否已配置，供系统配置页展示与 ai-kb 页在线按钮门控。均为分发服务器基址，
 * 不含密钥/凭证。
 */
@Data
public class KbOnlineSetting {

    /** 页面持久化的在线更新服务器基址（可编辑；空=未设置）。 */
    private String url;
    /** 部署期环境变量默认值（ASG_KB_ONLINE_URL，只读展示）。 */
    private String envUrl;
    /** 生效地址 = url 非空 ? url : envUrl（页面值优先，未设置回退 env）。 */
    private String effectiveUrl;
    /** 生效地址是否非空（决定在线更新是否可用）。 */
    private boolean configured;
}
