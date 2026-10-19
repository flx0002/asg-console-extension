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
import java.util.Arrays;
import java.util.Base64;

/**
 * 内容合规词库离线/在线测试包（{@code .wnt}）生成器（BE-011 测试工具，仅 test 源集，不进产物）。
 *
 * <p>用<b>与 console 完全相同的编解码类</b>（{@link WntBundleCipher#seal} + {@link WntBundleCodec#encode}）
 * 生成一个合法 {@code .wnt}，保证字节级可被 {@link WntBundleCipher#openWnt} 还原：
 * <ol>
 *   <li>读入附录 A 明文 bundle JSON（{@code appA_rules.json}）；</li>
 *   <li>内层 AES-256-GCM 封装（{@code ASG_KB_BUNDLE_KEY}，缺省沿用现网测试密钥 0x11×32）；</li>
 *   <li>外层用自签 ECC 私钥标量签名（{@link WntVerifier#signEcc} 经 codec 内部调用）；</li>
 *   <li>写 {@code .wnt} 与 {@code .wnt.b64}，并打印配套 33B 压缩公钥（hex），供验证时注入
 *       {@code ASG_KB_WNT_PUBKEY}。</li>
 * </ol>
 *
 * <p>与厂商交付流程一致之处：真实生产的 {@code .wnt} 由打包机 signtool + 厂商私钥产出；本工具仅在
 * 无 signtool/无私钥的测试环境复现同一容器格式，用途限于 e2e 验证，不代表生产签名密钥。
 */
public final class ComplianceKbSeedTool {

    private ComplianceKbSeedTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ComplianceKbSeedTool <appAJsonPath> <outWntPath> [bundleKeyB64] [privHex32]");
            System.exit(2);
        }
        String jsonPath = args[0];
        String outPath = args[1];
        String bundleKeyB64 = args.length > 2 && !args[2].isEmpty()
            ? args[2] : Base64.getEncoder().encodeToString(repeat((byte) 0x11, 32));
        // 固定 ECC 私钥标量（测试用；32B，1..n-1）。缺省 0x01×32。
        byte[] priv32 = args.length > 3 && !args[3].isEmpty()
            ? WntBundleCipher.parseKeyBytes(args[3]) : repeat((byte) 0x01, 32);
        if (priv32 == null || priv32.length != 32) {
            System.err.println("priv must be 32 bytes");
            System.exit(2);
        }
        byte[] bundleKey = WntBundleCipher.parseKeyBytes(bundleKeyB64);
        if (bundleKey == null || bundleKey.length != 32) {
            System.err.println("bundle key must decode to 32 bytes");
            System.exit(2);
        }

        String bundleJson = new String(Files.readAllBytes(Paths.get(jsonPath)), StandardCharsets.UTF_8);
        byte[] inner = WntBundleCipher.seal(bundleJson, bundleKey);
        byte[] wnt = WntBundleCodec.encode(inner, priv32, 3, 1); // ptype=engine(3), stype=release(1)
        byte[] pub33 = WntVerifier.eccPublicCompressed(priv32);

        Files.write(Paths.get(outPath), wnt);
        String b64 = Base64.getEncoder().encodeToString(wnt);
        Files.write(Paths.get(outPath + ".b64"), b64.getBytes(StandardCharsets.UTF_8));

        // 自校验：用刚生成的公钥 + bundleKey 还原，确保产物能被 console 同构解码
        WntBundleCipher cipher = new WntBundleCipher(pub33, null, bundleKey);
        WntBundleCipher.OpenBundle opened = cipher.openWnt(b64);
        if (!bundleJson.equals(opened.bundleJson)) {
            System.err.println("SELF-CHECK FAILED: round-trip bundleJson mismatch");
            System.exit(1);
        }

        System.out.println("WNT_FILE=" + outPath + " (" + wnt.length + " bytes)");
        System.out.println("WNT_B64_FILE=" + outPath + ".b64");
        System.out.println("SIG_ALG=" + opened.sigAlgorithm);
        System.out.println("BUNDLE_KEY_B64=" + bundleKeyB64);
        System.out.println("ASG_KB_WNT_PUBKEY=" + hex(pub33));
        System.out.println("ASG_KB_WNT_PUBKEY_B64=" + Base64.getEncoder().encodeToString(pub33));
        System.out.println("SEED_OK");
    }

    private static byte[] repeat(byte b, int n) {
        byte[] a = new byte[n];
        Arrays.fill(a, b);
        return a;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte x : bytes) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
