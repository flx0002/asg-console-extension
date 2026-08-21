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
import javax.persistence.Id;
import javax.persistence.Table;

import lombok.Data;

/**
 * Shadow AI DNS detection policy (single-row table), controls monitoring /
 * enforcement mode and authorized domains for the DNS collector (IR-004).
 */
@Data
@Entity
@Table(name = "shadow_ai_dns_policy")
public class ShadowAiDnsPolicy {

    public static final String MODE_MONITORING = "monitoring";
    public static final String MODE_ENFORCEMENT = "enforcement";

    @Id
    private Long id = 1L;

    /** monitoring (record only) or enforcement (block unauthorized). */
    @Column(name = "mode", nullable = false, length = 16)
    private String mode = MODE_MONITORING;

    /** Comma separated domains that are allowed even in enforcement mode. */
    @Column(name = "authorized_domains", columnDefinition = "TEXT")
    private String authorizedDomains;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
