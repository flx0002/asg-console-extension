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
import com.asg.console.extension.model.ShadowAiActionRequest;
import com.asg.console.extension.model.ShadowAiDetectedAccess;
import com.asg.console.extension.model.ShadowAiModeRequest;
import com.asg.console.extension.model.ShadowAiStatus;
import com.asg.console.extension.service.ShadowAiService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

@RestController("ShadowAiController")
@RequestMapping("/v1/shadow-ai")
@Validated
@Tag(name = "Shadow AI APIs")
public class ShadowAiController {

    private ShadowAiService shadowAiService;

    @Resource
    public void setShadowAiService(ShadowAiService shadowAiService) {
        this.shadowAiService = shadowAiService;
    }

    @GetMapping("/status")
    @Operation(summary = "List all AI routes' shadow AI status")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Shadow AI status listed successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<List<ShadowAiStatus>>> listStatus() {
        List<ShadowAiStatus> statusList = shadowAiService.getStatus();
        return ControllerUtil.buildResponseEntity(statusList);
    }

    @GetMapping("/status/{routeName}")
    @Operation(summary = "Get shadow AI status for a specific route")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Shadow AI status found"),
        @ApiResponse(responseCode = "404", description = "Route not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<ShadowAiStatus>> getStatus(
        @PathVariable("routeName") @NotBlank String routeName) {
        ShadowAiStatus status = shadowAiService.getStatus(routeName);
        return ControllerUtil.buildResponseEntity(status);
    }

    @PutMapping("/mode")
    @Operation(summary = "Set monitoring or enforcement mode for an AI route")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Mode updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<ShadowAiStatus>> setMode(@RequestBody ShadowAiModeRequest request) {
        ShadowAiStatus status = shadowAiService.setMode(request);
        return ControllerUtil.buildResponseEntity(status);
    }

    @PutMapping("/action")
    @Operation(summary = "Authorize or block a shadow AI consumer")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Action performed successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<ShadowAiStatus>> performAction(@RequestBody ShadowAiActionRequest request) {
        ShadowAiStatus status = shadowAiService.performAction(request);
        return ControllerUtil.buildResponseEntity(status);
    }

    @GetMapping("/detected")
    @Operation(summary = "List detected unauthorized AI service accesses")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Detected accesses listed successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<List<ShadowAiDetectedAccess>>> listDetectedAccesses() {
        List<ShadowAiDetectedAccess> detectedList = shadowAiService.getDetectedAccesses();
        return ControllerUtil.buildResponseEntity(detectedList);
    }

    @PutMapping("/detect-mode")
    @Operation(summary = "Set shadow AI detection mode (monitoring or enforcement)")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Detection mode updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<String>> setDetectMode(@RequestBody Map<String, String> body) {
        String mode = body.get("mode");
        shadowAiService.setDetectMode(mode);
        return ControllerUtil.buildResponseEntity(mode);
    }

    @GetMapping("/detect-mode")
    @Operation(summary = "Get current shadow AI detection mode")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Detection mode retrieved successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<String>> getDetectMode() {
        String mode = shadowAiService.getDetectMode();
        return ControllerUtil.buildResponseEntity(mode);
    }
}
