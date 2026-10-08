/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * KB 在线更新客户端（纯 HTTP 分发器，不持有地址）。基址由调用方按「页面持久化值优先、
 * 回退部署 env」逐次传入（见 {@code KbSettingService#effectiveOnlineUrl()}），以支持运行期改地址即时生效。
 *
 * <p>厂商在线服务器契约（我方 mock 亦实现之，替换时只需同构）：
 * <ul>
 *   <li>{@code GET  {base}/kb/latest} → {@code {bundle}}，bundle 为厂商 {@code .wnt} 的 Base64
 *       （外层 signtool 签名头保真 + 内层我方 AES-256-GCM 保机密），**链路不含明文域名**；</li>
 * </ul>
 *
 * <p>拉取到的 .wnt 仍走本地「校签 + 解密 + 授权门控」（与离线导入同一流水线），在线服务器不可信、只提供分发。
 */
@Slf4j
public class KbOnlineClient {

    private final RestTemplate restTemplate = new RestTemplate();

    /** 给定基址是否可用（非空）。 */
    public boolean isEnabled(String baseUrl) {
        return StringUtils.isNotBlank(normalize(baseUrl));
    }

    /** 在线拉取最新 KB 更新包；基址为空或失败抛异常（由服务层转成用户可读错误）。 */
    public OnlineKbPackage fetchLatestKb(String baseUrl) {
        String base = normalize(baseUrl);
        if (base.isEmpty()) {
            throw new IllegalStateException("在线更新未配置（更新服务器地址为空）");
        }
        String url = base + "/kb/latest";
        String body = restTemplate.getForObject(url, String.class);
        JSONObject obj = JSON.parseObject(body);
        if (obj == null || obj.getString("bundle") == null) {
            throw new IllegalStateException("在线更新包格式错误");
        }
        OnlineKbPackage pkg = new OnlineKbPackage();
        pkg.setBundle(obj.getString("bundle"));
        return pkg;
    }

    /** trim + 去末尾斜杠；空/null → 空串。 */
    private static String normalize(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }

    /** 在线 KB 更新包（bundle 为厂商 .wnt 的 Base64；外层签名 + 内层加密均已内含）。 */
    @Data
    public static class OnlineKbPackage {
        private String bundle;
    }
}
