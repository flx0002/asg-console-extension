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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.asg.console.extension.model.AiShadowDetectEvent;
import com.asg.console.extension.repository.AiShadowDetectEventRepository;
import com.alibaba.higress.sdk.exception.ValidationException;

import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of {@link AiShadowDetectEventService}.
 */
@Slf4j
@Service
public class AiShadowDetectEventServiceImpl implements AiShadowDetectEventService {

    private AiShadowDetectEventRepository eventRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Resource
    public void setEventRepository(AiShadowDetectEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    public List<AiShadowDetectEvent> saveEvents(List<AiShadowDetectEvent> events) {
        if (events == null || events.isEmpty()) {
            return new ArrayList<>();
        }
        LocalDateTime now = LocalDateTime.now();
        for (AiShadowDetectEvent event : events) {
            if (event.getEventTime() == null) {
                event.setEventTime(now);
            }
            if (event.getCreatedAt() == null) {
                event.setCreatedAt(now);
            }
        }
        List<AiShadowDetectEvent> saved = eventRepository.saveAll(events);
        log.info("Persisted {} shadow AI detect events to MySQL", saved.size());
        return saved;
    }

    /**
     * 事件明细按会话折叠后分页：一条记录 = 一个告警，命中数在 hitCount 里补偿，流水本身不删。
     * 折叠与计数都在 SQL 里完成，避免把整张表拉进 JVM 做内存分页——数据面关闭 60s 去重后
     * 流水量会明显增长。筛选作用在折叠后的会话行上（折叠后过滤），所以 hitCount 是会话内
     * 全部流水数、不随筛选变化；域名/IP 按会话代表值匹配，原因按会话内最近非空值匹配并展示。
     */
    @Override
    public Page<AiShadowDetectEvent> query(String domain, String status, String category, String riskLevel,
        String source, String surface, String reason, String srcIp, Long startTime, Long endTime, int page,
        int size) {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0 || size > 500) {
            size = 20;
        }
        // 空串 = 不过滤：SQL 用 (:x = '' OR ...) 统一处理，避免动态拼 SQL 带来注入面。
        String fDomain = StringUtils.trimToEmpty(domain);
        String fStatus = StringUtils.trimToEmpty(status);
        String fCategory = StringUtils.trimToEmpty(category);
        String fRisk = StringUtils.trimToEmpty(riskLevel);
        String fSource = StringUtils.trimToEmpty(source);
        String fSurface = StringUtils.trimToEmpty(surface);
        String fReason = StringUtils.trimToEmpty(reason);
        String fSrcIp = StringUtils.trimToEmpty(srcIp);
        Long startSec = epochSeconds(startTime);
        Long endSec = epochSeconds(endTime);
        List<Object[]> rows = eventRepository.aggregateSessions(startSec, endSec, fDomain, fStatus, fCategory,
            fRisk, fSource, fSurface, fReason, fSrcIp, page * size, size);
        // COUNT(*) 在 MySQL 原生查询里可能返回 BigInteger，声明成 Number 避免 ClassCastException。
        Number total = eventRepository.countSessions(startSec, endSec, fDomain, fStatus, fCategory, fRisk,
            fSource, fSurface, fReason, fSrcIp);
        List<Long> ids = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            if (row != null && row.length >= 7 && row[0] != null) {
                ids.add(((Number) row[0]).longValue());
            }
        }
        Map<Long, AiShadowDetectEvent> byId = new HashMap<>();
        for (AiShadowDetectEvent event : eventRepository.findAllById(ids)) {
            if (event.getId() != null) {
                byId.put(event.getId(), event);
            }
        }
        List<AiShadowDetectEvent> content = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            if (row == null || row.length < 7 || row[0] == null) {
                continue;
            }
            AiShadowDetectEvent event = byId.get(((Number) row[0]).longValue());
            if (event == null) {
                continue;
            }
            // 只写 transient 字段：findAllById 取回的是实体，改持久化字段有被 flush 回库的风险。
            event.setHitCount(numberOrZero(row[1]));
            event.setFirstSeen(epochMillis(row[2]));
            event.setLastSeen(epochMillis(row[3]));
            event.setSessionRiskLevel(text(row[4]));
            event.setSessionStatus(text(row[5]));
            event.setSessionReason(text(row[6]));
            content.add(event);
        }
        return new PageImpl<>(content, PageRequest.of(page, size), total == null ? 0L : total.longValue());
    }

    private static Long epochSeconds(Long epochMillis) {
        return epochMillis == null || epochMillis <= 0L ? null : epochMillis / 1000L;
    }

    private static long numberOrZero(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static Long epochMillis(Object value) {
        return value instanceof Number ? ((Number) value).longValue() * 1000L : null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void attachAuditLinks(List<AiShadowDetectEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        Set<String> domains = new HashSet<>();
        Set<String> srcIps = new HashSet<>();
        Set<String> sessionIds = new HashSet<>();
        for (AiShadowDetectEvent event : events) {
            if (StringUtils.isNotBlank(event.getSessionId())) {
                // Gateway-side event: link to audit log by session (strong link).
                sessionIds.add(event.getSessionId());
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
                    "SELECT src_ip, COUNT(*), MAX(event_time) FROM ai_shadow_detect_event "
                        + "WHERE src_ip IN (:ips) GROUP BY src_ip")
                    .setParameter("ips", srcIps).getResultList();
                for (Object[] row : rows) {
                    hostByIp.put(String.valueOf(row[0]), row);
                }
            } catch (Exception e) {
                log.warn("Failed to aggregate same-source events for ips {}, degrade to no link", srcIps, e);
            }
        }
        Map<String, Object[]> auditBySession = new HashMap<>();
        if (!sessionIds.isEmpty()) {
            try {
                List<Object[]> rows = entityManager.createNativeQuery(
                    "SELECT session_id, event_id, timestamp_ms FROM agent_audit_log "
                        + "WHERE session_id IN (:sids) ORDER BY timestamp_ms DESC LIMIT 1000")
                    .setParameter("sids", sessionIds).getResultList();
                for (Object[] row : rows) {
                    auditBySession.putIfAbsent(String.valueOf(row[0]),
                        new Object[]{row[0], row[1], ((Number) row[2]).longValue()});
                }
            } catch (Exception e) {
                log.warn("Failed to query audit logs for sessions {}, degrade to no link",
                    sessionIds, e);
            }
        }

        for (AiShadowDetectEvent event : events) {
            if (StringUtils.isNotBlank(event.getSessionId())) {
                // IR-027: strong Session-level link to the full-path audit log.
                Map<String, Object> sessionLink = new HashMap<>();
                sessionLink.put("linkType", "session");
                Object[] aud = auditBySession.get(event.getSessionId());
                if (aud != null) {
                    sessionLink.put("auditSessionId", String.valueOf(aud[0]));
                    sessionLink.put("auditEventId", String.valueOf(aud[1]));
                    sessionLink.put("auditTimeMs", ((Number) aud[2]).longValue());
                    sessionLink.put("handlingAudited", true);
                } else {
                    sessionLink.put("handlingAudited", false);
                }
                event.setAuditLink(sessionLink);
                continue;
            }
            // bypass/dns events carry no session (different traffic path):
            // fall back to host-level aggregation and mark the link as weak.
            Map<String, Object> link = new HashMap<>();
            link.put("linkType", "host-aggregate");
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

}
