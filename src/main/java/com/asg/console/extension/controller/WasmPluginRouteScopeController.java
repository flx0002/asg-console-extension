/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.controller;

import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Resource;
import javax.validation.constraints.NotBlank;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.higress.sdk.model.WasmPluginInstanceScope;
import com.alibaba.higress.sdk.service.WasmPluginInstanceService;
import com.asg.console.extension.controller.dto.Response;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Batch query of route-level plugin scopes for the console frontend. The
 * upstream WasmPluginInstancesController only exposes per-route instance reads,
 * which the "route scope" selector called once per route (N+1). This single
 * endpoint reads the plugin CR once and returns the enabled, non-internal
 * ROUTE-scope target names, replacing that loop.
 */
@RestController("WasmPluginRouteScopeController")
@RequestMapping("/v1")
@Validated
@Tag(name = "Wasm Plugin Route Scope APIs")
public class WasmPluginRouteScopeController {

    private WasmPluginInstanceService wasmPluginInstanceService;

    @Resource
    public void setWasmPluginInstanceService(WasmPluginInstanceService wasmPluginInstanceService) {
        this.wasmPluginInstanceService = wasmPluginInstanceService;
    }

    @GetMapping(value = "/wasm-plugins/{name}/route-scopes")
    @Operation(summary = "List route names that have an enabled route-level instance for the given plugin")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Route scopes listed successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<List<String>>> listPluginRouteScopes(
        @PathVariable("name") @NotBlank String pluginName) {
        // Single CR read (listWasmPlugin) flattened into instances, filtered to enabled ROUTE-scope targets.
        // Replaces the per-route GET /routes/{r}/plugin-instances/{name} loop used by the console frontend,
        // which was an N+1 that got painful once route count grew large.
        List<String> routes = wasmPluginInstanceService.list(pluginName).stream()
            .filter(instance -> !instance.isInternal())
            .filter(instance -> Boolean.TRUE.equals(instance.getEnabled()))
            .filter(instance -> instance.getTargets() != null
                && instance.getTargets().containsKey(WasmPluginInstanceScope.ROUTE))
            .map(instance -> instance.getTargets().get(WasmPluginInstanceScope.ROUTE))
            .filter(StringUtils::isNotEmpty)
            .distinct()
            .collect(Collectors.toList());
        return ResponseEntity.ok(Response.success(routes));
    }
}
