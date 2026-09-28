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
 * 授权凭证校验抽象。离线授权文件与在线激活返回的凭证都经此校验。
 * 具体格式随厂商授权工具而定，替换实现即可（当前实现：{@link DatLicenseVerifier}，{@code .dat} RSA/AES 信封，对齐工业防火墙 TEG）。
 */
public interface LicenseVerifier {

    /**
     * 校验并解析授权凭证。
     *
     * @param rawLicense 授权文件原始内容（当前实现为 {@code .dat} 二进制的 Base64）
     * @return 校验结果；从不抛异常，失败以 {@link LicenseInfo#isValid()} == false 表达
     */
    LicenseInfo verify(String rawLicense);
}
