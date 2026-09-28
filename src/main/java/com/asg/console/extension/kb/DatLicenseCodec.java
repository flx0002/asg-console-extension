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
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * KB 授权文件（{@code .dat}）的 RSA/AES 混合信封编解码（纯密码学，不含门控判断）。
 *
 * <p>对齐工业防火墙（TEG）授权的 {@code encryptType=3(RSA/AES)} 语义：随机 AES-256 密钥
 * 加密载荷，AES 密钥用「产品（console）RSA 公钥」OAEP 包裹；载荷规范串再用「厂商 RSA 私钥」
 * SHA256 签名。console 侧用产品私钥拆 AES 密钥、用厂商公钥验签。
 *
 * <p>{@code .dat} 二进制布局（大端）：
 * <pre>
 *   magic      7B   "ASGKBL1"
 *   ver        1B   0x01
 *   esnLen     2B   绑定 ESN 的 UTF-8 字节长度
 *   esn        n    绑定 ESN 明文（供解密前快速比对；权威值仍以签名载荷内 esn 为准）
 *   iv         12B  AES-GCM IV
 *   wrappedLen 2B   RSA-OAEP 包裹后的 AES 密钥长度（RSA-2048 为 256）
 *   wrappedKey m    RSA-2048-OAEP(SHA-256/MGF1-SHA-256) 加密的 32B AES-256 密钥（用 console 公钥）
 *   ctLen      4B   AES-GCM 密文长度（含 16B tag）
 *   ct         k    AES-256-GCM(载荷 JSON UTF-8)
 *   sigLen     2B   RSA 签名长度（RSA-2048 为 256）
 *   sig        p    RSA-SHA256(PKCS1v15) 对 {@link #canonicalString} 的签名（用厂商私钥）
 * </pre>
 *
 * <p>传输/落库时整体再做 Base64（{@link #encodeToBase64} / {@link #decodePayload}）。
 */
public final class DatLicenseCodec {

    /** 魔数（区别于 KB bundle 的 {@code ASGKB1}）。 */
    static final byte[] MAGIC = "ASGKBL1".getBytes(StandardCharsets.UTF_8);
    static final byte VERSION = 0x01;
    private static final int IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_KEY_LEN = 32; // AES-256
    private static final String RSA_TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String SIG_ALG = "SHA256withRSA";

    /**
     * canonicalString 参与签名的字段顺序（Python 参考工具须完全一致）。
     * 15 字段固定序，不再含顶层 createTime/endTime——到期唯一以功能位自身时间为准。
     */
    static final String[] CANONICAL_FIELDS = {
        "version", "licenseId", "productName", "productVersion", "companyName", "contractNo",
        "esn", "checkSn", "licenseType", "licenseModel", "licenseName", "licenseValue",
        "iegCustomerId", "iegAuthorizedCount", "functions",
    };

    private DatLicenseCodec() {
    }

    /** 解码结果：明文载荷 JSON + 厂商签名（供上层验签）。 */
    public static final class Decoded {
        public final String payloadJson;
        public final byte[] signature;

        Decoded(String payloadJson, byte[] signature) {
            this.payloadJson = payloadJson;
            this.signature = signature;
        }
    }

    /**
     * 编码：把明文载荷 JSON 打包成 Base64 的 {@code .dat}。厂商授权工具 / mock / 测试用。
     *
     * @param payloadJson    载荷 JSON 字符串（字段见 {@link #CANONICAL_FIELDS} 等）
     * @param esn            绑定 ESN（写入信封头部）
     * @param consolePubKey  产品（console）RSA 公钥，用于包裹 AES 密钥
     * @param vendorPrivKey  厂商 RSA 私钥，用于对 canonicalString 签名
     */
    public static String encodeToBase64(String payloadJson, String esn, PublicKey consolePubKey,
        PrivateKey vendorPrivKey) throws Exception {
        JSONObject payload = JSONObject.parseObject(payloadJson);
        byte[] aesKey = new byte[AES_KEY_LEN];
        new SecureRandom().nextBytes(aesKey);
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);

        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = aes.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8));

        Cipher oaep = Cipher.getInstance(RSA_TRANSFORM);
        oaep.init(Cipher.ENCRYPT_MODE, consolePubKey, oaepSpec());
        byte[] wrapped = oaep.doFinal(aesKey);

        byte[] canonical = canonicalString(payload).getBytes(StandardCharsets.UTF_8);
        java.security.Signature sig = java.security.Signature.getInstance(SIG_ALG);
        sig.initSign(vendorPrivKey);
        sig.update(canonical);
        byte[] sigBytes = sig.sign();

        byte[] esnBytes = (esn == null ? "" : esn).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC);
        out.write(VERSION);
        out.write(u16(esnBytes.length));
        out.write(esnBytes);
        out.write(iv);
        out.write(u16(wrapped.length));
        out.write(wrapped);
        out.write(u32(ct.length));
        out.write(ct);
        out.write(u16(sigBytes.length));
        out.write(sigBytes);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /**
     * 解码：Base64 的 {@code .dat} → 明文载荷 JSON + 签名。仅做拆信封与 AES-GCM 解密，
     * 不做验签/门控（由 {@link DatLicenseVerifier} 负责）。
     *
     * @param base64Dat      Base64 的 .dat 内容
     * @param consolePrivKey 产品（console）RSA 私钥，用于拆开 AES 密钥
     * @throws Exception 格式非法 / 解密失败 / 密文被篡改（GCM 校验不过）
     */
    public static Decoded decodePayload(String base64Dat, PrivateKey consolePrivKey) throws Exception {
        byte[] all = Base64.getMimeDecoder().decode(base64Dat.replaceAll("\\s", ""));
        ByteBuffer buf = ByteBuffer.wrap(all);
        byte[] magic = new byte[MAGIC.length];
        buf.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IllegalArgumentException("非 ASG KB 授权文件（魔数不符）");
        }
        byte ver = buf.get();
        if (ver != VERSION) {
            throw new IllegalArgumentException("不支持的授权文件版本: " + ver);
        }
        int esnLen = buf.getShort() & 0xFFFF;
        byte[] esnBytes = new byte[esnLen];
        buf.get(esnBytes);
        byte[] iv = new byte[IV_LEN];
        buf.get(iv);
        int wrappedLen = buf.getShort() & 0xFFFF;
        byte[] wrapped = new byte[wrappedLen];
        buf.get(wrapped);
        int ctLen = buf.getInt();
        byte[] ct = new byte[ctLen];
        buf.get(ct);
        int sigLen = buf.getShort() & 0xFFFF;
        byte[] sigBytes = new byte[sigLen];
        buf.get(sigBytes);

        Cipher oaep = Cipher.getInstance(RSA_TRANSFORM);
        oaep.init(Cipher.DECRYPT_MODE, consolePrivKey, oaepSpec());
        byte[] aesKey = oaep.doFinal(wrapped);

        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plain = aes.doFinal(ct);
        return new Decoded(new String(plain, StandardCharsets.UTF_8), sigBytes);
    }

    /**
     * 规范串：固定字段顺序 {@code key=value} 以 {@code \n} 拼接（无尾换行，不含签名）。
     * 签名与验签共用，保证确定性；{@code functions} 每项渲染为 {@code id|createTime|endTime}
     * （id 为功能位稳定标识，时间取载荷内原始字符串，缺省渲染空串），按整条记录升序后以逗号连接。
     */
    public static String canonicalString(JSONObject payload) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CANONICAL_FIELDS.length; i++) {
            String k = CANONICAL_FIELDS[i];
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(k).append('=');
            if ("functions".equals(k)) {
                // 防御：canonicalString 在验签之前执行，载荷由持有 console 公钥者构造，
                // functions 非数组（字符串/数字/对象）时不得抛未捕获异常，一律按「无功能位」渲染空串。
                Object fObj = payload.get("functions");
                JSONArray arr = (fObj instanceof JSONArray) ? (JSONArray) fObj : null;
                List<String> recs = new ArrayList<>();
                if (arr != null) {
                    for (int j = 0; j < arr.size(); j++) {
                        Object el = arr.get(j);
                        String id;
                        String ct;
                        String et;
                        if (el instanceof JSONObject) {
                            JSONObject o = (JSONObject) el;
                            // 优先取 id；兼容旧载荷以 name 承载标识（缺 id 时回退 name）
                            String oid = o.getString("id");
                            id = nz(oid != null && !oid.trim().isEmpty() ? oid : o.getString("name"));
                            ct = nz(o.getString("createTime"));
                            et = nz(o.getString("endTime"));
                        } else {
                            // 兼容旧写法：元素为纯功能名字符串，视作 id，时间留空
                            id = el == null ? "" : String.valueOf(el);
                            ct = "";
                            et = "";
                        }
                        recs.add(id + "|" + ct + "|" + et);
                    }
                }
                java.util.Collections.sort(recs);
                sb.append(String.join(",", recs));
            } else {
                Object v = payload.get(k);
                sb.append(v == null ? "" : String.valueOf(v));
            }
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** OAEP 参数：显式 SHA-256 + MGF1(SHA-256)，与 Python cryptography 默认一致。 */
    private static OAEPParameterSpec oaepSpec() {
        return new OAEPParameterSpec("SHA-256", "MGF1",
            java.security.spec.MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }

    private static byte[] u16(int v) {
        return new byte[] {(byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF)};
    }

    private static byte[] u32(int v) {
        return ByteBuffer.allocate(4).putInt(v).array();
    }
}
