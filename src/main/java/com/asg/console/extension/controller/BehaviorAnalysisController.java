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
package com.asg.console.extension.controller;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Resource;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.asg.console.extension.controller.dto.Response;
import com.asg.console.extension.controller.util.ControllerUtil;
import com.asg.console.extension.service.AuditLogCollectorService;
import com.asg.console.extension.service.BehaviorAnalysisService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 行为分析 REST API（方案 5.2）。
 * Base path: /v1/behavior-analysis
 */
@RestController("BehaviorAnalysisController")
@RequestMapping("/v1/behavior-analysis")
@Validated
@Tag(name = "Behavior Analysis APIs")
@Slf4j
public class BehaviorAnalysisController {

    private static final long CATCH_UP_INTERVAL_MS = 3000;
    private static final int CATCH_UP_SINCE_SECONDS = 120;

    private BehaviorAnalysisService behaviorAnalysisService;
    private AuditLogCollectorService auditLogCollectorService;
    private final AtomicLong lastCatchUpAt = new AtomicLong(0);

    @Resource
    public void setBehaviorAnalysisService(BehaviorAnalysisService behaviorAnalysisService) {
        this.behaviorAnalysisService = behaviorAnalysisService;
    }

    @Resource
    public void setAuditLogCollectorService(AuditLogCollectorService auditLogCollectorService) {
        this.auditLogCollectorService = auditLogCollectorService;
    }

    private void catchUpRecentAnalysis() {
        long now = System.currentTimeMillis();
        long last = lastCatchUpAt.get();
        if (now - last < CATCH_UP_INTERVAL_MS || !lastCatchUpAt.compareAndSet(last, now)) {
            return;
        }
        try {
            if (auditLogCollectorService != null) {
                auditLogCollectorService.collect(CATCH_UP_SINCE_SECONDS);
            }
        } catch (Exception e) {
            log.warn("Behavior analysis catch-up audit collection failed", e);
        }
        try {
            behaviorAnalysisService.rebuildProfiles();
        } catch (Exception e) {
            log.warn("Behavior analysis catch-up profile rebuild failed", e);
        }
        try {
            behaviorAnalysisService.runRiskDetection();
        } catch (Exception e) {
            log.warn("Behavior analysis catch-up risk detection failed", e);
        }
    }

    // ==================== 配置管理 ====================

    @GetMapping("/config")
    @Operation(summary = "Get behavior analysis configuration")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getConfig() {
        return ControllerUtil.buildResponseEntity(behaviorAnalysisService.getConfig());
    }

    @PutMapping("/config")
    @Operation(summary = "Update behavior analysis configuration")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Void> updateConfig(@RequestBody Map<String, Object> config) {
        behaviorAnalysisService.updateConfig(config);
        return ResponseEntity.ok().build();
    }

    // ==================== 画像查询 ====================

    @GetMapping("/profiles/users")
    @Operation(summary = "List user profiles")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<java.util.List<Map<String, Object>>>> listUserProfiles(
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        catchUpRecentAnalysis();
        return ControllerUtil.buildResponseEntity(behaviorAnalysisService.listUserProfiles(page, pageSize));
    }

    @GetMapping("/profiles/users/{userId}")
    @Operation(summary = "Get user profile detail")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getUserProfile(
        @PathVariable("userId") String userId) {
        catchUpRecentAnalysis();
        return ControllerUtil.buildResponseEntity(behaviorAnalysisService.getUserProfile(userId));
    }

    @GetMapping("/profiles/agents")
    @Operation(summary = "List agent profiles")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<java.util.List<Map<String, Object>>>> listAgentProfiles(
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        catchUpRecentAnalysis();
        return ControllerUtil.buildResponseEntity(behaviorAnalysisService.listAgentProfiles(page, pageSize));
    }

    @GetMapping("/profiles/agents/{agentId}")
    @Operation(summary = "Get agent profile detail")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getAgentProfile(
        @PathVariable("agentId") String agentId) {
        catchUpRecentAnalysis();
        return ControllerUtil.buildResponseEntity(behaviorAnalysisService.getAgentProfile(agentId));
    }

    // ==================== 告警管理 ====================

    @GetMapping("/alerts")
    @Operation(summary = "List alerts with filters")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getAlerts(
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "riskType", required = false) String riskType,
        @RequestParam(value = "userId", required = false) String userId,
        @RequestParam(value = "agentId", required = false) String agentId,
        @RequestParam(value = "startTime", required = false, defaultValue = "0") long startTime,
        @RequestParam(value = "endTime", required = false, defaultValue = "0") long endTime,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        catchUpRecentAnalysis();
        return ControllerUtil.buildResponseEntity(
            behaviorAnalysisService.getAlerts(status, riskType, userId, agentId,
                startTime, endTime, page, pageSize));
    }

    @GetMapping("/alerts/{alertId}")
    @Operation(summary = "Get alert detail")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getAlertDetail(
        @PathVariable("alertId") String alertId) {
        catchUpRecentAnalysis();
        return ControllerUtil.buildResponseEntity(behaviorAnalysisService.getAlertDetail(alertId));
    }

    @PutMapping("/alerts/{alertId}/disposition")
    @Operation(summary = "Update alert disposition (blacklist/degrade_config/acknowledge/false_positive)")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Void> updateAlertDisposition(
        @PathVariable("alertId") String alertId,
        @RequestBody DispositionRequest request) {
        behaviorAnalysisService.updateAlertDisposition(alertId,
            request.getDisposition(), request.getDisposer(), request.getNote());
        return ResponseEntity.ok().build();
    }

    // ==================== 行为统计与时间线 ====================

    @GetMapping("/stats")
    @Operation(summary = "Get behavior statistics overview")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getBehaviorStats(
        @RequestParam(value = "startTime", required = false, defaultValue = "0") long startTime,
        @RequestParam(value = "endTime", required = false, defaultValue = "0") long endTime) {
        catchUpRecentAnalysis();
        return ControllerUtil.buildResponseEntity(
            behaviorAnalysisService.getBehaviorStats(startTime, endTime));
    }

    @GetMapping("/timeline")
    @Operation(summary = "Get behavior timeline")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getBehaviorTimeline(
        @RequestParam(value = "startTime", required = false, defaultValue = "0") long startTime,
        @RequestParam(value = "endTime", required = false, defaultValue = "0") long endTime,
        @RequestParam(value = "granularity", defaultValue = "hour") String granularity) {
        catchUpRecentAnalysis();
        return ControllerUtil.buildResponseEntity(
            behaviorAnalysisService.getBehaviorTimeline(startTime, endTime, granularity));
    }

    // ==================== 会话图谱 ====================

    @GetMapping("/session-graph/{sessionId}")
    @Operation(summary = "Get session graph data")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getSessionGraph(
        @PathVariable("sessionId") String sessionId,
        @RequestParam(value = "maxNodes", required = false, defaultValue = "50") int maxNodes) {
        catchUpRecentAnalysis();
        return ControllerUtil.buildResponseEntity(behaviorAnalysisService.getSessionGraph(sessionId));
    }

    // ==================== 基线管理 ====================

    @GetMapping("/baselines/{entityType}/{entityId}")
    @Operation(summary = "Get baseline for user or agent")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getBaseline(
        @PathVariable("entityType") String entityType,
        @PathVariable("entityId") String entityId) {
        return ControllerUtil.buildResponseEntity(
            behaviorAnalysisService.getBaseline(entityType, entityId));
    }

    @PostMapping("/baselines/{entityType}/{entityId}/rebuild")
    @Operation(summary = "Rebuild baseline for user or agent")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Void> rebuildBaseline(
        @PathVariable("entityType") String entityType,
        @PathVariable("entityId") String entityId) {
        behaviorAnalysisService.rebuildBaseline(entityType, entityId);
        return ResponseEntity.ok().build();
    }

    // ==================== 处置请求体 ====================

    public static class DispositionRequest {
        private String disposition;
        private String disposer;
        private String note;

        public String getDisposition() {
            return disposition;
        }

        public void setDisposition(String disposition) {
            this.disposition = disposition;
        }

        public String getDisposer() {
            return disposer;
        }

        public void setDisposer(String disposer) {
            this.disposer = disposer;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }
}
