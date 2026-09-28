/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import lombok.extern.slf4j.Slf4j;

/**
 * RSA-SHA256 验签实现（Java 8 原生，无额外依赖），作为 P0 桩。
 *
 * <p>公钥来源优先级：构造注入 &gt; 环境变量 {@code ASG_KB_VERIFY_PUBKEY}（Base64 X.509）。
 * 厂商改用 Ed25519/SM2 时替换本实现即可（见 {@link KbSignatureVerifier}）。
 */
@Slf4j
public class RsaKbSignatureVerifier implements KbSignatureVerifier {

    public static final String ALG = "rsa-sha256";
    public static final String PUBKEY_ENV = "ASG_KB_VERIFY_PUBKEY";

    private final PublicKey publicKey;

    public RsaKbSignatureVerifier(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    /** 从环境变量加载公钥构造；未配置或解析失败返回 null（调用方据此判定"未配置验签公钥"）。 */
    public static RsaKbSignatureVerifier fromEnv() {
        String b64 = System.getenv(PUBKEY_ENV);
        if (b64 == null || b64.trim().isEmpty()) {
            return null;
        }
        try {
            return new RsaKbSignatureVerifier(loadPublicKey(b64));
        } catch (Exception e) {
            log.error("Failed to load KB verify public key from env: {}", e.getMessage());
            return null;
        }
    }

    /** 解析 Base64（可含 PEM 头尾/换行）的 X.509 公钥。 */
    public static PublicKey loadPublicKey(String b64) throws Exception {
        String clean = b64.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(clean);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    @Override
    public String algorithm() {
        return ALG;
    }

    @Override
    public boolean verify(byte[] data, byte[] signature) {
        if (publicKey == null || data == null || signature == null) {
            return false;
        }
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            log.warn("RSA verify failed: {}", e.getMessage());
            return false;
        }
    }
}
