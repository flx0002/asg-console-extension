/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

/**
 * KB / license 签名验签抽象。厂商私钥签名，ASG 侧用内置公钥验签。
 *
 * <p>算法可插拔：默认 {@link RsaKbSignatureVerifier}（Java 8 原生 SHA256withRSA）。
 * 若厂商改用 Ed25519 / 国密 SM2，新增对应实现（引入 BouncyCastle）并按配置切换，
 * 上层逻辑不变。
 */
public interface KbSignatureVerifier {

    /** 算法标识，落库到 AiKbVersion.sigAlgorithm，如 rsa-sha256 / ed25519 / sm2。 */
    String algorithm();

    /**
     * 验签。
     *
     * @param data      被签名的原始字节（明文 bundle JSON / license 规范串的 UTF-8 字节）
     * @param signature 签名值（原始字节，非 Base64）
     * @return 验签是否通过
     */
    boolean verify(byte[] data, byte[] signature);
}
