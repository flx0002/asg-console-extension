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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * BE-011 e2e 授权（{@code .dat}）签发工具（仅 test 源集，不进产物）。
 *
 * <p>用与 console 完全相同的 {@link DatLicenseCodec#encodeToBase64} 现签一把含
 * {@code content_compliance_kb} 功能位的 e2e 授权，并打印配套公钥/私钥，供端到端验证时
 * 临时注入到 console（{@code ASG_KB_VERIFY_PUBKEY}/{@code ASG_KB_LICENSE_PRIVKEY}）：
 * <ul>
 *   <li>厂商 RSA 密钥对：私钥签名、公钥注入验签器；</li>
 *   <li>产品（console）RSA 密钥对：公钥包裹 AES 会话密钥、私钥注入 {@code ASG_KB_LICENSE_PRIVKEY}；</li>
 *   <li>functions 同时含 {@code ai_kb_update}（使授权整体判定为 valid）与
 *       {@code content_compliance_kb}（使 {@code canUpdateCompliance()} 通过）；</li>
 *   <li>checkSn=false 跳过设备绑定，endTime 空=永久。</li>
 * </ul>
 *
 * <p>用途限于 e2e：这些是临时自签密钥，不代表厂商生产签名密钥。
 */
public final class LicenseSeedTool {

    private LicenseSeedTool() {
    }

    public static void main(String[] args) throws Exception {
        String outDat = args.length > 0 ? args[0] : "/home/wnt/ASG/asg-deploy/artifacts/ASG-compliance-e2e-license.dat";
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair vendor = kpg.generateKeyPair();
        KeyPair console = kpg.generateKeyPair();

        JSONObject p = new JSONObject();
        p.put("version", "V1.0");
        p.put("licenseId", "B011-E2E-LIC");
        p.put("productName", "AI融合安全网关");
        p.put("productVersion", "V100R001");
        p.put("companyName", "ASG E2E Test Customer");
        p.put("contractNo", "HT-B011-E2E");
        p.put("esn", "");
        p.put("checkSn", false);
        p.put("licenseType", "formal");
        p.put("licenseModel", "ASG-KB-STD");
        p.put("licenseName", "KB+COMPLIANCE");
        p.put("licenseValue", 1);
        p.put("iegCustomerId", "IEG-E2E");
        p.put("iegAuthorizedCount", 1);
        JSONArray fns = new JSONArray();
        fns.add(LicenseInfo.FEATURE_KB_UPDATE);
        fns.add(LicenseInfo.FEATURE_COMPLIANCE_KB);
        p.put("functions", fns);
        p.put("createTime", "2026-09-22 00:00:00");
        p.put("endTime", "");

        String dat = DatLicenseCodec.encodeToBase64(p.toJSONString(), "", console.getPublic(), vendor.getPrivate());
        Files.write(Paths.get(outDat), dat.getBytes(StandardCharsets.UTF_8));

        String vendorPubB64 = Base64.getEncoder().encodeToString(vendor.getPublic().getEncoded());
        String consolePrivB64 = Base64.getEncoder().encodeToString(console.getPrivate().getEncoded());

        System.out.println("LICENSE_DAT_FILE=" + outDat);
        System.out.println("LICENSE_DAT_B64_LEN=" + dat.length());
        System.out.println("ASG_KB_VERIFY_PUBKEY=" + vendorPubB64);
        System.out.println("ASG_KB_LICENSE_PRIVKEY=" + consolePrivB64);
        // 自校验：用刚生成的 console 私钥 + 厂商公钥还原，确保可被 console 同构验证
        LicenseInfo info = new DatLicenseVerifier(console.getPrivate(),
            new RsaKbSignatureVerifier(vendor.getPublic())).verify(dat);
        System.out.println("SELFCHK_valid=" + info.isValid()
            + " authenticated=" + info.isAuthenticated()
            + " hasAiKb=" + (info.findFunction(LicenseInfo.FEATURE_KB_UPDATE) != null)
            + " hasCompliance=" + (info.findFunction(LicenseInfo.FEATURE_COMPLIANCE_KB) != null)
            + " reason=" + info.getReason());
        System.out.println(info.isValid() ? "LICSEED_OK" : "LICSEED_FAIL");
    }
}
