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
import java.util.Map;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.Transient;

import lombok.Data;

/**
 * Shadow AI detection event, persisted to MySQL for long-term retention
 * and correlation with audit chain via session ID (IR-025).
 */
@Data
@Entity
@Table(name = "ai_shadow_detect_event", indexes = {
    @Index(name = "idx_sade_event_time", columnList = "event_time"),
    @Index(name = "idx_sade_domain", columnList = "domain"),
    @Index(name = "idx_sade_status", columnList = "status"),
    @Index(name = "idx_sade_session", columnList = "session_id"),
    // 准入事件查询固定带 surface + 时间窗，单列 event_time 索引会先扫两个 tab 的并集再过滤。
    @Index(name = "idx_sade_surface_time", columnList = "surface,event_time")
})
public class AiShadowDetectEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Detection time of the event. */
    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    /** Detection type, e.g. dns_ai_shadow. */
    @Column(name = "detect_type", nullable = false, length = 64)
    private String detectType;

    /** Access domain of the shadow AI service. */
    @Column(name = "domain", nullable = false, length = 255)
    private String domain;

    /** AI service category, e.g. saas_ai / embedded_ai / ai_agent. */
    @Column(name = "category", length = 64)
    private String category;

    /** Risk level, e.g. high / medium / critical. */
    @Column(name = "risk_level", length = 16)
    private String riskLevel;

    /** Handling result, e.g. allowed / blocked. */
    @Column(name = "status", length = 16)
    private String status;

    /**
     * Which view this event belongs to: {@code route} (access control on our own channels) or
     * {@code shadow} (shadow AI discovery). Single source of truth for tab membership — the UI must
     * never re-derive it from detectType. Nullable only for rows written before the column existed.
     */
    @Column(name = "surface", length = 16)
    private String surface;

    /** Verdict, {@code shadow} or {@code non_shadow}. Only shadow verdicts are reported today. */
    @Column(name = "verdict", length = 16)
    private String verdict;

    /**
     * Reason behind the verdict, e.g. off_channel / no_consumer / consumer_not_allowed. key-auth
     * appends the consumer name ({@code not_in_allow:<name>}), hence 128 rather than the 32 the
     * bare enum would need — a shorter column makes the insert fail and loses the whole event.
     */
    @Column(name = "reason", length = 128)
    private String reason;

    /** Human-readable channel match hint, e.g. {@code model=mock-llm}. Display only. */
    @Column(name = "route_hint", length = 255)
    private String routeHint;

    /** Event source, e.g. dns / gateway. */
    @Column(name = "source", length = 32)
    private String source;

    /** Client source IP. */
    @Column(name = "src_ip", length = 64)
    private String srcIp;

    /** Correlated audit chain session ID (may be null for DNS events). */
    @Column(name = "session_id", length = 128)
    private String sessionId;

    /** Extra detail as JSON text. */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    /** Record creation time. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Weak audit-chain link for bypass/dns events (IR-025/S5, not persisted):
     * {handlingAudited, auditAction, auditEventId, auditTimeMs} for the domain
     * handling audit, and {hostEventCount, hostLastEventMs} for same-source
     * host aggregation. Gateway-side events are linked via sessionId instead.
     */
    @Transient
    private Map<String, Object> auditLink;
}
