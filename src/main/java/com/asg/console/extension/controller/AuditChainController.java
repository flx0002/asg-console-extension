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

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.asg.console.extension.service.impl.AgentAuditPersistenceService;
import com.asg.console.extension.service.AuditChainService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController("AuditChainController")
@RequestMapping("/v1/audit-chain")
@Validated
@Tag(name = "Audit Chain APIs")
public class AuditChainController {

    private AuditChainService auditChainService;

    @Resource
    public void setAuditChainService(AuditChainService auditChainService) {
        this.auditChainService = auditChainService;
    }

    private AgentAuditPersistenceService auditPersistenceService;

    @Resource
    public void setAuditPersistenceService(AgentAuditPersistenceService auditPersistenceService) {
        this.auditPersistenceService = auditPersistenceService;
    }

    @GetMapping("/config")
    @Operation(summary = "Get audit chain configuration")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getAuditConfig() {
        Map<String, Object> config = auditChainService.getAuditConfig();
        return ControllerUtil.buildResponseEntity(config);
    }

    @PutMapping("/config")
    @Operation(summary = "Update audit chain configuration")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Void> updateAuditConfig(@RequestBody Map<String, Object> config) {
        auditChainService.updateAuditConfig(config);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/sessions")
    @Operation(summary = "List audit sessions (normal + degraded)")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<List<Map<String, Object>>>> getAuditSessions() {
        List<Map<String, Object>> sessions = auditChainService.getAuditSessions();
        return ControllerUtil.buildResponseEntity(sessions);
    }

    @GetMapping("/logs")
    @Operation(summary = "Get audit logs for a specific session")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getAuditLogs(
        @RequestParam("sessionId") String sessionId,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
        @RequestParam(value = "recordType", required = false) String recordType) {
        Map<String, Object> logs = auditChainService.getAuditLogs(sessionId, page, pageSize, recordType);
        return ControllerUtil.buildResponseEntity(logs);
    }

    @GetMapping("/logs/stats")
    @Operation(summary = "Get audit log statistics for a specific session")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getAuditStats(
        @RequestParam("sessionId") String sessionId) {
        Map<String, Object> stats = auditChainService.getAuditStats(sessionId);
        return ControllerUtil.buildResponseEntity(stats);
    }

    @DeleteMapping("/logs/session/{sessionId}")
    @Operation(summary = "Clear all audit logs for a specific session")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Void> clearSessionAuditLogs(@PathVariable("sessionId") String sessionId) {
        auditChainService.clearSessionAuditLogs(sessionId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/chain/{sessionId}")
    @Operation(summary = "Get audit chain (execution timeline) for a specific session")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getSessionAuditChain(
        @PathVariable("sessionId") String sessionId,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
        Map<String, Object> chain = auditChainService.getSessionAuditChain(sessionId, page, pageSize);
        return ControllerUtil.buildResponseEntity(chain);
    }

    @GetMapping("/chain/{sessionId}/stats")
    @Operation(summary = "Get audit chain statistics for a specific session")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getSessionAuditStats(
        @PathVariable("sessionId") String sessionId) {
        Map<String, Object> stats = auditChainService.getSessionAuditStats(sessionId);
        return ControllerUtil.buildResponseEntity(stats);
    }

    @PostMapping("/cleanup")
    @Operation(summary = "Manually trigger cleanup of expired audit logs")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Void> cleanupExpiredLogs() {
        auditChainService.cleanupExpiredLogs();
        // MySQL side retention (IR-057): drop rows older than max_days as well.
        if (auditPersistenceService != null) {
            auditPersistenceService.cleanupExpiredLogs();
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/export/{sessionId}")
    @Operation(summary = "Export audit logs for a specific session (JSON/CSV)")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public void exportAuditLogs(
        @PathVariable("sessionId") String sessionId,
        @RequestParam(value = "format", defaultValue = "json") String format,
        HttpServletResponse response) {
        auditChainService.exportAuditLogs(sessionId, format, response);
    }
}
