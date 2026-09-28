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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * P0 底座自测：用桩 RSA 密钥对验证 KB 内容签名/验签、AES-GCM 加解密，
 * 以及 {@code .dat} 授权文件的 RSA/AES 信封门控（有效/正式永久/过期/ESN 不匹配/篡改/缺功能位/缺密钥）。
 * 不依赖 Spring 容器与数据库。
 */
class KbP0Test {

    /** KB 内容签名密钥对（厂商侧签名 / console 侧验签）。 */
    private static KeyPair kp;
    /** 授权用：console 密钥对（拆 AES）与厂商密钥对（签名）。 */
    private static KeyPair consoleKp;
    private static KeyPair vendorKp;

    @BeforeAll
    static void gen() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        kp = g.generateKeyPair();
        consoleKp = g.generateKeyPair();
        vendorKp = g.generateKeyPair();
    }

    private static byte[] sign(PrivateKey priv, byte[] data) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(priv);
        s.update(data);
        return s.sign();
    }

    private KbBundle sampleBundle() {
        KbBundle b = new KbBundle();
        b.setKbVersion(20260922001L);
        b.setGeneratedAt("2026-09-22T00:00:00");
        KbBundle.Category c = new KbBundle.Category();
        c.setName("saas_ai");
        c.setLabel("SaaS AI");
        c.setRiskLevel("high");
        c.setDomains(Arrays.asList("chatgpt.com", "claude.ai"));
        b.setCategories(Arrays.asList(c));
        return b;
    }

    @Test
    void bundleSignVerifyAndEncryptRoundTrip() throws Exception {
        KbBundle bundle = sampleBundle();
        String plain = JSON.toJSONString(bundle);

        // 签名/验签
        byte[] sig = sign(kp.getPrivate(), plain.getBytes(StandardCharsets.UTF_8));
        KbSignatureVerifier verifier = new RsaKbSignatureVerifier(kp.getPublic());
        assertTrue(verifier.verify(plain.getBytes(StandardCharsets.UTF_8), sig));
        // 篡改明文 → 验签失败
        assertFalse(verifier.verify((plain + " ").getBytes(StandardCharsets.UTF_8), sig));
        assertEquals("rsa-sha256", verifier.algorithm());

        // 加解密往返
        KbCrypto crypto = new KbCrypto("unit-test-key");
        String cipher = crypto.encrypt(plain);
        assertNotEquals(plain, cipher);
        assertEquals(plain, crypto.decrypt(cipher));
        // 密文篡改 → GCM 完整性失败
        byte[] raw = Base64.getDecoder().decode(cipher);
        raw[raw.length - 1] ^= 0x01;
        boolean threw = false;
        try {
            crypto.decrypt(Base64.getEncoder().encodeToString(raw));
        } catch (Exception e) {
            threw = true;
        }
        assertTrue(threw, "tampered ciphertext must fail GCM auth");

        // 统计
        assertEquals(1, bundle.categoryTotal());
        assertEquals(2, bundle.domainTotal());
        assertEquals(64, KbCrypto.sha256Hex(plain).length());
    }

    // ===== .dat 授权 =====

    private DatLicenseVerifier verifier() {
        return new DatLicenseVerifier(consoleKp.getPrivate(), new RsaKbSignatureVerifier(vendorKp.getPublic()));
    }

    /** 构造一个 TEG 风格授权载荷。type: formal/temporary；endTime 为空表示永久。 */
    private JSONObject payload(String esn, String type, String endTime, String... functions) {
        JSONObject o = new JSONObject();
        o.put("version", "V1.0");
        o.put("licenseId", "LIC-TEST-0001");
        o.put("productName", "AI融合安全网关");
        o.put("productVersion", "V100R001");
        o.put("companyName", "Acme 工业有限公司");
        o.put("contractNo", "HT-2026-0001");
        o.put("esn", esn);
        o.put("licenseType", type);
        o.put("licenseModel", "威努特ASG-KB-STD");
        o.put("licenseName", "AI分类知识库更新授权");
        o.put("licenseValue", 12);
        o.put("iegCustomerId", "IEG-0001");
        o.put("iegAuthorizedCount", 5);
        o.put("functions", Arrays.asList(functions));
        o.put("createTime", "2026-09-22 00:00:00");
        o.put("endTime", endTime);
        return o;
    }

    private String dat(JSONObject payload, String esn) throws Exception {
        return DatLicenseCodec.encodeToBase64(payload.toJSONString(), esn, consoleKp.getPublic(), vendorKp.getPrivate());
    }

    private String future() {
        return java.time.LocalDateTime.now().plusYears(1).withNano(0).toString();
    }

    private String past() {
        return java.time.LocalDateTime.now().minusDays(1).withNano(0).toString();
    }

    @Test
    void licenseTemporaryValid() throws Exception {
        // 无设备绑定授权（checkSn=false）：ESN 留空也应通过
        JSONObject p = payload("", "temporary", future(), LicenseInfo.FEATURE_KB_UPDATE);
        p.put("checkSn", false);
        LicenseInfo ok = verifier().verify(dat(p, ""));
        assertTrue(ok.isValid(), "valid temporary license should pass: " + ok.getReason());
        assertTrue(ok.hasFeature(LicenseInfo.FEATURE_KB_UPDATE));
        assertEquals("Acme 工业有限公司", ok.getCompanyName());
        assertEquals(Integer.valueOf(12), ok.getLicenseValue());
    }

    @Test
    void licenseFormalNeverExpires() throws Exception {
        // 正式（永久）授权：即使 endTime 为空也有效（checkSn=false 免设备绑定）
        JSONObject p = payload("", "formal", "", LicenseInfo.FEATURE_KB_UPDATE);
        p.put("checkSn", false);
        LicenseInfo ok = verifier().verify(dat(p, ""));
        assertTrue(ok.isValid(), "formal license should pass: " + ok.getReason());
        assertTrue(ok.isFormal());
    }

    @Test
    void licenseExpiredNowGovernedByFunctionWindow() throws Exception {
        // 顶层到期时间已移除：临时型授权是否过期唯一看功能位 endTime（此处 ai_kb_update 已过期）。
        // licenseType 仅展示标签，不影响到期判定。
        JSONObject p = payload("", "temporary", "", LicenseInfo.FEATURE_KB_UPDATE);
        p.put("checkSn", false);
        p.put("functions", Arrays.asList(fnObj(LicenseInfo.FEATURE_KB_UPDATE, "2026-09-22 00:00:00", past())));
        LicenseInfo exp = verifier().verify(dat(p, ""));
        assertFalse(exp.isValid());
        assertEquals("知识库更新授权已过期", exp.getReason());
    }

    @Test
    void licenseEsnMismatch() throws Exception {
        LicenseInfo bad = verifier().verify(dat(payload("WRONG-ESN-999", "formal", "",
            LicenseInfo.FEATURE_KB_UPDATE), "WRONG-ESN-999"));
        assertFalse(bad.isValid());
        assertEquals("设备ESN不匹配", bad.getReason());
    }

    @Test
    void licenseEsnMatch() throws Exception {
        String esn = EsnProvider.current();
        LicenseInfo ok = verifier().verify(dat(payload(esn, "formal", "", LicenseInfo.FEATURE_KB_UPDATE), esn));
        assertTrue(ok.isValid(), "esn-bound license should pass: " + ok.getReason());
        assertEquals(esn, ok.getEsn());
    }

    @Test
    void licenseMissingFunction() throws Exception {
        // checkSn=false 免设备绑定，聚焦验证功能位缺失被拒
        JSONObject p = payload("", "formal", "", "some_other_feature");
        p.put("checkSn", false);
        LicenseInfo noFn = verifier().verify(dat(p, ""));
        assertFalse(noFn.isValid());
        assertTrue(noFn.getReason().contains(LicenseInfo.FEATURE_KB_UPDATE));
    }

    private JSONObject fnObj(String id, String createTime, String endTime) {
        JSONObject f = new JSONObject();
        f.put("id", id);
        f.put("createTime", createTime);
        f.put("endTime", endTime);
        return f;
    }

    @Test
    void licenseFunctionValidWindow() throws Exception {
        // 功能位携带各自时间且未过期（正式授权，checkSn=false）→ valid
        JSONObject p = payload("", "formal", "", LicenseInfo.FEATURE_KB_UPDATE);
        p.put("checkSn", false);
        p.put("functions", Arrays.asList(fnObj(LicenseInfo.FEATURE_KB_UPDATE, "2026-09-22 00:00:00", future())));
        LicenseInfo ok = verifier().verify(dat(p, ""));
        assertTrue(ok.isValid(), "function in valid window should pass: " + ok.getReason());
    }

    @Test
    void licenseFunctionExpired() throws Exception {
        // license 为正式（窗口永久）但 ai_kb_update 功能位自身过期 → invalid
        JSONObject p = payload("", "formal", "", LicenseInfo.FEATURE_KB_UPDATE);
        p.put("checkSn", false);
        p.put("functions", Arrays.asList(fnObj(LicenseInfo.FEATURE_KB_UPDATE, "2026-09-22 00:00:00", past())));
        LicenseInfo exp = verifier().verify(dat(p, ""));
        assertFalse(exp.isValid());
        assertEquals("知识库更新授权已过期", exp.getReason());
    }

    @Test
    void licenseTamperedCiphertext() throws Exception {
        // esn="" 时密文区从偏移 284 开始（magic7+ver1+esnLen2+iv12+wrappedLen2+wrapped256+ctLen4）
        String b64 = dat(payload("", "formal", "", LicenseInfo.FEATURE_KB_UPDATE), "");
        byte[] raw = Base64.getDecoder().decode(b64);
        raw[290] ^= 0x01; // 篡改密文
        LicenseInfo tampered = verifier().verify(Base64.getEncoder().encodeToString(raw));
        assertFalse(tampered.isValid());
        assertEquals("授权内容被篡改", tampered.getReason());
    }

    @Test
    void licenseBadSignature() throws Exception {
        // 用另一把厂商私钥签名（console 用 vendorKp 公钥验签）→ 签名校验失败
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair evil = g.generateKeyPair();
        String b64 = DatLicenseCodec.encodeToBase64(
            payload("", "formal", "", LicenseInfo.FEATURE_KB_UPDATE).toJSONString(), "",
            consoleKp.getPublic(), evil.getPrivate());
        LicenseInfo bad = verifier().verify(b64);
        assertFalse(bad.isValid());
        assertEquals("授权签名校验失败", bad.getReason());
    }

    @Test
    void licenseRejectsWhenKeysMissing() throws Exception {
        String b64 = dat(payload("", "formal", "", LicenseInfo.FEATURE_KB_UPDATE), "");
        // 无 console 解密私钥
        assertFalse(new DatLicenseVerifier(null, new RsaKbSignatureVerifier(vendorKp.getPublic()))
            .verify(b64).isValid());
        // 无厂商验签公钥
        assertFalse(new DatLicenseVerifier(consoleKp.getPrivate(), null).verify(b64).isValid());
    }
}
