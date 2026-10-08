/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

import java.time.LocalDateTime;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
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
 * KB 版本服务单测（Mockito 隔离仓储/授权）。更新包统一走厂商 {@code .wnt} 容器：
 * 外层 signtool ECC 头校签 + 内层我方 AES-256-GCM。覆盖：容器 round-trip、验签/解密负例、
 * 授权门控、导入落库脱敏、内容去重、网关同步态、回滚；授权 {@code .dat} 链路保持不变。
 *
 * <p>更新包相关测试用即时生成的临时 ECC 密钥对（ephemeral）自签自验，不依赖任何真实私钥；
 * 与厂商 signtool 的字节级互操作由 {@code wntDecodeMatchesVendorSigntoolFixture} 用一段
 * 本机 signtool.exe 产出的真实 {@code .wnt} 样本单独证明。
 */
class AiKbP1Test {

    /** 内层 AES-256-GCM 测试密钥（仅测试用，非生产 ASG_KB_BUNDLE_KEY，可安全入库）。 */
    private static final byte[] TEST_BUNDLE_KEY = repeat((byte) 0x11, 32);

    /**
     * 厂商互操作 fixture：本机 signtool.exe（{@code -m release -f sign -t engine}，new-r-key ECC 私钥）
     * 对 {@code WntBundleCipher.seal(FIXTURE_BUNDLE_JSON, TEST_BUNDLE_KEY)} 的产物签名得到的真实 {@code .wnt}。
     * 用于证明我方 {@link WntBundleCodec} 与厂商容器字节级兼容（仅含公钥与测试密钥，无任何真实私钥）。
     */
    private static final String FIXTURE_BUNDLE_JSON = "{\"kbVersion\":20260922101,\"generatedAt\":\"2026-09-22T00:00:00\","
        + "\"categories\":[{\"name\":\"fixture_ai\",\"label\":\"Fixture AI\",\"riskLevel\":\"high\","
        + "\"domains\":[\"chat.example.com\",\"model.example.org\"],\"suffixes\":[\".suffix.example\"]}]}";
    /** new-r-key.pub（33B SEC1 压缩点，公开，可入库）。 */
    private static final String FIXTURE_PUB_HEX =
        "03e74ce745cafa9a6fec00d7e9f306a8d6f643cd2de96361521a89f6e0a471a562";
    private static final String FIXTURE_WNT_B64 =
        "7eusvgMBAQH8AAAAyGa7ao5y5GoRMW2iJi8odB32TAJoao0FQPho5tGgOAGNYKWiuELH6c5OJUqA"
        + "MpuBMiQ0vE3136nzggegBfLIUZ4atFOAt8INsVj758/j0yXD7Fg9eoY+SzO7C8mgwerHVeD7RC4e"
        + "XVyS8ceXZuEBD8GU50W+Nl4b//6N9plYKVURgLhOqtNF5JiGQA8lOMJ0bu06wzuOeqxq4OMIrwaA"
        + "2xCIw0vToj0zfotbGhpnmat24cQqECZM8oxeG9hj5+DcS3w9/aO9taOs9Q6azt6uXGpvZWXSWSvR"
        + "6PdRXq6z1FFOGz27sFmnazl+drhW0UuOnB0kJP9BYfrOGPo9ESfxTu74o9thiJbQPQu7i16bHdXR"
        + "EKTqKN+4LTFT+wpJaOJcBCvrPV5aD5zCvusUkNKPt4HXi/KRgaADKcds6LOYjlIyrNlE";

    /** 授权验签：厂商 RSA 密钥对（私钥签名 / 公钥注入验签器），用于 .dat 授权用例。 */
    private static KeyPair vendorKeyPair;
    /** 授权信封：产品（console）RSA 密钥对，用于 .dat 授权用例。 */
    private static KeyPair consoleKeyPair;
    /** 授权页实时复核用的独立 RSA 密钥对。 */
    private static KeyPair keyPair;

    /** WNT 校签用临时 ECC 密钥（每次进程生成；私钥 32B，公钥 33B 压缩点）。 */
    private static byte[] eccPriv;
    private static byte[] eccPub33;

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
        eccPriv = WntVerifier.generateEccPriv();
        eccPub33 = WntVerifier.eccPublicCompressed(eccPriv);
    }

    // ===== helpers =====

    private static byte[] repeat(byte b, int n) {
        byte[] a = new byte[n];
        Arrays.fill(a, b);
        return a;
    }

    /** 用测试密钥把一个明文 bundle 打包成 Base64 .wnt（等价于本机 wnt_pack.ps1 的产物）。 */
    private static String wntFor(String bundleJson) throws Exception {
        return wntFor(bundleJson, eccPriv);
    }

    private static String wntFor(String bundleJson, byte[] priv) throws Exception {
        byte[] inner = WntBundleCipher.seal(bundleJson, TEST_BUNDLE_KEY);
        byte[] wnt = WntBundleCodec.encode(inner, priv, 3, 1); // ptype=engine, stype=release
        return Base64.getEncoder().encodeToString(wnt);
    }

    private void newService(boolean canUpdate) {
        versionRepository = mock(AiKbVersionRepository.class);
        licenseService = mock(KbLicenseService.class);
        when(licenseService.canUpdate()).thenReturn(canUpdate);
        service = new AiKbServiceImpl();
        service.setVersionRepository(versionRepository);
        service.setLicenseService(licenseService);
        service.setKbCrypto(new KbCrypto("unit-test-key"));
        service.setWntBundleCipher(new WntBundleCipher(eccPub33, null, TEST_BUNDLE_KEY));
        service.setOnlineClient(new KbOnlineClient());
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

    // ===== WNT 容器纯密码学：round-trip / 负例 =====

    @Test
    void wntCodecRoundTripsThroughSignAndVerify() throws Exception {
        String json = sampleBundleJson(20260922001L);
        byte[] inner = WntBundleCipher.seal(json, TEST_BUNDLE_KEY);
        byte[] wnt = WntBundleCodec.encode(inner, eccPriv, 3, 1);
        // dlen≤1024 时首块按 align8 补齐，故存储长度 = 80 + roundUp(dlen,8)
        assertEquals(80 + ((inner.length + 7) / 8 * 8), wnt.length);
        assertTrue(wnt.length >= 80 + inner.length);

        WntBundleCodec.Result r = WntBundleCodec.decode(wnt, eccPub33, null);
        assertArrayEquals(inner, r.inner); // 还原首块伪加密后与封装前的内层字节一致
        assertEquals("ecc-p256-sha256", r.sigAlgorithm);
        assertEquals(64, r.signature.length); // raw r‖s
        assertEquals(json, WntBundleCipher.open(r.inner, TEST_BUNDLE_KEY));
    }

    @Test
    void wntDecodeRejectsTamperedPayload() throws Exception {
        byte[] wnt = WntBundleCodec.encode(WntBundleCipher.seal(sampleBundleJson(1), TEST_BUNDLE_KEY), eccPriv, 3, 1);
        wnt[wnt.length - 1] ^= 0x01; // 篡改尾部 payload → 签名摘要不符
        IllegalArgumentException ex =
            assertThrows(IllegalArgumentException.class, () -> WntBundleCodec.decode(wnt, eccPub33, null));
        assertTrue(ex.getMessage().contains("验签"));
    }

    @Test
    void wntDecodeRejectsWrongPubkey() throws Exception {
        byte[] otherPriv = WntVerifier.generateEccPriv();
        byte[] otherPub = WntVerifier.eccPublicCompressed(otherPriv);
        byte[] wnt = WntBundleCodec.encode(WntBundleCipher.seal(sampleBundleJson(1), TEST_BUNDLE_KEY), eccPriv, 3, 1);
        assertThrows(IllegalArgumentException.class, () -> WntBundleCodec.decode(wnt, otherPub, null));
    }

    @Test
    void wntVerifierRejectsEmptyPubkey() {
        assertFalse(WntVerifier.verifyEcc(null, new byte[32], new byte[64]));
        assertFalse(WntVerifier.verifyEcc(new byte[33], new byte[32], new byte[64])); // 合法长度但点解码失败 → false
    }

    @Test
    void wntDecodeRejectsForgedDlen() throws Exception {
        byte[] wnt = WntBundleCodec.encode(WntBundleCipher.seal(sampleBundleJson(1), TEST_BUNDLE_KEY), eccPriv, 3, 1);
        // 伪造超大 dlen（> 头后存储字节数）→ 护栏应先于校签触发（DoS 防护）
        wnt[8] = (byte) 0xFF;
        wnt[9] = (byte) 0xFF;
        wnt[10] = (byte) 0xFF;
        wnt[11] = (byte) 0x7F;
        IllegalArgumentException ex =
            assertThrows(IllegalArgumentException.class, () -> WntBundleCodec.decode(wnt, eccPub33, null));
        assertTrue(ex.getMessage().contains("dlen") || ex.getMessage().contains("长度"));
    }

    @Test
    void wntGmPackageRejectedWithoutSm2Pub() throws Exception {
        byte[] wnt = WntBundleCodec.encode(WntBundleCipher.seal(sampleBundleJson(1), TEST_BUNDLE_KEY), eccPriv, 3, 1);
        wnt[7] = 2; // enver=GM；hdr[0:16] 改变使签名失效，但 GM 未配置公钥应在验签前即拒
        IllegalArgumentException ex =
            assertThrows(IllegalArgumentException.class, () -> WntBundleCodec.decode(wnt, eccPub33, null));
        assertTrue(ex.getMessage().contains("GM") || ex.getMessage().contains("SM2"), "msg=" + ex.getMessage());
    }

    // ===== 服务层：importWntBundle =====

    /**
     * 厂商互操作：用真实 new-r-key.pub 校签本机 signtool.exe 产出的 .wnt，还原内层并用测试密钥
     * 解密得到期望 bundle JSON——证明我方容器与厂商 signtool 字节级兼容。
     */
    @Test
    void wntDecodeMatchesVendorSigntoolFixture() throws Exception {
        byte[] pub = WntBundleCipher.parseKeyBytes(FIXTURE_PUB_HEX);
        assertEquals(33, pub.length);
        byte[] wnt = Base64.getDecoder().decode(FIXTURE_WNT_B64);

        WntBundleCodec.Result r = WntBundleCodec.decode(wnt, pub, null);
        assertEquals("ecc-p256-sha256", r.sigAlgorithm);
        assertEquals(64, r.signature.length);
        // 内层用测试 bundle 密钥解开，应与打包时喂给 signtool 的明文逐字节一致
        assertEquals(FIXTURE_BUNDLE_JSON, WntBundleCipher.open(r.inner, TEST_BUNDLE_KEY));
    }

    /** 端到端：同一 fixture 走服务层 importWntBundle（公钥=new-r-key.pub，内层密钥=测试密钥）成功落库。 */
    @Test
    void importWntBundleAppliesVendorSigntoolFixture() throws Exception {
        newService(true);
        byte[] pub = WntBundleCipher.parseKeyBytes(FIXTURE_PUB_HEX);
        service.setWntBundleCipher(new WntBundleCipher(pub, null, TEST_BUNDLE_KEY));
        when(versionRepository.findFirstByOrderByVersionNoDesc()).thenReturn(Optional.empty());
        when(versionRepository.findAllByStatus(AiKbVersion.STATUS_ACTIVE)).thenReturn(new ArrayList<>());
        when(versionRepository.findFirstByStatusOrderByCreatedAtDesc(AiKbVersion.STATUS_ACTIVE))
            .thenReturn(Optional.empty());

        KbMeta meta = service.importWntBundle(FIXTURE_WNT_B64, AiKbVersion.SRC_OFFLINE, "admin");

        assertEquals(AiKbVersion.STATUS_ACTIVE, meta.getStatus());
        assertEquals(20260922101L, meta.getVersionNo());
        assertEquals(1, meta.getCategoryCount());
        assertEquals(3, meta.getDomainCount()); // 2 domains + 1 suffix
        assertEquals("ecc-p256-sha256", meta.getSigAlgorithm());
        verify(versionRepository, times(1)).save(any(AiKbVersion.class));
    }

    @Test
    void importWntBundlePersistsActiveVersion() throws Exception {
        newService(true);
        when(versionRepository.findFirstByOrderByVersionNoDesc()).thenReturn(Optional.empty());
        when(versionRepository.findAllByStatus(AiKbVersion.STATUS_ACTIVE)).thenReturn(new ArrayList<>());
        when(versionRepository.findFirstByStatusOrderByCreatedAtDesc(AiKbVersion.STATUS_ACTIVE))
            .thenReturn(Optional.empty());
        String bundle = sampleBundleJson(20260922001L);

        KbMeta meta = service.importWntBundle(wntFor(bundle), AiKbVersion.SRC_OFFLINE, "admin");

        assertNotNull(meta);
        assertEquals(AiKbVersion.STATUS_ACTIVE, meta.getStatus());
        assertEquals(20260922001L, meta.getVersionNo());
        assertEquals(1, meta.getCategoryCount());
        assertEquals(3, meta.getDomainCount()); // 2 domains + 1 suffix
        assertEquals("ecc-p256-sha256", meta.getSigAlgorithm());
        assertNotNull(meta.getKbHash());
        assertFalse(meta.isUnchanged());
        verify(versionRepository, times(1)).save(any(AiKbVersion.class));
    }

    @Test
    void importWntBundleStoresSignatureAndAutoLabel() throws Exception {
        newService(true);
        when(versionRepository.findFirstByOrderByVersionNoDesc()).thenReturn(Optional.empty());
        when(versionRepository.findAllByStatus(AiKbVersion.STATUS_ACTIVE)).thenReturn(new ArrayList<>());
        when(versionRepository.findFirstByStatusOrderByCreatedAtDesc(AiKbVersion.STATUS_ACTIVE))
            .thenReturn(Optional.empty());

        service.importWntBundle(wntFor(sampleBundleJson(5)), AiKbVersion.SRC_OFFLINE, "admin");

        org.mockito.ArgumentCaptor<AiKbVersion> captor = org.mockito.ArgumentCaptor.forClass(AiKbVersion.class);
        verify(versionRepository, times(1)).save(captor.capture());
        AiKbVersion saved = captor.getValue();
        assertNotNull(saved.getSignature());
        assertEquals(64, Base64.getDecoder().decode(saved.getSignature()).length); // raw r‖s
        assertEquals("ecc-p256-sha256", saved.getSigAlgorithm());
        assertTrue(saved.getLabel().startsWith("kb-")); // label 自动生成
        assertNull(saved.getChangelog());
    }

    @Test
    void importWntBundleRejectedWhenNotLicensed() throws Exception {
        newService(false);
        AuthException ex = assertThrows(AuthException.class,
            () -> service.importWntBundle(wntFor(sampleBundleJson(1)), AiKbVersion.SRC_OFFLINE, "op"));
        assertTrue(ex.getMessage().contains("未授权"));
        verify(versionRepository, never()).save(any());
    }

    @Test
    void importWntBundleRejectedWhenVerifyFails() throws Exception {
        newService(true);
        // 用另一临时私钥签名 → 校签失败（对当前配置的 eccPub33 而言非法）
        byte[] otherPriv = WntVerifier.generateEccPriv();
        String wnt = wntFor(sampleBundleJson(1), otherPriv);
        com.alibaba.higress.sdk.exception.ValidationException ex =
            assertThrows(com.alibaba.higress.sdk.exception.ValidationException.class,
                () -> service.importWntBundle(wnt, AiKbVersion.SRC_OFFLINE, "op"));
        assertTrue(ex.getMessage().contains("验签") || ex.getMessage().contains("非法"));
        verify(versionRepository, never()).save(any());
    }

    @Test
    void importWntBundleRejectedWhenKeyNotConfigured() throws Exception {
        newService(true);
        service.setWntBundleCipher(new WntBundleCipher(null, null, null)); // 未配置密钥 → fail-closed
        assertThrows(com.alibaba.higress.sdk.exception.ValidationException.class,
            () -> service.importWntBundle(wntFor(sampleBundleJson(1)), AiKbVersion.SRC_OFFLINE, "op"));
        verify(versionRepository, never()).save(any());
    }

    @Test
    void activeBundleRoundTripsThroughEncryption() throws Exception {
        newService(true);
        when(versionRepository.findFirstByOrderByVersionNoDesc()).thenReturn(Optional.empty());
        when(versionRepository.findAllByStatus(AiKbVersion.STATUS_ACTIVE)).thenReturn(new ArrayList<>());
        when(versionRepository.findFirstByStatusOrderByCreatedAtDesc(AiKbVersion.STATUS_ACTIVE))
            .thenReturn(Optional.empty());
        String bundle = sampleBundleJson(7);
        service.importWntBundle(wntFor(bundle), AiKbVersion.SRC_OFFLINE, "admin");

        org.mockito.ArgumentCaptor<AiKbVersion> captor = org.mockito.ArgumentCaptor.forClass(AiKbVersion.class);
        verify(versionRepository).save(captor.capture());
        AiKbVersion stored = captor.getValue();
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
        newService(false);
        assertFalse(service.listVersions() == null); // listVersions 不依赖授权，正常返回
        when(versionRepository.findAllByOrderByVersionNoDesc()).thenReturn(new ArrayList<>());
        List<KbMeta> list = service.listVersions();
        assertTrue(list.isEmpty());
        when(versionRepository.findFirstByStatusOrderByCreatedAtDesc(anyString())).thenReturn(Optional.empty());
        assertEquals(null, service.getActiveMeta());
    }

    // ===== #1 内容去重：与当前 active 相同则不落新版本、返回 unchanged =====

    @Test
    void importSkipsWhenContentUnchanged() throws Exception {
        newService(true);
        String bundle = sampleBundleJson(11);
        AiKbVersion cur = new AiKbVersion();
        cur.setId(1L);
        cur.setVersionNo(11L);
        cur.setStatus(AiKbVersion.STATUS_ACTIVE);
        cur.setKbHash(KbCrypto.sha256Hex(bundle));
        when(versionRepository.findFirstByStatusOrderByCreatedAtDesc(AiKbVersion.STATUS_ACTIVE))
            .thenReturn(Optional.of(cur));

        KbMeta meta = service.importWntBundle(wntFor(bundle), AiKbVersion.SRC_OFFLINE, "admin");

        assertTrue(meta.isUnchanged());
        assertEquals(11L, meta.getVersionNo());
        verify(versionRepository, never()).save(any());
        verify(versionRepository, never()).saveAll(any());
    }

    @Test
    void importSucceedsWhenContentDiffers() throws Exception {
        newService(true);
        when(versionRepository.findFirstByOrderByVersionNoDesc()).thenReturn(Optional.empty());
        when(versionRepository.findAllByStatus(AiKbVersion.STATUS_ACTIVE)).thenReturn(new ArrayList<>());
        AiKbVersion cur = new AiKbVersion();
        cur.setVersionNo(1L);
        cur.setStatus(AiKbVersion.STATUS_ACTIVE);
        cur.setKbHash("stale-hash-different");
        when(versionRepository.findFirstByStatusOrderByCreatedAtDesc(AiKbVersion.STATUS_ACTIVE))
            .thenReturn(Optional.of(cur));
        String bundle = sampleBundleJson(2);

        KbMeta meta = service.importWntBundle(wntFor(bundle), AiKbVersion.SRC_OFFLINE, "admin");

        assertFalse(meta.isUnchanged());
        verify(versionRepository, times(1)).save(any(AiKbVersion.class));
    }

    // ===== #3 网关同步态：import 据 syncCategories 结果写回 gatewaySyncedHash/At =====

    @Test
    void importRecordsGatewaySyncedHashOnSyncSuccess() throws Exception {
        newService(true);
        when(versionRepository.findFirstByOrderByVersionNoDesc()).thenReturn(Optional.empty());
        when(versionRepository.findAllByStatus(AiKbVersion.STATUS_ACTIVE)).thenReturn(new ArrayList<>());
        when(versionRepository.findFirstByStatusOrderByCreatedAtDesc(AiKbVersion.STATUS_ACTIVE))
            .thenReturn(Optional.empty());
        KbGatewaySync sync = mock(KbGatewaySync.class);
        when(sync.syncCategories(any(KbBundle.class))).thenReturn(true);
        service.setGatewaySync(sync);

        String bundle = sampleBundleJson(21);
        String hash = KbCrypto.sha256Hex(bundle);
        service.importWntBundle(wntFor(bundle), AiKbVersion.SRC_OFFLINE, "admin");

        org.mockito.ArgumentCaptor<AiKbVersion> captor = org.mockito.ArgumentCaptor.forClass(AiKbVersion.class);
        verify(versionRepository, times(1)).save(captor.capture());
        AiKbVersion saved = captor.getValue();
        assertEquals(hash, saved.getKbHash());
        assertEquals(hash, saved.getGatewaySyncedHash());
        assertNotNull(saved.getGatewaySyncedAt());
    }

    @Test
    void importLeavesSyncPendingWhenGatewaySyncFails() throws Exception {
        newService(true);
        when(versionRepository.findFirstByOrderByVersionNoDesc()).thenReturn(Optional.empty());
        when(versionRepository.findAllByStatus(AiKbVersion.STATUS_ACTIVE)).thenReturn(new ArrayList<>());
        when(versionRepository.findFirstByStatusOrderByCreatedAtDesc(AiKbVersion.STATUS_ACTIVE))
            .thenReturn(Optional.empty());
        KbGatewaySync sync = mock(KbGatewaySync.class);
        when(sync.syncCategories(any(KbBundle.class))).thenReturn(false);
        service.setGatewaySync(sync);

        String bundle = sampleBundleJson(22);
        service.importWntBundle(wntFor(bundle), AiKbVersion.SRC_OFFLINE, "admin");

        org.mockito.ArgumentCaptor<AiKbVersion> captor = org.mockito.ArgumentCaptor.forClass(AiKbVersion.class);
        verify(versionRepository, times(1)).save(captor.capture());
        assertNull(captor.getValue().getGatewaySyncedHash());
        assertNull(captor.getValue().getGatewaySyncedAt());
    }

    /** #1/#3 回归：重试同步时解密失败必须清空可能残留的旧同步态（避免徽标谎报“已同步”）。 */
    @Test
    void syncActiveToGatewayClearsStaleStateOnDecryptFailure() {
        newService(true);
        service.setGatewaySync(mock(KbGatewaySync.class)); // 解密先失败，不应触达同步器
        AiKbVersion active = new AiKbVersion();
        active.setId(5L);
        active.setVersionNo(5L);
        active.setStatus(AiKbVersion.STATUS_ACTIVE);
        active.setKbHash("h5");
        active.setBundleCipher("not-a-valid-ciphertext");
        active.setGatewaySyncedHash("h5"); // 残留的旧“已同步”态
        active.setGatewaySyncedAt(LocalDateTime.now());
        when(versionRepository.findFirstByStatusOrderByCreatedAtDesc(AiKbVersion.STATUS_ACTIVE))
            .thenReturn(Optional.of(active));

        boolean ok = service.syncActiveToGateway();

        assertFalse(ok);
        assertNull(active.getGatewaySyncedHash());
        assertNull(active.getGatewaySyncedAt());
        verify(versionRepository, times(1)).save(active);
    }

    // ===== 授权 .dat 链路（RSA/AES 混合信封，本期零改动）=====

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
        LicenseInfo info = licenseVerifier().verify(licenseDat("", false));
        assertTrue(info.isValid(), "reason=" + info.getReason());
        assertFalse(info.isCheckSn());
    }

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
        // 回归 H1：formal 永久授权但 ai_kb_update 功能位 endTime 已过，运行时应被 getStatus 降级为 expired。
        com.asg.console.extension.repository.AiKbLicenseRepository licRepo =
            mock(com.asg.console.extension.repository.AiKbLicenseRepository.class);
        com.asg.console.extension.model.AiKbLicense row = new com.asg.console.extension.model.AiKbLicense();
        row.setId(1L);
        row.setStatus(com.asg.console.extension.model.AiKbLicense.STATUS_VALID);
        row.setLicenseType(com.asg.console.extension.model.AiKbLicense.TYPE_FORMAL);
        row.setExpiresAt(null);
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
