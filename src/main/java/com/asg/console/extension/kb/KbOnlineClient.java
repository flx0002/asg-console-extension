/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * KB 在线更新 / 在线激活客户端。基址由 {@code asg.kb.online.url} 配置（空=禁用在线通道）。
 *
 * <p>厂商在线服务器契约（我方 mock 亦实现之，替换时只需同构）：
 * <ul>
 *   <li>{@code GET  {base}/kb/latest} → {@code {bundle, signature, sigAlgorithm, label, changelog}}，
 *       bundle 为明文 KB JSON 字符串；</li>
 *   <li>{@code POST {base}/license/activate} body {@code {deviceFingerprint}} →
 *       {@code {rawLicense}}，rawLicense 为签名授权文件内容。</li>
 * </ul>
 *
 * <p>拉取到的 bundle/授权仍走本地「验签 + 授权门控」，在线服务器不可信、只提供分发。
 */
@Slf4j
public class KbOnlineClient {

    private final String baseUrl;
    private final RestTemplate restTemplate = new RestTemplate();

    public KbOnlineClient(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    }

    public boolean isEnabled() {
        return !baseUrl.isEmpty();
    }

    /** 在线拉取最新 KB 更新包；未启用或失败抛异常（由服务层转成用户可读错误）。 */
    public OnlineKbPackage fetchLatestKb() {
        if (!isEnabled()) {
            throw new IllegalStateException("在线更新未配置（asg.kb.online.url 为空）");
        }
        String url = baseUrl + "/kb/latest";
        String body = restTemplate.getForObject(url, String.class);
        JSONObject obj = JSON.parseObject(body);
        if (obj == null || obj.getString("bundle") == null) {
            throw new IllegalStateException("在线更新包格式错误");
        }
        OnlineKbPackage pkg = new OnlineKbPackage();
        pkg.setBundle(obj.getString("bundle"));
        pkg.setSignature(obj.getString("signature"));
        pkg.setSigAlgorithm(obj.getString("sigAlgorithm"));
        pkg.setLabel(obj.getString("label"));
        pkg.setChangelog(obj.getString("changelog"));
        return pkg;
    }

    /** 在线激活：上报本机 ESN，换取签名授权文件（{@code .dat} 的 Base64）内容。 */
    public String activateLicense() {
        if (!isEnabled()) {
            throw new IllegalStateException("在线激活未配置（asg.kb.online.url 为空）");
        }
        String url = baseUrl + "/license/activate";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> req = new HashMap<>();
        String esn = EsnProvider.current();
        req.put("esn", esn);
        req.put("deviceFingerprint", esn);
        String body = restTemplate.postForObject(url, new HttpEntity<>(req, headers), String.class);
        JSONObject obj = JSON.parseObject(body);
        if (obj == null || obj.getString("rawLicense") == null) {
            throw new IllegalStateException("在线激活返回格式错误");
        }
        return obj.getString("rawLicense");
    }

    /** 在线 KB 更新包。 */
    @Data
    public static class OnlineKbPackage {
        private String bundle;
        private String signature;
        private String sigAlgorithm;
        private String label;
        private String changelog;
    }
}
