/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

import org.bouncycastle.asn1.gm.GMNamedCurves;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.math.ec.ECPoint;

/**
 * WNT（.wnt）容器签名验签原语，与厂商 signtool（easy-ecc / mbedtls SM2）字节级兼容：
 *
 * <ul>
 *   <li><b>ECC</b>：ECDSA over NIST P-256（secp256r1），对 32 字节 SHA-256 摘要直接签名，
 *       签名为 raw {@code r‖s}（各 32B，非 DER）；公钥为 33 字节 SEC1 压缩点。</li>
 *   <li><b>GM</b>：SM2（sm2p256v1）raw 方案，对 32 字节 SM3 摘要直接验签（不含 ZA/用户标识，
 *       对齐 {@code smx_verify→sm2_verify}）；公钥为 65 字节非压缩点。</li>
 * </ul>
 *
 * <p>依赖已在 classpath 的 BouncyCastle（1.72）。本期生产交付仅用 ECC；GM 路径需注入 SM2 公钥
 * 方可启用，未注入即由上层 fail-closed 拒绝。
 */
public final class WntVerifier {

    private static final X9ECParameters P256 = ECNamedCurveTable.getByName("secp256r1");
    private static final ECDomainParameters P256_DOMAIN =
        new ECDomainParameters(P256.getCurve(), P256.getG(), P256.getN(), P256.getH());
    private static final X9ECParameters SM2 = GMNamedCurves.getByName("sm2p256v1");
    private static final ECDomainParameters SM2_DOMAIN =
        new ECDomainParameters(SM2.getCurve(), SM2.getG(), SM2.getN(), SM2.getH());

    private WntVerifier() {
    }

    /** ECDSA-P256 验签：pub33 为压缩点，hash32 为 SHA-256 摘要，sig64 为 raw r‖s。 */
    public static boolean verifyEcc(byte[] pub33, byte[] hash32, byte[] sig64) {
        if (pub33 == null || pub33.length != 33 || hash32 == null || hash32.length != 32
            || sig64 == null || sig64.length != 64) {
            return false;
        }
        try {
            ECPoint q = P256.getCurve().decodePoint(pub33);
            ECPublicKeyParameters pub = new ECPublicKeyParameters(q, P256_DOMAIN);
            ECDSASigner signer = new ECDSASigner();
            signer.init(false, pub);
            BigInteger r = new BigInteger(1, Arrays.copyOfRange(sig64, 0, 32));
            BigInteger s = new BigInteger(1, Arrays.copyOfRange(sig64, 32, 64));
            return signer.verifySignature(hash32, r, s);
        } catch (Exception e) {
            return false;
        }
    }

    /** SM2 验签（raw，无 ZA）：pub65 为非压缩点，hash32 为 SM3 摘要，sig64 为 raw r‖s。 */
    public static boolean verifySm2(byte[] pub65, byte[] hash32, byte[] sig64) {
        if (pub65 == null || pub65.length != 65 || hash32 == null || hash32.length != 32
            || sig64 == null || sig64.length != 64) {
            return false;
        }
        try {
            ECPoint q = SM2.getCurve().decodePoint(pub65);
            BigInteger n = SM2_DOMAIN.getN();
            BigInteger r = new BigInteger(1, Arrays.copyOfRange(sig64, 0, 32));
            BigInteger s = new BigInteger(1, Arrays.copyOfRange(sig64, 32, 64));
            if (r.signum() <= 0 || r.compareTo(n) >= 0 || s.signum() <= 0 || s.compareTo(n) >= 0) {
                return false;
            }
            BigInteger e = new BigInteger(1, hash32);
            BigInteger t = r.add(s).mod(n);
            if (t.signum() == 0) {
                return false;
            }
            ECPoint x1y1 = SM2_DOMAIN.getG().multiply(s).add(q.multiply(t)).normalize();
            BigInteger rr = e.add(x1y1.getAffineXCoord().toBigInteger()).mod(n);
            return rr.equals(r);
        } catch (Exception ex) {
            return false;
        }
    }

    /** ECDSA-P256 签名（打包工具/单测用）：priv32 标量，hash32 摘要 → raw r‖s(64B)。 */
    public static byte[] signEcc(byte[] priv32, byte[] hash32) {
        BigInteger d = new BigInteger(1, priv32);
        ECPrivateKeyParameters priv = new ECPrivateKeyParameters(d, P256_DOMAIN);
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, priv);
        BigInteger[] rs = signer.generateSignature(hash32);
        byte[] sig = new byte[64];
        copyFixed(rs[0], sig, 0, 32);
        copyFixed(rs[1], sig, 32, 32);
        return sig;
    }

    /** 由 32 字节私钥标量派生 33 字节压缩公钥（单测便利）。 */
    public static byte[] eccPublicCompressed(byte[] priv32) {
        ECPoint q = P256.getG().multiply(new BigInteger(1, priv32)).normalize();
        return q.getEncoded(true);
    }

    /** 生成一个合法的 P-256 私钥标量（32B，1..n-1），单测便利。 */
    public static byte[] generateEccPriv() {
        BigInteger n = P256_DOMAIN.getN();
        BigInteger d;
        SecureRandom rnd = new SecureRandom();
        do {
            d = new BigInteger(n.bitLength(), rnd);
        } while (d.signum() <= 0 || d.compareTo(n) >= 0);
        return toFixed32(d);
    }

    /** SM3 摘要（32B）。 */
    public static byte[] sm3(byte[] data, int off, int len) {
        SM3Digest digest = new SM3Digest();
        digest.update(data, off, len);
        byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return out;
    }

    private static void copyFixed(BigInteger v, byte[] dst, int off, int len) {
        byte[] b = toFixed32(v);
        System.arraycopy(b, 0, dst, off, len);
    }

    /** 大端定长 32 字节（左补零；超 32 取低 32 字节）。 */
    private static byte[] toFixed32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length >= 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }
}
