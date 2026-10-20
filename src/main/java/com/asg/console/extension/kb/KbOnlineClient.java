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
import com.alibaba.higress.sdk.exception.ValidationException;

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

    /** 在线拉取最新 KB 更新包（AI 分类知识库）。基址为空抛未配置；拉取异常按类型分层转可读文案。 */
    public OnlineKbPackage fetchLatestKb(String baseUrl) {
        String base = normalize(baseUrl);
        if (base.isEmpty()) {
            throw new ValidationException("在线更新未配置：请先设置更新服务器地址");
        }
        OnlineKbPackage pkg = new OnlineKbPackage();
        pkg.setBundle(fetchBundle(base, "/kb/latest", "KB"));
        return pkg;
    }

    /**
     * 在线拉取最新内容合规词库更新包（BE-011）；与 {@link #fetchLatestKb} 共用基址与
     * {@code {bundle}} 信封，仅路径不同：{@code GET {base}/compliance-kb/latest}。异常分层文案同上。
     */
    public OnlineKbPackage fetchLatestComplianceKb(String baseUrl) {
        String base = normalize(baseUrl);
        if (base.isEmpty()) {
            throw new ValidationException("在线更新未配置：请先设置更新服务器地址");
        }
        OnlineKbPackage pkg = new OnlineKbPackage();
        pkg.setBundle(fetchBundle(base, "/compliance-kb/latest", "词库"));
        return pkg;
    }

    /**
     * 两页在线更新共用的信封拉取（DEF-011 修复点）：把连接失败 / 服务端 HTTP 错误 / 响应格式非法
     * 三类异常分别译成分层可读文案（经 {@link OnlineUpdateErrorMapper}），不再一律「无法连接」。
     * 原始异常摘要仅落日志，不外泄；返回 bundle 字符串（厂商 .wnt 的 Base64）。
     */
    private String fetchBundle(String base, String path, String label) {
        String url = base + path;
        JSONObject obj;
        try {
            obj = JSON.parseObject(restTemplate.getForObject(url, String.class));
        } catch (Exception e) {
            log.warn("KB online fetch failed (url={}): {}", url, e.toString());
            throw OnlineUpdateErrorMapper.toValidation(e, label);
        }
        if (obj == null || obj.getString("bundle") == null) {
            throw new ValidationException(OnlineUpdateErrorMapper.badResponseMessage(label));
        }
        return obj.getString("bundle");
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
