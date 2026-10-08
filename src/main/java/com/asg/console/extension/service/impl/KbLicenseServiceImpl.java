/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.asg.console.extension.controller.dto.KbLicenseStatus;
import com.asg.console.extension.kb.DatLicenseVerifier;
import com.asg.console.extension.kb.EsnProvider;
import com.asg.console.extension.kb.KbFeatureCatalog;
import com.asg.console.extension.kb.LicenseInfo;
import com.asg.console.extension.kb.LicenseVerifier;
import com.asg.console.extension.model.AiKbLicense;
import com.asg.console.extension.repository.AiKbLicenseRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link KbLicenseService} 默认实现。授权状态是单行表（id=1），记录最后一次导入的凭证与校验结果
 * （在线激活端点已移除，来源仅为离线导入）。校验委托给可插拔的 {@link LicenseVerifier}（{@code .dat} RSA/AES 信封，对齐 TEG）。
 *
 * <p>安全默认：无有效授权时 {@link #canUpdate()} 返回 false，KB 更新入口全部拒绝，
 * 检测继续使用最后一次有效库。
 */
@Slf4j
@Service
public class KbLicenseServiceImpl implements KbLicenseService {

    private AiKbLicenseRepository licenseRepository;
    private LicenseVerifier licenseVerifier;

    @Resource
    public void setLicenseRepository(AiKbLicenseRepository licenseRepository) {
        this.licenseRepository = licenseRepository;
    }

    @Resource
    public void setLicenseVerifier(LicenseVerifier licenseVerifier) {
        this.licenseVerifier = licenseVerifier;
    }

    @Override
    public KbLicenseStatus getStatus() {
        KbLicenseStatus s = new KbLicenseStatus();
        s.setDeviceFingerprint(EsnProvider.current());
        AiKbLicense row = licenseRepository.findById(1L).orElse(null);
        if (row == null) {
            s.setStatus(AiKbLicense.STATUS_ABSENT);
            s.setCanUpdate(false);
            s.setReason("尚未导入授权");
            return s;
        }
        s.setLicenseId(row.getLicenseId());
        s.setSubject(row.getSubject());
        s.setFeatures(splitFeatures(row.getFeatures()));
        s.setFunctions(parseFunctionViews(row.getFunctionItems()));
        s.setIssuedAt(row.getIssuedAt());
        s.setExpiresAt(row.getExpiresAt());
        s.setSource(row.getSource());
        s.setActivatedAt(row.getActivatedAt());
        s.setEsn(row.getEsn());
        s.setCheckSn(row.isCheckSn());
        s.setLicenseType(row.getLicenseType());
        s.setProductName(row.getProductName());
        s.setProductVersion(row.getProductVersion());
        s.setCompanyName(row.getCompanyName());
        s.setContractNo(row.getContractNo());
        s.setLicenseModel(row.getLicenseModel());
        s.setLicenseValue(row.getLicenseValue());
        s.setIegCustomerId(row.getIegCustomerId());
        s.setIegAuthorizedCount(row.getIegAuthorizedCount());

        String status = row.getStatus();
        String reason = row.getReason();
        // 功能位级实时复核（唯一到期门）：valid 但 ai_kb_update 自身 endTime 现已到期/非法 → 降级。
        // （顶层授权时间已移除，不再做授权级到期复核；licenseType 仅展示不参与判定。）
        if (AiKbLicense.STATUS_VALID.equals(status)) {
            String fnReason = evaluateKbFunctionWindow(row.getFunctionItems());
            if (fnReason != null) {
                status = fnReason.contains("过期") ? AiKbLicense.STATUS_EXPIRED : AiKbLicense.STATUS_INVALID;
                reason = fnReason;
            }
        }
        s.setStatus(status);
        s.setReason(reason);
        s.setCanUpdate(AiKbLicense.STATUS_VALID.equals(status));
        return s;
    }

    /** 单条授权原始内容上限：真实 .dat 很小；超限直接判非法且不落库，防超大文件撑爆 TEXT 列致 500。 */
    private static final int MAX_RAW_LICENSE = 256 * 1024;

    @Override
    public KbLicenseStatus importLicense(String rawLicense, String source) {
        if (StringUtils.isBlank(rawLicense)) {
            return attemptStatus(LicenseInfo.invalid("授权内容为空"), source);
        }
        if (rawLicense.length() > MAX_RAW_LICENSE) {
            log.warn("KB license import rejected: oversized rawLicense length={} (limit={})",
                rawLicense.length(), MAX_RAW_LICENSE);
            return attemptStatus(LicenseInfo.invalid("授权文件过大或非授权文件"), source);
        }
        LicenseInfo info = licenseVerifier.verify(rawLicense);
        if (info.isAuthenticated()) {
            // 验签通过的真实 .dat（含过期/ESN 不符/缺功能位）→ 覆盖落库
            persist(info, rawLicense, source);
        } else {
            // 非授权/篡改/错签名 → 拒绝且不改动既有授权（防止坏文件摧毁有效授权、不把超大垃圾写库）
            log.warn("KB license import rejected as non-license; existing license preserved. reason={}",
                info.getReason());
        }
        log.info("KB license import result: valid={}, authenticated={}, source={}",
            info.isValid(), info.isAuthenticated(), source);
        return attemptStatus(info, source);
    }

    /**
     * 把「本次导入尝试」映射为前端可读状态。有效或已验签（真实但不授权）均已落库 → 回读权威状态；
     * 非授权/未验签未落库 → 直接返回失败原因（不回读旧库，避免"坏文件被拒却显示旧有效授权=成功"的误导）。
     */
    private KbLicenseStatus attemptStatus(LicenseInfo info, String source) {
        if (info.isValid() || info.isAuthenticated()) {
            return getStatus();
        }
        KbLicenseStatus s = new KbLicenseStatus();
        s.setDeviceFingerprint(EsnProvider.current());
        s.setSource(source);
        s.setCanUpdate(false);
        boolean expired = info.getReason() != null && info.getReason().contains("过期");
        s.setStatus(expired ? AiKbLicense.STATUS_EXPIRED : AiKbLicense.STATUS_INVALID);
        s.setReason(info.getReason());
        return s;
    }

    @Override
    public boolean canUpdate() {
        return getStatus().isCanUpdate();
    }

    /** 落库一次验签通过的真实授权（单行覆盖 id=1）；仅在 importLicense 判定 authenticated 时调用。 */
    private void persist(LicenseInfo info, String rawLicense, String source) {
        AiKbLicense row = licenseRepository.findById(1L).orElseGet(AiKbLicense::new);
        row.setId(1L);
        row.setLicenseId(info.getLicenseId());
        row.setSubject(info.getSubject());
        row.setDeviceFingerprint(info.getEsn());
        row.setEsn(info.getEsn());
        row.setCheckSn(info.isCheckSn());
        row.setLicenseType(info.getLicenseType());
        row.setProductName(info.getProductName());
        row.setProductVersion(info.getProductVersion());
        row.setCompanyName(info.getCompanyName());
        row.setContractNo(info.getContractNo());
        row.setLicenseModel(info.getLicenseModel());
        row.setLicenseValue(info.getLicenseValue());
        row.setIegCustomerId(info.getIegCustomerId());
        row.setIegAuthorizedCount(info.getIegAuthorizedCount());
        row.setFeatures(info.getFeatures() == null ? "" : String.join(",", info.getFeatures()));
        row.setFunctionItems(serializeFunctionItems(info.getFunctionItems()));
        row.setIssuedAt(info.getIssuedAt());
        row.setExpiresAt(info.getExpiresAt());
        row.setRawLicense(rawLicense);
        row.setSource(source);
        if (info.isValid()) {
            row.setStatus(AiKbLicense.STATUS_VALID);
            row.setReason(null);
            row.setActivatedAt(LocalDateTime.now());
        } else {
            boolean expired = info.getReason() != null && info.getReason().contains("过期");
            row.setStatus(expired ? AiKbLicense.STATUS_EXPIRED : AiKbLicense.STATUS_INVALID);
            row.setReason(info.getReason());
            row.setActivatedAt(null); // 非有效授权不应残留旧激活时间，保证 status 与 activatedAt 一致
        }
        licenseRepository.save(row);
    }

    private List<String> splitFeatures(String csv) {
        List<String> list = new ArrayList<>();
        if (StringUtils.isNotBlank(csv)) {
            for (String f : Arrays.asList(csv.split(","))) {
                if (StringUtils.isNotBlank(f)) {
                    list.add(f.trim());
                }
            }
        }
        return list;
    }

    /**
     * 功能位级实时复核：从落库 function_items 取 ai_kb_update 的 endTime 判定当前是否仍有效。
     * 返回 null=有效；非 null=失效原因（与导入时同源于 {@link DatLicenseVerifier#evaluateEnd}）。
     */
    private String evaluateKbFunctionWindow(String functionItemsJson) {
        if (StringUtils.isBlank(functionItemsJson)) {
            return "授权不含 " + LicenseInfo.FEATURE_KB_UPDATE + " 功能";
        }
        try {
            JSONArray a = JSON.parseArray(functionItemsJson);
            JSONObject kb = null;
            for (int i = 0; i < a.size(); i++) {
                JSONObject o = a.getJSONObject(i);
                String fid = functionIdentity(o);
                if (o != null && LicenseInfo.FEATURE_KB_UPDATE.equals(fid)) {
                    kb = o;
                    break;
                }
            }
            if (kb == null) {
                return "授权不含 " + LicenseInfo.FEATURE_KB_UPDATE + " 功能";
            }
            return DatLicenseVerifier.evaluateEnd(kb.getString("endTime"));
        } catch (Exception e) {
            log.warn("evaluate kb function window failed: {}", e.getMessage());
            return null; // 解析异常保守不改写（导入时已校验）
        }
    }

    /** 功能位序列化为 JSON（稳定 id + 原始 createTime/endTime 字符串），供落库。 */
    private String serializeFunctionItems(List<LicenseInfo.FunctionItem> items) {
        JSONArray a = new JSONArray();
        if (items != null) {
            for (LicenseInfo.FunctionItem fi : items) {
                if (fi == null || StringUtils.isBlank(fi.getId())) {
                    continue;
                }
                JSONObject o = new JSONObject(true);
                o.put("id", fi.getId().trim());
                o.put("createTime", fi.getCreateTime() == null ? "" : fi.getCreateTime());
                o.put("endTime", fi.getEndTime() == null ? "" : fi.getEndTime());
                a.add(o);
            }
        }
        return a.toJSONString();
    }

    /** 解析落库的 function_items JSON → 展示视图：以 id 为身份，显示名称由后端目录按 id 解析。 */
    private List<KbLicenseStatus.FunctionView> parseFunctionViews(String json) {
        List<KbLicenseStatus.FunctionView> list = new ArrayList<>();
        if (StringUtils.isBlank(json)) {
            return list;
        }
        try {
            JSONArray a = JSON.parseArray(json);
            for (int i = 0; i < a.size(); i++) {
                JSONObject o = a.getJSONObject(i);
                String id = functionIdentity(o);
                if (StringUtils.isBlank(id)) {
                    continue;
                }
                String endRaw = o.getString("endTime");
                boolean active = DatLicenseVerifier.evaluateEnd(endRaw) == null;
                list.add(new KbLicenseStatus.FunctionView(id,
                    KbFeatureCatalog.displayName(id),
                    DatLicenseVerifier.parseTime(o.getString("createTime")),
                    DatLicenseVerifier.parseTime(endRaw), active));
            }
        } catch (Exception e) {
            log.warn("parse function_items failed: {}", e.getMessage());
        }
        return list;
    }

    /** 取功能位身份：优先 id，兼容旧落库以 name 承载标识（缺 id 时回退 name，trim）。 */
    private static String functionIdentity(JSONObject o) {
        if (o == null) {
            return null;
        }
        String id = o.getString("id");
        if (StringUtils.isNotBlank(id)) {
            return id.trim();
        }
        String name = o.getString("name");
        return name == null ? null : name.trim();
    }
}
