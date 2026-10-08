/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * KB 更新包（{@code .wnt}）解密器：外层 signtool 头保真 + 内层我方 AES-256-GCM 保机密。
 *
 * <p>厂商 signtool 的首块「伪加密」密钥由明文头可推导（{@code SHA256(hdr[0:16])[0:16]}），
 * 不含机密性；故内层（签名前喂给 {@code signtool -i} 的原始字节）必须是我方用对称密钥
 * {@code ASG_KB_BUNDLE_KEY}（32B）以 AES-256-GCM 封装的 bundle。本类编排：
 * <ol>
 *   <li>{@link WntBundleCodec#decode} 校签（厂商公钥）并还原内层原始字节；</li>
 *   <li>{@link #open} 用 bundle 对称密钥解开内层得到 bundle 明文 JSON。</li>
 * </ol>
 * 任一失败即抛异常，调用方据此拒绝更新，保持最后一次有效库。
 *
 * <p>内层 blob 布局（大端）：{@code iv(12) | ctLen(4) | ct}，其中 {@code ct} 为 AES-256-GCM
 * 密文（含 16B tag）。沿用 {@link KbCrypto} 的 GCM 参数约定，仅去掉 RSA 包裹与 magic。
 *
 * <p>安全默认：验签公钥或 bundle 密钥缺失时，{@link #openWnt} 直接拒绝（无密钥不允许任何更新）。
 */
public class WntBundleCipher {

    private static final int IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_KEY_LEN = 32; // AES-256

    /** 33B SEC1 压缩 ECC 公钥（enver=1 验签用）；null 即拒绝 ECC 包。 */
    private final byte[] eccPub33;
    /** 65B 非压缩 SM2 公钥（enver=2 验签用）；本期不配置即 GM 包 fail-closed 拒绝。 */
    private final byte[] sm2Pub;
    /** 32B 内层 AES-256-GCM 对称密钥；null 即拒绝所有更新。 */
    private final byte[] bundleKey;

    public WntBundleCipher(byte[] eccPub33, byte[] sm2Pub, byte[] bundleKey) {
        this.eccPub33 = eccPub33;
        this.sm2Pub = sm2Pub;
        this.bundleKey = bundleKey;
    }

    /** 是否可安全启用（公钥 + 对称密钥齐备）。 */
    public boolean isConfigured() {
        return bundleKey != null && (eccPub33 != null || sm2Pub != null);
    }

    /** {@link #openWnt} 的结果：bundle 明文 JSON + WNT 头部签名（Base64）+ 签名算法标识。 */
    public static final class OpenBundle {
        public final String bundleJson;
        public final String signatureBase64;
        public final String sigAlgorithm;

        OpenBundle(String bundleJson, String signatureBase64, String sigAlgorithm) {
            this.bundleJson = bundleJson;
            this.signatureBase64 = signatureBase64;
            this.sigAlgorithm = sigAlgorithm;
        }
    }

    /**
     * 拆解一个 Base64 {@code .wnt}：校签（厂商公钥）→ 还原内层 → 用对称密钥解开内层。
     *
     * @throws Exception 密钥未配置、验签失败、长度非法或内层解密/完整性失败
     */
    public OpenBundle openWnt(String base64Wnt) throws Exception {
        if (base64Wnt == null || base64Wnt.trim().isEmpty()) {
            throw new IllegalArgumentException("KB 更新包内容为空");
        }
        if (bundleKey == null) {
            throw new IllegalStateException("KB 更新包内层解密密钥未配置（ASG_KB_BUNDLE_KEY）");
        }
        if (eccPub33 == null && sm2Pub == null) {
            throw new IllegalStateException("KB 更新包验签公钥未配置（ASG_KB_WNT_PUBKEY）");
        }
        byte[] wnt = Base64.getMimeDecoder().decode(base64Wnt.replaceAll("\\s", ""));
        WntBundleCodec.Result r = WntBundleCodec.decode(wnt, eccPub33, sm2Pub);
        String bundleJson = open(r.inner, bundleKey);
        return new OpenBundle(bundleJson, Base64.getEncoder().encodeToString(r.signature), r.sigAlgorithm);
    }

    // ===== 内层 AES-256-GCM（seal/open）=====

    /** 封装：明文 bundle JSON → 内层原始字节（{@code iv(12)|ctLen(4,BE)|ct}）。打包工具/单测用。 */
    public static byte[] seal(String bundleJson, byte[] key) throws Exception {
        requireKey(key);
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = cipher.doFinal(bundleJson.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(iv);
        out.write(ByteBuffer.allocate(4).putInt(ct.length).array());
        out.write(ct);
        return out.toByteArray();
    }

    /** 解封：内层原始字节 → 明文 bundle JSON。长度/完整性不符抛异常。 */
    public static String open(byte[] blob, byte[] key) throws Exception {
        requireKey(key);
        if (blob == null || blob.length < IV_LEN + 4) {
            throw new IllegalArgumentException("KB 更新包内层长度非法");
        }
        ByteBuffer buf = ByteBuffer.wrap(blob);
        byte[] iv = new byte[IV_LEN];
        buf.get(iv);
        int ctLen = buf.getInt();
        // 严格等长：ct 须为「剩余全部字节」且至少含 16B GCM tag，不容尾随垃圾字节（虽外层签名已覆盖，纵深防御）
        if (ctLen < GCM_TAG_BITS / 8 || ctLen != buf.remaining()) {
            throw new IllegalArgumentException("KB 更新包内层长度字段非法（ciphertext）");
        }
        byte[] ct = new byte[ctLen];
        buf.get(ct);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != AES_KEY_LEN) {
            throw new IllegalStateException("KB 更新包内层 AES-256 密钥须为 32 字节");
        }
    }

    // ===== 密钥解析辅助（供装配与打包工具复用）=====

    /** 解析 Base64 或 hex 的字节串（自动识别；hex 须偶数位且全为十六进制字符）。 */
    public static byte[] parseKeyBytes(String text) {
        if (text == null) {
            return null;
        }
        String s = text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        if (s.isEmpty()) {
            return null;
        }
        if (s.matches("(?i)^[0-9a-f]+$") && s.length() % 2 == 0) {
            return hexToBytes(s);
        }
        try {
            return Base64.getDecoder().decode(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("密钥解析失败（既非 Base64 亦非 hex）", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}
