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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * KB 落盘加解密：AES-256-GCM，复用 IR-028 ConfigBackupService 的信封格式。
 *
 * <p>信封：Base64( "ASGKB1"(6B magic) + 12B IV + ciphertext+GCM tag )。
 * 密钥材料来自环境变量 {@code ASG_KB_KEY}，缺省用内置默认 key（与备份一致的产品决策：
 * 系统内置密钥，无用户口令）。GCM 自带完整性校验，密文被篡改则 doFinal 抛异常。
 *
 * <p>非 Spring bean，便于单元测试直接 new；P1 由服务层持有单例。
 */
public class KbCrypto {

    static final String MAGIC = "ASGKB1";
    static final String KEY_ENV = "ASG_KB_KEY";
    static final String DEFAULT_KEY = "asg-ai-kb-default-key-2026";

    private final String keyMaterial;

    /** 生产构造：从环境变量取密钥材料，缺省用内置默认。 */
    public KbCrypto() {
        String m = System.getenv(KEY_ENV);
        this.keyMaterial = (m == null || m.isEmpty()) ? DEFAULT_KEY : m;
    }

    /** 测试/自定义构造。 */
    public KbCrypto(String keyMaterial) {
        this.keyMaterial = (keyMaterial == null || keyMaterial.isEmpty()) ? DEFAULT_KEY : keyMaterial;
    }

    private SecretKey key() throws Exception {
        byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, "AES");
    }

    /** 加密明文，返回 Base64 信封。 */
    public String encrypt(String plain) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC.getBytes(StandardCharsets.UTF_8));
        out.write(iv);
        out.write(cipherText);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** 解密 Base64 信封，返回明文；格式或完整性不符抛异常。 */
    public String decrypt(String content) throws Exception {
        byte[] all = Base64.getMimeDecoder().decode(content.replaceAll("\\s", ""));
        byte[] magic = MAGIC.getBytes(StandardCharsets.UTF_8);
        if (all.length <= magic.length + 12 || !Arrays.equals(Arrays.copyOfRange(all, 0, magic.length), magic)) {
            throw new IllegalArgumentException("Invalid KB bundle format");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, magic.length, 12));
        byte[] plain = cipher.doFinal(Arrays.copyOfRange(all, magic.length + 12, all.length));
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** 明文的 SHA-256（hex），用于完整性指纹与脱敏展示。 */
    public static String sha256Hex(String plain) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(plain.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
