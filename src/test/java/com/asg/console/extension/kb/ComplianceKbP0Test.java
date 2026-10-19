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

import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson.JSON;

/**
 * BE-011 内容合规词库 P0 自测（不依赖 Spring/DB）：附录 A bundle 解析与统计、block/advisory 分流、
 * {@code .wnt} 内层 AES-GCM 封装 + 外层 ECC 校签的完整往返，以及去重用的 sha256 稳定性。
 */
class ComplianceKbP0Test {

    /** 一个含 2 block + 1 advisory 的附录 A 同构 bundle JSON（与 appA_rules.json 字段一致）。 */
    private static String sampleBundleJson() {
        return "{\"standard\":\"GB/T 45654-2025\",\"appendix\":\"A\",\"generatedBy\":\"test\","
            + "\"categoryTotal\":3,\"keywordTotal\":5,\"sections\":["
            + "{\"section\":\"A.1\",\"title\":\"危害国家安全\",\"clause\":\"A.1\",\"categories\":["
            + "{\"code\":\"A.1-a\",\"name\":\"煽动颠覆\",\"mode\":\"block\",\"advice\":\"\","
            + "\"keywords\":[\"颠覆政权\",\"推翻制度\"],\"patterns\":[\"(颠[覆付]\\\\s*政[权全])\"],\"keywordCount\":2,"
            + "\"sample\":{\"positive\":\"煽动颠覆教程\",\"negative\":\"介绍历史文化\"}},"
            + "{\"code\":\"A.1-b\",\"name\":\"分裂主义\",\"mode\":\"block\",\"advice\":\"\","
            + "\"keywords\":[\"分裂国家\"],\"patterns\":[],\"keywordCount\":1,"
            + "\"sample\":{\"positive\":\"分裂国家\",\"negative\":\"团结统一\"}}]},"
            + "{\"section\":\"A.5\",\"title\":\"免责场景\",\"clause\":\"A.5\",\"categories\":["
            + "{\"code\":\"A.5-a\",\"name\":\"医疗建议\",\"mode\":\"advisory\",\"advice\":\"请咨询专业医师\","
            + "\"keywords\":[\"怎么治病\"],\"patterns\":[],\"keywordCount\":2,"
            + "\"sample\":{\"positive\":\"怎么治病\",\"negative\":\"今天天气\"}}]}]}";
    }

    @Test
    void bundleParseCountsAndModeSplit() {
        ComplianceKbBundle b = JSON.parseObject(sampleBundleJson(), ComplianceKbBundle.class);
        assertFalse(b.isEmpty());
        assertEquals(3, b.categoryCount());
        assertEquals(5, b.keywordCount());
        assertEquals("GB/T 45654-2025", b.getStandard());
        assertEquals("A", b.getAppendix());

        int block = 0;
        int advisory = 0;
        for (ComplianceKbBundle.Section s : b.getSections()) {
            for (ComplianceKbBundle.Category c : s.getCategories()) {
                if (ComplianceKbBundle.isAdvisory(c)) {
                    advisory++;
                    assertEquals("请咨询专业医师", c.getAdvice());
                } else {
                    block++;
                }
            }
        }
        assertEquals(2, block);
        assertEquals(1, advisory);
        // 样本对解析
        assertEquals("煽动颠覆教程", b.getSections().get(0).getCategories().get(0).getSample().getPositive());
    }

    @Test
    void wntSealAndOpenRoundTrip() throws Exception {
        String json = sampleBundleJson();
        byte[] bundleKey = Base64.getDecoder().decode(
            Base64.getEncoder().encodeToString(new byte[32])); // 任意 32B（全零）作测试密钥
        byte[] priv32 = WntVerifier.generateEccPriv();
        byte[] pub33 = WntVerifier.eccPublicCompressed(priv32);

        byte[] inner = WntBundleCipher.seal(json, bundleKey);
        byte[] wnt = WntBundleCodec.encode(inner, priv32, 3, 1);
        String b64 = Base64.getEncoder().encodeToString(wnt);

        WntBundleCipher cipher = new WntBundleCipher(pub33, null, bundleKey);
        WntBundleCipher.OpenBundle opened = cipher.openWnt(b64);
        assertEquals(json, opened.bundleJson);
        assertEquals("ecc-p256-sha256", opened.sigAlgorithm);
        // 签名头非空（r‖s 64B → Base64 88 字符含填充）
        assertEquals(64, Base64.getMimeDecoder().decode(opened.signatureBase64).length);

        // 用另一把私钥的公钥验签 → 失败（校签拒）
        byte[] evilPub = WntVerifier.eccPublicCompressed(WntVerifier.generateEccPriv());
        WntBundleCipher wrong = new WntBundleCipher(evilPub, null, bundleKey);
        boolean threw = false;
        try {
            wrong.openWnt(b64);
        } catch (Exception e) {
            threw = true;
        }
        assertTrue(threw, "signature under non-matching pubkey must fail openWnt");
    }

    @Test
    void sha256StableForDedup() throws Exception {
        String json = sampleBundleJson();
        String h1 = KbCrypto.sha256Hex(json);
        String h2 = KbCrypto.sha256Hex(json);
        assertEquals(64, h1.length());
        assertEquals(h1, h2);
        assertNotEquals(h1, KbCrypto.sha256Hex(json + " "));
    }
}
