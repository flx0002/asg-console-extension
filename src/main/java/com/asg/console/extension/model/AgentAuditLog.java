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
 * Audit chain log entry persisted to MySQL for long-term retention,
 * beyond the 21-day Redis TTL (IR-015). Correlates with sessions via
 * session_id and keeps the full original payload in raw_json.
 */
@Data
@Entity
@Table(name = "agent_audit_log", indexes = {
    @Index(name = "idx_aal_session", columnList = "session_id"),
    @Index(name = "idx_aal_timestamp", columnList = "timestamp_ms")
})
public class AgentAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique event id from the audit entry (dedup key for the Redis sync). */
    @Column(name = "event_id", nullable = false, length = 64, unique = true)
    private String eventId;

    /** Session id (may be virtual degraded_XXX). */
    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    /** User id, empty for untrusted/anonymous. */
    @Column(name = "user_id", length = 128)
    private String userId;

    /** Whether the identity was trusted at write time. */
    @Column(name = "identity_trusted")
    private Boolean identityTrusted;

    /** Agent id, empty if unknown. */
    @Column(name = "agent_id", length = 128)
    private String agentId;

    /** Event time in epoch milliseconds. */
    @Column(name = "timestamp_ms", nullable = false)
    private Long timestampMs;

    /** Whether the request was blocked. */
    @Column(name = "blocked")
    private Boolean blocked;

    /** Comma-joined record types, e.g. degraded,security_event. */
    @Column(name = "record_types", length = 255)
    private String recordTypes;

    /** Full original audit entry JSON payload. */
    @Column(name = "raw_json", columnDefinition = "LONGTEXT")
    private String rawJson;

    /** Record creation time. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
