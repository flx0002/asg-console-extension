/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

import java.util.regex.Pattern;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.higress.sdk.exception.ValidationException;
import com.asg.console.extension.controller.dto.KbOnlineSetting;
import com.asg.console.extension.model.AiKbSetting;
import com.asg.console.extension.repository.AiKbSettingRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link KbSettingService} 默认实现。在线更新地址单行持久化（id=1），env 默认值来自
 * {@code asg.kb.online.url}（{@code ASG_KB_ONLINE_URL}）。生效解析每次即时进行，改后立即生效。
 */
@Slf4j
@Service
public class KbSettingServiceImpl implements KbSettingService {

    /** 分发地址须为 http/https 绝对 URL（后跟主机名），不接受其它 scheme 或相对路径。 */
    private static final Pattern URL_PATTERN = Pattern.compile("^https?://[^\\s/?#]+.*$", Pattern.CASE_INSENSITIVE);

    private AiKbSettingRepository settingRepository;

    /** 部署期在线更新服务器基址（页面值优先，此项作回退默认）。 */
    @Value("${asg.kb.online.url:}")
    private String envOnlineUrl;

    @Resource
    public void setSettingRepository(AiKbSettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    @Override
    public KbOnlineSetting getOnlineSetting() {
        String db = trimToEmpty(loadDbUrl());
        String env = trimToEmpty(envOnlineUrl);
        String effective = StringUtils.isNotBlank(db) ? db : env;
        KbOnlineSetting s = new KbOnlineSetting();
        s.setUrl(db);
        s.setEnvUrl(env);
        s.setEffectiveUrl(effective);
        s.setConfigured(StringUtils.isNotBlank(effective));
        return s;
    }

    @Override
    @Transactional
    public KbOnlineSetting updateOnlineSetting(String url) {
        String clean = normalize(url);
        if (StringUtils.isNotBlank(clean) && !URL_PATTERN.matcher(clean).matches()) {
            throw new ValidationException("在线更新地址非法：须为以 http:// 或 https:// 开头的服务器地址");
        }
        AiKbSetting row = settingRepository.findById(1L).orElseGet(AiKbSetting::new);
        row.setId(1L);
        row.setOnlineUrl(clean);
        settingRepository.save(row);
        log.info("KB online update url configured via settings page: {}", clean.isEmpty() ? "(cleared, fallback env)"
            : clean);
        return getOnlineSetting();
    }

    @Override
    public String effectiveOnlineUrl() {
        String db = trimToEmpty(loadDbUrl());
        return StringUtils.isNotBlank(db) ? db : trimToEmpty(envOnlineUrl);
    }

    /** 读取页面持久化值；仓储未装配（如单测直接 new 服务）安全返回 null。 */
    private String loadDbUrl() {
        if (settingRepository == null) {
            return null;
        }
        return settingRepository.findById(1L).map(AiKbSetting::getOnlineUrl).orElse(null);
    }

    /** trim + 去末尾斜杠；空/null → 空串。 */
    private static String normalize(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }

    private static String trimToEmpty(String url) {
        return url == null ? "" : url.trim();
    }
}
