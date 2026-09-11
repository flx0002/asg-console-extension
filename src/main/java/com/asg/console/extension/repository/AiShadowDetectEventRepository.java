/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.asg.console.extension.model.AiShadowDetectEvent;

@Repository
public interface AiShadowDetectEventRepository extends JpaRepository<AiShadowDetectEvent, Long>,
    JpaSpecificationExecutor<AiShadowDetectEvent> {

    Page<AiShadowDetectEvent> findByDomainContaining(String domain, Pageable pageable);

    Page<AiShadowDetectEvent> findByStatus(String status, Pageable pageable);

    Page<AiShadowDetectEvent> findByCategory(String category, Pageable pageable);

    Page<AiShadowDetectEvent> findByRiskLevel(String riskLevel, Pageable pageable);

    Page<AiShadowDetectEvent> findBySource(String source, Pageable pageable);

    List<AiShadowDetectEvent> findByEventTimeBetween(LocalDateTime start, LocalDateTime end);
}
