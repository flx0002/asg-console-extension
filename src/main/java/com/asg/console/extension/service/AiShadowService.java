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
package com.asg.console.extension.service;

import java.util.List;
import java.util.Map;

import com.asg.console.extension.model.AiShadowBatchActionRequest;
import com.asg.console.extension.model.AiShadowActionRequest;
import com.asg.console.extension.model.AiShadowDetectedAccess;
import com.asg.console.extension.model.AiShadowModeRequest;
import com.asg.console.extension.model.AiConsumerUsageReport;
import com.asg.console.extension.model.AiShadowStatus;

public interface AiShadowService {

    /**
     * 所有 AI 路由的管控状态。
     * <p>
     * 该方法会顺带对账：缺 key-auth 实例的路由自动纳管，名单为空则落默认拒绝，
     * 所以返回结果里不会出现「未纳管」。对账只在状态不合规时写，重复调用是安全的。
     */
    List<AiShadowStatus> getStatus(boolean includeMetrics);

    /**
     * 消费者用量与拦截画像：谁在用我的 AI、用了多少、有没有被拦。
     * <p>
     * 与 {@link #getStatus(boolean)} 分开是因为两者数据源和口径天然不同：用量只在
     * Prometheus（被放行的请求才有计量），拦截只在事件表（被拦的请求不产生计量）。
     */
    AiConsumerUsageReport getConsumerUsage(Long startTime, Long endTime);

    AiShadowStatus getStatus(String routeName);

    AiShadowStatus setMode(AiShadowModeRequest request);

    AiShadowStatus performAction(AiShadowActionRequest request);

    /** 全部可授权的调用方名称，供授权抽屉选择；不含未认证桶。 */
    List<String> listConsumerNames();

    /**
     * 用一份「最终名单」整体替换某条路由的授权消费者，名单为空即回落为默认拒绝。
     * 批量场景必须一次提交，逐个 ADD/REMOVE 会在中途失败时留下半完成状态。
     */
    AiShadowStatus replaceAuthorizedConsumers(AiShadowBatchActionRequest request);

    /**
     * Detected accesses aggregated over the requested window. Same source (detection
     * event table) as the trend and the KPI summary, so all three views agree.
     */
    List<AiShadowDetectedAccess> getDetectedAccessesInWindow(Long startTime, Long endTime, String surface);

    /**
     * KPI summary over the requested window: total events, critical / high risk event
     * counts, blocked events and the number of distinct domains involved.
     * Returns {start, end, total, critical, high, blocked, domainCount, degraded}.
     */
    Map<String, Object> getDetectedStats(Long startTime, Long endTime, String surface);

    /**
     * Detection trend over the requested window (IR-004). The bucket granularity
     * follows the span (hourly up to two days, daily beyond) and the bucket edges
     * are aligned to the product time zone.
     * Returns {start, end, granularity, list=[{timestamp(ms), status, count}]}.
     */
    Map<String, Object> getDetectedTrend(Long startTime, Long endTime, String surface);

    /**
     * 受管通道准入违规明细（surface='route'），按域名 + 原因 + 处置聚合。
     * 「路由与消费者管控」页用它作为唯一数据源：Prometheus 只记录放行流量的 token，
     * 被 key-auth 拦下的请求不产生指标，从计量侧永远看不到它们。
     * Returns {start, end, total, blocked, domainCount, list=[{domain, reason, status, count, lastSeen}]}.
     */
    Map<String, Object> getAdmissionEvents(Long startTime, Long endTime);

    /**
     * Current authorized-domain view of the DNS/bypass policy (IR-003 unified
     * authorization). Returns {mode, domains, items}. Each item also carries
     * {@code hitCount} / {@code hitDomains}: how much traffic inside the given window
     * that single entry actually covers, so entries that match nothing are visible.
     * Pass null bounds to use the default window.
     */
    Map<String, Object> getAuthorizedDomains(Long startTime, Long endTime);

    /**
     * Add/remove authorized domains on the DNS/bypass policy (IR-003).
     * {@code source} records how the entry was created (manual / detected) for the
     * management table; it has no effect on the authorization decision itself.
     * Returns the updated {mode, domains, items} view; each change is audited.
     */
    Map<String, Object> updateAuthorizedDomains(String mode, List<String> addDomains, List<String> removeDomains,
        String source);

    void setDetectMode(String mode);

    String getDetectMode();

    /**
     * Master feature switch for the whole shadow AI detection capability.
     * Defaults to enabled. Disabling stops gateway detection/recording/blocking
     * (WasmPlugin defaultConfigDisable=true) and the DNS collector fallback.
     */
    void setEnabled(boolean enabled);

    /** Current enable state; true when no global instance exists yet (default enabled). */
    boolean getEnabled();
}
