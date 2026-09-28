/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.asg.console.extension.controller.dto.KbMeta;
import com.asg.console.extension.controller.exception.AuthException;
import com.asg.console.extension.model.AiKbVersion;
import com.asg.console.extension.repository.AiKbVersionRepository;
import com.asg.console.extension.service.AiKbServiceImpl;
import com.asg.console.extension.service.KbLicenseService;

/**
 * P1 KB 版本服务单测（Mockito 隔离仓储/授权，真 RSA 签名 + 真 AES-GCM 加密）。
 * 覆盖：授权门控、验签、导入落库脱敏、回滚切换 active。
 */
class AiKbP1Test {

    private static KeyPair keyPair;
    /** 授权验签：厂商密钥对（私钥签名 / 公钥注入验签器）。 */
    private static KeyPair vendorKeyPair;
    /** 授权信封：产品（console）密钥对（公钥包裹 AES / 私钥拆封）。 */
    private static KeyPair consoleKeyPair;

    private AiKbVersionRepository versionRepository;
    private KbLicenseService licenseService;
    private AiKbServiceImpl service;

    @BeforeAll
    static void genKeys() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();
        vendorKeyPair = kpg.generateKeyPair();
        consoleKeyPair = kpg.generateKeyPair();
    }

    private void newService(boolean canUpdate) {
        versionRepository = mock(AiKbVersionRepository.class);
        licenseService = mock(KbLicenseService.class);
        when(licenseService.canUpdate()).thenReturn(canUpdate);
        service = new AiKbServiceImpl();
        service.setVersionRepository(versionRepository);
        service.setLicenseService(licenseService);
        service.setKbCrypto(new KbCrypto("unit-test-key"));
        service.setSignatureVerifier(new RsaKbSignatureVerifier(keyPair.getPublic()));
        service.setOnlineClient(new KbOnlineClient(""));
        // save 回填 id 并原样返回
        when(versionRepository.save(any(AiKbVersion.class))).thenAnswer(inv -> {
            AiKbVersion v = inv.getArgument(0);
            if (v.getId() == null) {
                v.setId(System.nanoTime());
            }
            return v;
        });
    }

    private static String sampleBundleJson(long kbVersion) {
        KbBundle b = new KbBundle();
        b.setKbVersion(kbVersion);
        b.setGeneratedAt("2026-09-22T00:00:00");
        KbBundle.Category c = new KbBundle.Category();
        c.setName("saas_ai");
        c.setLabel("SaaS AI");
        c.setRiskLevel("high");
        c.setDomains(new ArrayList<>(Arrays.asList("chat.openai.com", "claude.ai")));
        c.setSuffixes(new ArrayList<>(Collections.singletonList("bard.google.com")));
        b.setCategories(new ArrayList<>(Collections.singletonList(c)));
        return JSON.toJSONString(b);
    }

    private static String sign(String data) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(keyPair.getPrivate());
        s.update(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    @Test
    void importRejectedWhenNotLicensed() {
        newService(false);
        String bundle = sampleBundleJson(1);
        AuthException ex = assertThrows(AuthException.class,
            () -> service.importBundle(bundle, "c2ln", "rsa-sha256", "l", "c", AiKbVersion.SRC_OFFLINE, "op"));
        assertTrue(ex.getMessage().contains("未授权"));
        verify(versionRepository, never()).save(any());
    }

    @Test
    void importRejectedWhenSignatureInvalid() {
        newService(true);
        String bundle = sampleBundleJson(1);
        String badSig = Base64.getEncoder().encodeToString("not-a-real-signature".getBytes(StandardCharsets.UTF_8));
        AuthException ex = assertThrows(AuthException.class,
            () -> service.importBundle(bundle, badSig, "rsa-sha256", "l", "c", AiKbVersion.SRC_OFFLINE, "op"));
        assertTrue(ex.getMessage().contains("验签失败"));
        verify(versionRepository, never()).save(any());
    }

    @Test
    void importSucceedsWhenLicensedAndSigned() throws Exception {
        newService(true);
        when(versionRepository.findFirstByOrderByVersionNoDesc()).thenReturn(Optional.empty());
        when(versionRepository.findAllByStatus(AiKbVersion.STATUS_ACTIVE)).thenReturn(new ArrayList<>());
        String bundle = sampleBundleJson(20260922001L);
        String sig = sign(bundle);

        KbMeta meta = service.importBundle(bundle, sig, "rsa-sha256", "首库", "init", AiKbVersion.SRC_OFFLINE, "admin");

        assertNotNull(meta);
        assertEquals(AiKbVersion.STATUS_ACTIVE, meta.getStatus());
        assertEquals(20260922001L, meta.getVersionNo());
        assertEquals(1, meta.getCategoryCount());
        assertEquals(3, meta.getDomainCount()); // 2 domains + 1 suffix
        assertEquals("rsa-sha256", meta.getSigAlgorithm());
        assertNotNull(meta.getKbHash());
        verify(versionRepository, times(1)).save(any(AiKbVersion.class));
    }

    @Test
    void activeBundleRoundTripsThroughEncryption() throws Exception {
        newService(true);
        when(versionRepository.findFirstByOrderByVersionNoDesc()).thenReturn(Optional.empty());
        when(versionRepository.findAllByStatus(AiKbVersion.STATUS_ACTIVE)).thenReturn(new ArrayList<>());
        String bundle = sampleBundleJson(7);
        service.importBundle(bundle, sign(bundle), "rsa-sha256", "l", "c", AiKbVersion.SRC_OFFLINE, "admin");

        // 捕获落库实体，模拟"查 active 时解密还原"
        AiKbVersion stored = new AiKbVersion();
        org.mockito.ArgumentCaptor<AiKbVersion> captor = org.mockito.ArgumentCaptor.forClass(AiKbVersion.class);
        verify(versionRepository).save(captor.capture());
        stored = captor.getValue();
        when(versionRepository.findFirstByStatusOrderByCreatedAtDesc(AiKbVersion.STATUS_ACTIVE))
            .thenReturn(Optional.of(stored));

        KbBundle decrypted = service.getActiveBundle();
        assertNotNull(decrypted);
        assertEquals(1, decrypted.getCategories().size());
        assertEquals("saas_ai", decrypted.getCategories().get(0).getName());
    }

    @Test
    void rollbackSwitchesActiveVersion() {
        newService(true);
        AiKbVersion active = new AiKbVersion();
        active.setId(1L);
        active.setVersionNo(2L);
        active.setStatus(AiKbVersion.STATUS_ACTIVE);
        AiKbVersion old = new AiKbVersion();
        old.setId(2L);
        old.setVersionNo(1L);
        old.setStatus(AiKbVersion.STATUS_INACTIVE);

        when(versionRepository.findByVersionNo(1L)).thenReturn(Optional.of(old));
        when(versionRepository.findAllByStatus(AiKbVersion.STATUS_ACTIVE))
            .thenReturn(new ArrayList<>(Collections.singletonList(active)));

        KbMeta meta = service.rollback(1L, "admin");

        assertEquals(AiKbVersion.STATUS_ACTIVE, meta.getStatus());
        assertEquals(1L, meta.getVersionNo());
        assertEquals(AiKbVersion.STATUS_INACTIVE, active.getStatus()); // 旧 active 被降级
        verify(versionRepository, times(1)).saveAll(any());
        verify(versionRepository, times(1)).save(old);
    }

    @Test
    void licenseStatusDrivesGate() {
        // 授权服务门控：absent → canUpdate=false；此处仅验证 AiKbService 依赖该门控
        newService(false);
        assertFalse(service.listVersions() == null); // listVersions 不依赖授权，正常返回
        when(versionRepository.findAllByOrderByVersionNoDesc()).thenReturn(new ArrayList<>());
        List<KbMeta> list = service.listVersions();
        assertTrue(list.isEmpty());
        // 授权关闭时 getActiveMeta 仍可读（查看不受门控）
        when(versionRepository.findFirstByStatusOrderByCreatedAtDesc(anyString())).thenReturn(Optional.empty());
        assertEquals(null, service.getActiveMeta());
        // 授权关闭时导入被拒
        assertThrows(AuthException.class, () -> service.importBundle(sampleBundleJson(1), "c2ln", "rsa-sha256",
            "l", "c", AiKbVersion.SRC_OFFLINE, "op"));
    }

    // ===== EsnProvider + checkSn 门控 =====

    /** 构造一个厂商签名 + console 加密的 .dat（正式/含 ai_kb_update），可控 esn / checkSn。 */
    private static String licenseDat(String esn, boolean checkSn) throws Exception {
        JSONObject p = new JSONObject();
        p.put("version", "V1.0");
        p.put("licenseId", "UT-LIC");
        p.put("productName", "AI融合安全网关");
        p.put("productVersion", "V100R001");
        p.put("companyName", "UT Customer");
        p.put("contractNo", "HT-UT");
        p.put("esn", esn);
        p.put("checkSn", checkSn);
        p.put("licenseType", "formal");
        p.put("licenseModel", "ASG-KB-STD");
        p.put("licenseName", "KB");
        p.put("licenseValue", 1);
        p.put("iegCustomerId", "IEG-UT");
        p.put("iegAuthorizedCount", 1);
        JSONArray fns = new JSONArray();
        fns.add(LicenseInfo.FEATURE_KB_UPDATE);
        p.put("functions", fns);
        p.put("createTime", "2026-09-22 00:00:00");
        p.put("endTime", "");
        return DatLicenseCodec.encodeToBase64(p.toJSONString(), esn, consoleKeyPair.getPublic(),
            vendorKeyPair.getPrivate());
    }

    private static DatLicenseVerifier licenseVerifier() {
        return new DatLicenseVerifier(consoleKeyPair.getPrivate(),
            new RsaKbSignatureVerifier(vendorKeyPair.getPublic()));
    }

    @Test
    void esnProviderAlwaysReturnsNonEmpty() {
        String esn = EsnProvider.current();
        assertNotNull(esn);
        assertFalse(esn.trim().isEmpty());
        // 若注入 SN 环境变量，应原样返回（否则返回 MAC/回退指纹，此处仅断言非空与去空格一致）
        String env = System.getenv(EsnProvider.ESN_ENV);
        if (env != null && !env.trim().isEmpty()) {
            assertEquals(env.trim(), esn);
        }
    }

    @Test
    void checkSnTrueEsnMatchValid() throws Exception {
        LicenseInfo info = licenseVerifier().verify(licenseDat(EsnProvider.current(), true));
        assertTrue(info.isValid(), "reason=" + info.getReason());
        assertTrue(info.isCheckSn());
    }

    @Test
    void checkSnTrueEsnMismatchInvalid() throws Exception {
        LicenseInfo info = licenseVerifier().verify(licenseDat(EsnProvider.current() + "-WRONG", true));
        assertFalse(info.isValid());
        assertTrue(info.getReason() != null && info.getReason().contains("ESN"), "reason=" + info.getReason());
    }

    @Test
    void checkSnTrueBlankEsnInvalid() throws Exception {
        LicenseInfo info = licenseVerifier().verify(licenseDat("", true));
        assertFalse(info.isValid());
        assertTrue(info.getReason() != null && info.getReason().contains("ESN"), "reason=" + info.getReason());
    }

    @Test
    void checkSnFalseSkipsDeviceBinding() throws Exception {
        // checkSn=false：即使 esn 空/不匹配，也应跳过设备绑定而有效
        LicenseInfo info = licenseVerifier().verify(licenseDat("", false));
        assertTrue(info.isValid(), "reason=" + info.getReason());
        assertFalse(info.isCheckSn());
    }

    // ===== 功能位独立时间门控（evaluateEnd + getStatus 实时复核）=====

    @Test
    void evaluateEndCoversPermanentExpiryAndMalformed() {
        java.time.format.DateTimeFormatter f =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        assertNull(DatLicenseVerifier.evaluateEnd(null));                    // 缺省=永久
        assertNull(DatLicenseVerifier.evaluateEnd(""));                      // 空=永久
        assertNull(DatLicenseVerifier.evaluateEnd(LocalDateTime.now().plusDays(1).format(f))); // 未过期
        assertTrue(DatLicenseVerifier.evaluateEnd(LocalDateTime.now().minusDays(1).format(f))
            .contains("过期"));                                              // 已过期
        assertTrue(DatLicenseVerifier.evaluateEnd("2026/13/45 99:99:99")
            .contains("非法"));                                              // 格式非法→fail-closed
    }

    @Test
    void formalLicenseWithExpiredFunctionBlocksUpdate() {
        // 回归 H1：formal 永久授权（license 级 expiresAt 为空）但 ai_kb_update 功能位 endTime 已过，
        // 导入瞬间为 valid，运行时应被 getStatus 功能位级实时复核降级为 expired、canUpdate=false。
        com.asg.console.extension.repository.AiKbLicenseRepository licRepo =
            mock(com.asg.console.extension.repository.AiKbLicenseRepository.class);
        com.asg.console.extension.model.AiKbLicense row = new com.asg.console.extension.model.AiKbLicense();
        row.setId(1L);
        row.setStatus(com.asg.console.extension.model.AiKbLicense.STATUS_VALID);
        row.setLicenseType(com.asg.console.extension.model.AiKbLicense.TYPE_FORMAL);
        row.setExpiresAt(null); // formal 永久，无授权级到期
        row.setFeatures("ai_kb_update");
        String past = LocalDateTime.now().minusDays(1)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        JSONArray fi = new JSONArray();
        JSONObject o = new JSONObject();
        o.put("id", "ai_kb_update");
        o.put("createTime", "2026-01-01 00:00:00");
        o.put("endTime", past);
        fi.add(o);
        row.setFunctionItems(fi.toJSONString());
        when(licRepo.findById(1L)).thenReturn(Optional.of(row));

        com.asg.console.extension.service.KbLicenseServiceImpl svc =
            new com.asg.console.extension.service.KbLicenseServiceImpl();
        svc.setLicenseRepository(licRepo);
        svc.setLicenseVerifier(new DatLicenseVerifier(null, new RsaKbSignatureVerifier(keyPair.getPublic())));

        com.asg.console.extension.controller.dto.KbLicenseStatus st = svc.getStatus();
        assertEquals(com.asg.console.extension.model.AiKbLicense.STATUS_EXPIRED, st.getStatus());
        assertFalse(st.isCanUpdate());
        assertTrue(st.getReason() != null && st.getReason().contains("知识库更新授权已过期"),
            "reason=" + st.getReason());
    }
}
