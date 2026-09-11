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
package com.asg.console.extension.controller;

import javax.annotation.Resource;
import javax.validation.constraints.NotBlank;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.asg.console.extension.controller.dto.Response;
import com.asg.console.extension.controller.util.ControllerUtil;
import com.asg.console.extension.model.AiShadowActionRequest;
import com.asg.console.extension.model.AiShadowDetectedAccess;
import com.asg.console.extension.model.AiShadowModeRequest;
import com.asg.console.extension.model.AiShadowStatus;
import com.asg.console.extension.service.AiShadowService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

@RestController("AiShadowController")
@RequestMapping("/v1/ai-shadow")
@Validated
@Tag(name = "Shadow AI APIs")
public class AiShadowController {

    private AiShadowService aiShadowService;

    @Resource
    public void setAiShadowService(AiShadowService aiShadowService) {
        this.aiShadowService = aiShadowService;
    }

    @GetMapping("/status")
    @Operation(summary = "List all AI routes' shadow AI status")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Shadow AI status listed successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<List<AiShadowStatus>>> listStatus() {
        List<AiShadowStatus> statusList = aiShadowService.getStatus();
        return ControllerUtil.buildResponseEntity(statusList);
    }

    @GetMapping("/status/{routeName}")
    @Operation(summary = "Get shadow AI status for a specific route")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Shadow AI status found"),
        @ApiResponse(responseCode = "404", description = "Route not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<AiShadowStatus>> getStatus(
        @PathVariable("routeName") @NotBlank String routeName) {
        AiShadowStatus status = aiShadowService.getStatus(routeName);
        return ControllerUtil.buildResponseEntity(status);
    }

    @PutMapping("/mode")
    @Operation(summary = "Set monitoring or enforcement mode for an AI route")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Mode updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<AiShadowStatus>> setMode(@RequestBody AiShadowModeRequest request) {
        AiShadowStatus status = aiShadowService.setMode(request);
        return ControllerUtil.buildResponseEntity(status);
    }

    @PutMapping("/action")
    @Operation(summary = "Authorize or block a shadow AI consumer")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Action performed successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<AiShadowStatus>> performAction(@RequestBody AiShadowActionRequest request) {
        AiShadowStatus status = aiShadowService.performAction(request);
        return ControllerUtil.buildResponseEntity(status);
    }

    @GetMapping("/detected")
    @Operation(summary = "List detected unauthorized AI service accesses")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Detected accesses listed successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<List<AiShadowDetectedAccess>>> listDetectedAccesses() {
        List<AiShadowDetectedAccess> detectedList = aiShadowService.getDetectedAccesses();
        return ControllerUtil.buildResponseEntity(detectedList);
    }

    @GetMapping("/detected-trend")
    @Operation(summary = "Hourly shadow AI detection trend (IR-004)")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Trend points listed successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<List<Map<String, Object>>>> getDetectedTrend(
        @org.springframework.web.bind.annotation.RequestParam(value = "hours", defaultValue = "24") int hours) {
        return ControllerUtil.buildResponseEntity(aiShadowService.getDetectedTrend(hours));
    }

    @GetMapping("/authorized-domains")
    @Operation(summary = "List authorized shadow AI domains (DNS/bypass policy, IR-003)")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Authorized domains listed successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<Map<String, Object>>> getAuthorizedDomains() {
        return ControllerUtil.buildResponseEntity(aiShadowService.getAuthorizedDomains());
    }

    @PutMapping("/authorized-domains")
    @Operation(summary = "Add or remove authorized shadow AI domains (IR-003 unified authorization)")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Authorized domains updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    @SuppressWarnings("unchecked")
    public ResponseEntity<Response<Map<String, Object>>> updateAuthorizedDomains(
        @RequestBody Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String mode = body.get("mode") != null ? String.valueOf(body.get("mode")) : null;
        List<String> addDomains = (List<String>) body.get("addDomains");
        List<String> removeDomains = (List<String>) body.get("removeDomains");
        return ControllerUtil.buildResponseEntity(
            aiShadowService.updateAuthorizedDomains(mode, addDomains, removeDomains));
    }

    @PutMapping("/detect-mode")
    @Operation(summary = "Set shadow AI detection mode (monitoring or enforcement)")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Detection mode updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<String>> setDetectMode(@RequestBody Map<String, String> body) {
        String mode = body.get("mode");
        aiShadowService.setDetectMode(mode);
        return ControllerUtil.buildResponseEntity(mode);
    }

    @GetMapping("/detect-mode")
    @Operation(summary = "Get current shadow AI detection mode")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Detection mode retrieved successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<String>> getDetectMode() {
        String mode = aiShadowService.getDetectMode();
        return ControllerUtil.buildResponseEntity(mode);
    }
}
