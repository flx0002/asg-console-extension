/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import lombok.Data;

/**
 * AI 分类知识库（KB）运行时设置（单行表，id=1）。
 *
 * <p>当前仅承载「在线更新服务器地址」：由系统配置页可编辑并持久化，运行期即时生效
 * （下次在线更新按此地址拉取），无需重启。为空时回退到部署期环境变量
 * {@code ASG_KB_ONLINE_URL}（见 {@code KbSettingServiceImpl}）。此处只存分发服务器基址，
 * 不含任何密钥/凭证。
 */
@Data
@Entity
@Table(name = "ai_kb_setting")
public class AiKbSetting {

    @Id
    private Long id = 1L;

    /** 在线更新服务器基址（如 http://kb.example.com；空=未设置，回退 env）。不含末尾 /kb/latest。 */
    @Column(name = "online_url", length = 512)
    private String onlineUrl;
}
