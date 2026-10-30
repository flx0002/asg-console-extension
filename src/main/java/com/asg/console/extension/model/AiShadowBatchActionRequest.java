/*
 * Copyright (c) 2022-2025 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.asg.console.extension.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批量授权请求：用一份最终名单替换该路由的授权消费者。
 * <p>
 * 名单为空表示「不授权任何人」，后端会落成默认拒绝，而不是留一个空名单让插件不介入。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiShadowBatchActionRequest {

    private String routeName;

    private List<String> consumerNames;
}
