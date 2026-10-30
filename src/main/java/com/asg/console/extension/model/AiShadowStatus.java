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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiShadowStatus {

    private String routeName;

    /** 该路由对应的 Ingress 资源名，与 Prometheus 的 ai_route 标签同值，用于对齐用量。 */
    private String resourceName;

    private String mode;

    private Boolean authEnabled;

    private List<String> authorizedConsumers;

    /** 是否已纳入 key-auth 管控（实例存在且启用）。未纳管时模式开关无意义。 */
    private Boolean managed;

    /**
     * 名单里没有任何真实消费者，即「默认拒绝」：除匿名请求按模式处理外，
     * 所有带有效 Key 但不在名单里的调用方都会被判为 not_in_allow。
     */
    private Boolean defaultDeny;
}
