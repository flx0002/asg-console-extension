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

import com.asg.console.extension.model.ShadowAiActionRequest;
import com.asg.console.extension.model.ShadowAiDetectedAccess;
import com.asg.console.extension.model.ShadowAiModeRequest;
import com.asg.console.extension.model.ShadowAiStatus;

public interface ShadowAiService {

    List<ShadowAiStatus> getStatus();

    ShadowAiStatus getStatus(String routeName);

    ShadowAiStatus setMode(ShadowAiModeRequest request);

    ShadowAiStatus performAction(ShadowAiActionRequest request);

    List<ShadowAiDetectedAccess> getDetectedAccesses();

    /**
     * Hourly detection trend over the requested lookback window (IR-004).
     * Returns long-format points: {time="MM-dd HH:00", status, count}.
     */
    List<Map<String, Object>> getDetectedTrend(int hours);

    /**
     * Current authorized-domain view of the DNS/bypass policy (IR-003 unified
     * authorization). Returns {mode, domains}.
     */
    Map<String, Object> getAuthorizedDomains();

    /**
     * Add/remove authorized domains on the DNS/bypass policy (IR-003).
     * Returns the updated {mode, domains} view; each change is audited.
     */
    Map<String, Object> updateAuthorizedDomains(String mode, List<String> addDomains, List<String> removeDomains);

    void setDetectMode(String mode);

    String getDetectMode();
}
