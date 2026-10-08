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
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

/**
 * KB 密码学组件装配。把纯 Java 组件暴露为 Spring bean。
 *
 * <p>授权（license）走 {@code .dat} RSA/AES 混合信封（对齐工业防火墙 TEG），需要两把密钥：
 * <ul>
 *   <li>厂商验签公钥：env {@code ASG_KB_VERIFY_PUBKEY} 或配置 {@code asg.kb.verify.pubkey}
 *       （Base64 X.509），用于验证授权签名；</li>
 *   <li>产品（console）解密私钥：env {@code ASG_KB_LICENSE_PRIVKEY} 或配置
 *       {@code asg.kb.license.privkey}（Base64 PKCS8），用于拆开信封里的 AES 密钥。</li>
 * </ul>
 * 任一缺失则授权校验恒失败（安全默认：无密钥不允许任何 KB 更新）。KB 更新包（{@code .wnt}）
 * 的真实性由厂商 signtool 头校签（见 {@link #wntBundleCipher()}，独立于授权 RSA 验签链路），
 * 机密性由我方内层 AES-256-GCM 保证。
 */
@Slf4j
@Configuration
public class KbBeansConfig {

    /** 产品（console）RSA 私钥环境变量名（Base64 PKCS8），用于拆 .dat 授权信封。 */
    public static final String LICENSE_PRIVKEY_ENV = "ASG_KB_LICENSE_PRIVKEY";

    /** WNT 外层校签的 ECC 压缩公钥（33B，Base64 或 hex），来自厂商 signtool 密钥。 */
    public static final String WNT_PUBKEY_ENV = "ASG_KB_WNT_PUBKEY";

    /** 可选：WNT GM(enver=2) 校签的 SM2 非压缩公钥（65B，Base64 或 hex）；缺省 GM 包 fail-closed 拒绝。 */
    public static final String WNT_SM2_PUBKEY_ENV = "ASG_KB_WNT_SM2_PUBKEY";

    /** WNT 内层 AES-256-GCM 对称密钥（32B，Base64），与打包机共享（我方自有密钥，经安全通道下发）。 */
    public static final String BUNDLE_KEY_ENV = "ASG_KB_BUNDLE_KEY";

    @Value("${asg.kb.verify.pubkey:}")
    private String verifyPubkeyProp;

    @Value("${asg.kb.license.privkey:}")
    private String licensePrivkeyProp;

    @Bean
    public KbCrypto kbCrypto() {
        return new KbCrypto();
    }

    /**
     * 验签器：优先 env ASG_KB_VERIFY_PUBKEY，其次配置 asg.kb.verify.pubkey。
     * 两者皆空则返回 publicKey=null 的实例（{@code verify()} 恒 false）——安全默认。
     */
    @Bean
    public KbSignatureVerifier kbSignatureVerifier() {
        RsaKbSignatureVerifier v = RsaKbSignatureVerifier.fromEnv();
        if (v == null && verifyPubkeyProp != null && !verifyPubkeyProp.trim().isEmpty()) {
            try {
                v = new RsaKbSignatureVerifier(RsaKbSignatureVerifier.loadPublicKey(verifyPubkeyProp));
            } catch (Exception e) {
                log.error("Failed to load KB verify pubkey from property: {}", e.getMessage());
            }
        }
        if (v == null) {
            log.warn("KB verify public key not configured; KB/license updates will be rejected until it is set");
            return new RsaKbSignatureVerifier(null);
        }
        return v;
    }

    /**
     * 授权校验器：{@code .dat} RSA/AES 信封。需要 console 解密私钥 + 厂商验签公钥；
     * 私钥缺失则传 null，{@link DatLicenseVerifier} 会拒绝所有授权（安全默认）。
     */
    @Bean
    public LicenseVerifier licenseVerifier(KbSignatureVerifier kbSignatureVerifier) {
        PrivateKey consolePriv = loadConsolePrivKey();
        if (consolePriv == null) {
            log.warn("KB license decrypt private key not configured; license import will be rejected "
                + "until {} (or asg.kb.license.privkey) is set", LICENSE_PRIVKEY_ENV);
        }
        return new DatLicenseVerifier(consolePriv, kbSignatureVerifier);
    }

    /**
     * 在线更新客户端：纯 HTTP 分发器，不持有地址。基址由服务层按「页面持久化值优先、回退 env」
     * 逐次传入（见 {@code KbSettingService}），以支持系统配置页改地址后即时生效、无需重启。
     */
    @Bean
    public KbOnlineClient kbOnlineClient() {
        return new KbOnlineClient();
    }

    /**
     * KB 更新包（{@code .wnt}）解密器：外层 signtool 头校签（厂商 ECC/SM2 公钥）+ 内层我方
     * AES-256-GCM（{@code ASG_KB_BUNDLE_KEY}）保机密。公钥/对称密钥任一缺失则构造持有 null 的
     * 实例，{@link WntBundleCipher#openWnt} 恒拒（安全默认：无密钥不允许任何 KB 更新）。
     */
    @Bean
    public WntBundleCipher wntBundleCipher() {
        byte[] eccPub = readKeyEnv(WNT_PUBKEY_ENV);
        byte[] sm2Pub = readKeyEnv(WNT_SM2_PUBKEY_ENV);
        byte[] bundleKey = readKeyEnv(BUNDLE_KEY_ENV);
        if (eccPub != null && eccPub.length != 33) {
            log.error("{} must decode to a 33-byte SEC1 compressed EC point (got {} bytes); ignoring", WNT_PUBKEY_ENV,
                eccPub.length);
            eccPub = null;
        }
        if (bundleKey != null && bundleKey.length != 32) {
            log.error("{} must decode to a 32-byte AES-256 key (got {} bytes); ignoring", BUNDLE_KEY_ENV,
                bundleKey.length);
            bundleKey = null;
        }
        if (eccPub == null || bundleKey == null) {
            log.warn("KB WNT verify pubkey / bundle key not configured; KB updates will be rejected until "
                + "{} and {} are set", WNT_PUBKEY_ENV, BUNDLE_KEY_ENV);
        }
        return new WntBundleCipher(eccPub, sm2Pub, bundleKey);
    }

    /** 读取环境变量并按 Base64/hex 解析为字节；缺失/空/解析失败返回 null。 */
    private byte[] readKeyEnv(String env) {
        String v = System.getenv(env);
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        try {
            return WntBundleCipher.parseKeyBytes(v);
        } catch (Exception e) {
            log.error("Failed to parse {} : {}", env, e.getMessage());
            return null;
        }
    }

    /** 加载 console RSA 私钥：优先 env，其次配置项；解析 Base64 PKCS8。缺失/失败返回 null。 */
    private PrivateKey loadConsolePrivKey() {
        String b64 = System.getenv(LICENSE_PRIVKEY_ENV);
        if (b64 == null || b64.trim().isEmpty()) {
            b64 = licensePrivkeyProp;
        }
        if (b64 == null || b64.trim().isEmpty()) {
            return null;
        }
        try {
            String clean = b64.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(clean);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            log.error("Failed to load KB license decrypt private key: {}", e.getMessage());
            return null;
        }
    }
}
