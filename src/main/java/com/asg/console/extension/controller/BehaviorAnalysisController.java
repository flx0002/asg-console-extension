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

import java.lang.reflect.Method;
import java.util.Map;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
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

    // 处置人取自控制台登录会话: SessionService 未随 SDK 暴露到编译期, 故反射调用
    private static final String SESSION_SERVICE_CLASS = "com.alibaba.higress.console.service.SessionService";

    private BehaviorAnalysisService behaviorAnalysisService;

    @Autowired
    private ApplicationContext applicationContext;

    @Resource
    public void setBehaviorAnalysisService(BehaviorAnalysisService behaviorAnalysisService) {
        this.behaviorAnalysisService = behaviorAnalysisService;
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
        try {
            behaviorAnalysisService.updateConfig(config);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid behavior analysis config: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().build();
    }

    // ==================== 画像查询 ====================

    @GetMapping("/profiles/users")
    @Operation(summary = "List user profiles")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<java.util.List<Map<String, Object>>>> listUserProfiles(
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ControllerUtil.buildResponseEntity(behaviorAnalysisService.listUserProfiles(page, pageSize));
    }

    @GetMapping("/profiles/users/{userId}")
    @Operation(summary = "Get user profile detail")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getUserProfile(
        @PathVariable("userId") String userId) {
        return ControllerUtil.buildResponseEntity(behaviorAnalysisService.getUserProfile(userId));
    }

    @GetMapping("/profiles/agents")
    @Operation(summary = "List agent profiles")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<java.util.List<Map<String, Object>>>> listAgentProfiles(
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ControllerUtil.buildResponseEntity(behaviorAnalysisService.listAgentProfiles(page, pageSize));
    }

    @GetMapping("/profiles/agents/{agentId}")
    @Operation(summary = "Get agent profile detail")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getAgentProfile(
        @PathVariable("agentId") String agentId) {
        return ControllerUtil.buildResponseEntity(behaviorAnalysisService.getAgentProfile(agentId));
    }

    // ==================== 告警管理 ====================

    @GetMapping("/alerts")
    @Operation(summary = "List alerts with filters")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getAlerts(
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "riskType", required = false) String riskType,
        @RequestParam(value = "riskLevel", required = false) String riskLevel,
        @RequestParam(value = "userId", required = false) String userId,
        @RequestParam(value = "agentId", required = false) String agentId,
        @RequestParam(value = "startTime", required = false, defaultValue = "0") long startTime,
        @RequestParam(value = "endTime", required = false, defaultValue = "0") long endTime,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ControllerUtil.buildResponseEntity(
            behaviorAnalysisService.getAlerts(status, riskType, riskLevel, userId, agentId,
                startTime, endTime, page, pageSize));
    }

    @GetMapping("/alerts/{alertId}")
    @Operation(summary = "Get alert detail")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getAlertDetail(
        @PathVariable("alertId") String alertId) {
        return ControllerUtil.buildResponseEntity(behaviorAnalysisService.getAlertDetail(alertId));
    }

    @PutMapping("/alerts/{alertId}/disposition")
    @Operation(summary = "Update alert disposition (blacklist/acknowledge/false_positive)")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Void> updateAlertDisposition(
        @PathVariable("alertId") String alertId,
        @RequestBody DispositionRequest request,
        HttpServletRequest servletRequest) {
        // JSON 字面量 null 能绕过 @RequestBody(required=true), 不拦住会在下面 NPE 成 500
        if (request == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        String disposition = request.getDisposition() == null ? "" : request.getDisposition().trim();
        // degrade_config 本期没有落地实现, 明确拒绝, 不能只改状态就返回成功
        if ("degrade_config".equals(disposition)) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }
        if (!isValidDisposition(disposition)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        // 处置人取自控制台登录会话, 不信任请求体: 审计字段必须不可伪造
        String operator;
        try {
            operator = resolveOperator(servletRequest);
        } catch (RuntimeException e) {
            // 会话服务不可用属于服务端故障: 报 401 会让控台拦截器把管理员踢回登录页, 掩盖真实原因
            log.error("resolve disposition operator failed, alertId={}", alertId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        if (operator == null) {
            // 未登录: 改变封禁状态的操作用户拿不到, 落库只会伪造出"某人处置过"的审计记录
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean applied = behaviorAnalysisService.updateAlertDisposition(alertId, disposition, operator,
            request.getNote(), request.getTtl());
        return applied ? ResponseEntity.ok().build() : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    // ==================== 行为统计与时间线 ====================

    @GetMapping("/stats")
    @Operation(summary = "Get behavior statistics overview")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getBehaviorStats(
        @RequestParam(value = "startTime", required = false, defaultValue = "0") long startTime,
        @RequestParam(value = "endTime", required = false, defaultValue = "0") long endTime) {
        return ControllerUtil.buildResponseEntity(
            behaviorAnalysisService.getBehaviorStats(startTime, endTime));
    }

    @GetMapping("/timeline")
    @Operation(summary = "Get behavior timeline")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getBehaviorTimeline(
        @RequestParam(value = "startTime", required = false, defaultValue = "0") long startTime,
        @RequestParam(value = "endTime", required = false, defaultValue = "0") long endTime,
        // 不指定粒度时交给服务端按跨度自适应（≤2 天小时桶，否则天桶）
        @RequestParam(value = "granularity", required = false, defaultValue = "") String granularity) {
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
        private String note;
        /** 黑名单有效期(秒), 仅 blacklist 动作生效 */
        private Integer ttl;

        public String getDisposition() {
            return disposition;
        }

        public void setDisposition(String disposition) {
            this.disposition = disposition;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public Integer getTtl() {
            return ttl;
        }

        public void setTtl(Integer ttl) {
            this.ttl = ttl;
        }
    }

    private static boolean isValidDisposition(String disposition) {
        return "acknowledge".equals(disposition) || "blacklist".equals(disposition)
            || "false_positive".equals(disposition);
    }

    /**
     * 解析处置人 = 控制台登录用户名。
     *
     * <p>控制台会话鉴权由 {@code com.alibaba.higress.console.service.SessionService} 承担; 该类型未随
     * Higress SDK 暴露到扩展的编译期, 故经 Spring 容器反射调用其 {@code validateSession(request)}。
     * 请求体里的操作者字段可被任意伪造, 不作为审计依据。
     *
     * <p>不做常量回退: 回退值会与真实用户名混在同一个字段里, 事后无法分辨谁真正处置过。
     *
     * @return 登录用户名; 未登录返回 {@code null}
     * @throws IllegalStateException 会话服务不可用或用户对象不可读, 属于服务端故障而非未登录
     */
    private String resolveOperator(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object user;
        try {
            Class<?> sessionServiceType = Class.forName(SESSION_SERVICE_CLASS);
            Object sessionService = applicationContext.getBean(sessionServiceType);
            Method validateSession = sessionServiceType.getMethod("validateSession", HttpServletRequest.class);
            user = validateSession.invoke(sessionService, request);
        } catch (Exception e) {
            throw new IllegalStateException("console session service is unavailable", e);
        }
        if (user == null) {
            // 未登录时 validateSession 返回 null(实测), 与上面的服务端故障区分开
            return null;
        }
        Object name;
        try {
            name = user.getClass().getMethod("getName").invoke(user);
        } catch (Exception e) {
            throw new IllegalStateException("session user has no readable name", e);
        }
        if (name == null || StringUtils.isBlank(name.toString())) {
            return null;
        }
        return name.toString();
    }

}
