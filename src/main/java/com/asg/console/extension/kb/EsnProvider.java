/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;

import lombok.extern.slf4j.Slf4j;

/**
 * 设备 ESN（设备标识 / 序列号）来源，对齐工业防火墙（TEG）授权的「SN 校验」语义。
 *
 * <p>取值优先级（对应用户诉求「有 SN 用 SN、没 SN 用 MAC」）：
 * <ol>
 *   <li>环境变量 {@code ASG_KB_ESN}：注入的稳定 ASG 设备 SN（原样返回）。多副本部署必须配此值，
 *       否则各副本 MAC 不同会导致绑定漂移。</li>
 *   <li>未注入 SN 时：取本机第一个非回线、已启用网卡的 <b>MAC 地址</b>（大写冒号格式 {@code AA:BB:CC:DD:EE:FF}），
 *       对齐堡垒机/态势感知等「无 SN 产品线用 MAC」的口径。</li>
 *   <li>MAC 也取不到时：末级回退 machine-id → HOSTNAME → 主机名的 SHA-256(hex) 前 32 位，并告警。</li>
 * </ol>
 *
 * <p>授权文件内的 {@code esn} 字段（当 {@code checkSn=true}）须与 {@link #current()} 一致，否则拒绝
 * （见 {@link DatLicenseVerifier}）。
 */
@Slf4j
public final class EsnProvider {

    /** 注入稳定设备 SN 的环境变量名。 */
    public static final String ESN_ENV = "ASG_KB_ESN";

    private EsnProvider() {
    }

    /** 当前设备 ESN：SN（注入）优先，其次 MAC，末级 hostname 哈希。 */
    public static String current() {
        String esn = System.getenv(ESN_ENV);
        if (esn != null && !esn.trim().isEmpty()) {
            return esn.trim();
        }
        String mac = primaryMac();
        if (mac != null) {
            return mac;
        }
        log.warn("ASG_KB_ESN(SN) 未注入且无可用 MAC，设备标识回退主机指纹（多副本下不稳定，建议注入 SN）");
        return fallbackFingerprint();
    }

    /** 第一个非回线、已启用且具备硬件地址的网卡 MAC，格式 {@code AA:BB:CC:DD:EE:FF}；无则 null。 */
    private static String primaryMac() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp() || ni.isVirtual()) {
                    continue;
                }
                byte[] hw = ni.getHardwareAddress();
                if (hw == null || hw.length == 0) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < hw.length; i++) {
                    if (i > 0) {
                        sb.append(':');
                    }
                    sb.append(String.format("%02X", hw[i]));
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("读取网卡 MAC 失败: {}", e.getMessage());
        }
        return null;
    }

    /** 末级回退：machine-id → HOSTNAME → 主机名，SHA-256(hex) 前 32 位。 */
    private static String fallbackFingerprint() {
        String raw = readMachineId();
        if (raw == null || raw.trim().isEmpty()) {
            raw = System.getenv("HOSTNAME");
        }
        if (raw == null || raw.trim().isEmpty()) {
            try {
                raw = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                raw = "unknown-device";
            }
        }
        return sha256Hex(raw.trim()).substring(0, 32);
    }

    private static String readMachineId() {
        try {
            return new String(Files.readAllBytes(Paths.get("/etc/machine-id")), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(Arrays.hashCode(s.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
