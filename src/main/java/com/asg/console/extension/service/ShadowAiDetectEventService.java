/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;

import com.asg.console.extension.model.ShadowAiDetectEvent;

/**
 * Service for shadow AI detection event persistence and query.
 */
public interface ShadowAiDetectEventService {

    List<ShadowAiDetectEvent> saveEvents(List<ShadowAiDetectEvent> events);

    Page<ShadowAiDetectEvent> query(String domain, String status, String category, String riskLevel, String source,
        int page, int size);

    /**
     * Attach weak audit-chain links to bypass/dns events (IR-025/S5):
     * domain handling audit (authorize/deauthorize_domain) and same-source
     * host aggregation. Gateway-side events keep their sessionId link.
     */
    void attachAuditLinks(List<ShadowAiDetectEvent> events);

    long count(LocalDateTime start, LocalDateTime end);
}
