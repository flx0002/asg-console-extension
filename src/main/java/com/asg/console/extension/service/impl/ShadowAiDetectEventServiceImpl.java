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
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.asg.console.extension.model.ShadowAiDetectEvent;
import com.asg.console.extension.repository.ShadowAiDetectEventRepository;
import com.alibaba.higress.sdk.exception.ValidationException;

import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of {@link ShadowAiDetectEventService}.
 */
@Slf4j
@Service
public class ShadowAiDetectEventServiceImpl implements ShadowAiDetectEventService {

    private ShadowAiDetectEventRepository eventRepository;

    @Resource
    public void setEventRepository(ShadowAiDetectEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    public List<ShadowAiDetectEvent> saveEvents(List<ShadowAiDetectEvent> events) {
        if (events == null || events.isEmpty()) {
            return new ArrayList<>();
        }
        LocalDateTime now = LocalDateTime.now();
        for (ShadowAiDetectEvent event : events) {
            if (event.getEventTime() == null) {
                event.setEventTime(now);
            }
            if (event.getCreatedAt() == null) {
                event.setCreatedAt(now);
            }
        }
        List<ShadowAiDetectEvent> saved = eventRepository.saveAll(events);
        log.info("Persisted {} shadow AI detect events to MySQL", saved.size());
        return saved;
    }

    @Override
    public Page<ShadowAiDetectEvent> query(String domain, String status, String category, String riskLevel,
        String source, int page, int size) {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0 || size > 500) {
            size = 20;
        }
        Specification<ShadowAiDetectEvent> spec = buildSpec(domain, status, category, riskLevel, source);
        return eventRepository.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "eventTime")));
    }

    @Override
    public long count(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || start.isAfter(end)) {
            throw new ValidationException("Invalid time range for event count");
        }
        return eventRepository.findByEventTimeBetween(start, end).size();
    }

    private Specification<ShadowAiDetectEvent> buildSpec(String domain, String status, String category,
        String riskLevel, String source) {
        return (root, query, cb) -> {
            List<javax.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (domain != null && !StringUtils.isBlank(domain)) {
                predicates.add(cb.like(root.get("domain"), "%" + domain.trim() + "%"));
            }
            if (status != null && !StringUtils.isBlank(status)) {
                predicates.add(cb.equal(root.get("status"), status.trim()));
            }
            if (category != null && !StringUtils.isBlank(category)) {
                predicates.add(cb.equal(root.get("category"), category.trim()));
            }
            if (riskLevel != null && !StringUtils.isBlank(riskLevel)) {
                predicates.add(cb.equal(root.get("riskLevel"), riskLevel.trim()));
            }
            if (source != null && !StringUtils.isBlank(source)) {
                predicates.add(cb.equal(root.get("source"), source.trim()));
            }
            return cb.and(predicates.toArray(new javax.persistence.criteria.Predicate[0]));
        };
    }
}
