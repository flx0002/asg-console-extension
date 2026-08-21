/*
 * Copyright (c) 2022-2024 Alibaba Group Holding Ltd.
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

/**
 * 行为分析服务接口（方案 5.1）
 *
 * 负责用户/智能体画像构建、行为基线建立、风险检测与告警管理。
 * 画像与基线采用增量时间窗口 + Lua 原子合并，禁止全量 SCAN 审计日志。
 */
public interface BehaviorAnalysisService {

    // === 配置管理 ===
    Map<String, Object> getConfig();

    void updateConfig(Map<String, Object> config);

    // === 画像查询 ===
    Map<String, Object> getUserProfile(String userId);

    Map<String, Object> getAgentProfile(String agentId);

    List<Map<String, Object>> listUserProfiles(int page, int pageSize);

    List<Map<String, Object>> listAgentProfiles(int page, int pageSize);

    // === 告警管理 ===
    Map<String, Object> getAlerts(String status, String riskType, String userId, String agentId,
        long startTime, long endTime, int page, int pageSize);

    Map<String, Object> getAlertDetail(String alertId);

    void updateAlertDisposition(String alertId, String disposition, String disposer, String note);

    // === 行为统计 ===
    Map<String, Object> getBehaviorStats(long startTime, long endTime);

    Map<String, Object> getBehaviorTimeline(long startTime, long endTime, String granularity);

    // === 会话图谱 ===
    Map<String, Object> getSessionGraph(String sessionId);

    // === 基线管理 ===
    Map<String, Object> getBaseline(String entityType, String entityId);

    void rebuildBaseline(String entityType, String entityId);

    // === 画像构建（定时任务调用） ===
    void rebuildProfiles();

    void rebuildBaselines();

    // === 风险检测（定时任务调用） ===
    void runRiskDetection();

    // === 误报复盘（方案 9.2 / 阶段五 任务 3，每日定时调用） ===
    void runRuleFeedback();
}
