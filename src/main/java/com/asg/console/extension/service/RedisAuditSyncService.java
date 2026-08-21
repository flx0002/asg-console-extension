/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * Incremental sync of audit entries from the Redis audit chain to the audit
 * log sink (MySQL persistence), covering entries written directly by Wasm
 * plugins (which bypass the stdout collector path).
 *
 * <p>Cursor semantics: scores in the audit ZSETs are {@code timestampMs * 1000}
 * (see {@link AuditChainServiceImpl#writeAuditLog}), so a single monotonically
 * increasing score cursor is safe across all session ZSETs. On every cycle we
 * re-scan {@code (cursor, +inf)} of each ZSET; entries already persisted are
 * deduplicated by event id on the sink side.</p>
 *
 * <p>Idempotency: if Redis is unavailable the cursor is simply not advanced;
 * the next successful cycle re-reads the same window and the sink's event-id
 * uniqueness filter drops duplicates.</p>
 *
 * <p>The service self-schedules an incremental sync every 30 seconds on a
 * daemon thread (mirroring the cleanup executor in
 * {@link AuditChainServiceImpl}); it requires no Spring context.</p>
 */
@Slf4j
public class RedisAuditSyncService {

    private static final String AUDIT_ZSET_PREFIX = "agent_audit:";
    private static final String AUDIT_LOG_PREFIX = "agent_audit_log:";
    private static final String AUDIT_CONFIG_KEY = "agent_audit_config";
    private static final String CLEANUP_LOCK_KEY = "agent_audit:cleanup_lock";
    private static final String AUDIT_USER_INDEX_PREFIX = "agent_audit:user:";
    private static final String AUDIT_AGENT_INDEX_PREFIX = "agent_audit:agent:";
    private static final String SYNC_CURSOR_KEY = "agent_audit:sync_cursor";
    /** Max events read per ZSET per cycle, bounds memory of each sync round. */
    private static final int BATCH_SIZE = 500;
    private static final long SYNC_INTERVAL_SECONDS = 30;
    /**
     * Atomic cursor advance: only writes when the new score is higher than the
     * current value, so concurrent replicas can never roll the cursor back
     * (rolling back would re-sync the same window on every cycle).
     */
    private static final String ADVANCE_CURSOR_LUA =
        "local cur = tonumber(redis.call('GET', KEYS[1]) or '0') or 0 " +
        "if tonumber(ARGV[1]) > cur then " +
        "  redis.call('SET', KEYS[1], ARGV[1]) " +
        "  return 1 " +
        "end " +
        "return 0";

    private final String redisHost;
    private final int redisPort;
    private final AuditLogSink auditLogSink;
    private final ScheduledExecutorService syncExecutor;

    public RedisAuditSyncService(String redisHost, int redisPort, AuditLogSink auditLogSink) {
        this.redisHost = redisHost != null ? redisHost : "redis-stack-server.higress-system.svc.cluster.local";
        this.redisPort = redisPort > 0 ? redisPort : 6379;
        this.auditLogSink = auditLogSink;
        this.syncExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-redis-sync-" + new AtomicInteger().incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.syncExecutor.scheduleWithFixedDelay(this::syncOnce, SYNC_INTERVAL_SECONDS, SYNC_INTERVAL_SECONDS,
            TimeUnit.SECONDS);
    }

    /**
     * Run one incremental sync cycle. Returns the number of entries forwarded to the sink.
     */
    public int syncOnce() {
        if (auditLogSink == null) {
            return 0;
        }
        int[] syncedRef = new int[1];
        try (Jedis jedis = new Jedis(redisHost, redisPort)) {
            double lastCursor = parseCursor(jedis.get(SYNC_CURSOR_KEY));
            double maxScore = lastCursor;

            ScanParams scanParams = new ScanParams().match(AUDIT_ZSET_PREFIX + "*").count(100);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                cursor = scanResult.getCursor();
                for (String zsetKey : scanResult.getResult()) {
                    if (zsetKey.equals(AUDIT_CONFIG_KEY) || zsetKey.equals(CLEANUP_LOCK_KEY)
                        || zsetKey.equals(SYNC_CURSOR_KEY) || zsetKey.startsWith(AUDIT_LOG_PREFIX)
                        || zsetKey.startsWith(AUDIT_USER_INDEX_PREFIX) || zsetKey.startsWith(AUDIT_AGENT_INDEX_PREFIX)) {
                        continue;
                    }
                    // Type guard: SCAN matches non-ZSET keys (e.g. cleanup_lock)
                    // which must never reach zrangeByScore.
                    if (!"zset".equals(jedis.type(zsetKey))) {
                        continue;
                    }
                    double zsetMax = syncZset(jedis, zsetKey, lastCursor, syncedRef);
                    if (zsetMax > maxScore) {
                        maxScore = zsetMax;
                    }
                }
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));

            if (maxScore > lastCursor) {
                // Multi-replica safety (IR-076): advance the cursor atomically
                // only when it is strictly higher than the stored value, so a
                // slower replica can never move the cursor backwards.
                jedis.eval(ADVANCE_CURSOR_LUA, Collections.singletonList(SYNC_CURSOR_KEY),
                    Collections.singletonList(String.valueOf(maxScore)));
            }
        } catch (JedisConnectionException e) {
            log.error("Redis audit sync failed to connect to Redis: {}:{}", redisHost, redisPort, e);
        } catch (Exception e) {
            log.error("Redis audit sync cycle failed", e);
        }
        int synced = syncedRef[0];
        if (synced > 0) {
            log.info("RedisAuditSync: forwarded {} audit entries to sink", synced);
        }
        return synced;
    }

    /**
     * Forward entries of one session ZSET with score in (lastCursor, +inf).
     *
     * @param syncedRef single-element array accumulating the forwarded count
     * @return the maximum score seen in this ZSET (or lastCursor if none)
     */
    private double syncZset(Jedis jedis, String zsetKey, double lastCursor, int[] syncedRef) {
        java.util.Set<String> eventIds =
            jedis.zrangeByScore(zsetKey, lastCursor + 1, Double.POSITIVE_INFINITY, 0, BATCH_SIZE);
        if (eventIds.isEmpty()) {
            return lastCursor;
        }

        Pipeline pipeline = jedis.pipelined();
        List<Response<String>> responses = new ArrayList<>();
        for (String eventId : eventIds) {
            responses.add(pipeline.get(AUDIT_LOG_PREFIX + eventId));
        }
        pipeline.sync();

        double maxScore = lastCursor;
        int index = 0;
        for (String eventId : eventIds) {
            String json = responses.get(index++).get();
            if (json == null || json.isEmpty()) {
                continue;
            }
            try {
                forwardEntry(eventId, json);
                syncedRef[0]++;
            } catch (Exception e) {
                log.warn("RedisAuditSync: failed to forward event_id={}", eventId, e);
            }
            maxScore = Math.max(maxScore, parseScore(jedis, zsetKey, eventId));
        }
        return maxScore;
    }

    private void forwardEntry(String eventId, String json) {
        JSONObject obj = JSON.parseObject(json);
        long timestampMs = obj.getLongValue("timestamp_ms");
        if (timestampMs == 0) {
            timestampMs = obj.getLongValue("timestamp") * 1000L;
        }
        if (timestampMs == 0) {
            timestampMs = System.currentTimeMillis();
        }

        String sessionId = obj.getString("session_id");
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "degraded_" + Instant.ofEpochMilli(timestampMs)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HH"));
        }

        String userId = obj.getString("user_id");
        boolean identityTrusted = obj.getBooleanValue("identity_trusted");
        String agentId = obj.getString("agent_id");

        auditLogSink.sink(json, eventId, sessionId, timestampMs, userId, identityTrusted, agentId);
    }

    private double parseScore(Jedis jedis, String zsetKey, String eventId) {
        Double score = jedis.zscore(zsetKey, eventId);
        return score != null ? score : 0;
    }

    private double parseCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(cursor);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
