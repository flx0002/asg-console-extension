/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import lombok.Data;

/**
 * Whole-machine config backup operation log (IR-028).
 */
@Data
@Entity
@Table(name = "config_backup_log")
public class ConfigBackupLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** export / import. */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    /** Backup file name (export) or uploaded file name (import). */
    @Column(name = "file_name", length = 255)
    private String fileName;

    /** Number of config domains included in the backup file. */
    @Column(name = "domain_count")
    private Integer domainCount;

    @Column(name = "operator", length = 64)
    private String operator;

    /** Result: success / failed. */
    @Column(name = "result", length = 16)
    private String result;

    @Column(name = "message", length = 512)
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
