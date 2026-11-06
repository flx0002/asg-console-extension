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
 * Shadow AI bypass capture configuration (single-row table), controls which
 * physical mirror ports the bypass collector (asg-bypass-guard) captures on and
 * per-port reinjection control interfaces (IR-079 mode 3).
 */
@Data
@Entity
@Table(name = "ai_shadow_capture_config")
public class AiShadowCaptureConfig {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    /**
     * Comma separated capture interface names. {@code null} means never
     * configured — the collector keeps whatever its local yaml says; an empty
     * string means explicitly configured with no ports (capture off).
     */
    @Column(name = "capture_interfaces", columnDefinition = "TEXT")
    private String captureInterfaces;

    /**
     * JSON object mapping capture interface to reinjection control interface,
     * e.g. {"enp3s0":"enp3s0","enp4s0":""}. An empty value means "do not
     * inject" on that port; a missing key defaults to the same port.
     */
    @Column(name = "control_interfaces", columnDefinition = "TEXT")
    private String controlInterfaces;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
