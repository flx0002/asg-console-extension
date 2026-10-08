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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson.JSON;
import com.asg.console.extension.model.AiKbVersion;
import com.asg.console.extension.repository.AiKbVersionRepository;
import com.asg.console.extension.service.AiKbServiceImpl;
import com.asg.console.extension.service.KbLicenseService;

/**
 * P2 单测：KB → 网关 categories 映射与同步。
 * 覆盖：mapper 产出 snake_case（含 body_features/path_patterns，与 CR 同源）、
 * 导入后触发 CR 同步、syncActiveToGateway。
 */
class AiKbP2Test {

    /** 内层 AES-256-GCM 测试密钥（仅测试用，非生产 ASG_KB_BUNDLE_KEY）。 */
    private static final byte[] TEST_BUNDLE_KEY = filled((byte) 0x11, 32);
    /** WNT 校签用临时 ECC 密钥（每次进程生成）。 */
    private static byte[] eccPriv;
    private static byte[] eccPub33;

    private static byte[] filled(byte b, int n) {
        byte[] a = new byte[n];
        Arrays.fill(a, b);
        return a;
    }

    @BeforeAll
    static void genKeys() {
        eccPriv = WntVerifier.generateEccPriv();
        eccPub33 = WntVerifier.eccPublicCompressed(eccPriv);
    }

    /** 把明文 bundle 打包成 Base64 .wnt（等价于本机 wnt_pack.ps1）。 */
    private static String wntFor(String bundleJson) throws Exception {
        byte[] inner = WntBundleCipher.seal(bundleJson, TEST_BUNDLE_KEY);
        return Base64.getEncoder().encodeToString(WntBundleCodec.encode(inner, eccPriv, 3, 1));
    }

    private static KbBundle.Category fullCategory() {
        KbBundle.Category c = new KbBundle.Category();
        c.setName("saas_ai");
        c.setLabel("云端SaaS AI");
        c.setRiskLevel("high");
        c.setDomains(new ArrayList<>(Arrays.asList("chat.openai.com", "claude.ai")));
        c.setSuffixes(new ArrayList<>(Collections.singletonList(".openai.com")));
        c.setBodyFeatures(new ArrayList<>(Arrays.asList("model", "messages", "prompt")));
        c.setPathPatterns(new ArrayList<>(Arrays.asList("/v1/chat/completions", "/v1/messages")));
        return c;
    }

    private static KbBundle bundleWith(KbBundle.Category... cats) {
        KbBundle b = new KbBundle();
        b.setKbVersion(20260922002L);
        b.setGeneratedAt("2026-09-22T00:00:00");
        b.setCategories(new ArrayList<>(Arrays.asList(cats)));
        return b;
    }

    @Test
    void mapperEmitsSnakeCaseKeysAlignedWithCr() {
        List<Map<String, Object>> cats = KbCategoryMapper.toGatewayCategories(bundleWith(fullCategory()));
        assertNotNull(cats);
        assertEquals(1, cats.size());
        Map<String, Object> m = cats.get(0);
        // 键与 ai-shadow-detect CR / bypass CategoryRule 完全同源
        assertTrue(m.containsKey("name"));
        assertTrue(m.containsKey("risk_level"));
        assertTrue(m.containsKey("body_features"));
        assertTrue(m.containsKey("path_patterns"));
        assertTrue(m.containsKey("domains"));
        assertTrue(m.containsKey("suffixes"));
        assertEquals("saas_ai", m.get("name"));
        assertEquals("high", m.get("risk_level"));
        assertEquals(Arrays.asList("model", "messages", "prompt"), m.get("body_features"));
    }

    @Test
    void mapperReturnsNullForEmptyBundle() {
        assertNull(KbCategoryMapper.toGatewayCategories(null));
        assertNull(KbCategoryMapper.toGatewayCategories(new KbBundle()));
    }

    @Test
    void importTriggersGatewaySync() throws Exception {
        AiKbVersionRepository versionRepository = mock(AiKbVersionRepository.class);
        KbLicenseService licenseService = mock(KbLicenseService.class);
        KbGatewaySync gatewaySync = mock(KbGatewaySync.class);
        when(licenseService.canUpdate()).thenReturn(true);
        when(versionRepository.findFirstByOrderByVersionNoDesc()).thenReturn(Optional.empty());
        when(versionRepository.findAllByStatus(AiKbVersion.STATUS_ACTIVE)).thenReturn(new ArrayList<>());
        when(versionRepository.save(any(AiKbVersion.class))).thenAnswer(inv -> {
            AiKbVersion v = inv.getArgument(0);
            if (v.getId() == null) {
                v.setId(System.nanoTime());
            }
            return v;
        });
        when(gatewaySync.syncCategories(any(KbBundle.class))).thenReturn(true);

        AiKbServiceImpl service = new AiKbServiceImpl();
        service.setVersionRepository(versionRepository);
        service.setLicenseService(licenseService);
        service.setKbCrypto(new KbCrypto("unit-test-key"));
        service.setWntBundleCipher(new WntBundleCipher(eccPub33, null, TEST_BUNDLE_KEY));
        service.setOnlineClient(new KbOnlineClient());
        service.setGatewaySync(gatewaySync);

        KbBundle bundle = bundleWith(fullCategory());
        String bundleJson = JSON.toJSONString(bundle);
        service.importWntBundle(wntFor(bundleJson), AiKbVersion.SRC_OFFLINE, "admin");

        verify(gatewaySync, times(1)).syncCategories(any(KbBundle.class));
    }

    @Test
    void syncActiveToGatewayUsesDecryptedActiveBundle() throws Exception {
        AiKbVersionRepository versionRepository = mock(AiKbVersionRepository.class);
        KbLicenseService licenseService = mock(KbLicenseService.class);
        KbGatewaySync gatewaySync = mock(KbGatewaySync.class);
        KbCrypto crypto = new KbCrypto("unit-test-key");
        when(licenseService.canUpdate()).thenReturn(true);
        when(gatewaySync.syncCategories(any(KbBundle.class))).thenReturn(true);

        // 预置一个已加密的 active 版本
        KbBundle bundle = bundleWith(fullCategory());
        String bundleJson = JSON.toJSONString(bundle);
        AiKbVersion active = new AiKbVersion();
        active.setId(1L);
        active.setVersionNo(20260922002L);
        active.setStatus(AiKbVersion.STATUS_ACTIVE);
        active.setBundleCipher(crypto.encrypt(bundleJson));
        when(versionRepository.findFirstByStatusOrderByCreatedAtDesc(AiKbVersion.STATUS_ACTIVE))
            .thenReturn(Optional.of(active));

        AiKbServiceImpl service = new AiKbServiceImpl();
        service.setVersionRepository(versionRepository);
        service.setLicenseService(licenseService);
        service.setKbCrypto(crypto);
        service.setOnlineClient(new KbOnlineClient());
        service.setGatewaySync(gatewaySync);

        assertTrue(service.syncActiveToGateway());
        verify(gatewaySync, times(1)).syncCategories(any(KbBundle.class));
    }
}
