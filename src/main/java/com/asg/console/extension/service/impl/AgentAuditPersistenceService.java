/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.asg.console.extension.model.AgentAuditLog;
import com.asg.console.extension.repository.AgentAuditLogRepository;
import com.asg.console.extension.service.AuditLogSink;

import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;

/**
 * Asynchronous MySQL persistence for audit chain entries (IR-015).
 *
 * <p>Implements {@link AuditLogSink} with a bounded in-memory queue and a
 * single background flusher that batches inserts (batch size or fixed
 * interval, whichever comes first). The write path back to Redis is never
 * blocked: {@link #sink} only offers to the queue.</p>
 *
 * <p>Outage fallback: while MySQL is unavailable, entries stay in the queue
 * and are re-inserted with exponential backoff after recovery; if the queue
 * fills up the oldest entries are dropped and counted for alerting. Duplicate
 * event ids (e.g. from the Redis sync racing the collector) are filtered
 * before insert and additionally protected by the unique index.</p>
 */
@Slf4j
@Service
public class AgentAuditPersistenceService implements AuditLogSink {

    /** Max entries buffered in memory while MySQL is unavailable. */
    private static final int QUEUE_CAPACITY = 5000;
    /** Flush when this many entries are buffered. */
    private static final int FLUSH_BATCH_SIZE = 200;
    /** Periodic flush interval in milliseconds. */
    private static final long FLUSH_INTERVAL_MS = 5000;
    /** Initial retry backoff in milliseconds, doubling up to the cap. */
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 60000;

    /** Redis connection used only to refresh the payload mode (falls back to the SDK defaults). */
    private static final String REDIS_HOST_DEFAULT = "redis-stack-server.higress-system.svc.cluster.local";
    private static final int REDIS_PORT_DEFAULT = 6379;
    private static final String AUDIT_CONFIG_KEY = "agent_audit_config";
    private static final String PAYLOAD_MODE_FIELD = "payload_mode";
    /** Payload modes (IR-015): full keeps everything, summary strips request/response bodies, none keeps metadata only. */
    private static final String PAYLOAD_MODE_FULL = "full";
    private static final String PAYLOAD_MODE_SUMMARY = "summary";
    private static final String PAYLOAD_MODE_NONE = "none";
    /** Config refresh interval in milliseconds; changes take effect for newly written entries. */
    private static final long CONFIG_REFRESH_INTERVAL_MS = 30000;
    /** Fields removed in summary mode; metadata (hash/size) is kept. */
    private static final String[] SUMMARY_STRIP_FIELDS = {"request_body_content", "response_body_content"};
    /** Retention (IR-057): delete expired rows in bounded batches to keep transactions short. */
    private static final int DELETE_BATCH_SIZE = 500;
    private static final long CLEANUP_INTERVAL_MS = 3600000;
    private static final int DEFAULT_MAX_DAYS = 7;
    /** Admin-operation audit retention, independent of data-plane max_days. */
    private static final int DEFAULT_ADMIN_MAX_DAYS = 180;

    private AgentAuditLogRepository repository;

    /** Current payload mode, refreshed from Redis; never throws on refresh failure. */
    private volatile String payloadMode = PAYLOAD_MODE_FULL;
    private volatile long lastConfigRefreshMs;

    private final BlockingQueue<AgentAuditLog> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ScheduledExecutorService flusher =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-mysql-flusher");
            t.setDaemon(true);
            return t;
        });
    private final ScheduledExecutorService retentionCleaner =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-mysql-retention");
            t.setDaemon(true);
            return t;
        });

    /** Entries dropped because the queue was full (for alerting/recovery log). */
    private final AtomicLong droppedCounter = new AtomicLong();
    /** Consecutive flush failures, reset on success. */
    private final AtomicLong consecutiveFailures = new AtomicLong();
    private volatile long backoffMs = INITIAL_BACKOFF_MS;

    @Resource
    public void setRepository(AgentAuditLogRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void start() {
        refreshPayloadMode();
        flusher.scheduleWithFixedDelay(this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        // Retention policy (IR-057): delete MySQL rows older than max_days once per hour.
        retentionCleaner.scheduleWithFixedDelay(this::cleanupExpiredLogs, CLEANUP_INTERVAL_MS,
            CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("AgentAuditPersistenceService started (queue={}, batch={}, interval={}ms, payload_mode={})",
            QUEUE_CAPACITY, FLUSH_BATCH_SIZE, FLUSH_INTERVAL_MS, payloadMode);
    }

    @PreDestroy
    public void stop() {
        flusher.shutdown();
        retentionCleaner.shutdown();
        // Final drain attempt before shutdown (best effort).
        flush();
    }

    @Override
    public void sink(String auditEntryJson, String eventId, String sessionId, long timestampMs,
        String userId, boolean identityTrusted, String agentId) {
        // Refresh the payload mode first so the entry below is stored with the
        // latest configuration (mode changes apply to newly written entries).
        maybeRefreshPayloadMode();
        AgentAuditLog entity = new AgentAuditLog();
        entity.setEventId(eventId);
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setIdentityTrusted(identityTrusted);
        entity.setAgentId(agentId);
        entity.setTimestampMs(timestampMs);
        entity.setCreatedAt(LocalDateTime.now());
        fillFromPayload(entity, auditEntryJson);
        entity.setRawJson(applyPayloadMode(auditEntryJson));

        if (!queue.offer(entity)) {
            // Queue full: drop the oldest to keep the newest, count for alerting.
            AgentAuditLog dropped = queue.poll();
            if (dropped != null) {
                droppedCounter.incrementAndGet();
            }
            if (!queue.offer(entity)) {
                droppedCounter.incrementAndGet();
                log.warn("Audit sink queue full, dropping event_id={}", eventId);
                return;
            }
        }
        if (queue.size() >= FLUSH_BATCH_SIZE) {
            flush();
        }
    }

    /**
     * Apply the configured payload mode to the stored raw JSON (IR-015):
     * full keeps the entry as-is, summary removes the request/response body
     * content while keeping all metadata, none stores metadata only.
     */
    private String applyPayloadMode(String auditEntryJson) {
        String mode = this.payloadMode;
        if (auditEntryJson == null || PAYLOAD_MODE_FULL.equals(mode)) {
            return auditEntryJson;
        }
        try {
            JSONObject obj = JSON.parseObject(auditEntryJson);
            if (obj == null) {
                return PAYLOAD_MODE_NONE.equals(mode) ? null : auditEntryJson;
            }
            if (PAYLOAD_MODE_NONE.equals(mode)) {
                return null;
            }
            for (String field : SUMMARY_STRIP_FIELDS) {
                obj.remove(field);
            }
            return obj.toJSONString();
        } catch (Exception e) {
            // Malformed payload: never block the audit write path.
            return auditEntryJson;
        }
    }

    /**
     * Refresh the payload mode from Redis at most once per interval. A failed
     * refresh keeps the previous mode; the sink must never fail because of it.
     */
    private void maybeRefreshPayloadMode() {
        long now = System.currentTimeMillis();
        if (now - lastConfigRefreshMs < CONFIG_REFRESH_INTERVAL_MS) {
            return;
        }
        lastConfigRefreshMs = now;
        refreshPayloadMode();
    }

    private void refreshPayloadMode() {
        try (Jedis jedis = new Jedis(REDIS_HOST_DEFAULT, REDIS_PORT_DEFAULT)) {
            String mode = jedis.hget(AUDIT_CONFIG_KEY, PAYLOAD_MODE_FIELD);
            if (mode != null && !mode.isEmpty()) {
                if (PAYLOAD_MODE_FULL.equals(mode) || PAYLOAD_MODE_SUMMARY.equals(mode) || PAYLOAD_MODE_NONE.equals(mode)) {
                    payloadMode = mode;
                } else {
                    log.warn("Unknown payload_mode={} in Redis, keeping {}", mode, payloadMode);
                }
            }
        } catch (Exception e) {
            // Redis unreachable: keep the previous mode, the collector/sync path is unaffected.
            log.debug("Payload mode refresh failed, keeping {}", payloadMode);
        }
    }

    /**
     * Apply the retention policy (IR-057): delete MySQL rows whose timestamp is
     * older than max_days, in bounded batches to keep transactions short.
     * max_days is read from Redis (falls back to the default). Values <= 0
     * disable MySQL-side cleanup.
     *
     * @return number of deleted rows
     */
    public long cleanupExpiredLogs() {
        int maxDays = readMaxDaysFromRedis();
        long now = System.currentTimeMillis();
        long total = 0;
        if (maxDays > 0) {
            total += deleteInBatches(now - maxDays * 86400_000L, false);
        }
        // admin_operation rows are excluded from the statement above and expire on their own
        // (longer) window, so console actions stay auditable after the data-plane retention.
        int adminMaxDays = readAdminMaxDaysFromRedis();
        if (adminMaxDays > 0) {
            total += deleteInBatches(now - adminMaxDays * 86400_000L, true);
        }
        return total;
    }

    private long deleteInBatches(long cutoff, boolean adminOnly) {
        long total = 0;
        try {
            int batch;
            do {
                batch = adminOnly
                    ? repository.deleteExpiredAdminBatch(cutoff, DELETE_BATCH_SIZE)
                    : repository.deleteExpiredBatch(cutoff, DELETE_BATCH_SIZE);
                total += batch;
            } while (batch >= DELETE_BATCH_SIZE);
            if (total > 0) {
                log.info("MySQL audit retention cleanup removed {} entries (admin_only={})", total, adminOnly);
            }
        } catch (Exception e) {
            // Cleanup must never take the service down; the next cycle retries.
            log.error("MySQL audit retention cleanup failed (admin_only={})", adminOnly, e);
        }
        return total;
    }

    private int readAdminMaxDaysFromRedis() {
        try (Jedis jedis = new Jedis(REDIS_HOST_DEFAULT, REDIS_PORT_DEFAULT)) {
            String v = jedis.hget(AUDIT_CONFIG_KEY, "admin_max_days");
            if (v != null && !v.isEmpty()) {
                int days = Integer.parseInt(v);
                return Math.min(Math.max(days, 1), 3650);
            }
        } catch (Exception e) {
            log.debug("Failed to read admin_max_days from Redis, using default {}", DEFAULT_ADMIN_MAX_DAYS);
        }
        return DEFAULT_ADMIN_MAX_DAYS;
    }

    /** Escape LIKE wildcards so a literal '%' or '_' cannot widen an audit search. */
    private static String escapeLike(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** Read-only admin-operation listing. Exports reuse this with a larger pageSize. */
    public Map<String, Object> queryAdminOperations(Long startMs, Long endMs, String action,
        String keyword, int page, int pageSize) {
        // Repository uses ESCAPE '!' on both LIKE predicates; escape here so list and
        // count stay in sync and user input is matched literally.
        String safeAction = escapeLike(action);
        String safeKeyword = escapeLike(keyword);
        int safeSize = Math.min(Math.max(pageSize, 1), 10000);
        int offset = (Math.max(page, 1) - 1) * safeSize;
        long total = repository.countAdminOperations(startMs, endMs, safeAction, safeKeyword).longValue();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] row : repository.findAdminOperations(startMs, endMs, safeAction, safeKeyword,
            offset, safeSize)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ((Number) row[0]).longValue());
            item.put("time", String.valueOf(row[1]));
            item.put("timestampMs", ((Number) row[2]).longValue());
            item.put("sessionId", row[3] == null ? "" : String.valueOf(row[3]));
            item.put("userId", row[4] == null ? "" : String.valueOf(row[4]));
            String raw = row[6] == null ? "" : String.valueOf(row[6]);
            JSONObject rawObj = parseRaw(raw);
            item.put("action", rawField(rawObj, "action"));
            item.put("clientIp", rawField(rawObj, "client_ip"));
            item.put("httpStatus", rawField(rawObj, "http_status"));
            item.put("source", rawField(rawObj, "source"));
            item.put("rawJson", sanitizeRaw(raw));
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", items);
        result.put("total", total);
        return result;
    }

    /** Parse once per row; exports can return up to 10000 rows. */
    private static JSONObject parseRaw(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return JSON.parseObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * raw_json is handed to both the list API and the JSON export, so strip fields that must
     * not leave the server: query_string may carry tokens / search keywords, error leaks the
     * exception class (tech stack), forwarded_for is caller-controlled. Keep the fields the
     * detail view needs (http_method / http_path / event_id / route_name / consumer_name /
     * mode / detail). Storage keeps the full record for accountability.
     */
    private static String sanitizeRaw(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        try {
            JSONObject obj = JSON.parseObject(raw);
            if (obj == null) {
                return raw;
            }
            obj.remove("query_string");
            obj.remove("error");
            obj.remove("forwarded_for");
            return obj.toJSONString();
        } catch (Exception e) {
            // Unparseable raw is returned as-is rather than dropped, so audit data is not lost.
            return raw;
        }
    }

    private static String rawField(JSONObject obj, String key) {
        if (obj == null) {
            return "";
        }
        Object value = obj.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private int readMaxDaysFromRedis() {
        try (Jedis jedis = new Jedis(REDIS_HOST_DEFAULT, REDIS_PORT_DEFAULT)) {
            String v = jedis.hget(AUDIT_CONFIG_KEY, "max_days");
            if (v != null && !v.isEmpty()) {
                int days = Integer.parseInt(v);
                return days > 180 ? 180 : days;
            }
        } catch (Exception e) {
            log.debug("Failed to read max_days from Redis, using default {}", DEFAULT_MAX_DAYS);
        }
        return DEFAULT_MAX_DAYS;
    }

    private void fillFromPayload(AgentAuditLog entity, String auditEntryJson) {
        try {
            JSONObject obj = JSON.parseObject(auditEntryJson);
            entity.setBlocked(obj.getBooleanValue("blocked"));
            JSONArray recordTypes = obj.getJSONArray("record_types");
            if (recordTypes != null && !recordTypes.isEmpty()) {
                entity.setRecordTypes(String.join(",", recordTypes.toJavaList(String.class)));
            }
        } catch (Exception e) {
            // Keep the raw payload as-is; extraction is best effort.
        }
    }

    /**
     * Drain up to FLUSH_BATCH_SIZE entries and persist them. On failure the
     * batch is requeued and a retry is scheduled with exponential backoff.
     */
    private void flush() {
        List<AgentAuditLog> batch = new ArrayList<>();
        queue.drainTo(batch, FLUSH_BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        try {
            List<AgentAuditLog> toSave = filterExisting(batch);
            if (!toSave.isEmpty()) {
                repository.saveAll(toSave);
            }
            consecutiveFailures.set(0);
            backoffMs = INITIAL_BACKOFF_MS;
            long dropped = droppedCounter.getAndSet(0);
            if (dropped > 0) {
                log.warn("Audit sink recovered; {} entries were dropped while MySQL was unavailable", dropped);
            }
        } catch (DataIntegrityViolationException e) {
            // saveAll runs the whole batch in one transaction: a single duplicate event_id
            // rolls every row back. The old code only logged a warning, silently dropping
            // unrelated audit entries (observed 2026-11-10: 6 of 6 rows lost, including
            // 2 console admin-operation records). Retry row by row so only genuine
            // duplicates are skipped and the rest still reach MySQL.
            int saved = 0;
            int skipped = 0;
            for (AgentAuditLog entry : filterExisting(batch)) {
                try {
                    repository.saveAndFlush(entry);
                    saved++;
                } catch (Exception rowError) {
                    skipped++;
                    log.debug("Audit sink skipped duplicate event {}", entry.getEventId(), rowError);
                }
            }
            log.warn("Audit sink duplicate batch recovered: {} saved, {} skipped", saved, skipped);
            consecutiveFailures.set(0);
            backoffMs = INITIAL_BACKOFF_MS;
        } catch (Exception e) {
            long failures = consecutiveFailures.incrementAndGet();
            long wait = Math.min(backoffMs, MAX_BACKOFF_MS);
            log.error("Audit MySQL flush failed ({} consecutive), backing off {}ms, requeueing {} entries",
                failures, wait, batch.size(), e);
            // Requeue in order; entries that no longer fit are dropped (counted).
            for (int i = batch.size() - 1; i >= 0; i--) {
                if (!queue.offer(batch.get(i))) {
                    droppedCounter.incrementAndGet();
                }
            }
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            flusher.schedule(this::flush, wait, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Drop entries whose event ids are already persisted, so the unique index
     * is never the first line of defense (the Redis sync and the collector may
     * deliver the same event twice).
     */
    private List<AgentAuditLog> filterExisting(List<AgentAuditLog> batch) {
        Set<String> ids = new HashSet<>();
        for (AgentAuditLog entry : batch) {
            ids.add(entry.getEventId());
        }
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> existing = new HashSet<>();
        for (AgentAuditLog found : repository.findByEventIdIn(ids)) {
            existing.add(found.getEventId());
        }
        List<AgentAuditLog> result = new ArrayList<>();
        for (AgentAuditLog entry : batch) {
            if (!existing.contains(entry.getEventId())) {
                result.add(entry);
            }
        }
        return result;
    }
}
