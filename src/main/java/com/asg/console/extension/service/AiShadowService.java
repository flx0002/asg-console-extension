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

import com.asg.console.extension.model.AiShadowActionRequest;
import com.asg.console.extension.model.AiShadowDetectedAccess;
import com.asg.console.extension.model.AiShadowModeRequest;
import com.asg.console.extension.model.AiShadowStatus;

public interface AiShadowService {

    List<AiShadowStatus> getStatus();

    AiShadowStatus getStatus(String routeName);

    AiShadowStatus setMode(AiShadowModeRequest request);

    AiShadowStatus performAction(AiShadowActionRequest request);

    /**
     * Detected accesses aggregated over the requested window. Same source (detection
     * event table) as the trend and the KPI summary, so all three views agree.
     */
    List<AiShadowDetectedAccess> getDetectedAccessesInWindow(Long startTime, Long endTime);

    /**
     * KPI summary over the requested window: total events, critical / high risk event
     * counts, blocked events and the number of distinct domains involved.
     * Returns {start, end, total, critical, high, blocked, domainCount, degraded}.
     */
    Map<String, Object> getDetectedStats(Long startTime, Long endTime);

    /**
     * Detection trend over the requested window (IR-004). The bucket granularity
     * follows the span (hourly up to two days, daily beyond) and the bucket edges
     * are aligned to the product time zone.
     * Returns {start, end, granularity, list=[{timestamp(ms), status, count}]}.
     */
    Map<String, Object> getDetectedTrend(Long startTime, Long endTime);

    /**
     * Current authorized-domain view of the DNS/bypass policy (IR-003 unified
     * authorization). Returns {mode, domains}.
     */
    Map<String, Object> getAuthorizedDomains();

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
