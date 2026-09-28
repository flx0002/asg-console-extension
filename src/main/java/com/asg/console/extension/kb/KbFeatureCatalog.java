/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import java.util.HashMap;
import java.util.Map;

/**
 * 授权功能位「ID → 显示名称」权威目录（后端为唯一真相源）。
 *
 * <p>功能位在 {@code .dat} 载荷中以稳定 {@code id}（如 {@code ai_kb_update}）标识；显示名称
 * <b>不取自载荷</b>，而由本目录按 id 解析，保证「ID↔名称」与授权签发时一一对应、且不可被载荷伪造。
 * 未知 id 回退显示 id 本身（机器标识），不臆造名称。
 *
 * <p>新增可售卖功能位时，在此登记 id→名称，并与厂商授权工具的功能位目录保持一致。
 */
public final class KbFeatureCatalog {

    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        NAMES.put(LicenseInfo.FEATURE_KB_UPDATE, "AI分类知识库更新");
    }

    private KbFeatureCatalog() {
    }

    /** 按功能位 ID 解析权威显示名称；null/空返回空串，未知 id 回退返回 id 本身。 */
    public static String displayName(String id) {
        if (id == null) {
            return "";
        }
        String key = id.trim();
        if (key.isEmpty()) {
            return "";
        }
        String name = NAMES.get(key);
        return name != null ? name : key;
    }
}
