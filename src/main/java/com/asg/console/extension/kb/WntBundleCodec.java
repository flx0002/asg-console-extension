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
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;

import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.crypto.engines.SM4Engine;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * WNT（{@code .wnt}）容器编解码——与厂商 signtool（{@code ecc_sign.c}/{@code sm_sign.c}）字节级兼容。
 *
 * <p>文件 = <b>80 字节 signhdr（小端前置）</b> + payload：
 * <pre>
 *   [0..3]  magic  u32 = 0xBEACEBED
 *   [4]     ptype  包类型    [5] stype 签名方式(0=alpha,1=release)
 *   [6]     sver   签名版本(=1)  [7] enver 加密版本(1=ECC,2=GM)
 *   [8..11] dlen   原始 payload 长度 u32   [12..15] ts 时间戳 u32
 *   [16..79] sign  raw r‖s（64B）
 * </pre>
 *
 * <p>签名摘要 = {@code SHA256(hdr[0:16] ‖ 头后全部 payload 字节)}（ECC）或 {@code SM3(同)}（GM）。
 * payload 首块（≤1024B）用 XTEA-ECB(ECC, align8)/SM4-ECB(GM, align16)「伪加密」，密钥
 * = {@code SHA256/SM3(hdr[0:16])[0:16]}（明文头可推导 → 无机密性），故内层必须再叠加我方
 * AES-256-GCM（见 {@link WntBundleCipher}）。
 *
 * <p>本类只做容器与「伪加密」还原，不含验签算法（见 {@link WntVerifier}）与内层机密性。
 */
public final class WntBundleCodec {

    static final int MAGIC = 0xBEACEBED;
    static final int HDR_LEN = 80;
    static final int SIGN_SIZE = 64;
    static final byte SVER = 1;
    static final byte ENVER_ECC = 1;
    static final byte ENVER_GM = 2;
    private static final int BUFSZ = 1024;

    private WntBundleCodec() {
    }

    /** 解码结果：还原的内层（AES-GCM blob）字节 + 头部元信息。 */
    public static final class Result {
        public final byte[] inner;
        public final byte[] signature;
        public final String sigAlgorithm;
        public final int ptype;
        public final int stype;
        public final int enver;

        Result(byte[] inner, byte[] signature, String sigAlgorithm, int ptype, int stype, int enver) {
            this.inner = inner;
            this.signature = signature;
            this.sigAlgorithm = sigAlgorithm;
            this.ptype = ptype;
            this.stype = stype;
            this.enver = enver;
        }
    }

    /**
     * 验签并还原内层原始字节（即签名前喂给 signtool 的 {@code -i} 输入）。
     *
     * @param wnt     完整 .wnt 文件字节
     * @param eccPub  ECC 33B 压缩公钥（enver=1 必需）
     * @param sm2Pub  SM2 65B 非压缩公钥（enver=2 必需；缺省 null 即拒绝 GM 包，fail-closed）
     * @throws IllegalArgumentException 魔数/长度非法、验签失败或 GM 未启用
     */
    public static Result decode(byte[] wnt, byte[] eccPub, byte[] sm2Pub) {
        if (wnt == null || wnt.length < HDR_LEN) {
            throw new IllegalArgumentException("非 WNT 容器（长度不足头部）");
        }
        ByteBuffer hdr = ByteBuffer.wrap(wnt, 0, HDR_LEN).order(ByteOrder.LITTLE_ENDIAN);
        if (hdr.getInt(0) != MAGIC) {
            throw new IllegalArgumentException("非 WNT 容器（魔数不符）");
        }
        int ptype = wnt[4] & 0xFF;
        int stype = wnt[5] & 0xFF;
        int sver = wnt[6] & 0xFF;
        int enver = wnt[7] & 0xFF;
        long dlen = hdr.getInt(8) & 0xFFFFFFFFL;
        if (sver != SVER) {
            throw new IllegalArgumentException("不支持的 WNT 签名版本: " + sver);
        }
        int storedLen = wnt.length - HDR_LEN;
        if (dlen > storedLen) {
            // dlen 必须 ≤ 头后字节数（dlen>1024 时二者相等；否则首块含填充使 storedLen≥dlen）。防伪造超大长度 DoS。
            throw new IllegalArgumentException("WNT 长度字段非法（dlen=" + dlen + " > " + storedLen + "）");
        }
        int dl = (int) dlen;

        // 1) 校签（对 hdr[0:16] + 头后全部存储字节计算摘要）
        byte[] sign = Arrays.copyOfRange(wnt, 16, HDR_LEN);
        boolean ok;
        String alg;
        if (enver == ENVER_ECC) {
            byte[] hash = sha256(wnt);
            ok = WntVerifier.verifyEcc(eccPub, hash, sign);
            alg = "ecc-p256-sha256";
        } else if (enver == ENVER_GM) {
            if (sm2Pub == null) {
                throw new IllegalArgumentException("WNT 为 GM(enver=2) 包但未配置 SM2 验签公钥，拒绝");
            }
            ok = WntVerifier.verifySm2(sm2Pub, sm3Full(wnt), sign);
            alg = "sm2-sm3";
        } else {
            throw new IllegalArgumentException("未知的 WNT 加密版本 enver=" + enver);
        }
        if (!ok) {
            throw new IllegalArgumentException("WNT 验签失败，拒绝应用");
        }

        // 2) 还原首块伪加密 → 内层原始字节（截断到 dlen）；密钥 = SHA256/SM3(hdr[0:16])[0:16]（16B）
        byte[] key = Arrays.copyOf(
            (enver == ENVER_ECC) ? sha256Range(wnt, 0, 16) : WntVerifier.sm3(wnt, 0, 16), 16);
        int align = (enver == ENVER_ECC) ? 8 : 16;
        int firstLen = Math.min(BUFSZ, dl);
        int a = roundUp(firstLen, align);
        byte[] inner = recoverInner(wnt, HDR_LEN, storedLen, dl, a, firstLen, key, enver == ENVER_GM);

        return new Result(inner, sign, alg, ptype, stype, enver);
    }

    /**
     * 构建一个合法 .wnt（供单测与跨平台打包用；生产交付仍以本机 signtool.exe 为准）。
     *
     * @param inner  内层原始字节（我方 AES-GCM blob）
     * @param priv32 ECC 32B 私钥标量
     */
    public static byte[] encode(byte[] inner, byte[] priv32, int ptype, int stype) {
        int dlen = inner.length;
        byte[] hdr = new byte[HDR_LEN];
        ByteBuffer bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0, MAGIC);
        hdr[4] = (byte) ptype;
        hdr[5] = (byte) stype;
        hdr[6] = SVER;
        hdr[7] = ENVER_ECC;
        bb.putInt(8, dlen);
        bb.putInt(12, (int) (System.currentTimeMillis() / 1000L));
        // sign[16..79] 先置零

        byte[] key = sha256Range(hdr, 0, 16);
        byte[] stored = applyFirstBlock(inner, key);
        byte[] toSign = concat(hdr, stored); // hdr 完整 80 + 存储 payload；hash 只用 hdr[0:16]
        byte[] hash = sha256(toSign);
        byte[] sig = WntVerifier.signEcc(priv32, hash);
        System.arraycopy(sig, 0, hdr, 16, SIGN_SIZE);
        return concat(hdr, stored);
    }

    // ===== 首块「伪加密」编解码（XTEA/SM4 ECB）=====

    /** 还原内层：首块 a 字节 ECB 解密，取其前 firstLen 字节 + 存储区第 a 字节起剩余（拼成 dlen 字节）。 */
    private static byte[] recoverInner(byte[] wnt, int payloadOff, int storedLen, int dlen,
        int a, int firstLen, byte[] key, boolean gm) {
        if (storedLen < a) {
            throw new IllegalArgumentException("WNT 存储区长度不足以解密首块");
        }
        byte[] decFirst = new byte[a];
        System.arraycopy(wnt, payloadOff, decFirst, 0, a);
        decryptFirstBlock(decFirst, a, key, gm);
        ByteArrayOutputStream out = new ByteArrayOutputStream(dlen);
        out.write(decFirst, 0, firstLen);
        if (dlen > firstLen) {
            out.write(wnt, payloadOff + a, dlen - firstLen);
        }
        return out.toByteArray();
    }

    /** 构建时对 inner 首块加密（ECC/XTEA），返回存储 payload（首块加密 + 其余原样）。 */
    private static byte[] applyFirstBlock(byte[] inner, byte[] key) {
        int dlen = inner.length;
        int firstLen = Math.min(BUFSZ, dlen);
        int a = roundUp(firstLen, 8);
        byte[] stored = new byte[a + Math.max(0, dlen - firstLen)];
        // 首块（含零填充）
        byte[] firstBlock = new byte[a];
        System.arraycopy(inner, 0, firstBlock, 0, firstLen);
        encryptFirstBlock(firstBlock, a, key, false);
        System.arraycopy(firstBlock, 0, stored, 0, a);
        if (dlen > firstLen) {
            System.arraycopy(inner, firstLen, stored, a, dlen - firstLen);
        }
        return stored;
    }

    /** 就地解密首块（每 8/16 字节一块 ECB）。 */
    private static void decryptFirstBlock(byte[] buf, int len, byte[] key, boolean gm) {
        if (gm) {
            SM4Engine engine = new SM4Engine();
            engine.init(false, new KeyParameter(key));
            for (int i = 0; i + 16 <= len; i += 16) {
                engine.processBlock(buf, i, buf, i);
            }
        } else {
            int[] k = xteaKey(key);
            for (int i = 0; i + 8 <= len; i += 8) {
                xteaCryptEcb(k, false, buf, i, buf, i);
            }
        }
    }

    /** 就地加密首块（每 8 字节一块 ECB，ECC/XTEA）。 */
    private static void encryptFirstBlock(byte[] buf, int len, byte[] key, boolean gm) {
        if (gm) {
            SM4Engine engine = new SM4Engine();
            engine.init(true, new KeyParameter(key));
            for (int i = 0; i + 16 <= len; i += 16) {
                engine.processBlock(buf, i, buf, i);
            }
        } else {
            int[] k = xteaKey(key);
            for (int i = 0; i + 8 <= len; i += 8) {
                xteaCryptEcb(k, true, buf, i, buf, i);
            }
        }
    }

    // ===== XTEA（逐行复刻 signtool xtea.c：BE、32 轮、delta=0x9E3779B9、k[(sum>>11)&3]）=====

    private static int[] xteaKey(byte[] key) {
        int[] k = new int[4];
        for (int i = 0; i < 4; i++) {
            k[i] = be32(key, i << 2);
        }
        return k;
    }

    private static void xteaCryptEcb(int[] k, boolean enc, byte[] in, int inOff, byte[] out, int outOff) {
        int v0 = be32(in, inOff);
        int v1 = be32(in, inOff + 4);
        int delta = 0x9E3779B9;
        if (enc) {
            int sum = 0;
            for (int i = 0; i < 32; i++) {
                v0 += (((v1 << 4) ^ (v1 >>> 5)) + v1) ^ (sum + k[sum & 3]);
                sum += delta;
                v1 += (((v0 << 4) ^ (v0 >>> 5)) + v0) ^ (sum + k[(sum >>> 11) & 3]);
            }
        } else {
            int sum = delta * 32;
            for (int i = 0; i < 32; i++) {
                v1 -= (((v0 << 4) ^ (v0 >>> 5)) + v0) ^ (sum + k[(sum >>> 11) & 3]);
                sum -= delta;
                v0 -= (((v1 << 4) ^ (v1 >>> 5)) + v1) ^ (sum + k[sum & 3]);
            }
        }
        putBe32(out, outOff, v0);
        putBe32(out, outOff + 4, v1);
    }

    // ===== 摘要与工具 =====

    /** SHA-256(wnt[0:16] ‖ wnt[80:end]) —— 对非连续区段计算签名摘要。 */
    private static byte[] sha256(byte[] wnt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(wnt, 0, 16);
            md.update(wnt, HDR_LEN, wnt.length - HDR_LEN);
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] sha256Range(byte[] data, int off, int len) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(data, off, off + len));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** SM3(wnt[0:16] ‖ wnt[80:end]) —— GM 签名摘要（与非连续区段一致）。 */
    private static byte[] sm3Full(byte[] wnt) {
        SM3Digest d = new SM3Digest();
        d.update(wnt, 0, 16);
        d.update(wnt, HDR_LEN, wnt.length - HDR_LEN);
        byte[] out = new byte[d.getDigestSize()];
        d.doFinal(out, 0);
        return out;
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8)
            | (b[off + 3] & 0xFF);
    }

    private static void putBe32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static int roundUp(int x, int align) {
        return (x + align - 1) & ~(align - 1);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
