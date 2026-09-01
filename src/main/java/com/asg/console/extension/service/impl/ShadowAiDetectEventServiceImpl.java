/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

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

    @PersistenceContext
    private EntityManager entityManager;

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
    @SuppressWarnings("unchecked")
    public void attachAuditLinks(List<ShadowAiDetectEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        Set<String> domains = new HashSet<>();
        Set<String> srcIps = new HashSet<>();
        for (ShadowAiDetectEvent event : events) {
            if (StringUtils.isNotBlank(event.getSessionId())) {
                // Gateway-side event: already strongly linked via sessionId.
                continue;
            }
            if (StringUtils.isNotBlank(event.getDomain())) {
                domains.add(event.getDomain());
            }
            if (StringUtils.isNotBlank(event.getSrcIp())) {
                srcIps.add(event.getSrcIp());
            }
        }
        Map<String, Object[]> auditByDomain = new HashMap<>();
        if (!domains.isEmpty()) {
            try {
                // action/consumer_name live inside raw_json (no dedicated columns),
                // so filter coarsely by LIKE and parse precisely in Java.
                List<Object[]> rows = entityManager.createNativeQuery(
                    "SELECT raw_json, timestamp_ms FROM agent_audit_log "
                        + "WHERE raw_json LIKE '%authorize_domain%' "
                        + "ORDER BY timestamp_ms DESC LIMIT 500")
                    .getResultList();
                for (Object[] row : rows) {
                    String json = String.valueOf(row[0]);
                    long ts = ((Number) row[1]).longValue();
                    String action = extractJsonField(json, "action");
                    String domain = extractJsonField(json, "consumer_name");
                    String eventId = extractJsonField(json, "event_id");
                    if (domain == null || (!"authorize_domain".equals(action)
                        && !"deauthorize_domain".equals(action))
                        || !domains.contains(domain)) {
                        continue;
                    }
                    // rows are DESC by time: keep the latest per domain.
                    auditByDomain.putIfAbsent(domain,
                        new Object[]{domain, action, eventId, ts});
                }
            } catch (Exception e) {
                log.warn("Failed to query handling audit for domains {}, degrade to no link", domains, e);
            }
        }
        Map<String, Object[]> hostByIp = new HashMap<>();
        if (!srcIps.isEmpty()) {
            try {
                List<Object[]> rows = entityManager.createNativeQuery(
                    "SELECT src_ip, COUNT(*), MAX(event_time) FROM shadow_ai_detect_event "
                        + "WHERE src_ip IN (:ips) GROUP BY src_ip")
                    .setParameter("ips", srcIps).getResultList();
                for (Object[] row : rows) {
                    hostByIp.put(String.valueOf(row[0]), row);
                }
            } catch (Exception e) {
                log.warn("Failed to aggregate same-source events for ips {}, degrade to no link", srcIps, e);
            }
        }
        for (ShadowAiDetectEvent event : events) {
            if (StringUtils.isNotBlank(event.getSessionId())) {
                continue;
            }
            Map<String, Object> link = new HashMap<>();
            Object[] aud = auditByDomain.get(event.getDomain());
            if (aud != null) {
                link.put("handlingAudited", true);
                link.put("auditAction", String.valueOf(aud[1]));
                link.put("auditEventId", String.valueOf(aud[2]));
                link.put("auditTimeMs", ((Number) aud[3]).longValue());
            } else {
                link.put("handlingAudited", false);
            }
            Object[] host = hostByIp.get(event.getSrcIp());
            if (host != null) {
                link.put("hostEventCount", ((Number) host[1]).longValue());
                if (host[2] instanceof Timestamp) {
                    link.put("hostLastEventMs", ((Timestamp) host[2]).getTime());
                }
            }
            event.setAuditLink(link);
        }
    }

    /** Minimal string-field extractor for flat audit JSON entries. */
    private static String extractJsonField(String json, String field) {
        if (json == null) {
            return null;
        }
        String key = "\"" + field + "\":";
        int i = json.indexOf(key);
        if (i < 0) {
            return null;
        }
        int start = json.indexOf('"', i + key.length());
        if (start < 0) {
            return null;
        }
        int end = json.indexOf('"', start + 1);
        return end < 0 ? null : json.substring(start + 1, end);
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
