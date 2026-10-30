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
 * 「谁在用我的 AI、用了多少、有没有被拦」的整页数据。
 * <p>
 * 注意两个计数不是同一个口径，页面必须分别标注，否则又是两套数字打架：
 * <ul>
 *   <li>用量（requestCount / tokens）是 Prometheus 计量的累计值，只有被放行的请求才有；</li>
 *   <li>拦截（blockedCount / monitoredCount）是事件表在 {@code eventWindowHours} 窗口内的条数。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiConsumerUsageReport {

    private Long requestCount;

    private Long inputTokens;

    private Long outputTokens;

    private Long totalTokens;

    /** 有身份的消费者数量（不含 none）。 */
    private Integer consumerCount;

    /** 其中在它调用过的每条路由上都被授权的数量。 */
    private Integer authorizedCount;

    /** 未认证流量的请求数（consumer=none）。 */
    private Long unauthenticatedRequestCount;

    private Long blockedCount;

    private Long monitoredCount;

    private List<AiConsumerUsage> consumers;

    /**
     * Prometheus 不可达时为 false。此时用量全是 0，页面必须提示「用量暂不可用」，
     * 而不是让人读成「没有人用」——这两个结论差别很大。
     */
    private Boolean usageAvailable;

    /** 用量口径：cumulative = 网关启动以来的累计（Prometheus 只保留 6h 样本，算不出历史区间）。 */
    private String usageScope;

    /** 拦截计数所用的事件窗口（小时）。与 usageScope 口径不同，页面需分开展示。 */
    private Integer eventWindowHours;
}
