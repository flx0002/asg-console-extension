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
import javax.persistence.Index;
import javax.persistence.Table;

import lombok.Data;

/**
 * Config version history entry (IR-028).
 *
 * <p>Recorded whenever a user saves configuration via console write APIs.
 * Each config object (category + objectKey) keeps at most {@code MAX_VERSIONS}
 * rolling versions. The payload envelope stores the controller method reference
 * and arguments so that a version can be replayed (rollback / restore) without
 * per-domain dispatcher code.
 *
 * <p>Payload envelope JSON structure:
 * <pre>{@code
 * {
 *   "controller": "com.alibaba.higress.console.controller.RoutesController",
 *   "method": "update",
 *   "paramTypes": ["java.lang.String", "com.alibaba.higress.sdk.model.Route"],
 *   "args": [{"type": "...", "value": {...}}, ...]
 * }
 * }</pre>
 */
@Data
@Entity
@Table(name = "config_version_history", indexes = {
    @Index(name = "idx_cvh_object", columnList = "category, object_key"),
    @Index(name = "idx_cvh_object_version", columnList = "category, object_key, version_id", unique = true)
})
public class ConfigVersionHistory {

    /** Rolling versions kept per config object. */
    public static final int MAX_VERSIONS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Config domain, e.g. plugin-instance / ai-route / route / domain / consumer / system / ai-shadow. */
    @Column(name = "category", nullable = false, length = 64)
    private String category;

    /** Object identity within the domain, e.g. global/ai-prompt-guard or route/demo-llm-route. */
    @Column(name = "object_key", nullable = false, length = 255)
    private String objectKey;

    /** Version number, increments within (category, objectKey). */
    @Column(name = "version_id", nullable = false)
    private Long versionId;

    /** Controller-method-replay envelope (see class javadoc). */
    @Column(name = "payload_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String payloadJson;

    /** Operator name from session; "unknown" when unavailable. */
    @Column(name = "operator", length = 64)
    private String operator;

    /** save / delete / rollback / import / restore. */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
