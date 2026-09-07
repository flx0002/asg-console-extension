/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.configversion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.asg.console.extension.controller.util.ControllerUtil;
import com.asg.console.extension.model.ConfigBackupLog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Config snapshot endpoints (IR-028): whole-machine snapshot list / manual
 * snapshot / restore, plus the encrypted whole-machine backup file export /
 * import and operation logs.
 */
@Tag(name = "ConfigVersion", description = "Product-level whole-machine config snapshots and encrypted backup")
@RestController
@RequestMapping("/v1/config-version")
public class ConfigVersionController {

    private final ConfigBackupService backupService;

    public ConfigVersionController(ConfigBackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping("/snapshots")
    @Operation(summary = "List whole-machine config snapshots (newest first, at most 5)")
    public ResponseEntity<?> listSnapshots() {
        return ControllerUtil.buildResponseEntity(backupService.listSnapshots());
    }

    @PostMapping("/snapshots")
    @Operation(summary = "Take a whole-machine config snapshot right now")
    public ResponseEntity<?> takeSnapshot() {
        int count = backupService.takeSnapshot("manual", "admin");
        Map<String, Integer> result = new HashMap<>();
        result.put("objects", count);
        return ControllerUtil.buildResponseEntity(result);
    }

    @PostMapping("/snapshots/{id}/restore")
    @Operation(summary = "Restore the whole-machine config from a stored snapshot (pre-restore snapshot is taken first)")
    public ResponseEntity<?> restoreSnapshot(@PathVariable("id") Long id) {
        int restored = backupService.restoreSnapshot(id);
        Map<String, Integer> result = new HashMap<>();
        result.put("restoredObjects", restored);
        return ControllerUtil.buildResponseEntity(result);
    }

    @GetMapping("/backup/export")
    @Operation(summary = "Export the whole-machine config as an encrypted backup string")
    public ResponseEntity<?> exportBackup() {
        return ControllerUtil.buildResponseEntity(backupService.export());
    }

    @PostMapping("/backup/import")
    @Operation(summary = "Restore the whole-machine config from an encrypted backup string")
    public ResponseEntity<?> importBackup(@RequestBody Map<String, String> body) {
        String content = body == null ? null : body.get("content");
        if (content == null || content.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        int restored = backupService.importBackup(content);
        Map<String, Integer> result = new HashMap<>();
        result.put("restoredObjects", restored);
        return ControllerUtil.buildResponseEntity(result);
    }

    @GetMapping("/backup/logs")
    @Operation(summary = "List recent snapshot/backup operations (newest first)")
    public ResponseEntity<?> backupLogs() {
        List<ConfigBackupLog> logs = backupService.listLogs();
        return ControllerUtil.buildResponseEntity(logs);
    }
}
