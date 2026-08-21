/*
 * Copyright (c) 2022-2024 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.asg.console.extension.service;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.params.SetParams;

/**
 * Audit chain service implementation based on Redis secondary index (ZSET + String).
 */
@Slf4j
public class AuditChainServiceImpl implements AuditChainService {

    private static final String SESSION_KEY_PREFIX = "agent_session:";
    private static final String SESSION_KEY_SUFFIX = ":meta";
    private static final String AUDIT_ZSET_PREFIX = "agent_audit:";
    private static final String AUDIT_LOG_PREFIX = "agent_audit_log:";
    private static final String AUDIT_CONFIG_KEY = "agent_audit_config";
    private static final String CLEANUP_LOCK_KEY = "agent_audit:cleanup_lock";
    private static final int CLEANUP_LOCK_TTL = 1800;
    private static final int MAX_SCAN = 10000;
    /** Hard limit on the number of sessions returned to bound memory usage. */
    private static final int MAX_SESSIONS_LIMIT = 1000;
    /** Sample size for stats computation to avoid full-scan OOM. */
    private static final int STATS_SAMPLE_SIZE = 1000;
    /** Batch size for streaming export to bound memory per batch. */
    private static final int EXPORT_BATCH_SIZE = 100;

    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[\\w-]{1,128}$");

    /**
     * Lua script for safe lock release (compare lockValue then delete to avoid removing another instance's lock).
     */
    private static final String RELEASE_LOCK_SCRIPT =
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final String redisHost;
    private final int redisPort;
    private final ScheduledExecutorService cleanupExecutor;

    /**
     * Optional long-term persistence sink (e.g. MySQL). Invoked asynchronously
     * by the sink implementation; a sink outage never blocks the Redis path.
     */
    private volatile AuditLogSink auditLogSink;

    /** Redis Keys used when writing audit logs (mirrors Wasm plugin writeAuditToRedis). */
    private static final String AUDIT_USER_INDEX_PREFIX = "agent_audit:user:";
    private static final String AUDIT_AGENT_INDEX_PREFIX = "agent_audit:agent:";
    private static final String AUDIT_UNTRUSTED_BUCKET = "agent_audit:user:untrusted_anonymous";
    private static final String AUDIT_CALLERS_WINDOW_PREFIX = "agent_behavior:callers:";
    private static final int CALLERS_WINDOW_TTL = 600;
    private static final int UNTRUSTED_BUCKET_TTL = 86400;
    private static final int DEFAULT_FALLBACK_TTL = 21 * 86400; // 21 days
    private static final String UNTRUSTED_USER_ID = "untrusted_anonymous";

    /** Lua script mirroring Wasm plugin luaAppendAuditLog: detail + indexes atomically. */
    private static final String APPEND_AUDIT_LUA =
        "local zsetKey = KEYS[1] " +
        "local logKey = KEYS[2] " +
        "local userKey = KEYS[3] " +
        "local agentKey = KEYS[4] " +
        "local callersKey = KEYS[5] " +
        "local eventId = ARGV[1] " +
        "local data = ARGV[2] " +
        "local score = tonumber(ARGV[3]) " +
        "local fallbackTTL = tonumber(ARGV[4]) " +
        "local callersMember = ARGV[5] " +
        "local tsMs = tonumber(ARGV[6]) " +
        "local callersTTL = tonumber(ARGV[7]) " +
        "local untrustedFlag = ARGV[8] " +
        "local untrustedTTL = tonumber(ARGV[9]) " +
        "local detailOk = redis.call('SETEX', logKey, fallbackTTL, data) " +
        "if not detailOk then return -1 end " +
        "redis.call('ZADD', zsetKey, score, eventId) " +
        "redis.call('EXPIRE', zsetKey, fallbackTTL) " +
        "if userKey ~= '' then " +
        "  redis.call('ZADD', userKey, score, eventId) " +
        "  if untrustedFlag == '1' then " +
        "    redis.call('EXPIRE', userKey, untrustedTTL) " +
        "  else " +
        "    redis.call('EXPIRE', userKey, fallbackTTL) " +
        "  end " +
        "end " +
        "if agentKey ~= '' then " +
        "  redis.call('ZADD', agentKey, score, eventId) " +
        "  redis.call('EXPIRE', agentKey, fallbackTTL) " +
        "end " +
        "if callersKey ~= '' then " +
        "  redis.call('ZADD', callersKey, tsMs, callersMember) " +
        "  redis.call('EXPIRE', callersKey, callersTTL) " +
        "end " +
        "return redis.call('ZCARD', zsetKey)";

    public AuditChainServiceImpl(String redisHost, int redisPort) {
        this.redisHost = redisHost != null ? redisHost : "redis-stack-server.higress-system.svc.cluster.local";
        this.redisPort = redisPort > 0 ? redisPort : 6379;
        this.cleanupExecutor = new ScheduledThreadPoolExecutor(1, new AuditCleanupThreadFactory());
    }

    /**
     * Set the optional audit log sink for long-term persistence (MySQL).
     * Must be called before the collector starts writing, or any time later:
     * entries written while the sink was null are back-filled by the Redis sync.
     */
    public void setAuditLogSink(AuditLogSink auditLogSink) {
        this.auditLogSink = auditLogSink;
    }

    private Jedis createJedis() {
        return new Jedis(redisHost, redisPort);
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || !SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("Invalid session ID format");
        }
    }

    @Override
    public Map<String, Object> getAuditConfig() {
        try (Jedis jedis = createJedis()) {
            Map<String, String> config = jedis.hgetAll(AUDIT_CONFIG_KEY);
            if (config.isEmpty()) {
                Map<String, Object> defaults = new LinkedHashMap<>();
                defaults.put("enabled", "true");
                defaults.put("max_days", "7");
                defaults.put("max_entries_per_session", "1000");
                defaults.put("payload_mode", "full");
                defaults.put("config_version", "0");
                return defaults;
            }
            if (!config.containsKey("payload_mode")) {
                // Configs written before IR-015 payload mode existed: default to full.
                config.put("payload_mode", "full");
            }
            return new LinkedHashMap<>(config);
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis: {}:{}", redisHost, redisPort, e);
            return Collections.emptyMap();
        }
    }

    @Override
    public void updateAuditConfig(Map<String, Object> config) {
        try (Jedis jedis = createJedis()) {
            String versionStr = jedis.hget(AUDIT_CONFIG_KEY, "config_version");
            long version = 0;
            if (versionStr != null) {
                try {
                    version = Long.parseLong(versionStr);
                } catch (NumberFormatException e) {
                    version = 0;
                }
            }
            version++;

            Map<String, String> fields = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : config.entrySet()) {
                fields.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            fields.put("config_version", String.valueOf(version));

            jedis.hmset(AUDIT_CONFIG_KEY, fields);
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis", e);
        }
    }

    @Override
    public List<Map<String, Object>> getAuditSessions() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        try (Jedis jedis = createJedis()) {
            // 1. Normal sessions: SCAN agent_session:*:meta
            ScanParams normalScan = new ScanParams().match(SESSION_KEY_PREFIX + "*" + SESSION_KEY_SUFFIX).count(100);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, normalScan);
                for (String key : scanResult.getResult()) {
                    if (sessions.size() >= MAX_SESSIONS_LIMIT) {
                        log.warn("getAuditSessions reached MAX_SESSIONS_LIMIT={}, truncating normal sessions",
                            MAX_SESSIONS_LIMIT);
                        break;
                    }
                    try {
                        Map<String, Object> sessionInfo = parseSessionMeta(jedis, key);
                        if (sessionInfo != null) {
                            sessions.add(sessionInfo);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse session key: {}", key, e);
                    }
                }
                if (sessions.size() >= MAX_SESSIONS_LIMIT) {
                    break;
                }
                cursor = scanResult.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));

            // 2. Degraded virtual sessions: SCAN agent_audit:degraded_* (hourly aggregated degraded requests)
            ScanParams degradedScan = new ScanParams().match(AUDIT_ZSET_PREFIX + "degraded_*").count(100);
            cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, degradedScan);
                for (String zsetKey : scanResult.getResult()) {
                    if (sessions.size() >= MAX_SESSIONS_LIMIT) {
                        log.warn("getAuditSessions reached MAX_SESSIONS_LIMIT={}, truncating degraded sessions",
                            MAX_SESSIONS_LIMIT);
                        break;
                    }
                    try {
                        String sessionId = zsetKey.substring(AUDIT_ZSET_PREFIX.length());
                        long count = jedis.zcard(zsetKey);
                        Map<String, Object> meta = new LinkedHashMap<>();
                        meta.put("sessionId", sessionId);
                        meta.put("mode", "degraded");
                        meta.put("stepCount", count);
                        meta.put("lastActiveTime", formatTimestamp(String.valueOf(System.currentTimeMillis())));
                        meta.put("createdAt", formatTimestamp(String.valueOf(System.currentTimeMillis())));
                        sessions.add(meta);
                    } catch (Exception e) {
                        log.warn("Failed to parse degraded session key: {}", zsetKey, e);
                    }
                }
                if (sessions.size() >= MAX_SESSIONS_LIMIT) {
                    break;
                }
                cursor = scanResult.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis: {}:{}", redisHost, redisPort, e);
        }
        return sessions;
    }

    private Map<String, Object> parseSessionMeta(Jedis jedis, String key) {
        Map<String, String> data = jedis.hgetAll(key);
        if (data.isEmpty()) {
            return null;
        }
        String sessionId = key.substring(SESSION_KEY_PREFIX.length(), key.length() - SESSION_KEY_SUFFIX.length());
        long ttl = jedis.ttl(key);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("riskScore", parseDouble(data.getOrDefault("risk_score", "0")));
        result.put("requestCount", parseLong(data.getOrDefault("request_count", "0")));
        result.put("stepCount", parseLong(data.getOrDefault("step_count", "0")));
        result.put("tokenCount", parseLong(data.getOrDefault("token_count", "0")));
        result.put("violationCount", parseLong(data.getOrDefault("violation_count", "0")));
        result.put("lastActiveTime", formatTimestamp(data.get("last_active_time")));
        result.put("createdAt", formatTimestamp(data.get("created_at")));
        result.put("ttl", ttl);
        return result;
    }

    @Override
    public Map<String, Object> getAuditLogs(String sessionId, int page, int pageSize, String recordType) {
        validateSessionId(sessionId);
        String zsetKey = AUDIT_ZSET_PREFIX + sessionId;
        Map<String, Object> result = new LinkedHashMap<>();

        try (Jedis jedis = createJedis()) {
            long total = jedis.zcard(zsetKey);

            if (recordType == null || recordType.isEmpty()) {
                // No filter: Redis native pagination with exact total
                int start = (page - 1) * pageSize;
                int end = start + pageSize - 1;
                Set<String> eventIds = jedis.zrevrange(zsetKey, start, end);
                List<Map<String, Object>> logs = batchGetLogs(jedis, eventIds, sessionId);
                result.put("list", logs);
                result.put("total", total);
                result.put("page", page);
                result.put("pageSize", pageSize);
                result.put("exact", true);
            } else {
                // With filter: cursor pagination, total=-1 (not exact)
                List<Map<String, Object>> pageLogs =
                    getLogsBySessionWithFilter(jedis, zsetKey, sessionId, page, pageSize, recordType);
                result.put("list", pageLogs);
                result.put("total", -1);
                result.put("page", page);
                result.put("pageSize", pageSize);
                result.put("exact", false);
            }
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis", e);
            result.put("list", Collections.emptyList());
            result.put("total", 0);
            result.put("page", page);
            result.put("pageSize", pageSize);
            result.put("exact", true);
        }
        return result;
    }

    /**
     * Cursor pagination with filter: keep ZREVRANGE until enough qualified records are collected, maxScan=10000.
     */
    private List<Map<String, Object>> getLogsBySessionWithFilter(Jedis jedis, String zsetKey, String sessionId,
        int page, int pageSize, String recordType) {
        int targetCount = page * pageSize;
        int scanBatch = Math.max(pageSize * 3, 100);
        int cursor = 0;
        int matched = 0;
        List<Map<String, Object>> pageLogs = new ArrayList<>();
        int scanned = 0;

        while (matched < targetCount && scanned < MAX_SCAN) {
            Set<String> eventIds = jedis.zrevrange(zsetKey, cursor, cursor + scanBatch - 1);
            if (eventIds.isEmpty()) {
                break;
            }
            List<Map<String, Object>> logs = batchGetLogs(jedis, eventIds, sessionId);
            for (Map<String, Object> log : logs) {
                List<String> types = toStringList(log.get("record_types"));
                if (types.contains(recordType)) {
                    matched++;
                    if (matched > (page - 1) * pageSize && pageLogs.size() < pageSize) {
                        pageLogs.add(log);
                    }
                    if (pageLogs.size() >= pageSize) {
                        break;
                    }
                }
            }
            cursor += scanBatch;
            scanned += scanBatch;
        }
        return pageLogs;
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object obj) {
        if (obj instanceof List) {
            return (List<String>)obj;
        }
        return Collections.emptyList();
    }

    /**
     * Pipeline MGET to batch fetch audit log details.
     */
    private List<Map<String, Object>> batchGetLogs(Jedis jedis, Set<String> eventIds, String sessionId) {
        if (eventIds.isEmpty()) {
            return Collections.emptyList();
        }
        Pipeline pipeline = jedis.pipelined();
        List<Response<String>> responses = new ArrayList<>();
        for (String eventId : eventIds) {
            responses.add(pipeline.get(AUDIT_LOG_PREFIX + eventId));
        }
        pipeline.sync();

        List<Map<String, Object>> logs = new ArrayList<>();
        for (Response<String> resp : responses) {
            String json = resp.get();
            if (json != null) {
                try {
                    JSONObject obj = JSON.parseObject(json);
                    Map<String, Object> log = new LinkedHashMap<>(obj);
                    // Format timestamp for display
                    String ts = obj.getString("timestamp");
                    if (ts != null) {
                        log.put("timestamp", formatTimestamp(ts));
                    }
                    String time = obj.getString("time");
                    if (time != null) {
                        log.put("time", formatTimestamp(time));
                    }
                    log.put("sessionId", sessionId);
                    logs.add(log);
                } catch (Exception e) {
                    log.warn("Failed to parse audit log JSON", e);
                }
            }
        }
        return logs;
    }

    @Override
    public Map<String, Object> getAuditStats(String sessionId) {
        validateSessionId(sessionId);
        return getSessionAuditStats(sessionId);
    }

    @Override
    public void clearSessionAuditLogs(String sessionId) {
        validateSessionId(sessionId);
        String zsetKey = AUDIT_ZSET_PREFIX + sessionId;
        try (Jedis jedis = createJedis()) {
            Set<String> eventIds = jedis.zrange(zsetKey, 0, -1);
            if (!eventIds.isEmpty()) {
                Pipeline pipeline = jedis.pipelined();
                for (String eventId : eventIds) {
                    pipeline.del(AUDIT_LOG_PREFIX + eventId);
                }
                pipeline.sync();
            }
            jedis.del(zsetKey);
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis", e);
        }
    }

    @Override
    public Map<String, Object> getSessionAuditChain(String sessionId, int page, int pageSize) {
        validateSessionId(sessionId);
        String zsetKey = AUDIT_ZSET_PREFIX + sessionId;
        Map<String, Object> result = new LinkedHashMap<>();
        try (Jedis jedis = createJedis()) {
            long total = jedis.zcard(zsetKey);
            int start = (page - 1) * pageSize;
            int end = start + pageSize - 1;
            // ZRANGE ascending by time to get event IDs
            Set<String> eventIds = jedis.zrange(zsetKey, start, end);
            List<Map<String, Object>> steps = batchGetLogs(jedis, eventIds, sessionId);
            for (int i = 0; i < steps.size(); i++) {
                steps.get(i).put("sequence", start + i + 1);
            }
            result.put("list", steps);
            result.put("total", total);
            result.put("page", page);
            result.put("pageSize", pageSize);
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis", e);
            result.put("list", Collections.emptyList());
            result.put("total", 0);
            result.put("page", page);
            result.put("pageSize", pageSize);
        }
        return result;
    }

    @Override
    public Map<String, Object> getSessionAuditStats(String sessionId) {
        validateSessionId(sessionId);
        String zsetKey = AUDIT_ZSET_PREFIX + sessionId;
        Map<String, Object> stats = new LinkedHashMap<>();
        try (Jedis jedis = createJedis()) {
            long totalSteps = jedis.zcard(zsetKey);
            stats.put("sessionId", sessionId);
            stats.put("totalSteps", totalSteps);

            // Get first and last time range
            Set<String> first = jedis.zrange(zsetKey, 0, 0);
            Set<String> last = jedis.zrevrange(zsetKey, 0, 0);
            String startTime = null;
            String endTime = null;
            if (!first.isEmpty()) {
                String json = jedis.get(AUDIT_LOG_PREFIX + first.iterator().next());
                if (json != null) {
                    JSONObject obj = JSON.parseObject(json);
                    startTime = formatTimestamp(obj.getString("timestamp"));
                }
            }
            if (!last.isEmpty()) {
                String json = jedis.get(AUDIT_LOG_PREFIX + last.iterator().next());
                if (json != null) {
                    JSONObject obj = JSON.parseObject(json);
                    endTime = formatTimestamp(obj.getString("timestamp"));
                }
            }
            Map<String, String> timeRange = new LinkedHashMap<>();
            timeRange.put("start", startTime);
            timeRange.put("end", endTime);
            stats.put("timeRange", timeRange);

            // Compute blockedCount, violationCount, totalToken by sampling the most recent
            // STATS_SAMPLE_SIZE logs. Full scan of all logs risks OOM; sampling bounds memory.
            // When totalSteps > STATS_SAMPLE_SIZE, stats are marked approximate.
            int sampleSize = (int)Math.min(totalSteps, STATS_SAMPLE_SIZE);
            Set<String> sampledEventIds = jedis.zrevrange(zsetKey, 0, sampleSize - 1);
            long blockedCount = 0;
            long violationCount = 0;
            long totalToken = 0;
            for (String eventId : sampledEventIds) {
                String json = jedis.get(AUDIT_LOG_PREFIX + eventId);
                if (json != null) {
                    try {
                        JSONObject obj = JSON.parseObject(json);
                        String action = obj.getString("action");
                        if ("block".equals(action)) {
                            blockedCount++;
                        }
                        Object eventsObj = obj.get("events");
                        if (eventsObj instanceof List) {
                            violationCount += ((List<?>) eventsObj).size();
                        } else if (eventsObj != null) {
                            violationCount += 1;
                        }
                        totalToken += obj.getLongValue("input_token") + obj.getLongValue("output_token");
                    } catch (Exception e) {
                        // skip parse errors
                    }
                }
            }
            stats.put("blockedCount", blockedCount);
            stats.put("violationCount", violationCount);
            stats.put("totalToken", totalToken);
            stats.put("approximate", totalSteps > STATS_SAMPLE_SIZE);
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis", e);
            stats.put("sessionId", sessionId);
            stats.put("totalSteps", 0);
            stats.put("blockedCount", 0);
            stats.put("violationCount", 0);
            stats.put("totalToken", 0);
        }
        return stats;
    }

    @Override
    public void cleanupExpiredLogs() {
        cleanupExecutor.submit(() -> {
            try {
                doCleanupExpiredLogs();
            } catch (Exception e) {
                log.error("cleanup task failed", e);
            }
        });
    }

    private void doCleanupExpiredLogs() {
        Map<String, Object> config = getAuditConfig();
        int maxDays = 7;
        Object maxDaysObj = config.get("max_days");
        if (maxDaysObj != null) {
            try {
                maxDays = Integer.parseInt(String.valueOf(maxDaysObj));
            } catch (NumberFormatException e) {
                maxDays = 7;
            }
        }
        if (maxDays <= 0) {
            return;
        }
        if (maxDays > 180) {
            maxDays = 180;
        }

        String lockValue = UUID.randomUUID().toString();
        boolean locked = false;
        try (Jedis jedis = createJedis()) {
            // Distributed lock: SET NX EX, TTL=1800s, prevent concurrent cleanup across instances
            String ok = jedis.set(CLEANUP_LOCK_KEY, lockValue,
                SetParams.setParams().nx().ex(CLEANUP_LOCK_TTL));
            if (!"OK".equals(ok)) {
                log.info("cleanup task already running on another instance, skip");
                return;
            }
            locked = true;

            // Score = timestampMs * 1000 + sequence, cutoff must be multiplied by 1000 to align with Score scale
            long cutoffScore = (Instant.now().toEpochMilli() - maxDays * 86400L * 1000) * 1000;
            long cleanedCount = 0;

            // Per-Session SCAN to avoid N+1 queries and blocking
            ScanParams scanParams = new ScanParams().match(AUDIT_ZSET_PREFIX + "*").count(100);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                List<String> zsetKeys = scanResult.getResult();
                cursor = scanResult.getCursor();

                for (String zsetKey : zsetKeys) {
                    // Skip non-ZSET keys (e.g. agent_audit_config, agent_audit:cleanup_lock,
                    // agent_audit:sync_cursor, agent_audit_log: details)
                    if (zsetKey.equals(AUDIT_CONFIG_KEY) || zsetKey.equals(CLEANUP_LOCK_KEY)
                        || zsetKey.startsWith(AUDIT_LOG_PREFIX)) {
                        continue;
                    }
                    // Defensive type check: SCAN may match string keys created by other
                    // components (e.g. agent_audit:sync_cursor), ZRANGEBYSCORE would fail.
                    if (!"zset".equals(jedis.type(zsetKey))) {
                        continue;
                    }

                    Set<String> expiredIds = jedis.zrangeByScore(zsetKey, 0, cutoffScore);
                    if (expiredIds.isEmpty()) {
                        continue;
                    }

                    jedis.zremrangeByScore(zsetKey, 0, cutoffScore);

                    // Pipeline batch DEL log details
                    Pipeline pipeline = jedis.pipelined();
                    for (String eventId : expiredIds) {
                        pipeline.del(AUDIT_LOG_PREFIX + eventId);
                    }
                    pipeline.sync();

                    cleanedCount += expiredIds.size();
                }
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));

            log.info("cleanup completed, removed {} expired audit logs", cleanedCount);
        } catch (Exception e) {
            log.error("cleanup task failed", e);
        } finally {
            if (locked) {
                releaseLock(lockValue);
            }
        }
    }

    /**
     * Write a single audit log entry to Redis (mirrors Wasm plugin writeAuditToRedis).
     */
    @Override
    public void writeAuditLog(String auditEntryJson, String eventId, String sessionId,
        long timestampMs, String userId, boolean identityTrusted, String agentId) {
        if (eventId == null || eventId.isEmpty() || sessionId == null || sessionId.isEmpty()) {
            return;
        }
        String zsetKey = AUDIT_ZSET_PREFIX + sessionId;
        String logKey = AUDIT_LOG_PREFIX + eventId;
        double score = timestampMs * 1000.0;
        int fallbackTTL = DEFAULT_FALLBACK_TTL;

        // Identity index key (mirrors Wasm plugin logic)
        String userKey;
        String untrustedFlag = "0";
        if (identityTrusted && userId != null && !userId.isEmpty()) {
            userKey = AUDIT_USER_INDEX_PREFIX + userId;
        } else {
            userKey = AUDIT_UNTRUSTED_BUCKET;
            untrustedFlag = "1";
        }

        // Agent index key
        String agentKey = (agentId != null && !agentId.isEmpty()) ? AUDIT_AGENT_INDEX_PREFIX + agentId : "";

        // Callers sliding window key
        String callersKey = (agentId != null && !agentId.isEmpty()) ? AUDIT_CALLERS_WINDOW_PREFIX + agentId : "";
        String callersMember = (userId != null && !userId.isEmpty()) ? userId : UNTRUSTED_USER_ID;

        try (Jedis jedis = createJedis()) {
            java.util.List<String> keys = java.util.Arrays.asList(zsetKey, logKey, userKey, agentKey, callersKey);
            java.util.List<String> args = java.util.Arrays.asList(
                eventId, auditEntryJson, String.valueOf(score), String.valueOf(fallbackTTL),
                callersMember, String.valueOf(timestampMs), String.valueOf(CALLERS_WINDOW_TTL),
                untrustedFlag, String.valueOf(UNTRUSTED_BUCKET_TTL));
            Object ret = jedis.eval(APPEND_AUDIT_LUA, keys, args);
            if ("-1".equals(String.valueOf(ret))) {
                log.warn("writeAuditLog detail write failed (redis may be full), event_id={}", eventId);
            }
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis for writeAuditLog: event_id={}", eventId, e);
        }
        // Notify the long-term persistence sink regardless of Redis outcome:
        // when Redis is down this is exactly when the MySQL fallback matters.
        notifySink(auditEntryJson, eventId, sessionId, timestampMs, userId, identityTrusted, agentId);
    }

    /**
     * Forward the entry to the optional persistence sink. The sink is
     * asynchronous (queue + background flusher), so this call never blocks.
     */
    private void notifySink(String auditEntryJson, String eventId, String sessionId,
        long timestampMs, String userId, boolean identityTrusted, String agentId) {
        AuditLogSink sink = this.auditLogSink;
        if (sink == null) {
            return;
        }
        try {
            sink.sink(auditEntryJson, eventId, sessionId, timestampMs, userId, identityTrusted, agentId);
        } catch (Exception e) {
            log.warn("Failed to notify audit log sink: event_id={}", eventId, e);
        }
    }

    /**
     * Release distributed lock using Lua script (compare lockValue then delete to avoid mis-removal).
     */
    private void releaseLock(String lockValue) {
        try (Jedis jedis = createJedis()) {
            jedis.eval(RELEASE_LOCK_SCRIPT, 1, CLEANUP_LOCK_KEY, lockValue);
        } catch (Exception e) {
            log.warn("failed to release cleanup lock", e);
        }
    }

    @Override
    public void exportAuditLogs(String sessionId, String format, HttpServletResponse response) {
        validateSessionId(sessionId);
        String zsetKey = AUDIT_ZSET_PREFIX + sessionId;
        try (Jedis jedis = createJedis()) {
            long total = jedis.zcard(zsetKey);
            String filename = "audit_logs_" + sessionId;
            if ("csv".equalsIgnoreCase(format)) {
                response.setContentType("text/csv; charset=utf-8");
                response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + URLEncoder.encode(filename + ".csv", "UTF-8") + "\"");
                streamCsv(jedis, zsetKey, sessionId, total, response);
            } else {
                response.setContentType("application/json; charset=utf-8");
                response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + URLEncoder.encode(filename + ".json", "UTF-8") + "\"");
                streamJson(jedis, zsetKey, sessionId, total, response);
            }
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis", e);
            try {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            log.error("export audit logs failed", e);
        }
    }

    /**
     * Stream JSON array in batches: write '[', then each batch of logs separated by commas, then ']'.
     * Memory bounded to EXPORT_BATCH_SIZE logs at a time.
     */
    private void streamJson(Jedis jedis, String zsetKey, String sessionId, long total,
        HttpServletResponse response) throws Exception {
        try (OutputStream os = response.getOutputStream()) {
            os.write('[');
            long written = 0;
            int start = 0;
            boolean first = true;
            while (written < total) {
                int end = start + EXPORT_BATCH_SIZE - 1;
                Set<String> eventIds = jedis.zrange(zsetKey, start, end);
                if (eventIds.isEmpty()) {
                    break;
                }
                List<Map<String, Object>> logs = batchGetLogs(jedis, eventIds, sessionId);
                for (Map<String, Object> log : logs) {
                    if (!first) {
                        os.write(',');
                    }
                    first = false;
                    os.write(JSON.toJSONString(log).getBytes(StandardCharsets.UTF_8));
                    written++;
                }
                start += EXPORT_BATCH_SIZE;
            }
            os.write(']');
            os.flush();
        }
    }

    /**
     * Stream CSV in batches: write BOM + headers (from first batch), then rows batch by batch.
     * Memory bounded to EXPORT_BATCH_SIZE logs at a time.
     */
    private void streamCsv(Jedis jedis, String zsetKey, String sessionId, long total,
        HttpServletResponse response) throws Exception {
        try (OutputStream os = response.getOutputStream()) {
            // BOM for Excel UTF-8 recognition
            os.write('\ufeff');
            List<String> headers = null;
            long written = 0;
            int start = 0;
            while (written < total) {
                int end = start + EXPORT_BATCH_SIZE - 1;
                Set<String> eventIds = jedis.zrange(zsetKey, start, end);
                if (eventIds.isEmpty()) {
                    break;
                }
                List<Map<String, Object>> logs = batchGetLogs(jedis, eventIds, sessionId);
                if (headers == null) {
                    // Derive headers from the first log entry
                    headers = new ArrayList<>();
                    if (!logs.isEmpty()) {
                        headers.addAll(logs.get(0).keySet());
                    }
                    os.write(String.join(",", headers).getBytes(StandardCharsets.UTF_8));
                    os.write('\n');
                }
                for (Map<String, Object> log : logs) {
                    List<String> values = new ArrayList<>();
                    for (String header : headers) {
                        Object val = log.get(header);
                        String str = val == null ? "" : String.valueOf(val);
                        if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
                            str = "\"" + str.replace("\"", "\"\"") + "\"";
                        }
                        values.add(str);
                    }
                    os.write(String.join(",", values).getBytes(StandardCharsets.UTF_8));
                    os.write('\n');
                    written++;
                }
                start += EXPORT_BATCH_SIZE;
            }
            os.flush();
        }
    }

    private long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return (long)Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double parseDouble(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String formatTimestamp(String unixTimestamp) {
        if (unixTimestamp == null || unixTimestamp.isEmpty()) {
            return "-";
        }
        try {
            long ts = (long)Double.parseDouble(unixTimestamp);
            if (ts <= 0) {
                return "-";
            }
            // Detect millisecond timestamps (13+ digits, > 10^12)
            if (ts > 1000000000000L) {
                ts = ts / 1000;
            }
            return Instant.ofEpochSecond(ts).toString();
        } catch (NumberFormatException e) {
            // Not a numeric timestamp, return as-is (could be ISO format)
            return unixTimestamp;
        }
    }

    /**
     * Thread factory for audit cleanup tasks (daemon threads to avoid blocking JVM shutdown).
     */
    private static class AuditCleanupThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "audit-cleanup-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
