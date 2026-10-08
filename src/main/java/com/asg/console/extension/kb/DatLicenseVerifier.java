/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import lombok.extern.slf4j.Slf4j;

/**
 * {@code .dat} 授权文件校验实现（对齐工业防火墙 TEG 授权格式）。
 *
 * <p>校验顺序：拆信封（console 私钥 OAEP 拆 AES + AES-GCM 解密）→ 解析载荷 → 验签
 * （厂商公钥 RSA-SHA256 over {@link DatLicenseCodec#canonicalString}）→ ESN 绑定（载荷 esn ==
 * {@link EsnProvider#current()}）→ 功能位（含 {@code ai_kb_update}，且该功能位自身未过期）。
 * 到期唯一以功能位自身 endTime 为准（授权不再携带顶层到期时间）；licenseType（formal/temporary）
 * 仅作展示/审计标签，不参与到期判定。任一不过即 valid=false。
 *
 * <p>安全默认：未配置 console 私钥或厂商验签公钥时，任何授权都判为无效。
 */
@Slf4j
public class DatLicenseVerifier implements LicenseVerifier {

    private static final DateTimeFormatter[] TIME_FORMATS = {
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
    };

    private final PrivateKey consolePrivKey;
    private final KbSignatureVerifier vendorVerifier;

    /**
     * @param consolePrivKey 产品（console）RSA 私钥，用于拆开 AES 密钥；null=未配置（拒绝所有授权）
     * @param vendorVerifier 厂商验签器（内含厂商公钥）；null 或无公钥=拒绝所有授权
     */
    public DatLicenseVerifier(PrivateKey consolePrivKey, KbSignatureVerifier vendorVerifier) {
        this.consolePrivKey = consolePrivKey;
        this.vendorVerifier = vendorVerifier;
    }

    @Override
    public LicenseInfo verify(String rawLicense) {
        if (consolePrivKey == null) {
            return LicenseInfo.invalid("授权解密私钥未配置");
        }
        if (vendorVerifier == null) {
            return LicenseInfo.invalid("验签公钥未配置");
        }
        if (rawLicense == null || rawLicense.trim().isEmpty()) {
            return LicenseInfo.invalid("授权内容为空");
        }

        DatLicenseCodec.Decoded decoded;
        try {
            decoded = DatLicenseCodec.decodePayload(rawLicense, consolePrivKey);
        } catch (IllegalArgumentException e) {
            return LicenseInfo.invalid(e.getMessage());
        } catch (javax.crypto.AEADBadTagException e) {
            return LicenseInfo.invalid("授权内容被篡改");
        } catch (Exception e) {
            log.warn("license .dat decode failed: {}", e.getMessage());
            return LicenseInfo.invalid("授权解密失败");
        }

        JSONObject obj;
        try {
            obj = JSON.parseObject(decoded.payloadJson);
        } catch (Exception e) {
            return LicenseInfo.invalid("授权文件格式错误");
        }
        if (obj == null) {
            return LicenseInfo.invalid("授权文件格式错误");
        }

        // 验签（厂商公钥 over canonicalString）
        String canonical = DatLicenseCodec.canonicalString(obj);
        if (!vendorVerifier.verify(canonical.getBytes(StandardCharsets.UTF_8), decoded.signature)) {
            return LicenseInfo.invalid("授权签名校验失败");
        }

        LicenseInfo info = mapFields(obj);
        // 验签已过：确认为厂商真实 .dat（后续过期/ESN/功能位不过仍属"真实凭证"，允许落库覆盖）
        info.setAuthenticated(true);

        // 到期唯一以功能位自身 endTime 为准（见下方功能位校验）；不再对顶层授权时间做独立到期门。
        // licenseType（formal/temporary）仅作展示/审计标签，不参与到期判定。
        // ESN 绑定（checkSn=false 则完全跳过设备绑定；checkSn=true 时 esn 非空且须等于本机标识）
        if (info.isCheckSn()) {
            String esn = info.getEsn();
            if (esn == null || esn.trim().isEmpty()) {
                info.setValid(false);
                info.setReason("授权要求校验设备但未提供 ESN");
                return info;
            }
            if (!esn.trim().equals(EsnProvider.current())) {
                info.setValid(false);
                info.setReason("设备ESN不匹配");
                return info;
            }
        }
        // 功能位：必须含 ai_kb_update，且该功能位自身未过期（endTime 非空且已过/格式非法→视为无效）
        LicenseInfo.FunctionItem kb = info.findFunction(LicenseInfo.FEATURE_KB_UPDATE);
        if (kb == null) {
            info.setValid(false);
            info.setReason("授权不含 " + LicenseInfo.FEATURE_KB_UPDATE + " 功能");
            return info;
        }
        String win = evaluateEnd(kb.getEndTime());
        if (win != null) {
            info.setValid(false);
            info.setReason(win);
            return info;
        }
        info.setValid(true);
        return info;
    }

    /**
     * 功能位有效期判定（导入时与运行时共用，保证语义一致）。
     * 返回 {@code null}=有效（endTime 为空=永久）；非 null=失效原因。
     * endTime 非空但解析失败按 fail-closed 处理（防止格式笔误使限时授权变永久）。
     */
    public static String evaluateEnd(String endTimeRaw) {
        if (endTimeRaw == null || endTimeRaw.trim().isEmpty()) {
            return null;
        }
        LocalDateTime end = parseTime(endTimeRaw);
        if (end == null) {
            return "知识库更新授权时间格式非法";
        }
        if (end.isBefore(LocalDateTime.now())) {
            return "知识库更新授权已过期";
        }
        return null;
    }

    private LicenseInfo mapFields(JSONObject obj) {
        LicenseInfo info = new LicenseInfo();
        info.setVersion(obj.getString("version"));
        info.setLicenseId(obj.getString("licenseId"));
        info.setProductName(obj.getString("productName"));
        info.setProductVersion(obj.getString("productVersion"));
        info.setCompanyName(obj.getString("companyName"));
        info.setContractNo(obj.getString("contractNo"));
        info.setEsn(obj.getString("esn"));
        info.setDeviceFingerprint(obj.getString("esn"));
        // checkSn 缺省（旧授权无此字段）= true，保持与工业防火墙一致的设备绑定默认语义
        Boolean cs = obj.getBoolean("checkSn");
        info.setCheckSn(cs == null || cs);
        info.setLicenseType(obj.getString("licenseType"));
        info.setLicenseModel(obj.getString("licenseModel"));
        info.setLicenseName(obj.getString("licenseName"));
        info.setLicenseValue(obj.getInteger("licenseValue"));
        info.setIegCustomerId(obj.getString("iegCustomerId"));
        info.setIegAuthorizedCount(obj.getInteger("iegAuthorizedCount"));
        info.setEndCustomerAcceptName(obj.getString("endCustomerAcceptName"));
        info.setCustomerAcceptCompanyEmail(obj.getString("customerAcceptCompanyEmail"));
        info.setCustomerAcceptTelephone(obj.getString("customerAcceptTelephone"));
        // subject 优先取载荷 subject，缺省回退 companyName（TEG 授权以公司名为主体）
        String subject = obj.getString("subject");
        info.setSubject(subject != null && !subject.trim().isEmpty() ? subject : obj.getString("companyName"));
        info.setIssuedAt(parseTime(obj.getString("createTime")));
        info.setExpiresAt(parseTime(obj.getString("endTime")));
        List<String> names = new ArrayList<>();
        List<LicenseInfo.FunctionItem> items = new ArrayList<>();
        Object fObj = obj.get("functions");
        JSONArray arr = (fObj instanceof JSONArray) ? (JSONArray) fObj : null;
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                Object el = arr.get(i);
                LicenseInfo.FunctionItem fi;
                if (el instanceof JSONObject) {
                    JSONObject o = (JSONObject) el;
                    // 功能位以稳定 id 为身份；兼容旧载荷以 name 承载标识（缺 id 时回退 name）
                    String oid = o.getString("id");
                    String idv = (oid != null && !oid.trim().isEmpty()) ? oid : o.getString("name");
                    fi = new LicenseInfo.FunctionItem(idv,
                        o.getString("createTime"), o.getString("endTime"));
                } else {
                    // 兼容旧写法：纯功能名字符串，视作 id，无独立时间
                    fi = new LicenseInfo.FunctionItem(arr.getString(i), null, null);
                }
                if (fi.getId() != null && !fi.getId().trim().isEmpty()) {
                    fi.setId(fi.getId().trim());
                    names.add(fi.getId());
                    items.add(fi);
                }
            }
        }
        info.setFeatures(names);
        info.setFunctionItems(items);
        return info;
    }

    /** 解析多种时间格式：ISO LocalDateTime / "yyyy-MM-dd HH:mm:ss" / 纯日期。 */
    public static LocalDateTime parseTime(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        String v = s.trim();
        for (DateTimeFormatter f : TIME_FORMATS) {
            try {
                return LocalDateTime.parse(v, f);
            } catch (Exception ignore) {
                // try next
            }
        }
        try {
            return LocalDate.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd")).atStartOfDay();
        } catch (Exception e) {
            log.warn("license time parse failed: {}", s);
            return null;
        }
    }
}
