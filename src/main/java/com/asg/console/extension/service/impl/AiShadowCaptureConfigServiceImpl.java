/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.asg.console.extension.controller.dto.CaptureNic;
import com.asg.console.extension.model.AiShadowCaptureConfig;
import com.asg.console.extension.repository.AiShadowCaptureConfigRepository;
import com.alibaba.higress.sdk.exception.ValidationException;

import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of {@link AiShadowCaptureConfigService}. The config is
 * a single-row table; a never-written row keeps the collector on its own local
 * yaml, while an explicitly saved empty list turns capture off. The NIC
 * inventory lives in the bypass collector (it is the only component that can
 * see the appliance's physical ports), so probing proxies to its /nics
 * endpoint.
 */
@Slf4j
@Service
public class AiShadowCaptureConfigServiceImpl implements AiShadowCaptureConfigService {

    // Linux IFNAMSIZ is 16 including the terminator; keep names conservative so
    // junk input never reaches the collector's AF_PACKET layer.
    private static final Pattern IFACE_NAME = Pattern.compile("^[a-zA-Z0-9_.:-]{1,15}$");
    private static final int MAX_INTERFACES = 32;

    private AiShadowCaptureConfigRepository captureConfigRepository;

    private AuditChainService auditChainService;

    @Value("${asg.bypass-guard.base:http://172.22.0.1:9102}")
    private String bypassGuardBase;

    @Resource
    public void setCaptureConfigRepository(AiShadowCaptureConfigRepository captureConfigRepository) {
        this.captureConfigRepository = captureConfigRepository;
    }

    @Resource
    public void setAuditChainService(AuditChainService auditChainService) {
        this.auditChainService = auditChainService;
    }

    @PostConstruct
    public void init() {
        try {
            getConfig();
        } catch (Exception e) {
            log.warn("Failed to initialize shadow AI capture config: {}", e.getMessage());
        }
    }

    @Override
    public AiShadowCaptureConfig getConfig() {
        AiShadowCaptureConfig config = captureConfigRepository.findById(AiShadowCaptureConfig.SINGLETON_ID).orElse(null);
        if (config == null) {
            config = new AiShadowCaptureConfig();
            config.setCaptureInterfaces(null);
            config.setControlInterfaces(null);
            config.setUpdatedAt(LocalDateTime.now());
            config = captureConfigRepository.save(config);
            log.info("Initialized default shadow AI capture config (unset, collector keeps local yaml)");
        }
        return config;
    }

    @Override
    public AiShadowCaptureConfig updateConfig(List<String> interfaces, Map<String, String> controlInterfaces) {
        if (interfaces == null) {
            throw new ValidationException("interfaces is required (use [] to turn capture off)");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String name : interfaces) {
            String trimmed = StringUtils.trimToEmpty(name);
            if (!IFACE_NAME.matcher(trimmed).matches()) {
                throw new ValidationException("invalid interface name: " + name);
            }
            normalized.add(trimmed);
        }
        if (normalized.size() > MAX_INTERFACES) {
            throw new ValidationException("too many interfaces: " + normalized.size());
        }
        Map<String, String> controls = new LinkedHashMap<>();
        if (controlInterfaces != null) {
            for (Map.Entry<String, String> entry : controlInterfaces.entrySet()) {
                String key = StringUtils.trimToEmpty(entry.getKey());
                if (!normalized.contains(key)) {
                    throw new ValidationException("control_interfaces references unselected interface: " + key);
                }
                String value = StringUtils.trimToEmpty(entry.getValue());
                // Empty value is meaningful: "do not inject" on that port.
                if (!value.isEmpty() && !IFACE_NAME.matcher(value).matches()) {
                    throw new ValidationException("invalid control interface: " + entry.getValue());
                }
                controls.put(key, value);
            }
        }
        AiShadowCaptureConfig config = getConfig();
        config.setCaptureInterfaces(String.join(",", normalized));
        config.setControlInterfaces(controls.isEmpty() ? null : JSON.toJSONString(controls));
        config.setUpdatedAt(LocalDateTime.now());
        config = captureConfigRepository.save(config);
        log.info("Updated shadow AI capture config: interfaces={} controls={}", config.getCaptureInterfaces(),
            config.getControlInterfaces());
        writeCaptureAudit(config.getCaptureInterfaces(), config.getControlInterfaces());
        return config;
    }

    /**
     * 管理动作审计（admin_ops 会话 / admin_operation），与 AiShadowServiceImpl
     * 的 writeAdminAudit 同形：采集口变更会改变安全数据面覆盖面，必须可追溯。
     * 审计失败只告警不回滚——配置写入本身已成功，与既有审计语义一致。
     */
    private void writeCaptureAudit(String interfaces, String controls) {
        try {
            long timestampMs = System.currentTimeMillis();
            String eventId = timestampMs + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            JSONObject entry = new JSONObject();
            entry.put("event_id", eventId);
            entry.put("timestamp_ms", timestampMs);
            entry.put("session_id", "admin_ops");
            entry.put("record_types", Collections.singletonList("admin_operation"));
            entry.put("blocked", false);
            entry.put("identity_trusted", true);
            String operator = com.asg.console.extension.util.AdminOperatorResolver.resolve();
            entry.put("user_id", operator);
            entry.put("user_name", operator);
            String clientIp = com.asg.console.extension.util.AdminOperatorResolver.currentClientIp();
            if (org.apache.commons.lang3.StringUtils.isNotEmpty(clientIp)) {
                entry.put("client_ip", clientIp);
            }
            entry.put("action", "set_capture_config");
            entry.put("source", "ai_shadow_console");
            entry.put("consumer_name", interfaces == null ? "" : interfaces);
            entry.put("detail", "controls=" + (controls == null ? "" : controls));
            auditChainService.writeAuditLog(entry.toJSONString(), eventId, "admin_ops", timestampMs, operator, true,
                null);
            com.asg.console.extension.config.AdminOperationAuditInterceptor.markWritten();
        } catch (Exception e) {
            log.error("Failed to write admin audit log for action: set_capture_config", e);
        }
    }

    @Override
    public List<CaptureNic> probeNics() {
        String url = StringUtils.appendIfMissing(bypassGuardBase, "/") + "nics";
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != 200) {
                log.warn("Bypass guard /nics returned {}", connection.getResponseCode());
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                byte[] chunk = new byte[8192];
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, read);
                }
                JSONArray array = JSON.parseArray(new String(buf.toByteArray(), StandardCharsets.UTF_8));
                List<CaptureNic> nics = new ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    CaptureNic nic = new CaptureNic();
                    nic.setName(item.getString("name"));
                    nic.setOper(item.getString("oper"));
                    nic.setSpeed(item.getString("speed"));
                    nics.add(nic);
                }
                return nics;
            }
        } catch (Exception e) {
            log.warn("Bypass guard probe failed (base={}): {}", bypassGuardBase, e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
