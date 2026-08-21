package com.asg.console.extension.controller;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.asg.console.extension.controller.dto.Response;
import com.asg.console.extension.controller.util.ControllerUtil;
import com.asg.console.extension.service.AgentGuardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController("AgentGuardController")
@RequestMapping("/v1/agent-guard")
@Validated
@Tag(name = "AI Agent Guard APIs")
public class AgentGuardController {

    private AgentGuardService agentGuardService;

    @Resource
    public void setAgentGuardService(AgentGuardService agentGuardService) {
        this.agentGuardService = agentGuardService;
    }

    @GetMapping("/sessions")
    @Operation(summary = "List all active agent sessions")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<List<Map<String, Object>>>> listSessions() {
        List<Map<String, Object>> sessions = agentGuardService.listSessions();
        return ControllerUtil.buildResponseEntity(sessions);
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "Get agent session details")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<Map<String, Object>>> getSession(
        @PathVariable("sessionId") String sessionId) {
        Map<String, Object> session = agentGuardService.getSession(sessionId);
        return ControllerUtil.buildResponseEntity(session);
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "Delete an agent session")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Void> deleteSession(
        @PathVariable("sessionId") String sessionId) {
        agentGuardService.deleteSession(sessionId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/audit-logs")
    @Operation(summary = "Get audit logs")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success")})
    public ResponseEntity<Response<List<Map<String, Object>>>> getAuditLogs(
        @RequestParam(value = "limit", defaultValue = "100") int limit) {
        List<Map<String, Object>> logs = agentGuardService.getAuditLogs(limit);
        return ControllerUtil.buildResponseEntity(logs);
    }
}
