/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个消费者的用量与拦截画像。
 * <p>
 * 一行同时回答三件事：谁在用（consumer）、用了多少（requestCount / tokens）、
 * 有没有被拦（blockedCount / monitoredCount）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiConsumerUsage {

    /** 消费者名；未认证流量固定为 {@code none}，与 Prometheus 的 ai_consumer 标签取值一致。 */
    private String consumer;

    /** true 表示这是一群「没有身份」的流量，不是某个具体的人。 */
    private Boolean unauthenticated;

    /** 请求数，来自 route_upstream_model_consumer_metric_llm_duration_count。 */
    private Long requestCount;

    private Long inputTokens;

    private Long outputTokens;

    private List<String> models;

    /** 该消费者实际调用过的路由资源名（即 Prometheus 的 ai_route 标签，等于 Ingress 名）。 */
    private List<String> routes;

    /** 调用过、但不在其允许名单里的路由。非空即代表越权。 */
    private List<String> unauthorizedRoutes;

    /** 在它调用过的每一条路由上都被授权时为 true；未认证流量恒为 false。 */
    private Boolean authorized;

    /** 事件表 status=blocked 的条数，按 reason 归属到该消费者。 */
    private Long blockedCount;

    /** 事件表 status=monitored 的条数：被识别但没有拦截。 */
    private Long monitoredCount;
}
