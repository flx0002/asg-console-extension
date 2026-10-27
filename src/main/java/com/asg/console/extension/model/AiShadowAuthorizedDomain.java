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
 * Metadata for an authorized shadow AI domain (note / origin / operator / authorization time).
 * <p>
 * The authoritative set of authorized domains remains {@code ai_shadow_dns_policy.authorized_domains},
 * because the DNS collector reads it through {@code GET /v1/ai-shadow/dns-policy}. This table only
 * carries the extra columns the management table needs, so a missing row can never change an
 * authorization decision — the read path degrades to "imported" metadata instead.
 */
@Data
@Entity
@Table(name = "ai_shadow_authorized_domain")
public class AiShadowAuthorizedDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalized domain: lower case, without a leading {@code *.} wildcard. */
    @Column(name = "domain", nullable = false, unique = true, length = 255)
    private String domain;

    /** Free-form note shown in the management table. */
    @Column(name = "note", length = 255)
    private String note;

    /** {@code manual} = added from the console form; {@code detected} = authorized from a detection row. */
    @Column(name = "source", length = 32)
    private String source;

    /** Operator identity, or {@code console} when the request carries none. */
    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
