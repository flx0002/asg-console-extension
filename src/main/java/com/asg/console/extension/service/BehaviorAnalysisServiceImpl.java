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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
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
 * 行为分析服务实现（方案 5.3 / 5.4）
 *
 * 画像与基线采用增量时间窗口 + Lua 原子合并，禁止全量 SCAN 审计日志。
 * 数值字段下推 Lua 原子合并（解决并发 Lost Update），JSON 频次字段 Java 端合并（容忍最终一致）。
 */
@Slf4j
public class BehaviorAnalysisServiceImpl implements BehaviorAnalysisService {

    // ===== Redis Key 前缀 =====
    private static final String CONFIG_KEY = "agent_behavior:config";
    private static final String PROFILE_USER_PREFIX = "agent_behavior:profile:user:";
    private static final String PROFILE_AGENT_PREFIX = "agent_behavior:profile:agent:";
    private static final String BASELINE_PREFIX = "agent_behavior:baseline:";
    private static final String AUDIT_USER_INDEX_PREFIX = "agent_audit:user:";
    private static final String AUDIT_AGENT_INDEX_PREFIX = "agent_audit:agent:";
    private static final String AUDIT_LOG_PREFIX = "agent_audit_log:";
    private static final String UNTRUSTED_BUCKET_KEY = "agent_audit:user:untrusted_anonymous";
    private static final String ALERT_KEY_PREFIX = "agent_behavior:alert:";
    private static final String ALERTS_ZSET_KEY = "agent_behavior:alerts:zset";
    private static final String ALERTS_USER_ZSET_PREFIX = "agent_behavior:alerts:user:";
    private static final String ALERTS_AGENT_ZSET_PREFIX = "agent_behavior:alerts:agent:";
    private static final String ALERTS_STATUS_ZSET_PREFIX = "agent_behavior:alerts:status:";
    private static final String DEDUP_KEY_PREFIX = "agent_behavior:dedup:";
    private static final String BLACKLIST_USER_PREFIX = "agent_behavior:blacklist:user:";
    private static final String BLACKLIST_AGENT_PREFIX = "agent_behavior:blacklist:agent:";
    private static final String WHITELIST_USER_PREFIX = "agent_behavior:whitelist:user:";
    private static final String WHITELIST_AGENT_PREFIX = "agent_behavior:whitelist:agent:";
    private static final String RULE_CHANGES_ZSET_KEY = "agent_behavior:rule_changes:zset";
    private static final String CALLERS_WINDOW_PREFIX = "agent_behavior:callers:";
    private static final String PROFILE_PROCESSED_USER_PREFIX = "agent_behavior:profile:processed:user:";
    private static final String PROFILE_PROCESSED_AGENT_PREFIX = "agent_behavior:profile:processed:agent:";
    private static final String SESSION_META_PREFIX = "agent_session:";
    private static final String SESSION_META_SUFFIX = ":meta";
    private static final int DEFAULT_BLACKLIST_TTL = 7 * 86400;
    private static final int WHITELIST_DEFAULT_TTL = 7 * 86400;
    private static final int RULE_CHANGES_TTL = 90 * 86400;
    private static final int DEDUP_DEFAULT_TTL = 300;
    private static final int RISK_DETECTION_WINDOW_MS = 120000; // 检测窗口 120s（2 倍调度周期，避免遗漏）
    private static final int PROFILE_BUILD_LOOKBACK_MS = 120000; // 覆盖审计日志兜底采集延迟

    // ===== RuleFeedbackTask 阈值（方案 9.2） =====
    private static final double FP_RATE_AUTO_ADJUST = 0.30; // 误报率 >30% 自动上调阈值
    private static final double FP_RATE_MANUAL_REVIEW = 0.50; // 误报率 >50% 标记待人工复核
    private static final double THRESHOLD_INCREASE_FACTOR = 1.20; // 阈值倍率 ×1.2（绝对阈值提高 20%）
    private static final int RULE_FEEDBACK_MIN_SAMPLES = 5; // 单规则样本下限

    // ===== 默认配置 =====
    private static final int PROFILE_TTL = 7 * 86400;
    private static final int BASELINE_TTL = 30 * 86400;
    private static final int ALERT_TTL = 90 * 86400;
    private static final int DEFAULT_MIN_SAMPLES = 10;
    private static final double DEFAULT_EMA_ALPHA = 0.1;
    private static final int SCAN_BATCH_SIZE = 200;
    private static final int MAX_SCAN_KEYS = 10000;

    // ===== Lua 脚本：原子合并画像数值字段（方案 5.3） =====
    // ARGV 格式：ttl, field1, delta1, field2, delta2, ...
    // 特殊处理：sum_risk_score 累加后联动更新 avg_risk_score
    private static final String LUA_MERGE_PROFILE_NUMERIC =
        "local key = KEYS[1]\n" +
        "local ttl = tonumber(ARGV[1])\n" +
        "local i = 2\n" +
        "while i <= #ARGV do\n" +
        "  local field = ARGV[i]\n" +
        "  local delta = tonumber(ARGV[i+1]) or 0\n" +
        "  if delta ~= 0 then\n" +
        "    local old = tonumber(redis.call('HGET', key, field) or '0') or 0\n" +
        "    redis.call('HSET', key, field, tostring(old + delta))\n" +
        "  end\n" +
        "  i = i + 2\n" +
        "end\n" +
        "-- 联动计算 avg_risk_score = sum_risk_score / total_requests\n" +
        "-- 智能体画像用 total_calls（无 total_requests），此处回退读取保证两者均可算出均分\n" +
        "local sumRisk = tonumber(redis.call('HGET', key, 'sum_risk_score') or '0') or 0\n" +
        "local totalReq = tonumber(redis.call('HGET', key, 'total_requests') or '0') or 0\n" +
        "if totalReq == 0 then\n" +
        "  totalReq = tonumber(redis.call('HGET', key, 'total_calls') or '0') or 0\n" +
        "end\n" +
        "if totalReq > 0 then\n" +
        "  redis.call('HSET', key, 'avg_risk_score', tostring(sumRisk / totalReq))\n" +
        "end\n" +
        "redis.call('EXPIRE', key, ttl)\n" +
        "return 'OK'";

    // ===== Lua 脚本：EMA 合并基线数值字段（方案 5.4） =====
    // ARGV 格式：alpha, ttl, field1, newValue1, field2, newValue2, ...
    // 计算：new = old * (1-alpha) + newValue * alpha
    private static final String LUA_EMA_MERGE_BASELINE =
        "local key = KEYS[1]\n" +
        "local alpha = tonumber(ARGV[1])\n" +
        "local ttl = tonumber(ARGV[2])\n" +
        "local i = 3\n" +
        "while i <= #ARGV do\n" +
        "  local field = ARGV[i]\n" +
        "  local newVal = tonumber(ARGV[i+1]) or 0\n" +
        "  local old = tonumber(redis.call('HGET', key, field) or '0') or 0\n" +
        "  local merged = old * (1 - alpha) + newVal * alpha\n" +
        "  redis.call('HSET', key, field, tostring(merged))\n" +
        "  i = i + 2\n" +
        "end\n" +
        "-- sample_count 累加（非 EMA）\n" +
        "local oldCount = tonumber(redis.call('HGET', key, 'sample_count') or '0') or 0\n" +
        "local newCount = tonumber(ARGV[#ARGV]) or 0\n" +
        "redis.call('HSET', key, 'sample_count', tostring(oldCount + newCount))\n" +
        "redis.call('HSET', key, 'last_build_at', ARGV[#ARGV - 1])\n" +
        "redis.call('EXPIRE', key, ttl)\n" +
        "return 'OK'";

    // ===== Lua 脚本：初始化基线（冷启动，方案 5.4） =====
    // ARGV 格式：ttl, field1, value1, field2, value2, ...
    private static final String LUA_INIT_BASELINE =
        "local key = KEYS[1]\n" +
        "local ttl = tonumber(ARGV[1])\n" +
        "local i = 2\n" +
        "while i <= #ARGV do\n" +
        "  redis.call('HSET', key, ARGV[i], ARGV[i+1])\n" +
        "  i = i + 2\n" +
        "end\n" +
        "redis.call('EXPIRE', key, ttl)\n" +
        "return 'OK'";

    // ===== Lua 脚本：原子更新告警 risk_score（方案 10.5） =====
    // 仅当 new_score > old_score 时更新，evidence 追加覆盖
    private static final String LUA_UPDATE_ALERT_SCORE =
        "local key = KEYS[1]\n" +
        "local old = tonumber(redis.call('HGET', key, 'risk_score') or '0') or 0\n" +
        "local newScore = tonumber(ARGV[1]) or 0\n" +
        "if newScore > old then\n" +
        "  redis.call('HMSET', key, 'risk_score', ARGV[1], 'evidence', ARGV[2])\n" +
        "  return 1\n" +
        "end\n" +
        "return 0";

    private final String redisHost;
    private final int redisPort;

    public BehaviorAnalysisServiceImpl(String redisHost, int redisPort) {
        this.redisHost = redisHost != null ? redisHost : "redis-stack-server.higress-system.svc.cluster.local";
        this.redisPort = redisPort > 0 ? redisPort : 6379;
    }

    private Jedis createJedis() {
        return new Jedis(redisHost, redisPort);
    }

    /**
     * 合并 KEYS 与 ARGV 为单个 String[]，供 jedis.eval(script, keyCount, String...) 使用。
     * Jedis 的 eval varargs 期望单个数组（前 keyCount 个为 KEYS，其余为 ARGV）。
     */
    private static String[] evalArgs(String key, List<String> args) {
        String[] params = new String[args.size() + 1];
        params[0] = key;
        for (int i = 0; i < args.size(); i++) {
            params[i + 1] = args.get(i);
        }
        return params;
    }

    // ==================== 配置管理 ====================

    @Override
    public Map<String, Object> getConfig() {
        try (Jedis jedis = createJedis()) {
            Map<String, String> config = jedis.hgetAll(CONFIG_KEY);
            if (config.isEmpty()) {
                return getDefaultConfig();
            }
            return new LinkedHashMap<>(config);
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis: {}:{}", redisHost, redisPort, e);
            return getDefaultConfig();
        }
    }

    @Override
    public void updateConfig(Map<String, Object> config) {
        try (Jedis jedis = createJedis()) {
            String versionStr = jedis.hget(CONFIG_KEY, "config_version");
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
            jedis.hmset(CONFIG_KEY, fields);
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis", e);
        }
    }

    private Map<String, Object> getDefaultConfig() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("enabled", "true");
        defaults.put("analysis_interval_seconds", "60");
        defaults.put("baseline_interval_seconds", "3600");
        defaults.put("baseline_min_samples", String.valueOf(DEFAULT_MIN_SAMPLES));
        defaults.put("baseline_ema_alpha", String.valueOf(DEFAULT_EMA_ALPHA));
        defaults.put("alert_retention_days", "90");
        defaults.put("profile_retention_days", "7");
        defaults.put("alert_dedup_window_seconds", "300");
        defaults.put("timezone", "Asia/Shanghai");
        defaults.put("absolute_token_threshold", "1000000");
        defaults.put("absolute_step_threshold", "200");
        defaults.put("absolute_request_threshold", "500");
        defaults.put("config_version", "0");
        return defaults;
    }

    private String getConfigValue(Jedis jedis, String field, String def) {
        String v = jedis.hget(CONFIG_KEY, field);
        return v != null ? v : def;
    }

    // ==================== 画像查询 ====================

    @Override
    public Map<String, Object> getUserProfile(String userId) {
        return getHashAsMap(PROFILE_USER_PREFIX + userId);
    }

    @Override
    public Map<String, Object> getAgentProfile(String agentId) {
        return getHashAsMap(PROFILE_AGENT_PREFIX + agentId);
    }

    @Override
    public List<Map<String, Object>> listUserProfiles(int page, int pageSize) {
        return listHashProfiles(PROFILE_USER_PREFIX, page, pageSize);
    }

    @Override
    public List<Map<String, Object>> listAgentProfiles(int page, int pageSize) {
        return listHashProfiles(PROFILE_AGENT_PREFIX, page, pageSize);
    }

    private Map<String, Object> getHashAsMap(String key) {
        try (Jedis jedis = createJedis()) {
            Map<String, String> data = jedis.hgetAll(key);
            if (data.isEmpty()) {
                return Collections.emptyMap();
            }
            // 续期 TTL（方案 3.3.1：每次访问自动续期）
            jedis.expire(key, PROFILE_TTL);
            return new LinkedHashMap<>(data);
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis", e);
            return Collections.emptyMap();
        }
    }

    private List<Map<String, Object>> listHashProfiles(String prefix, int page, int pageSize) {
        List<Map<String, Object>> profiles = new ArrayList<>();
        try (Jedis jedis = createJedis()) {
            ScanParams scanParams = new ScanParams().match(prefix + "*").count(SCAN_BATCH_SIZE);
            String cursor = ScanParams.SCAN_POINTER_START;
            List<String> keys = new ArrayList<>();
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                keys.addAll(scanResult.getResult());
                if (keys.size() >= MAX_SCAN_KEYS) {
                    break;
                }
                cursor = scanResult.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));

            // 分页
            int start = (page - 1) * pageSize;
            int end = Math.min(start + pageSize, keys.size());
            if (start >= keys.size()) {
                return Collections.emptyList();
            }
            List<String> pageKeys = keys.subList(start, end);

            // Pipeline 批量 HGETALL
            Pipeline pipeline = jedis.pipelined();
            List<Response<Map<String, String>>> responses = new ArrayList<>();
            for (String key : pageKeys) {
                responses.add(pipeline.hgetAll(key));
            }
            pipeline.sync();
            for (int i = 0; i < responses.size(); i++) {
                Map<String, String> data = responses.get(i).get();
                if (!data.isEmpty()) {
                    Map<String, Object> profile = new LinkedHashMap<>(data);
                    profile.put("entity_id", pageKeys.get(i).substring(prefix.length()));
                    profiles.add(profile);
                }
            }
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis", e);
        }
        return profiles;
    }

    // ==================== 基线查询 ====================

    @Override
    public Map<String, Object> getBaseline(String entityType, String entityId) {
        return getHashAsMap(BASELINE_PREFIX + entityType + ":" + entityId);
    }

    @Override
    public void rebuildBaseline(String entityType, String entityId) {
        try (Jedis jedis = createJedis()) {
            rebuildBaselineForEntity(jedis, entityType, entityId);
        } catch (JedisConnectionException e) {
            log.error("Failed to connect to Redis", e);
        }
    }

    // ==================== 画像构建（定时任务，方案 5.3） ====================

    @Override
    public void rebuildProfiles() {
        try (Jedis jedis = createJedis()) {
            long lastBuildAt = loadWatermark(jedis, "profile_build_at");
            long now = System.currentTimeMillis();
            long fromScore = profileBuildFromScore(lastBuildAt);
            long toScore = now * 1000 + 999;

            // 1. 构建用户画像
            Set<String> userIndexKeys = scanKeys(jedis, AUDIT_USER_INDEX_PREFIX + "*");
            for (String indexKey : userIndexKeys) {
                // 跳过未信任聚合桶（不为伪造身份建画像）
                if (UNTRUSTED_BUCKET_KEY.equals(indexKey)) {
                    continue;
                }
                String userId = indexKey.substring(AUDIT_USER_INDEX_PREFIX.length());
                buildUserProfile(jedis, userId, indexKey, fromScore, toScore);
            }

            // 2. 构建智能体画像
            Set<String> agentIndexKeys = scanKeys(jedis, AUDIT_AGENT_INDEX_PREFIX + "*");
            for (String indexKey : agentIndexKeys) {
                String agentId = indexKey.substring(AUDIT_AGENT_INDEX_PREFIX.length());
                buildAgentProfile(jedis, agentId, indexKey, fromScore, toScore);
            }

            // 3. 更新水位线
            saveWatermark(jedis, "profile_build_at", now);
            log.info("rebuildProfiles completed, users={}, agents={}", userIndexKeys.size(), agentIndexKeys.size());
        } catch (JedisConnectionException e) {
            log.error("rebuildProfiles failed: Redis connection error", e);
        } catch (Exception e) {
            log.error("rebuildProfiles failed", e);
        }
    }

    private void buildUserProfile(Jedis jedis, String userId, String indexKey,
        long fromScore, long toScore) {
        // 增量拉取审计日志（ZRANGEBYSCORE，禁止 SCAN）
        Set<String> eventIds = jedis.zrangeByScore(indexKey, fromScore, toScore);
        if (eventIds.isEmpty()) {
            return;
        }
        List<JSONObject> logs = batchGetFreshProfileLogs(jedis, PROFILE_PROCESSED_USER_PREFIX + userId, eventIds);
        if (logs.isEmpty()) {
            return;
        }

        String profileKey = PROFILE_USER_PREFIX + userId;
        long now = System.currentTimeMillis();

        // 聚合数值字段
        long totalRequests = logs.size();
        long totalTokens = 0;
        long totalViolations = 0;
        long totalEventCount = 0;
        long degradeCount = 0;
        long sumRiskScore = 0;
        long maxRiskScore = 0;
        Set<String> sessionIds = new java.util.HashSet<>();
        Map<String, Integer> modelFreq = new HashMap<>();
        Map<String, Integer> toolFreq = new HashMap<>();
        Map<String, Integer> agentFreq = new HashMap<>();
        Map<String, Integer> ipFreq = new HashMap<>();
        int[] hourCounts = new int[24];

        for (JSONObject log : logs) {
            totalTokens += log.getLongValue("input_token") + log.getLongValue("output_token");
            sumRiskScore += log.getIntValue("risk_score");
            maxRiskScore = Math.max(maxRiskScore, log.getIntValue("risk_score"));
            String sessionId = log.getString("session_id");
            if (sessionId != null) {
                sessionIds.add(sessionId);
            }
            JSONArray events = log.getJSONArray("events");
            if (events != null && !events.isEmpty()) {
                totalViolations++;
                totalEventCount += events.size();
            }
            String action = log.getString("action");
            if ("alert".equals(action) || "enhance".equals(action)) {
                degradeCount++;
            }
            incrementFreq(modelFreq, log.getString("model"));
            incrementFreq(toolFreq, log.getString("tool_name"));
            incrementFreq(agentFreq, log.getString("agent_id"));
            incrementFreq(ipFreq, log.getString("source_ip"));
            // 按时段统计（使用日志 timestamp，秒级 → 小时）
            long ts = log.getLongValue("timestamp");
            if (ts > 0) {
                int hour = (int)((ts / 3600) % 24);
                hourCounts[hour]++;
            }
        }

        // Lua 原子合并数值字段
        List<String> luaArgs = new ArrayList<>();
        luaArgs.add(String.valueOf(PROFILE_TTL));
        luaArgs.add("total_sessions"); luaArgs.add(String.valueOf(sessionIds.size()));
        luaArgs.add("total_requests"); luaArgs.add(String.valueOf(totalRequests));
        luaArgs.add("total_tokens"); luaArgs.add(String.valueOf(totalTokens));
        luaArgs.add("total_violations"); luaArgs.add(String.valueOf(totalViolations));
        luaArgs.add("total_event_count"); luaArgs.add(String.valueOf(totalEventCount));
        luaArgs.add("degrade_count"); luaArgs.add(String.valueOf(degradeCount));
        luaArgs.add("sum_risk_score"); luaArgs.add(String.valueOf(sumRiskScore));

        jedis.eval(LUA_MERGE_PROFILE_NUMERIC, 1,
            evalArgs(profileKey, luaArgs));

        // max_risk_score（best-effort，读-比较-写）
        String oldMaxStr = jedis.hget(profileKey, "max_risk_score");
        long oldMax = oldMaxStr != null ? parseLongSafe(oldMaxStr) : 0;
        if (maxRiskScore > oldMax) {
            jedis.hset(profileKey, "max_risk_score", String.valueOf(maxRiskScore));
        }

        // 基本身份字段 + 时间戳
        Map<String, String> metaFields = new LinkedHashMap<>();
        metaFields.put("user_id", userId);
        metaFields.put("last_seen", String.valueOf(now));
        if (jedis.hget(profileKey, "first_seen") == null) {
            metaFields.put("first_seen", String.valueOf(now));
        }
        // 从最新日志提取用户名/部门/角色
        if (!logs.isEmpty()) {
            JSONObject latest = logs.get(logs.size() - 1);
            putIfNotEmpty(metaFields, "user_name", latest.getString("user_name"));
            putIfNotEmpty(metaFields, "user_dept", latest.getString("user_dept"));
            putIfNotEmpty(metaFields, "user_role", latest.getString("user_role"));
        }
        jedis.hmset(profileKey, metaFields);

        // JSON 频次字段（Java 端合并，容忍最终一致）
        mergeJsonFreqField(jedis, profileKey, "common_models", modelFreq, 5);
        mergeJsonFreqField(jedis, profileKey, "common_tools", toolFreq, 5);
        mergeJsonFreqField(jedis, profileKey, "common_agents", agentFreq, 5);
        mergeJsonFreqField(jedis, profileKey, "common_source_ips", ipFreq, 5);
        mergeHourField(jedis, profileKey, "common_access_hours", hourCounts);

        // 续期
        jedis.expire(profileKey, PROFILE_TTL);
    }

    private void buildAgentProfile(Jedis jedis, String agentId, String indexKey,
        long fromScore, long toScore) {
        Set<String> eventIds = jedis.zrangeByScore(indexKey, fromScore, toScore);
        if (eventIds.isEmpty()) {
            return;
        }
        List<JSONObject> logs = batchGetFreshProfileLogs(jedis, PROFILE_PROCESSED_AGENT_PREFIX + agentId, eventIds);
        if (logs.isEmpty()) {
            return;
        }

        String profileKey = PROFILE_AGENT_PREFIX + agentId;
        long now = System.currentTimeMillis();

        long totalCalls = logs.size();
        long totalTokens = 0;
        long sumRiskScore = 0;
        Set<String> sessionIds = new java.util.HashSet<>();
        Map<String, Integer> toolFreq = new HashMap<>();
        Map<String, Integer> callerFreq = new HashMap<>();
        Map<String, Integer> modelFreq = new HashMap<>();

        for (JSONObject log : logs) {
            totalTokens += log.getLongValue("input_token") + log.getLongValue("output_token");
            sumRiskScore += log.getIntValue("risk_score");
            String sessionId = log.getString("session_id");
            if (sessionId != null) {
                sessionIds.add(sessionId);
            }
            incrementFreq(toolFreq, log.getString("tool_name"));
            incrementFreq(callerFreq, log.getString("user_id"));
            incrementFreq(modelFreq, log.getString("model"));
        }

        // Lua 原子合并数值字段
        List<String> luaArgs = new ArrayList<>();
        luaArgs.add(String.valueOf(PROFILE_TTL));
        luaArgs.add("total_sessions"); luaArgs.add(String.valueOf(sessionIds.size()));
        luaArgs.add("total_calls"); luaArgs.add(String.valueOf(totalCalls));
        luaArgs.add("total_tokens"); luaArgs.add(String.valueOf(totalTokens));
        luaArgs.add("sum_risk_score"); luaArgs.add(String.valueOf(sumRiskScore));

        jedis.eval(LUA_MERGE_PROFILE_NUMERIC, 1,
            evalArgs(profileKey, luaArgs));

        // 基本字段 + 时间戳
        Map<String, String> metaFields = new LinkedHashMap<>();
        metaFields.put("agent_id", agentId);
        metaFields.put("last_seen", String.valueOf(now));
        if (jedis.hget(profileKey, "first_seen") == null) {
            metaFields.put("first_seen", String.valueOf(now));
        }
        if (!logs.isEmpty()) {
            JSONObject latest = logs.get(logs.size() - 1);
            putIfNotEmpty(metaFields, "agent_owner", latest.getString("agent_owner"));
            putIfNotEmpty(metaFields, "agent_type", latest.getString("agent_type"));
        }
        jedis.hmset(profileKey, metaFields);

        // JSON 频次字段
        mergeJsonFreqField(jedis, profileKey, "common_tools", toolFreq, 5);
        mergeJsonFreqField(jedis, profileKey, "common_callers", callerFreq, 5);
        mergeJsonFreqField(jedis, profileKey, "common_models", modelFreq, 5);

        jedis.expire(profileKey, PROFILE_TTL);
    }

    // ==================== 基线建立（定时任务，方案 5.4） ====================

    @Override
    public void rebuildBaselines() {
        try (Jedis jedis = createJedis()) {
            long now = System.currentTimeMillis();
            double alpha = Double.parseDouble(
                getConfigValue(jedis, "baseline_ema_alpha", String.valueOf(DEFAULT_EMA_ALPHA)));
            int minSamples = Integer.parseInt(
                getConfigValue(jedis, "baseline_min_samples", String.valueOf(DEFAULT_MIN_SAMPLES)));

            // 为所有有画像的实体重建基线
            Set<String> userProfileKeys = scanKeys(jedis, PROFILE_USER_PREFIX + "*");
            for (String profileKey : userProfileKeys) {
                String userId = profileKey.substring(PROFILE_USER_PREFIX.length());
                rebuildBaselineForEntity(jedis, "user", userId);
            }

            Set<String> agentProfileKeys = scanKeys(jedis, PROFILE_AGENT_PREFIX + "*");
            for (String profileKey : agentProfileKeys) {
                String agentId = profileKey.substring(PROFILE_AGENT_PREFIX.length());
                rebuildBaselineForEntity(jedis, "agent", agentId);
            }

            log.info("rebuildBaselines completed, users={}, agents={}",
                userProfileKeys.size(), agentProfileKeys.size());
        } catch (JedisConnectionException e) {
            log.error("rebuildBaselines failed: Redis connection error", e);
        } catch (Exception e) {
            log.error("rebuildBaselines failed", e);
        }
    }

    private void rebuildBaselineForEntity(Jedis jedis, String entityType, String entityId) {
        String baselineKey = BASELINE_PREFIX + entityType + ":" + entityId;
        String indexKey = (entityType.equals("user") ? AUDIT_USER_INDEX_PREFIX : AUDIT_AGENT_INDEX_PREFIX) + entityId;

        long lastBuildAt = loadWatermark(jedis, "baseline_build_at:" + entityType + ":" + entityId);
        long now = System.currentTimeMillis();
        long fromScore = lastBuildAt * 1000;
        long toScore = now * 1000 + 999;

        Set<String> eventIds = jedis.zrangeByScore(indexKey, fromScore, toScore);
        if (eventIds.isEmpty()) {
            return;
        }
        List<JSONObject> logs = batchGetLogs(jedis, eventIds);
        if (logs.isEmpty()) {
            return;
        }

        double alpha = Double.parseDouble(
            getConfigValue(jedis, "baseline_ema_alpha", String.valueOf(DEFAULT_EMA_ALPHA)));
        int minSamples = Integer.parseInt(
            getConfigValue(jedis, "baseline_min_samples", String.valueOf(DEFAULT_MIN_SAMPLES)));

        // 计算新样本统计量
        long sampleCount = logs.size();
        double avgRiskScore = 0;
        double avgTokens = 0;
        double avgRequests = 0;
        double avgChainLength = 0;
        double avgLatency = 0;
        Map<String, Integer> modelFreq = new HashMap<>();
        Map<String, Integer> toolFreq = new HashMap<>();
        int[] hourCounts = new int[24];
        Set<String> sessionIds = new java.util.HashSet<>();

        for (JSONObject log : logs) {
            avgRiskScore += log.getIntValue("risk_score");
            avgTokens += log.getLongValue("input_token") + log.getLongValue("output_token");
            sessionIds.add(log.getString("session_id"));
            incrementFreq(modelFreq, log.getString("model"));
            incrementFreq(toolFreq, log.getString("tool_name"));
            avgLatency += log.getLongValue("response_latency");
            long ts = log.getLongValue("timestamp");
            if (ts > 0) {
                int hour = (int)((ts / 3600) % 24);
                hourCounts[hour]++;
            }
        }
        avgRiskScore /= sampleCount;
        avgTokens /= sampleCount;
        avgRequests = sampleCount; // 每次增量窗口的请求数
        avgChainLength = sessionIds.isEmpty() ? 0 : (double)sampleCount / sessionIds.size();
        avgLatency /= sampleCount;

        // 检查旧基线是否存在且样本量足够
        boolean oldExists = jedis.exists(baselineKey);
        long oldSampleCount = 0;
        if (oldExists) {
            String oldCountStr = jedis.hget(baselineKey, "sample_count");
            oldSampleCount = oldCountStr != null ? parseLongSafe(oldCountStr) : 0;
        }

        if (oldExists && oldSampleCount >= minSamples) {
            // EMA 平滑合并（Lua 原子，仅数值字段）
            List<String> luaArgs = new ArrayList<>();
            luaArgs.add(String.valueOf(alpha));
            luaArgs.add(String.valueOf(BASELINE_TTL));
            luaArgs.add("avg_risk_score"); luaArgs.add(String.valueOf(avgRiskScore));
            luaArgs.add("avg_tokens_per_session"); luaArgs.add(String.valueOf(avgTokens));
            luaArgs.add("avg_requests_per_session"); luaArgs.add(String.valueOf(avgRequests));
            luaArgs.add("avg_chain_length"); luaArgs.add(String.valueOf(avgChainLength));
            luaArgs.add("avg_response_latency"); luaArgs.add(String.valueOf(avgLatency));
            luaArgs.add("std_risk_score"); luaArgs.add("0"); // 标准差暂不计算
            // last_build_at 和 sample_count 由 Lua 脚本内部处理
            luaArgs.add(String.valueOf(now)); // ARGV[#ARGV-1] = last_build_at
            luaArgs.add(String.valueOf(sampleCount)); // ARGV[#ARGV] = new sample count

            jedis.eval(LUA_EMA_MERGE_BASELINE, 1,
                evalArgs(baselineKey, luaArgs));

            // JSON 频次字段 Java 端 EMA 合并
            mergeBaselineJsonFreq(jedis, baselineKey, "common_models", modelFreq, alpha);
            mergeBaselineJsonFreq(jedis, baselineKey, "common_tools", toolFreq, alpha);
            mergeHourField(jedis, baselineKey, "common_hours", hourCounts);
        } else {
            // 冷启动：直接初始化
            List<String> luaArgs = new ArrayList<>();
            luaArgs.add(String.valueOf(BASELINE_TTL));
            luaArgs.add("entity_type"); luaArgs.add(entityType);
            luaArgs.add("entity_id"); luaArgs.add(entityId);
            luaArgs.add("baseline_version"); luaArgs.add("1");
            luaArgs.add("computed_at"); luaArgs.add(String.valueOf(now));
            luaArgs.add("last_build_at"); luaArgs.add(String.valueOf(now));
            luaArgs.add("sample_count"); luaArgs.add(String.valueOf(sampleCount));
            luaArgs.add("ema_alpha"); luaArgs.add(String.valueOf(alpha));
            luaArgs.add("avg_risk_score"); luaArgs.add(String.valueOf(avgRiskScore));
            luaArgs.add("avg_tokens_per_session"); luaArgs.add(String.valueOf(avgTokens));
            luaArgs.add("avg_requests_per_session"); luaArgs.add(String.valueOf(avgRequests));
            luaArgs.add("avg_chain_length"); luaArgs.add(String.valueOf(avgChainLength));
            luaArgs.add("avg_response_latency"); luaArgs.add(String.valueOf(avgLatency));
            luaArgs.add("std_risk_score"); luaArgs.add("0");

            jedis.eval(LUA_INIT_BASELINE, 1,
                evalArgs(baselineKey, luaArgs));

            // JSON 频次字段直接写入
            jedis.hset(baselineKey, "common_models", JSON.toJSONString(topFreqKeys(modelFreq, 5)));
            jedis.hset(baselineKey, "common_tools", JSON.toJSONString(topFreqKeys(toolFreq, 5)));
            jedis.hset(baselineKey, "common_hours", JSON.toJSONString(hourCounts));
        }

        // 更新水位线
        saveWatermark(jedis, "baseline_build_at:" + entityType + ":" + entityId, now);
    }

    // ==================== 辅助方法 ====================

    private Set<String> scanKeys(Jedis jedis, String pattern) {
        Set<String> keys = new java.util.LinkedHashSet<>();
        ScanParams scanParams = new ScanParams().match(pattern).count(SCAN_BATCH_SIZE);
        String cursor = ScanParams.SCAN_POINTER_START;
        do {
            ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
            keys.addAll(scanResult.getResult());
            if (keys.size() >= MAX_SCAN_KEYS) {
                break;
            }
            cursor = scanResult.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        return keys;
    }

    private List<JSONObject> batchGetLogs(Jedis jedis, Set<String> eventIds) {
        if (eventIds.isEmpty()) {
            return Collections.emptyList();
        }
        Pipeline pipeline = jedis.pipelined();
        List<Response<String>> responses = new ArrayList<>();
        for (String eventId : eventIds) {
            responses.add(pipeline.get(AUDIT_LOG_PREFIX + eventId));
        }
        pipeline.sync();

        List<JSONObject> logs = new ArrayList<>();
        for (Response<String> resp : responses) {
            String json = resp.get();
            if (json != null) {
                try {
                    logs.add(JSON.parseObject(json));
                } catch (Exception e) {
                    log.warn("Failed to parse audit log JSON", e);
                }
            }
        }
        return logs;
    }

    private List<JSONObject> batchGetFreshProfileLogs(Jedis jedis, String processedKey, Set<String> eventIds) {
        if (eventIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> orderedEventIds = new ArrayList<>(eventIds);
        Pipeline pipeline = jedis.pipelined();
        List<Response<String>> responses = new ArrayList<>();
        for (String eventId : orderedEventIds) {
            responses.add(pipeline.get(AUDIT_LOG_PREFIX + eventId));
        }
        pipeline.sync();

        List<JSONObject> logs = new ArrayList<>();
        for (int i = 0; i < responses.size(); i++) {
            String json = responses.get(i).get();
            if (json == null) {
                continue;
            }
            try {
                JSONObject logObj = JSON.parseObject(json);
                Long added = jedis.sadd(processedKey, orderedEventIds.get(i));
                if (added != null && added == 1L) {
                    logs.add(logObj);
                }
            } catch (Exception e) {
                log.warn("Failed to parse audit log JSON", e);
            }
        }
        if (!logs.isEmpty()) {
            jedis.expire(processedKey, PROFILE_TTL);
        }
        return logs;
    }

    private long loadWatermark(Jedis jedis, String key) {
        String val = jedis.hget(CONFIG_KEY, key);
        if (val == null || val.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void saveWatermark(Jedis jedis, String key, long value) {
        jedis.hset(CONFIG_KEY, key, String.valueOf(value));
    }

    private long profileBuildFromScore(long lastBuildAt) {
        long fromMs = lastBuildAt > PROFILE_BUILD_LOOKBACK_MS ? lastBuildAt - PROFILE_BUILD_LOOKBACK_MS : 0;
        return fromMs * 1000;
    }

    private void incrementFreq(Map<String, Integer> freq, String key) {
        if (key != null && !key.isEmpty()) {
            freq.merge(key, 1, Integer::sum);
        }
    }

    private void putIfNotEmpty(Map<String, String> map, String field, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(field, value);
        }
    }

    private long parseLongSafe(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 合并 JSON 频次字段（Java 端，容忍最终一致）
     * 读取旧频次 → 合并新频次 → 取 top N → 写回
     */
    @SuppressWarnings("unchecked")
    private void mergeJsonFreqField(Jedis jedis, String key, String field,
        Map<String, Integer> newFreq, int topN) {
        if (newFreq.isEmpty()) {
            return;
        }
        String oldJson = jedis.hget(key, field);
        Map<String, Integer> merged = new HashMap<>(newFreq);
        if (oldJson != null && !oldJson.isEmpty()) {
            try {
                List<JSONArray> oldList = JSON.parseArray(oldJson, JSONArray.class);
                // 旧格式：[{"name":"gpt-4","count":10}, ...] 或 ["gpt-4", ...]
                for (Object item : oldList) {
                    if (item instanceof JSONObject) {
                        JSONObject obj = (JSONObject)item;
                        String name = obj.getString("name");
                        int count = obj.getIntValue("count");
                        if (name != null) {
                            merged.merge(name, count, Integer::sum);
                        }
                    } else if (item instanceof String) {
                        merged.merge((String)item, 1, Integer::sum);
                    }
                }
            } catch (Exception e) {
                // 旧格式解析失败，用新频次覆盖
            }
        }
        String newJson = JSON.toJSONString(topFreqObjects(merged, topN));
        jedis.hset(key, field, newJson);
    }

    /**
     * 合并基线 JSON 频次字段（EMA 衰减，Java 端）
     * 旧频次 × (1-alpha) + 新频次 × alpha
     */
    @SuppressWarnings("unchecked")
    private void mergeBaselineJsonFreq(Jedis jedis, String key, String field,
        Map<String, Integer> newFreq, double alpha) {
        String oldJson = jedis.hget(key, field);
        Map<String, Double> merged = new HashMap<>();
        // 旧频次衰减
        if (oldJson != null && !oldJson.isEmpty()) {
            try {
                JSONArray oldArr = JSON.parseArray(oldJson);
                for (Object item : oldArr) {
                    if (item instanceof JSONObject) {
                        JSONObject obj = (JSONObject)item;
                        String name = obj.getString("name");
                        double count = obj.getDoubleValue("count");
                        if (name != null) {
                            merged.put(name, count * (1 - alpha));
                        }
                    }
                }
            } catch (Exception e) {
                // 解析失败，忽略旧值
            }
        }
        // 新频次叠加
        for (Map.Entry<String, Integer> entry : newFreq.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue() * alpha, Double::sum);
        }
        // 取 top 5，过滤低频
        List<JSONObject> result = merged.entrySet().stream()
            .filter(e -> e.getValue() >= 0.5)
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(5)
            .map(e -> {
                JSONObject obj = new JSONObject();
                obj.put("name", e.getKey());
                obj.put("count", Math.round(e.getValue()));
                return obj;
            })
            .collect(Collectors.toList());
        jedis.hset(key, field, JSON.toJSONString(result));
    }

    private void mergeHourField(Jedis jedis, String key, String field, int[] newHours) {
        String oldJson = jedis.hget(key, field);
        int[] merged = new int[24];
        if (oldJson != null && !oldJson.isEmpty()) {
            try {
                JSONArray oldArr = JSON.parseArray(oldJson);
                for (int i = 0; i < oldArr.size() && i < 24; i++) {
                    merged[i] = oldArr.getIntValue(i);
                }
            } catch (Exception e) {
                // 解析失败，用新值
            }
        }
        for (int i = 0; i < 24; i++) {
            merged[i] += newHours[i];
        }
        jedis.hset(key, field, JSON.toJSONString(merged));
    }

    private List<String> topFreqKeys(Map<String, Integer> freq, int topN) {
        return freq.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .limit(topN)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    private List<JSONObject> topFreqObjects(Map<String, Integer> freq, int topN) {
        return freq.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .limit(topN)
            .map(e -> {
                JSONObject obj = new JSONObject();
                obj.put("name", e.getKey());
                obj.put("count", e.getValue());
                return obj;
            })
            .collect(Collectors.toList());
    }

    // ==================== 风险检测引擎（方案 5.5 / 7.1-7.6） ====================

    @Override
    public void runRiskDetection() {
        try (Jedis jedis = createJedis()) {
            long lastRunAt = loadWatermark(jedis, "risk_detection_at");
            long now = System.currentTimeMillis();
            // 回扫 RISK_DETECTION_WINDOW_MS（120s）缓冲，覆盖 Wasm 审计日志的写入延迟
            // （大模型响应可能耗时 60s+，Wasm 在响应阶段才写日志但 score 用请求开始时间戳）
            // 去重窗口 300s 可防止重复告警
            long fromScore = (lastRunAt > RISK_DETECTION_WINDOW_MS ? lastRunAt - RISK_DETECTION_WINDOW_MS : 0) * 1000;
            long toScore = now * 1000 + 999;

            // 收集窗口内活跃 session 与每条日志，执行 per-log 规则
            Set<String> activeSessions = new HashSet<>();
            boolean enabled = "true".equals(getConfigValue(jedis, "enabled", "true"));
            if (!enabled) {
                saveWatermark(jedis, "risk_detection_at", now);
                return;
            }

            // 遍历用户维度索引（跳过 untrusted 桶）
            Set<String> userIndexKeys = scanKeys(jedis, AUDIT_USER_INDEX_PREFIX + "*");
            for (String indexKey : userIndexKeys) {
                if (UNTRUSTED_BUCKET_KEY.equals(indexKey)) {
                    continue;
                }
                Set<String> eventIds = jedis.zrangeByScore(indexKey, fromScore, toScore);
                if (eventIds.isEmpty()) {
                    continue;
                }
                List<JSONObject> logs = batchGetLogs(jedis, eventIds);
                for (JSONObject log : logs) {
                    String sessionId = log.getString("session_id");
                    if (sessionId != null) {
                        activeSessions.add(sessionId);
                    }
                    // per-log 规则
                    detectIdentityMismatch(jedis, log);
                    detectBehaviorRarity(jedis, log);
                }
            }

            // session 级规则：链路异常、数据异常、权限蔓延
            for (String sessionId : activeSessions) {
                Map<String, String> meta = jedis.hgetAll(
                    SESSION_META_PREFIX + sessionId + SESSION_META_SUFFIX);
                if (!meta.isEmpty()) {
                    detectChainAnomaly(jedis, sessionId, meta);
                    detectDataAnomaly(jedis, sessionId, meta);
                }
                detectPrivilegePropagation(jedis, sessionId);
            }

            // 用户级规则：降级滥用（policy_anomaly 规则 2）
            detectPolicyAnomaly(jedis, userIndexKeys);

            saveWatermark(jedis, "risk_detection_at", now);
            log.info("runRiskDetection completed, activeSessions={}", activeSessions.size());
        } catch (JedisConnectionException e) {
            log.error("runRiskDetection failed: Redis connection error", e);
        } catch (Exception e) {
            log.error("runRiskDetection failed", e);
        }
    }

    // ==================== 误报复盘闭环（方案 9.2 / 阶段五 任务 3） ====================

    /**
     * 每日误报复盘任务（由调度器触发，方案 9.2）。
     *
     * 流程：
     * 1. 扫描过去 24h 告警，按 risk_type 聚合 (total, false_positive)
     * 2. 误报率 > 30% → 自动上调 threshold_multiplier ×1.2（绝对阈值提高 20%）
     * 3. 误报率 > 50% → 标记 needs_review=true、auto_block=false（暂停自动阻断）
     * 4. 误报涉及的 user/agent → 加入临时白名单（7 天），createAlert 期间同类告警降级为 low
     * 5. 阈值变更记录写入 agent_behavior:rule_changes:zset（供 KPI policyIterateDays 计算）
     */
    @Override
    public void runRuleFeedback() {
        try (Jedis jedis = createJedis()) {
            long now = System.currentTimeMillis();
            long oneDayAgo = now - 86400000L;

            // 1. 扫描过去 24h 告警并聚合
            Set<String> recentAlertIds = jedis.zrangeByScore(ALERTS_ZSET_KEY, oneDayAgo, now);
            Map<String, long[]> stats = new HashMap<>(); // riskType -> [total, falsePositive]
            Map<String, Set<String>> fpUsers = new HashMap<>();
            Map<String, Set<String>> fpAgents = new HashMap<>();

            for (String alertId : recentAlertIds) {
                Map<String, String> alert = jedis.hgetAll(ALERT_KEY_PREFIX + alertId);
                if (alert.isEmpty()) {
                    continue;
                }
                String riskType = alert.get("risk_type");
                if (riskType == null || riskType.isEmpty()) {
                    continue;
                }
                long[] arr = stats.computeIfAbsent(riskType, k -> new long[2]);
                arr[0]++;
                if ("false_positive".equals(alert.get("disposition"))) {
                    arr[1]++;
                    String userId = alert.get("user_id");
                    String agentId = alert.get("agent_id");
                    if (userId != null && !userId.isEmpty() && !"null".equals(userId)) {
                        fpUsers.computeIfAbsent(riskType, k -> new HashSet<>()).add(userId);
                    }
                    if (agentId != null && !agentId.isEmpty() && !"null".equals(agentId)) {
                        fpAgents.computeIfAbsent(riskType, k -> new HashSet<>()).add(agentId);
                    }
                }
            }

            // 2. 读取当前 risk_thresholds
            String riskThresholdsJson = getConfigValue(jedis, "risk_thresholds", "{}");
            JSONObject riskThresholds;
            try {
                riskThresholds = JSON.parseObject(riskThresholdsJson);
                if (riskThresholds == null) {
                    riskThresholds = new JSONObject();
                }
            } catch (Exception e) {
                log.warn("RuleFeedback: risk_thresholds parse error, resetting to empty JSON", e);
                riskThresholds = new JSONObject();
            }

            boolean thresholdsChanged = false;
            for (Map.Entry<String, long[]> entry : stats.entrySet()) {
                String riskType = entry.getKey();
                long total = entry.getValue()[0];
                long fp = entry.getValue()[1];
                if (total < RULE_FEEDBACK_MIN_SAMPLES) {
                    continue; // 样本不足跳过，避免噪声
                }
                double fpRate = (double) fp / total;

                JSONObject ruleConfig = riskThresholds.getJSONObject(riskType);
                if (ruleConfig == null) {
                    ruleConfig = new JSONObject();
                    ruleConfig.put("threshold_multiplier", 1.0);
                    ruleConfig.put("auto_block", true);
                    ruleConfig.put("needs_review", false);
                }

                if (fpRate > FP_RATE_MANUAL_REVIEW) {
                    // 误报率 > 50%：标记待人工复核，暂停自动阻断
                    ruleConfig.put("auto_block", false);
                    ruleConfig.put("needs_review", true);
                    ruleConfig.put("last_fp_rate", roundRate(fpRate));
                    ruleConfig.put("last_evaluated_at", now);
                    riskThresholds.put(riskType, ruleConfig);
                    thresholdsChanged = true;
                    log.warn("RuleFeedback: riskType={} fpRate={} > 0.5, paused auto-block, marked for review",
                        riskType, String.format("%.4f", fpRate));

                    // 加入临时白名单（7 天）
                    addTempWhitelist(jedis, fpUsers.get(riskType), WHITELIST_USER_PREFIX,
                        riskType, now);
                    addTempWhitelist(jedis, fpAgents.get(riskType), WHITELIST_AGENT_PREFIX,
                        riskType, now);
                } else if (fpRate > FP_RATE_AUTO_ADJUST) {
                    // 误报率 > 30%：自动上调 threshold_multiplier ×1.2
                    double currentMult = ruleConfig.getDoubleValue("threshold_multiplier");
                    if (currentMult <= 0) {
                        currentMult = 1.0;
                    }
                    double newMult = roundRate(currentMult * THRESHOLD_INCREASE_FACTOR);
                    ruleConfig.put("threshold_multiplier", newMult);
                    ruleConfig.put("last_fp_rate", roundRate(fpRate));
                    ruleConfig.put("last_evaluated_at", now);
                    riskThresholds.put(riskType, ruleConfig);
                    thresholdsChanged = true;
                    log.info("RuleFeedback: riskType={} fpRate={} > 0.3, threshold_multiplier {} -> {}",
                        riskType, String.format("%.4f", fpRate), currentMult, newMult);
                }
            }

            // 3. 写回 risk_thresholds + 变更记录（供 policyIterateDays KPI）
            if (thresholdsChanged) {
                jedis.hset(CONFIG_KEY, "risk_thresholds", riskThresholds.toJSONString());
                JSONObject changeRecord = new JSONObject();
                changeRecord.put("timestamp", now);
                changeRecord.put("changes", riskThresholds.toJSONString());
                jedis.zadd(RULE_CHANGES_ZSET_KEY, now, changeRecord.toJSONString());
                jedis.expire(RULE_CHANGES_ZSET_KEY, RULE_CHANGES_TTL);
                log.info("RuleFeedback: risk_thresholds updated, changeRecord saved");
            } else {
                log.info("RuleFeedback: no threshold adjustments needed");
            }
        } catch (JedisConnectionException e) {
            log.error("runRuleFeedback failed: Redis connection error", e);
        } catch (Exception e) {
            log.error("runRuleFeedback failed", e);
        }
    }

    /**
     * 写入临时白名单条目（7 天 TTL，方案 9.2 第 4 步）。
     */
    private void addTempWhitelist(Jedis jedis, Set<String> entityIds, String prefix,
        String riskType, long now) {
        if (entityIds == null || entityIds.isEmpty()) {
            return;
        }
        for (String entityId : entityIds) {
            JSONObject entry = new JSONObject();
            entry.put("risk_type", riskType);
            entry.put("reason", "false_positive_temp_whitelist");
            entry.put("created_at", now);
            entry.put("expire_at", now + (long) WHITELIST_DEFAULT_TTL * 1000);
            jedis.setex(prefix + entityId, WHITELIST_DEFAULT_TTL, entry.toJSONString());
        }
    }

    /**
     * 检查 (userId, agentId) 是否在临时白名单中且匹配该 riskType（方案 9.2 第 4 步降级依据）。
     */
    private boolean isTempWhitelisted(Jedis jedis, String userId, String agentId, String riskType) {
        if (riskType == null) {
            return false;
        }
        if (checkWhitelistEntry(jedis, WHITELIST_USER_PREFIX, userId, riskType)) {
            return true;
        }
        return checkWhitelistEntry(jedis, WHITELIST_AGENT_PREFIX, agentId, riskType);
    }

    private boolean checkWhitelistEntry(Jedis jedis, String prefix, String entityId, String riskType) {
        if (entityId == null || entityId.isEmpty() || "null".equals(entityId)) {
            return false;
        }
        String entry = jedis.get(prefix + entityId);
        if (entry == null) {
            return false;
        }
        try {
            JSONObject obj = JSON.parseObject(entry);
            return riskType.equals(obj.getString("risk_type"));
        } catch (Exception e) {
            return false;
        }
    }

    // --- 7.1 身份错配 ---
    private void detectIdentityMismatch(Jedis jedis, JSONObject log) {
        boolean identityTrusted = log.getBooleanValue("identity_trusted");
        if (!identityTrusted) {
            return; // 未信任身份跳过身份类规则
        }
        String userId = log.getString("user_id");
        String userRole = log.getString("user_role");
        String agentId = log.getString("agent_id");
        String sessionId = log.getString("session_id");
        String traceId = log.getString("trace_id");
        int riskScore = log.getIntValue("risk_score");

        // 规则 1：低权限用户 + 高风险智能体
        if ("guest".equals(userRole) && riskScore >= 70) {
            JSONObject evidence = new JSONObject();
            evidence.put("user_role", userRole);
            evidence.put("session_risk_score", riskScore);
            evidence.put("session_id", sessionId);
            evidence.put("event_id", log.getString("event_id"));
            createAlert(jedis, "identity_mismatch", "high", riskScore,
                userId, agentId, sessionId, traceId,
                "低权限用户驱动高风险智能体",
                "用户(" + userRole + ")驱动风险分:" + riskScore + " 的智能体",
                evidence, Collections.singletonList(log.getString("event_id")));
        }

        // 规则 2：同一智能体短时间内被多用户调用（横向移动）
        if (agentId != null && !agentId.isEmpty()) {
            long distinctUsers = jedis.zcard(CALLERS_WINDOW_PREFIX + agentId);
            if (distinctUsers >= 3) {
                JSONObject evidence = new JSONObject();
                evidence.put("distinct_user_count", distinctUsers);
                evidence.put("agent_id", agentId);
                evidence.put("window_seconds", 300);
                createAlert(jedis, "identity_mismatch", "medium", 50,
                    userId, agentId, sessionId, traceId,
                    "智能体被多用户调用",
                    "智能体" + agentId + "在5分钟内被" + distinctUsers + "个不同用户调用",
                    evidence, Collections.singletonList(log.getString("event_id")));
            }
        }
    }

    // --- 7.2 行为罕见 ---
    private void detectBehaviorRarity(Jedis jedis, JSONObject log) {
        boolean identityTrusted = log.getBooleanValue("identity_trusted");
        if (!identityTrusted) {
            return;
        }
        String userId = log.getString("user_id");
        String agentId = log.getString("agent_id");
        String sessionId = log.getString("session_id");
        String traceId = log.getString("trace_id");
        String model = log.getString("model");
        String toolName = log.getString("tool_name");
        String sourceIp = log.getString("source_ip");

        // 冷启动保护：基线不足时回退到画像判断首次使用
        Map<String, String> baseline = jedis.hgetAll(BASELINE_PREFIX + "user:" + userId);
        boolean baselineAvailable = !baseline.isEmpty();
        if (baselineAvailable) {
            String sampleCountStr = baseline.get("sample_count");
            long sampleCount = sampleCountStr != null ? parseLongSafe(sampleCountStr) : 0;
            int minSamples = Integer.parseInt(
                getConfigValue(jedis, "baseline_min_samples", String.valueOf(DEFAULT_MIN_SAMPLES)));
            if (sampleCount < minSamples) {
                baselineAvailable = false;
            }
        }
        Map<String, String> profile = null;
        if (!baselineAvailable) {
            profile = jedis.hgetAll(PROFILE_USER_PREFIX + userId);
            if (profile.isEmpty()) {
                return;
            }
        }
        String refSource = baselineAvailable ? "基线" : "画像";

        // 全局白名单
        Set<String> whitelistedModels = parseJsonArrayToSet(
            getConfigValue(jedis, "global_whitelisted_models", "[]"));
        Set<String> whitelistedTools = parseJsonArrayToSet(
            getConfigValue(jedis, "global_whitelisted_tools", "[]"));

        // 首次使用模型（基于 Baseline，冷启动时回退到画像）
        if (model != null && !model.isEmpty()) {
            Set<String> knownModels = baselineAvailable
                ? extractNamesFromFreqJson(baseline.get("common_models"))
                : extractNamesFromFreqJson(profile.get("common_models"));
            if (!knownModels.contains(model) && !whitelistedModels.contains(model)) {
                JSONObject evidence = new JSONObject();
                evidence.put("model", model);
                evidence.put("baseline_models", new ArrayList<>(knownModels));
                createAlert(jedis, "behavior_rarity", "low", 20,
                    userId, agentId, sessionId, traceId,
                    "首次使用模型",
                    "用户首次使用模型(" + refSource + "内未见): " + model,
                    evidence, Collections.singletonList(log.getString("event_id")));
            }
        }

        // 首次调用工具
        if (toolName != null && !toolName.isEmpty()) {
            Set<String> knownTools = baselineAvailable
                ? extractNamesFromFreqJson(baseline.get("common_tools"))
                : extractNamesFromFreqJson(profile.get("common_tools"));
            if (!knownTools.contains(toolName) && !whitelistedTools.contains(toolName)) {
                JSONObject evidence = new JSONObject();
                evidence.put("tool", toolName);
                evidence.put("baseline_tools", new ArrayList<>(knownTools));
                createAlert(jedis, "behavior_rarity", "low", 20,
                    userId, agentId, sessionId, traceId,
                    "首次调用工具",
                    "用户首次调用工具(" + refSource + "内未见): " + toolName,
                    evidence, Collections.singletonList(log.getString("event_id")));
            }
        }

        // 首次从某 IP 访问
        if (sourceIp != null && !sourceIp.isEmpty() && !"0.0.0.0".equals(sourceIp)) {
            Set<String> knownIps = baselineAvailable
                ? extractNamesFromFreqJson(baseline.get("common_source_ips"))
                : extractNamesFromFreqJson(profile.get("common_source_ips"));
            if (!knownIps.contains(sourceIp)) {
                JSONObject evidence = new JSONObject();
                evidence.put("source_ip", sourceIp);
                evidence.put("baseline_ips", new ArrayList<>(knownIps));
                createAlert(jedis, "behavior_rarity", "medium", 30,
                    userId, agentId, sessionId, traceId,
                    "新 IP 访问",
                    "用户从新IP地址访问(" + refSource + "内未见): " + sourceIp,
                    evidence, Collections.singletonList(log.getString("event_id")));
            }
        }
    }

    // --- 7.3 链路异常 ---
    private void detectChainAnomaly(Jedis jedis, String sessionId, Map<String, String> meta) {
        String userId = meta.get("user_id");
        String agentId = meta.get("agent_id");
        String traceId = meta.get("trace_id");
        String stepCountStr = meta.get("step_count");
        int stepCount = stepCountStr != null ? Integer.parseInt(stepCountStr) : 0;
        if (stepCount <= 0 || userId == null) {
            return;
        }

        Map<String, String> baseline = jedis.hgetAll(BASELINE_PREFIX + "user:" + userId);
        if (baseline.isEmpty()) {
            return;
        }
        double avgChainLength = parseDoubleSafe(baseline.get("avg_chain_length"));
        int absoluteStepThreshold = Integer.parseInt(
            getConfigValue(jedis, "absolute_step_threshold", "200"));

        // 动态阈值 + 静态绝对阈值兜底
        if (avgChainLength > 0 && stepCount > avgChainLength * 3
            && stepCount > absoluteStepThreshold) {
            JSONObject evidence = new JSONObject();
            evidence.put("step_count", stepCount);
            evidence.put("baseline_avg_chain_length", avgChainLength);
            evidence.put("absolute_threshold", absoluteStepThreshold);
            createAlert(jedis, "chain_anomaly", "medium", 40,
                userId, agentId, sessionId, traceId,
                "会话轮次突增",
                "会话轮次(" + stepCount + ")远超基线均值(" + avgChainLength + ")且超绝对阈值",
                evidence, null);
        }
    }

    // --- 7.4 数据异常 ---
    private void detectDataAnomaly(Jedis jedis, String sessionId, Map<String, String> meta) {
        String userId = meta.get("user_id");
        String agentId = meta.get("agent_id");
        String traceId = meta.get("trace_id");
        String requestCountStr = meta.get("request_count");
        int requestCount = requestCountStr != null ? Integer.parseInt(requestCountStr) : 0;

        // Token 异常
        String totalTokensStr = meta.get("total_tokens");
        long totalTokens = totalTokensStr != null ? parseLongSafe(totalTokensStr) : 0;
        if (totalTokens > 0 && userId != null) {
            Map<String, String> baseline = jedis.hgetAll(BASELINE_PREFIX + "user:" + userId);
            if (!baseline.isEmpty()) {
                double avgTokens = parseDoubleSafe(baseline.get("avg_tokens_per_session"));
                long absoluteTokenThreshold = parseLongSafe(
                    getConfigValue(jedis, "absolute_token_threshold", "1000000"));
                if (avgTokens > 0 && totalTokens > avgTokens * 5
                    && totalTokens > absoluteTokenThreshold) {
                    JSONObject evidence = new JSONObject();
                    evidence.put("total_tokens", totalTokens);
                    evidence.put("baseline_avg_tokens", avgTokens);
                    evidence.put("absolute_threshold", absoluteTokenThreshold);
                    createAlert(jedis, "data_anomaly", "high", 60,
                        userId, agentId, sessionId, traceId,
                        "Token 消耗异常",
                        "Token消耗(" + totalTokens + ")远超基线均值(" + avgTokens
                            + ")且超绝对阈值",
                        evidence, null);
                }
            }
        }

        // 深夜批量操作（后端 Java 侧时区判定）
        String timezone = getConfigValue(jedis, "timezone", "Asia/Shanghai");
        int hour = getCurrentHour(timezone);
        if (hour >= 2 && hour < 6 && requestCount > 10) {
            JSONObject evidence = new JSONObject();
            evidence.put("hour", hour);
            evidence.put("request_count", requestCount);
            evidence.put("timezone", timezone);
            createAlert(jedis, "data_anomaly", "medium", 40,
                userId, agentId, sessionId, traceId,
                "深夜批量操作",
                "深夜时段(" + hour + ":00)批量操作(" + requestCount + "次请求)",
                evidence, null);
        }
    }

    // --- 7.5 策略异常（降级滥用） ---
    private void detectPolicyAnomaly(Jedis jedis, Set<String> userIndexKeys) {
        // 全局降级比例均值（从所有用户画像统计）
        double globalDegradeSum = 0;
        double globalEventSum = 0;
        int userCount = 0;

        // 先收集用户列表与全局统计
        List<String> userIds = new ArrayList<>();
        for (String indexKey : userIndexKeys) {
            if (UNTRUSTED_BUCKET_KEY.equals(indexKey)) {
                continue;
            }
            String userId = indexKey.substring(AUDIT_USER_INDEX_PREFIX.length());
            userIds.add(userId);
            Map<String, String> profile = jedis.hgetAll(PROFILE_USER_PREFIX + userId);
            if (profile.isEmpty()) {
                continue;
            }
            long degradeCount = parseLongSafe(profile.get("degrade_count"));
            long totalEventCount = parseLongSafe(profile.get("total_event_count"));
            if (totalEventCount > 0) {
                globalDegradeSum += degradeCount;
                globalEventSum += totalEventCount;
                userCount++;
            }
        }
        if (globalEventSum == 0 || userCount == 0) {
            return;
        }
        double globalDegradeRatio = globalDegradeSum / globalEventSum;

        // 检查每个用户是否降级比例远超大盘均值 ×3
        for (String userId : userIds) {
            Map<String, String> profile = jedis.hgetAll(PROFILE_USER_PREFIX + userId);
            if (profile.isEmpty()) {
                continue;
            }
            long degradeCount = parseLongSafe(profile.get("degrade_count"));
            long totalEventCount = parseLongSafe(profile.get("total_event_count"));
            if (totalEventCount <= 20) {
                continue; // 样本不足不告警
            }
            double userDegradeRatio = (double)degradeCount / totalEventCount;
            if (userDegradeRatio > globalDegradeRatio * 3) {
                JSONObject evidence = new JSONObject();
                evidence.put("user_degrade_ratio", userDegradeRatio);
                evidence.put("global_degrade_ratio", globalDegradeRatio);
                evidence.put("degrade_count", degradeCount);
                evidence.put("total_event_count", totalEventCount);
                createAlert(jedis, "policy_anomaly", "medium", 45,
                    userId, null, null, null,
                    "降级滥用",
                    "用户 WARN 降级比例(" + String.format("%.2f", userDegradeRatio)
                        + ")远超大盘均值(" + String.format("%.2f", globalDegradeRatio) + ")",
                    evidence, null);
            }
        }
    }

    // --- 7.6 权限蔓延 ---
    private void detectPrivilegePropagation(Jedis jedis, String sessionId) {
        // 读取 session 全量审计日志（ZRANGE 升序）
        String auditZsetKey = "agent_audit:" + sessionId;
        Set<String> eventIds = jedis.zrange(auditZsetKey, 0, -1);
        if (eventIds.isEmpty()) {
            return;
        }
        List<JSONObject> logs = batchGetLogs(jedis, eventIds);
        if (logs.isEmpty()) {
            return;
        }

        // 按 trace_id 分组构建链路
        Map<String, List<JSONObject>> traceGraph = new HashMap<>();
        for (JSONObject log : logs) {
            String traceId = log.getString("trace_id");
            if (traceId == null || traceId.isEmpty()) {
                traceId = "default";
            }
            traceGraph.computeIfAbsent(traceId, k -> new ArrayList<>()).add(log);
        }

        for (List<JSONObject> chain : traceGraph.values()) {
            // 找到链路起点（root，无 parent_step）
            JSONObject root = null;
            for (JSONObject node : chain) {
                String parentStep = node.getString("parent_step");
                if (parentStep == null || parentStep.isEmpty()) {
                    root = node;
                    break;
                }
            }
            if (root == null) {
                root = chain.get(0); // 回退：取第一条
            }
            boolean identityTrusted = root.getBooleanValue("identity_trusted");
            if (!identityTrusted) {
                continue;
            }
            String rootRole = root.getString("user_role");
            String rootUserId = root.getString("user_id");
            String rootSessionId = root.getString("session_id");
            String rootTraceId = root.getString("trace_id");
            if (rootRole == null) {
                continue;
            }
            boolean isLowPrivilege = "guest".equals(rootRole) || "viewer".equals(rootRole);
            if (!isLowPrivilege && !"operator".equals(rootRole)) {
                continue; // 仅关注 guest/viewer/operator
            }

            // 检测链路中高敏感工具调用
            for (JSONObject node : chain) {
                String toolName = node.getString("tool_name");
                String agentId = node.getString("agent_id");
                if (toolName == null || toolName.isEmpty() || agentId == null) {
                    continue;
                }
                Map<String, String> agentProfile = jedis.hgetAll(PROFILE_AGENT_PREFIX + agentId);
                Set<String> privilegedTools = parseJsonArrayToSet(agentProfile.get("privileged_tools"));
                if (!privilegedTools.contains(toolName)) {
                    continue; // 非高敏感工具
                }
                String agentOwner = agentProfile.get("agent_owner");
                JSONObject evidence = new JSONObject();
                evidence.put("root_user", rootUserId);
                evidence.put("root_role", rootRole);
                evidence.put("agent_owner", agentOwner);
                evidence.put("tool", toolName);
                evidence.put("trace_id", rootTraceId);
                evidence.put("event_id", node.getString("event_id"));

                if (isLowPrivilege) {
                    // guest/viewer：无权调用任何高敏感工具
                    createAlert(jedis, "privilege_propagation", "high", 70,
                        rootUserId, agentId, rootSessionId, rootTraceId,
                        "低权限用户调用高敏感工具",
                        "低权限用户(" + rootRole + ")调用高敏感工具: " + toolName
                            + "(Agent 无论是否自建，访客无权调用高敏感工具)",
                        evidence, Collections.singletonList(node.getString("event_id")));
                } else if (!rootUserId.equals(agentOwner)) {
                    // operator：仅当使用非自建的特权 Agent 时告警
                    createAlert(jedis, "privilege_propagation", "medium", 50,
                        rootUserId, agentId, rootSessionId, rootTraceId,
                        "普通用户经非自建 Agent 调用高敏感工具",
                        "普通用户(" + rootRole + ")经非自建 Agent 调用高敏感工具: " + toolName,
                        evidence, Collections.singletonList(node.getString("event_id")));
                }
            }
        }
    }

    // ==================== 告警创建与去重（方案 3.3.4 / 10.5） ====================

    /**
     * 创建告警（含去重）。同一 (user_id, agent_id, session_id, risk_type) 在去重窗口内只生成一条，
     * 后续命中更新 risk_score（取最高值）与 evidence。
     */
    private void createAlert(Jedis jedis, String riskType, String riskLevel, int riskScore,
        String userId, String agentId, String sessionId, String traceId,
        String title, String description, JSONObject evidence, List<String> relatedLogs) {
        // 临时白名单降级（方案 9.2 第 4 步）：误报复盘后 user/agent 被加入临时白名单，
        // 同类风险告警在白名单有效期内降级为 low，避免持续误报打扰。
        if (!"low".equals(riskLevel) && isTempWhitelisted(jedis, userId, agentId, riskType)) {
            riskLevel = "low";
        }

        // 去重键：user_id:agent_id:session_id:risk_type
        String dedupKey = DEDUP_KEY_PREFIX + nullSafe(userId) + ":" + nullSafe(agentId)
            + ":" + nullSafe(sessionId) + ":" + riskType;
        int dedupTtl = Integer.parseInt(
            getConfigValue(jedis, "alert_dedup_window_seconds", String.valueOf(DEDUP_DEFAULT_TTL)));

        // 先生成 alertId，用 SET NX 原子抢占去重键
        String alertId = "ba-" + System.currentTimeMillis() + "-"
            + UUID.randomUUID().toString().substring(0, 8);
        SetParams nxParams = new SetParams().nx().ex(dedupTtl);
        String setResult = jedis.set(dedupKey, alertId, nxParams);
        if (setResult == null) {
            // 去重命中：已有告警，原子更新 risk_score（仅取最高值）
            String existingAlertId = jedis.get(dedupKey);
            if (existingAlertId != null) {
                String evidenceJson = evidence != null ? evidence.toJSONString() : "{}";
                // KEYS[1]=existingAlertId, ARGV[1]=riskScore, ARGV[2]=evidenceJson
                // eval(String, int, String...) 要求 KEYS+ARGV 合并为单个 String[]
                String[] evalParams = {existingAlertId, String.valueOf(riskScore), evidenceJson};
                jedis.eval(LUA_UPDATE_ALERT_SCORE, 1, evalParams);
            }
            return;
        }

        // 创建新告警（Hash 结构，支持 Lua 原子字段更新）
        String alertKey = ALERT_KEY_PREFIX + alertId;
        long now = System.currentTimeMillis();
        List<String> logs = relatedLogs != null ? relatedLogs : Collections.emptyList();

        Map<String, String> alertFields = new LinkedHashMap<>();
        alertFields.put("alert_id", alertId);
        alertFields.put("timestamp", String.valueOf(now));
        alertFields.put("session_id", nullSafe(sessionId));
        alertFields.put("trace_id", nullSafe(traceId));
        alertFields.put("user_id", nullSafe(userId));
        alertFields.put("agent_id", nullSafe(agentId));
        alertFields.put("risk_type", riskType);
        alertFields.put("risk_level", riskLevel);
        alertFields.put("risk_score", String.valueOf(riskScore));
        alertFields.put("title", title);
        alertFields.put("description", description);
        alertFields.put("evidence", evidence != null ? evidence.toJSONString() : "{}");
        alertFields.put("status", "open");
        alertFields.put("disposition", "");
        alertFields.put("disposition_by", "");
        alertFields.put("disposition_at", "0");
        alertFields.put("disposition_note", "");
        alertFields.put("related_logs", JSON.toJSONString(logs));
        jedis.hmset(alertKey, alertFields);
        jedis.expire(alertKey, ALERT_TTL);

        // 写入 ZSET 索引
        jedis.zadd(ALERTS_ZSET_KEY, now, alertId);
        if (userId != null && !userId.isEmpty()) {
            jedis.zadd(ALERTS_USER_ZSET_PREFIX + userId, now, alertId);
        }
        if (agentId != null && !agentId.isEmpty()) {
            jedis.zadd(ALERTS_AGENT_ZSET_PREFIX + agentId, now, alertId);
        }
        jedis.zadd(ALERTS_STATUS_ZSET_PREFIX + "open", now, alertId);

        // 告警计数累计到画像（仅当画像已存在，避免为未构建画像创建空壳）
        // 解决仪表盘/画像 total_alerts 与告警中心一致性问题
        incrementProfileAlertCount(jedis, PROFILE_USER_PREFIX, userId);
        incrementProfileAlertCount(jedis, PROFILE_AGENT_PREFIX, agentId);

        // 去重键已在上方用 SET NX EX 原子设置
    }

    /**
     * 告警创建时累加画像 total_alerts（Lua 原子，仅当画像存在时）。
     */
    private void incrementProfileAlertCount(Jedis jedis, String prefix, String entityId) {
        if (entityId == null || entityId.isEmpty() || "null".equals(entityId)) {
            return;
        }
        String profileKey = prefix + entityId;
        if (!jedis.exists(profileKey)) {
            return;
        }
        List<String> luaArgs = new ArrayList<>();
        luaArgs.add(String.valueOf(PROFILE_TTL));
        luaArgs.add("total_alerts"); luaArgs.add("1");
        jedis.eval(LUA_MERGE_PROFILE_NUMERIC, 1, evalArgs(profileKey, luaArgs));
    }

    // ==================== 告警查询 ====================

    @Override
    public Map<String, Object> getAlerts(String status, String riskType, String userId, String agentId,
        long startTime, long endTime, int page, int pageSize) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Jedis jedis = createJedis()) {
            // 选择索引：优先按状态/用户/智能体过滤，否则用全局 ZSET
            String indexKey = ALERTS_ZSET_KEY;
            if (status != null && !status.isEmpty()) {
                indexKey = ALERTS_STATUS_ZSET_PREFIX + status;
            } else if (userId != null && !userId.isEmpty()) {
                indexKey = ALERTS_USER_ZSET_PREFIX + userId;
            } else if (agentId != null && !agentId.isEmpty()) {
                indexKey = ALERTS_AGENT_ZSET_PREFIX + agentId;
            }

            long min = startTime > 0 ? startTime : 0;
            long max = endTime > 0 ? endTime : System.currentTimeMillis() + 60000;
            // ZREVRANGEBYSCORE 降序取分页
            int start = (page - 1) * pageSize;
            int end = start + pageSize - 1;
            Set<String> alertIds = jedis.zrevrangeByScore(indexKey, max, min, start, end);
            long total = jedis.zcount(indexKey, min, max);

            // Pipeline 批量获取告警详情
            List<Map<String, Object>> alerts = new ArrayList<>();
            if (!alertIds.isEmpty()) {
                Pipeline pipeline = jedis.pipelined();
                List<Response<Map<String, String>>> responses = new ArrayList<>();
                for (String alertId : alertIds) {
                    responses.add(pipeline.hgetAll(ALERT_KEY_PREFIX + alertId));
                }
                pipeline.sync();
                for (Response<Map<String, String>> resp : responses) {
                    Map<String, String> data = resp.get();
                    if (!data.isEmpty()) {
                        Map<String, Object> alert = new LinkedHashMap<>(data);
                        // risk_type 过滤（无法用 ZSET 索引时的 Java 端过滤）
                        if (riskType != null && !riskType.isEmpty()
                            && !riskType.equals(alert.get("risk_type"))) {
                            continue;
                        }
                        alerts.add(alert);
                    }
                }
            }
            result.put("list", alerts);
            result.put("total", total);
            result.put("page", page);
            result.put("pageSize", pageSize);
        } catch (JedisConnectionException e) {
            log.error("getAlerts failed", e);
            result.put("list", Collections.emptyList());
            result.put("total", 0);
        }
        return result;
    }

    @Override
    public Map<String, Object> getAlertDetail(String alertId) {
        try (Jedis jedis = createJedis()) {
            Map<String, String> data = jedis.hgetAll(ALERT_KEY_PREFIX + alertId);
            if (data.isEmpty()) {
                return Collections.emptyMap();
            }
            return new LinkedHashMap<>(data);
        } catch (JedisConnectionException e) {
            log.error("getAlertDetail failed", e);
            return Collections.emptyMap();
        }
    }

    // ==================== 告警处置（方案 6.3） ====================

    @Override
    public void updateAlertDisposition(String alertId, String disposition, String disposer, String note) {
        try (Jedis jedis = createJedis()) {
            String alertKey = ALERT_KEY_PREFIX + alertId;
            Map<String, String> alert = jedis.hgetAll(alertKey);
            if (alert.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            Map<String, String> updates = new LinkedHashMap<>();
            updates.put("disposition", disposition);
            updates.put("disposition_by", disposer != null ? disposer : "");
            updates.put("disposition_at", String.valueOf(now));
            updates.put("disposition_note", note != null ? note : "");

            // 状态映射
            String newStatus = "acknowledged";
            if ("blacklist".equals(disposition)) {
                newStatus = "resolved";
            } else if ("false_positive".equals(disposition)) {
                newStatus = "false_positive";
            } else if ("degrade_config".equals(disposition)) {
                newStatus = "acknowledged";
            }
            updates.put("status", newStatus);
            jedis.hmset(alertKey, updates);

            // 更新状态索引 ZSET
            String oldStatus = alert.get("status");
            if (oldStatus != null && !oldStatus.equals(newStatus)) {
                jedis.zrem(ALERTS_STATUS_ZSET_PREFIX + oldStatus, alertId);
                jedis.zadd(ALERTS_STATUS_ZSET_PREFIX + newStatus, now, alertId);
            }

            // 拉黑处置：写入黑名单（方案 6.3 处置闭环）
            String userId = alert.get("user_id");
            String agentId = alert.get("agent_id");
            if ("blacklist".equals(disposition)) {
                int ttl = DEFAULT_BLACKLIST_TTL;
                JSONObject blacklistEntry = new JSONObject();
                blacklistEntry.put("reason", alert.get("description"));
                blacklistEntry.put("alert_id", alertId);
                blacklistEntry.put("expire_at", now + (long)ttl * 1000);
                blacklistEntry.put("disposition_by", disposer);
                if (userId != null && !userId.isEmpty() && !"null".equals(userId)) {
                    jedis.setex(BLACKLIST_USER_PREFIX + userId, ttl, blacklistEntry.toJSONString());
                }
                if (agentId != null && !agentId.isEmpty() && !"null".equals(agentId)) {
                    jedis.setex(BLACKLIST_AGENT_PREFIX + agentId, ttl, blacklistEntry.toJSONString());
                }
            }

            // 处置反馈写入画像（方案 阶段五 任务 2）
            // 累计 total_disposed_alerts；按处置类型累计 total_blacklisted / total_false_positives
            updateProfileDisposition(jedis, PROFILE_USER_PREFIX, userId, disposition, disposer, now);
            updateProfileDisposition(jedis, PROFILE_AGENT_PREFIX, agentId, disposition, disposer, now);
        } catch (JedisConnectionException e) {
            log.error("updateAlertDisposition failed", e);
        }
    }

    /**
     * 处置反馈写入画像（方案 阶段五 任务 2）。
     * 累计 total_disposed_alerts，按处置类型分别累计 total_blacklisted / total_false_positives，
     * 并记录最近一次处置动作 last_disposition / last_disposition_at / last_disposition_by。
     */
    private void updateProfileDisposition(Jedis jedis, String prefix, String entityId,
        String disposition, String disposer, long now) {
        if (entityId == null || entityId.isEmpty() || "null".equals(entityId)) {
            return;
        }
        String profileKey = prefix + entityId;
        // 仅当画像存在时更新，避免为已失效实体创建空画像
        if (!jedis.exists(profileKey)) {
            return;
        }
        // Lua 原子累加数值字段（沿用方案 5.3 的原子合并模式）
        List<String> luaArgs = new ArrayList<>();
        luaArgs.add(String.valueOf(PROFILE_TTL));
        luaArgs.add("total_disposed_alerts"); luaArgs.add("1");
        if ("blacklist".equals(disposition)) {
            luaArgs.add("total_blacklisted"); luaArgs.add("1");
        } else if ("false_positive".equals(disposition)) {
            luaArgs.add("total_false_positives"); luaArgs.add("1");
        }
        jedis.eval(LUA_MERGE_PROFILE_NUMERIC, 1, evalArgs(profileKey, luaArgs));

        // 非数值字段直接 HMSET
        Map<String, String> metaFields = new LinkedHashMap<>();
        metaFields.put("last_disposition", disposition != null ? disposition : "");
        metaFields.put("last_disposition_at", String.valueOf(now));
        metaFields.put("last_disposition_by", disposer != null ? disposer : "");
        jedis.hmset(profileKey, metaFields);
    }

    // ==================== 行为统计与时间线 ====================

    @Override
    public Map<String, Object> getBehaviorStats(long startTime, long endTime) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Jedis jedis = createJedis()) {
            long min = startTime > 0 ? startTime : 0;
            long max = endTime > 0 ? endTime : System.currentTimeMillis() + 60000;

            // 告警统计
            long totalAlerts = jedis.zcount(ALERTS_ZSET_KEY, min, max);
            long openAlerts = jedis.zcount(ALERTS_STATUS_ZSET_PREFIX + "open", min, max);
            long resolvedAlerts = jedis.zcount(ALERTS_STATUS_ZSET_PREFIX + "resolved", min, max);
            long acknowledgedAlerts = jedis.zcount(ALERTS_STATUS_ZSET_PREFIX + "acknowledged", min, max);
            long falsePositiveAlerts = jedis.zcount(ALERTS_STATUS_ZSET_PREFIX + "false_positive", min, max);

            // 画像统计
            long userProfiles = scanKeys(jedis, PROFILE_USER_PREFIX + "*").size();
            long agentProfiles = scanKeys(jedis, PROFILE_AGENT_PREFIX + "*").size();

            // 按风险类型统计 + 收集 KPI 所需指标
            Map<String, Integer> alertsByType = new LinkedHashMap<>();
            Map<String, Integer> alertsByLevel = new LinkedHashMap<>();
            // Top 风险用户/智能体聚合：entityId -> [alertCount, maxRiskScore]
            Map<String, long[]> userAlertStats = new HashMap<>();
            Map<String, long[]> agentAlertStats = new HashMap<>();
            Set<String> recentAlertIds = jedis.zrangeByScore(ALERTS_ZSET_KEY, min, max, 0, 999);
            long sumDispositionMinutes = 0;
            long disposedCount = 0;
            Set<String> highRiskSessions = new HashSet<>();
            Set<String> highRiskSessionsReplayable = new HashSet<>();
            for (String alertId : recentAlertIds) {
                Map<String, String> alert = jedis.hgetAll(ALERT_KEY_PREFIX + alertId);
                if (alert.isEmpty()) {
                    continue;
                }
                String riskType = alert.get("risk_type");
                if (riskType != null) {
                    alertsByType.merge(riskType, 1, Integer::sum);
                }
                String riskLevel = alert.get("risk_level");
                if (riskLevel != null) {
                    alertsByLevel.merge(riskLevel, 1, Integer::sum);
                }
                // 聚合 Top 风险用户/智能体（按告警数 + 最高风险分）
                int score = (int) parseLongSafe(alert.get("risk_score"));
                String aUserId = alert.get("user_id");
                if (aUserId != null && !aUserId.isEmpty() && !"null".equals(aUserId)) {
                    long[] us = userAlertStats.computeIfAbsent(aUserId, k -> new long[2]);
                    us[0]++;
                    if (score > us[1]) us[1] = score;
                }
                String aAgentId = alert.get("agent_id");
                if (aAgentId != null && !aAgentId.isEmpty() && !"null".equals(aAgentId)) {
                    long[] as = agentAlertStats.computeIfAbsent(aAgentId, k -> new long[2]);
                    as[0]++;
                    if (score > as[1]) as[1] = score;
                }
                // 平均处置时长：disposition_at - timestamp
                String dispositionAtStr = alert.get("disposition_at");
                if (dispositionAtStr != null) {
                    long dispositionAt = parseLongSafe(dispositionAtStr);
                    if (dispositionAt > 0) {
                        long ts = parseLongSafe(alert.get("timestamp"));
                        if (dispositionAt > ts) {
                            sumDispositionMinutes += (dispositionAt - ts) / 60000L;
                            disposedCount++;
                        }
                    }
                }
                // 高风险会话回放成功率：高风险告警的 session 是否仍有审计日志可回放
                if ("high".equals(riskLevel) || "critical".equals(riskLevel)) {
                    String sessionId = alert.get("session_id");
                    if (sessionId != null && !sessionId.isEmpty()) {
                        if (highRiskSessions.add(sessionId)) {
                            long sessionAuditCount = jedis.zcard("agent_audit:" + sessionId);
                            if (sessionAuditCount > 0) {
                                highRiskSessionsReplayable.add(sessionId);
                            }
                        }
                    }
                }
            }

            // Top 风险用户/智能体（按告警数降序取 Top 5，附带画像 avg_risk_score）
            result.put("top_risk_users", buildTopRiskList(jedis, PROFILE_USER_PREFIX, userAlertStats));
            result.put("top_risk_agents", buildTopRiskList(jedis, PROFILE_AGENT_PREFIX, agentAlertStats));

            result.put("total_alerts", totalAlerts);
            result.put("open_alerts", openAlerts);
            result.put("resolved_alerts", resolvedAlerts);
            result.put("acknowledged_alerts", acknowledgedAlerts);
            result.put("false_positive_alerts", falsePositiveAlerts);
            result.put("user_profiles", userProfiles);
            result.put("agent_profiles", agentProfiles);
            result.put("alerts_by_type", alertsByType);
            result.put("alerts_by_level", alertsByLevel);

            // ===== KPI 指标（方案 6.2 / 阶段五 任务 4） =====
            Map<String, Object> kpi = new LinkedHashMap<>();

            // 1. 误报率：false_positive 处置数 / 总告警数（目标 ≤15%）
            double falsePositiveRate = totalAlerts > 0
                ? (double) falsePositiveAlerts / totalAlerts : 0.0;
            kpi.put("falsePositiveRate", roundRate(falsePositiveRate));
            kpi.put("falsePositiveRateTarget", "<=0.15");

            // 2. 平均处置时长（分钟）：disposed 告警 disposition_at - timestamp 均值（目标 <30min）
            double avgDispositionMinutes = disposedCount > 0
                ? (double) sumDispositionMinutes / disposedCount : 0.0;
            kpi.put("avgDispositionMinutes", roundRate(avgDispositionMinutes));
            kpi.put("avgDispositionMinutesTarget", "<30");

            // 3. 异常会话检出率：人工确认数 / 规则命中数
            //    人工确认 = 已处置（resolved+acknowledged），规则命中 = 总告警（目标 ≥85%）
            long humanConfirmed = resolvedAlerts + acknowledgedAlerts;
            double anomalyDetectRate = totalAlerts > 0
                ? (double) humanConfirmed / totalAlerts : 0.0;
            kpi.put("anomalyDetectRate", roundRate(anomalyDetectRate));
            kpi.put("anomalyDetectRateTarget", ">=0.85");

            // 4. 高风险会话回放成功率：图谱可还原的高风险会话数 / 高风险会话总数（目标 ≥99%）
            double replaySuccessRate = highRiskSessions.size() > 0
                ? (double) highRiskSessionsReplayable.size() / highRiskSessions.size() : 1.0;
            kpi.put("replaySuccessRate", roundRate(replaySuccessRate));
            kpi.put("replaySuccessRateTarget", ">=0.99");

            // 5. 实时告警到达率：告警数 / 活跃会话数（近似值，目标 ≥95%）
            //    真实值需对比审计日志中的"实际异常会话"，本期以告警/活跃会话比近似
            long activeSessions = scanKeys(jedis, SESSION_META_PREFIX + "*" + SESSION_META_SUFFIX).size();
            double alertArrivalRate = activeSessions > 0
                ? Math.min(1.0, (double) totalAlerts / activeSessions) : 0.0;
            kpi.put("alertArrivalRate", roundRate(alertArrivalRate));
            kpi.put("alertArrivalRateTarget", ">=0.95");
            kpi.put("alertArrivalRateNote",
                "approximate: alerts/activeSessions; precise needs audit-log anomaly diff");

            // 6. 高风险前置拦截率：blacklist 拦截的请求数 / 触发 high/critical 告警的会话数（目标 ≥90%）
            //    需 Wasm 上报 blacklist 命中计数（方案未落地埋点），本期返回 null + 说明
            kpi.put("blockInterceptRate", null);
            kpi.put("blockInterceptRateTarget", ">=0.90");
            kpi.put("blockInterceptRateNote",
                "needs Wasm blacklist hit counter instrumentation");

            // 7. 策略迭代闭环周期：规则阈值变更到误报率下降的周期（目标 ≤7 天）
            //    需 RuleFeedbackTask 变更记录 + 后续误报率对比，本期返回最近变更距今（天）
            Long policyIterateDays = computePolicyIterateDays(jedis);
            kpi.put("policyIterateDays", policyIterateDays);
            kpi.put("policyIterateDaysTarget", "<=7");
            if (policyIterateDays == null) {
                kpi.put("policyIterateDaysNote", "no rule change record yet");
            }

            result.put("kpi", kpi);
        } catch (JedisConnectionException e) {
            log.error("getBehaviorStats failed", e);
        }
        return result;
    }

    /**
     * 策略迭代闭环周期：最近一次规则阈值变更距今天数（方案 阶段五 KPI 7）。
     * 由 RuleFeedbackTask 写入 agent_behavior:rule_changes:zset，score=变更时间。
     */
    private Long computePolicyIterateDays(Jedis jedis) {
        try {
            Set<String> latest = jedis.zrevrange("agent_behavior:rule_changes:zset", 0, 0);
            if (latest == null || latest.isEmpty()) {
                return null;
            }
            // score 即变更时间戳
            Double score = jedis.zscore("agent_behavior:rule_changes:zset", latest.iterator().next());
            if (score == null) {
                return null;
            }
            long changedAt = score.longValue();
            long elapsedDays = (System.currentTimeMillis() - changedAt) / 86400000L;
            return elapsedDays;
        } catch (Exception e) {
            return null;
        }
    }

    private static double roundRate(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * 构建 Top 风险实体列表（按告警数降序，Top 5）。
     * 每条包含：entity_id, alert_count, max_risk_score, avg_risk_score（从画像读取，可能为 null）。
     */
    private List<Map<String, Object>> buildTopRiskList(Jedis jedis, String profilePrefix,
        Map<String, long[]> stats) {
        List<Map<String, Object>> top = new ArrayList<>();
        if (stats == null || stats.isEmpty()) {
            return top;
        }
        stats.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .limit(5)
            .forEach(e -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("entity_id", e.getKey());
                item.put("alert_count", e.getValue()[0]);
                item.put("max_risk_score", e.getValue()[1]);
                // 读取画像 avg_risk_score（best-effort，画像可能尚未构建）
                String avgStr = jedis.hget(profilePrefix + e.getKey(), "avg_risk_score");
                item.put("avg_risk_score", avgStr != null ? parseDoubleSafe(avgStr) : null);
                // 用户名（仅用户画像有意义，智能体画像无 user_name 字段）
                if (PROFILE_USER_PREFIX.equals(profilePrefix)) {
                    String name = jedis.hget(profilePrefix + e.getKey(), "user_name");
                    item.put("user_name", name != null ? name : e.getKey());
                }
                top.add(item);
            });
        return top;
    }

    @Override
    public Map<String, Object> getBehaviorTimeline(long startTime, long endTime, String granularity) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> timeline = new ArrayList<>();
        try (Jedis jedis = createJedis()) {
            long min = startTime > 0 ? startTime : 0;
            long max = endTime > 0 ? endTime : System.currentTimeMillis() + 60000;
            // 按 granularity 分桶（hour/day）
            long bucketMs = "day".equals(granularity) ? 86400000L : 3600000L;
            Map<Long, Integer> buckets = new LinkedHashMap<>();
            Set<String> alertIds = jedis.zrangeByScore(ALERTS_ZSET_KEY, min, max);
            for (String alertId : alertIds) {
                String tsStr = jedis.hget(ALERT_KEY_PREFIX + alertId, "timestamp");
                if (tsStr == null) {
                    continue;
                }
                long ts = parseLongSafe(tsStr);
                long bucket = ts / bucketMs * bucketMs;
                buckets.merge(bucket, 1, Integer::sum);
            }
            for (Map.Entry<Long, Integer> entry : buckets.entrySet()) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("timestamp", entry.getKey());
                point.put("count", entry.getValue());
                timeline.add(point);
            }
            timeline.sort((a, b) -> Long.compare((Long)a.get("timestamp"), (Long)b.get("timestamp")));
        } catch (JedisConnectionException e) {
            log.error("getBehaviorTimeline failed", e);
        }
        result.put("list", timeline);
        return result;
    }

    // ==================== 会话图谱（方案 5.6） ====================

    @Override
    public Map<String, Object> getSessionGraph(String sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Jedis jedis = createJedis()) {
            // 1. 读取 session 全量审计日志（ZRANGE 升序）
            String auditZsetKey = "agent_audit:" + sessionId;
            Set<String> eventIds = jedis.zrange(auditZsetKey, 0, -1);
            if (eventIds.isEmpty()) {
                result.put("nodes", Collections.emptyList());
                result.put("edges", Collections.emptyList());
                return result;
            }
            List<JSONObject> logs = batchGetLogs(jedis, eventIds);

            // 2. 构建节点与边
            List<Map<String, Object>> nodes = new ArrayList<>();
            List<Map<String, Object>> edges = new ArrayList<>();
            int maxNodes = 50; // 大图折叠阈值
            for (int i = 0; i < logs.size(); i++) {
                JSONObject log = logs.get(i);
                Map<String, Object> node = new LinkedHashMap<>();
                String stepIndex = log.getString("step_index");
                String nodeId = "step-" + i;
                node.put("id", nodeId);
                node.put("step_index", stepIndex);
                node.put("tool_name", log.getString("tool_name"));
                node.put("step_type", log.getString("step_type"));
                node.put("risk_score", log.getIntValue("risk_score"));
                node.put("high_risk", log.getBooleanValue("high_risk"));
                node.put("model", log.getString("model"));
                node.put("timestamp", log.getLongValue("timestamp"));
                nodes.add(node);

                String parentStep = log.getString("parent_step");
                if (parentStep != null && !parentStep.isEmpty()) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("source", parentStep);
                    edge.put("target", nodeId);
                    edge.put("trace_id", log.getString("trace_id"));
                    edges.add(edge);
                } else if (i > 0) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("source", "step-" + (i - 1));
                    edge.put("target", nodeId);
                    edge.put("trace_id", log.getString("trace_id"));
                    edges.add(edge);
                }
            }

            // 3. 大图折叠：节点数 > maxNodes 时标记需折叠（前端处理折叠展示）
            result.put("nodes", nodes);
            result.put("edges", edges);
            result.put("node_count", nodes.size());
            result.put("folded", nodes.size() > maxNodes);
            result.put("max_nodes", maxNodes);
            result.put("session_id", sessionId);
        } catch (JedisConnectionException e) {
            log.error("getSessionGraph failed", e);
            result.put("nodes", Collections.emptyList());
            result.put("edges", Collections.emptyList());
        }
        return result;
    }

    // ==================== 辅助方法（Phase 3） ====================

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }

    private double parseDoubleSafe(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private int getCurrentHour(String timezone) {
        try {
            return java.time.LocalTime.now(java.time.ZoneId.of(timezone)).getHour();
        } catch (Exception e) {
            return java.time.LocalTime.now().getHour(); // 回退到系统时区
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> parseJsonArrayToSet(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) {
            return Collections.emptySet();
        }
        try {
            JSONArray arr = JSON.parseArray(json);
            Set<String> set = new HashSet<>();
            for (Object item : arr) {
                if (item instanceof String) {
                    set.add((String)item);
                }
            }
            return set;
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractNamesFromFreqJson(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptySet();
        }
        try {
            JSONArray arr = JSON.parseArray(json);
            Set<String> names = new HashSet<>();
            for (Object item : arr) {
                if (item instanceof JSONObject) {
                    String name = ((JSONObject)item).getString("name");
                    if (name != null) {
                        names.add(name);
                    }
                } else if (item instanceof String) {
                    names.add((String)item);
                }
            }
            return names;
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }
}
