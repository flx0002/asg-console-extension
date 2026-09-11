package com.asg.console.extension.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;
import redis.clients.jedis.Tuple;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * AI Agent Guard Service 实现 - 直接连接 Redis 查询 Session 状态
 */
@Slf4j
public class AgentGuardServiceImpl implements AgentGuardService {

    private static final String SESSION_KEY_PREFIX = "agent_session:";
    private static final String SESSION_KEY_SUFFIX = ":meta";
    private static final String AUDIT_LOG_KEY = "agent_guard:audit_logs";
    /** 审计 ZSET 键前缀 agent_audit:<sessionId> */
    private static final String AUDIT_ZSET_PREFIX = "agent_audit:";
    /** 身份维度索引前缀（非会话，扫描会话时需排除） */
    private static final String AUDIT_USER_INDEX_PREFIX = "user:";
    /** 智能体维度索引前缀（非会话，扫描会话时需排除） */
    private static final String AUDIT_AGENT_INDEX_PREFIX = "agent:";
    /** Hard limit on the number of sessions returned to bound memory usage. */
    private static final int MAX_SESSIONS_LIMIT = 1000;

    private final String redisHost;
    private final int redisPort;

    public AgentGuardServiceImpl(String redisHost, int redisPort) {
        this.redisHost = redisHost != null ? redisHost : "redis-stack-server.higress-system.svc.cluster.local";
        this.redisPort = redisPort > 0 ? redisPort : 6379;
    }

    private Jedis createJedis() {
        return new Jedis(redisHost, redisPort);
    }

    @Override
    public List<Map<String, Object>> listSessions() {
        try (Jedis jedis = createJedis()) {
            ScanParams scanParams = new ScanParams().match(SESSION_KEY_PREFIX + "*" + SESSION_KEY_SUFFIX).count(100);
            String cursor = ScanParams.SCAN_POINTER_START;
            List<Map<String, Object>> sessions = new ArrayList<>();

            do {
                ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                for (String key : scanResult.getResult()) {
                    if (sessions.size() >= MAX_SESSIONS_LIMIT) {
                        log.warn("listSessions reached MAX_SESSIONS_LIMIT={}, truncating", MAX_SESSIONS_LIMIT);
                        break;
                    }
                    try {
                        Map<String, Object> sessionInfo = parseSessionKey(jedis, key);
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

            // 兼容扫描审计 ZSET 会话（R1）：Wasm 自动生成的会话仅落
            // agent_audit:<sessionId> ZSET，不写 agent_session:*:meta，
            // 若不扫描会导致会话列表恒为空。
            ScanParams auditScan = new ScanParams().match(AUDIT_ZSET_PREFIX + "*").count(100);
            cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, auditScan);
                for (String key : scanResult.getResult()) {
                    if (sessions.size() >= MAX_SESSIONS_LIMIT) {
                        break;
                    }
                    if (!isSessionZsetKey(key)) {
                        continue;
                    }
                    try {
                        Map<String, Object> sessionInfo = parseAuditZsetSession(jedis, key);
                        if (sessionInfo != null) {
                            sessions.add(sessionInfo);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse audit zset session key: {}", key, e);
                    }
                }
                if (sessions.size() >= MAX_SESSIONS_LIMIT) {
                    break;
                }
                cursor = scanResult.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));

            return sessions;
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis: {}:{}", redisHost, redisPort, e);
            return Collections.emptyList();
        }
    }

    @Override
    public Map<String, Object> getSession(String sessionId) {
        String key = SESSION_KEY_PREFIX + sessionId + SESSION_KEY_SUFFIX;
        try (Jedis jedis = createJedis()) {
            Map<String, String> data = jedis.hgetAll(key);
            if (data.isEmpty()) {
                return Collections.emptyMap();
            }
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
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis", e);
            return Collections.emptyMap();
        }
    }

    @Override
    public List<Map<String, Object>> getAuditLogs(int limit) {
        // 审计日志存储在 Envoy ai_log 中，Console 从 Redis Session 数据构造审计视图
        // Cap limit to MAX_SESSIONS_LIMIT to bound memory; scan early-stops when limit reached.
        int effectiveLimit = Math.min(limit > 0 ? limit : MAX_SESSIONS_LIMIT, MAX_SESSIONS_LIMIT);
        try (Jedis jedis = createJedis()) {
            ScanParams scanParams = new ScanParams().match(SESSION_KEY_PREFIX + "*" + SESSION_KEY_SUFFIX).count(100);
            String cursor = ScanParams.SCAN_POINTER_START;
            List<Map<String, Object>> logs = new ArrayList<>();

            do {
                ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                for (String key : scanResult.getResult()) {
                    if (logs.size() >= effectiveLimit) {
                        break;
                    }
                    try {
                        Map<String, Object> sessionInfo = parseSessionKey(jedis, key);
                        if (sessionInfo != null) {
                            Map<String, Object> logEntry = new LinkedHashMap<>();
                            logEntry.put("sessionId", sessionInfo.get("sessionId"));
                            logEntry.put("riskScore", sessionInfo.get("riskScore"));
                            logEntry.put("requestCount", sessionInfo.get("requestCount"));
                            logEntry.put("stepCount", sessionInfo.get("stepCount"));
                            logEntry.put("violationCount", sessionInfo.get("violationCount"));
                            logEntry.put("lastActiveTime", sessionInfo.get("lastActiveTime"));
                            logEntry.put("createdAt", sessionInfo.get("createdAt"));
                            logEntry.put("ttl", sessionInfo.get("ttl"));
                            logEntry.put("source", "redis_session");
                            logs.add(logEntry);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse session key: {}", key, e);
                    }
                }
                if (logs.size() >= effectiveLimit) {
                    break;
                }
                cursor = scanResult.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));

            // 兼容扫描审计 ZSET 会话（R1），同上
            ScanParams auditScan = new ScanParams().match(AUDIT_ZSET_PREFIX + "*").count(100);
            cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, auditScan);
                for (String key : scanResult.getResult()) {
                    if (logs.size() >= effectiveLimit) {
                        break;
                    }
                    if (!isSessionZsetKey(key)) {
                        continue;
                    }
                    try {
                        Map<String, Object> sessionInfo = parseAuditZsetSession(jedis, key);
                        if (sessionInfo != null) {
                            Map<String, Object> logEntry = new LinkedHashMap<>();
                            logEntry.put("sessionId", sessionInfo.get("sessionId"));
                            logEntry.put("riskScore", sessionInfo.get("riskScore"));
                            logEntry.put("requestCount", sessionInfo.get("requestCount"));
                            logEntry.put("stepCount", sessionInfo.get("stepCount"));
                            logEntry.put("violationCount", sessionInfo.get("violationCount"));
                            logEntry.put("lastActiveTime", sessionInfo.get("lastActiveTime"));
                            logEntry.put("createdAt", sessionInfo.get("createdAt"));
                            logEntry.put("ttl", sessionInfo.get("ttl"));
                            logEntry.put("source", "redis_audit_zset");
                            logs.add(logEntry);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse audit zset session key: {}", key, e);
                    }
                }
                if (logs.size() >= effectiveLimit) {
                    break;
                }
                cursor = scanResult.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));

            return logs;
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis", e);
            return Collections.emptyList();
        }
    }

    @Override
    public void deleteSession(String sessionId) {
        String metaKey = SESSION_KEY_PREFIX + sessionId + SESSION_KEY_SUFFIX;
        String windowKey = SESSION_KEY_PREFIX + sessionId + ":req_window";
        try (Jedis jedis = createJedis()) {
            jedis.del(metaKey, windowKey);
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis", e);
        }
    }

    /**
     * 判断 audit 键是否为会话维度 ZSET。
     * agent_audit:user:* 与 agent_audit:agent:* 为身份/智能体维度索引，非会话，需排除。
     */
    private boolean isSessionZsetKey(String key) {
        if (!key.startsWith(AUDIT_ZSET_PREFIX)) {
            return false;
        }
        String suffix = key.substring(AUDIT_ZSET_PREFIX.length());
        return !suffix.startsWith(AUDIT_USER_INDEX_PREFIX)
            && !suffix.startsWith(AUDIT_AGENT_INDEX_PREFIX);
    }

    /**
     * 从审计 ZSET（agent_audit:<sessionId>）构造会话信息。
     * Wasm 侧自动生成的会话（如 auto-agent_*）不会写入 agent_session:*:meta，
     * 仅存在审计 ZSET；此处兼容解析，避免会话列表为空（R1）。
     */
    private Map<String, Object> parseAuditZsetSession(Jedis jedis, String key) {
        long count = jedis.zcard(key);
        if (count <= 0) {
            return null;
        }
        String sessionId = key.substring(AUDIT_ZSET_PREFIX.length());
        long ttl = jedis.ttl(key);

        long lastActiveSec = 0;
        Set<Tuple> last = jedis.zrangeWithScores(key, -1, -1);
        if (!last.isEmpty()) {
            Tuple t = last.iterator().next();
            lastActiveSec = (long) (t.getScore() / 1000 / 1000); // score = timestampMs*1000 + seq
        }
        if (lastActiveSec <= 0) {
            lastActiveSec = System.currentTimeMillis() / 1000;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("riskScore", 0);
        result.put("requestCount", count);
        result.put("stepCount", count);
        result.put("tokenCount", 0);
        result.put("violationCount", 0);
        result.put("lastActiveTime", formatTimestamp(String.valueOf(lastActiveSec)));
        result.put("createdAt", formatTimestamp(String.valueOf(lastActiveSec)));
        result.put("ttl", ttl);
        result.put("source", "redis_audit_zset");
        return result;
    }

    private Map<String, Object> parseSessionKey(Jedis jedis, String key) {
        Map<String, String> data = jedis.hgetAll(key);
        if (data.isEmpty()) {
            return null;
        }

        // 从 key 中提取 sessionId: agent_session:{sessionId}:meta
        String sessionId = key.substring(SESSION_KEY_PREFIX.length(),
            key.length() - SESSION_KEY_SUFFIX.length());

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

    private long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return (long) Double.parseDouble(value);
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
            long ts = (long) Double.parseDouble(unixTimestamp);
            if (ts <= 0) {
                return "-";
            }
            return Instant.ofEpochSecond(ts).toString();
        } catch (NumberFormatException e) {
            return "-";
        }
    }

    /**
     * 简单 JSON 解析器（仅支持扁平 key-value 结构）
     */
    private Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (json == null || json.length() < 2) {
            return result;
        }
        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1);
        }
        // 简单的 key-value 解析
        String[] pairs = json.split(",");
        for (String pair : pairs) {
            int colonIdx = pair.indexOf(':');
            if (colonIdx < 0) {
                continue;
            }
            String key = pair.substring(0, colonIdx).trim().replace("\"", "");
            String value = pair.substring(colonIdx + 1).trim();
            // 去除引号
            if (value.startsWith("\"") && value.endsWith("\"")) {
                result.put(key, value.substring(1, value.length() - 1));
            } else {
                try {
                    if (value.contains(".")) {
                        result.put(key, Double.parseDouble(value));
                    } else {
                        result.put(key, Long.parseLong(value));
                    }
                } catch (NumberFormatException e) {
                    result.put(key, value);
                }
            }
        }
        return result;
    }
}
