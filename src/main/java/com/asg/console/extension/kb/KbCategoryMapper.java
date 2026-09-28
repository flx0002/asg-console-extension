/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * KB bundle → 网关 categories 结构映射。
 *
 * <p>{@link KbBundle.Category} 已用 fastjson {@code @JSONField} 标注 snake_case，故
 * {@code JSON.toJSON(category)} 直接产出与 ai-shadow-detect WasmPlugin CR、bypass-guard
 * {@code CategoryRule} 完全同源的键：name / label / risk_level / domains / suffixes /
 * body_features / path_patterns。KB 因此是网关与旁路检测的统一权威源。
 */
public final class KbCategoryMapper {

    private KbCategoryMapper() {
    }

    /**
     * 转换为网关 categories 列表；bundle 为空或无有效分类返回 null（调用方据此跳过下发，
     * 保持 CR 现有配置不被空值覆盖）。
     */
    public static List<Map<String, Object>> toGatewayCategories(KbBundle bundle) {
        if (bundle == null || bundle.getCategories() == null || bundle.getCategories().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (KbBundle.Category c : bundle.getCategories()) {
            if (c == null || StringUtils.isBlank(c.getName())) {
                continue;
            }
            JSONObject o = (JSONObject) JSON.toJSON(c);
            list.add(o);
        }
        return list.isEmpty() ? null : list;
    }
}
