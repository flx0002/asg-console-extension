/*
 * Copyright (c) 2022-2024 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * specific language governing permissions and limitations under the License.
 */
package com.asg.console.extension.service;

import java.io.BufferedReader;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import lombok.extern.slf4j.Slf4j;

/**
 * Collects audit logs from Higress Gateway pod stdout (ai_log access log format),
 * parses the embedded {@code agent_guard_audit} field, and writes them to Redis
 * via {@link AuditChainService#writeAuditLog}.
 *
 * <p>This service bridges the gap when the Wasm runtime does not support Redis
 * host functions (proxy_redis_init not compiled into Envoy). In that case the
 * ai-agent-guard plugin still writes audit data to Envoy's ai_log (filter state
 * -> access log stdout), and this collector reads those logs and persists them
 * to the Redis audit chain that Console modules read from.</p>
 *
 * <p>Idempotency: a bounded LRU set of seen event_ids is kept in memory to avoid
 * re-writing the same log on every collection cycle.</p>
 */
@Slf4j
public class AuditLogCollectorService {

    private static final String GATEWAY_NAMESPACE = "higress-system";
    private static final String GATEWAY_LABEL_SELECTOR = "app=higress-gateway";
    private static final String GATEWAY_CONTAINER = "higress-gateway";

    /** Number of recent event_ids to remember for dedup. */
    private static final int DEDUP_SET_MAX = 5000;

    private final CoreV1Api coreV1Api;
    private final AuditChainService auditChainService;

    /** Dedup set: event_ids already written in this JVM lifetime. */
    private final Set<String> seenEventIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public AuditLogCollectorService(ApiClient apiClient, AuditChainService auditChainService) {
        this.coreV1Api = new CoreV1Api(apiClient);
        this.auditChainService = auditChainService;
    }

    /**
     * Collect audit logs from all gateway pods since the given number of seconds.
     * Returns the number of audit entries successfully written.
     *
     * @param sinceSeconds look back window in seconds (e.g. 120 for last 2 minutes)
     * @return number of audit entries written
     */
    public int collect(int sinceSeconds) {
        int written = 0;
        try {
            V1PodList podList = coreV1Api.listNamespacedPod(GATEWAY_NAMESPACE, null, null, null,
                null, GATEWAY_LABEL_SELECTOR, null, null, null, null, null);
            for (V1Pod pod : podList.getItems()) {
                String podName = pod.getMetadata().getName();
                if (!isRunning(pod)) {
                    continue;
                }
                written += collectFromPod(podName, sinceSeconds);
            }
        } catch (ApiException e) {
            log.error("Failed to list gateway pods: code={} msg={}", e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Failed to collect audit logs from gateway pods", e);
        }
        if (written > 0) {
            log.info("AuditLogCollector: collected and wrote {} audit entries", written);
        }
        return written;
    }

    private boolean isRunning(V1Pod pod) {
        return pod.getStatus() != null && "Running".equals(pod.getStatus().getPhase());
    }

    private int collectFromPod(String podName, int sinceSeconds) {
        int written = 0;
        try {
            String logText = coreV1Api.readNamespacedPodLog(podName, GATEWAY_NAMESPACE, GATEWAY_CONTAINER,
                Boolean.FALSE, null, null, null, Boolean.FALSE, sinceSeconds, null, Boolean.FALSE);
            if (logText == null || logText.isEmpty()) {
                return 0;
            }
            try (BufferedReader reader = new BufferedReader(
                    new java.io.StringReader(logText))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        if (processLogLine(line)) {
                            written++;
                        }
                    } catch (Exception e) {
                        // skip unparseable lines silently
                    }
                }
            }
        } catch (ApiException e) {
            log.warn("Failed to read pod log for {}: code={} msg={}", podName, e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to read pod log for {}: {}", podName, e.getMessage());
        }
        return written;
    }

    /**
     * Parse a single gateway access log line, extract agent_guard_audit, write to Redis.
     *
     * @return true if an audit entry was written, false otherwise
     */
    private boolean processLogLine(String line) {
        if (line == null || line.isEmpty() || !line.contains("agent_guard_audit")) {
            return false;
        }

        JSONObject accessLog;
        try {
            accessLog = JSON.parseObject(line);
        } catch (Exception e) {
            return false;
        }

        String aiLogStr = accessLog.getString("ai_log");
        if (aiLogStr == null || aiLogStr.isEmpty()) {
            return false;
        }

        JSONObject aiLog;
        try {
            aiLog = JSON.parseObject(aiLogStr);
        } catch (Exception e) {
            return false;
        }

        String auditJsonStr = aiLog.getString("agent_guard_audit");
        if (auditJsonStr == null || auditJsonStr.isEmpty()) {
            return false;
        }

        JSONObject audit;
        try {
            audit = JSON.parseObject(auditJsonStr);
        } catch (Exception e) {
            return false;
        }

        String eventId = audit.getString("event_id");
        if (eventId == null || eventId.isEmpty()) {
            return false;
        }

        // Dedup: skip if already written
        if (seenEventIds.contains(eventId)) {
            return false;
        }

        long timestampMs = audit.getLongValue("timestamp_ms");
        if (timestampMs == 0) {
            timestampMs = audit.getLongValue("timestamp") * 1000L;
        }
        if (timestampMs == 0) {
            timestampMs = System.currentTimeMillis();
        }

        // Determine sessionId: use session_id if present, else generate degraded virtual session
        String sessionId = audit.getString("session_id");
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "degraded_" + Instant.ofEpochMilli(timestampMs)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HH"));
            audit.put("session_id", sessionId);
        }

        // Infer security events from access log response_code and response_code_details.
        // Wasm plugins run in separate VMs, so Dynamic Metadata doesn't cross plugin boundaries.
        // We reconstruct events here based on which plugin blocked/processed the request.
        inferSecurityEvents(audit, accessLog);

        // Update blocked flag and record_types based on inferred events and response code
        String responseCodeStr = accessLog.getString("response_code");
        int responseCode = 0;
        try {
            if (responseCodeStr != null) {
                responseCode = Integer.parseInt(responseCodeStr.trim());
            }
        } catch (NumberFormatException e) {
            // leave as 0
        }
        if (responseCode == 403) {
            audit.put("blocked", true);
            audit.put("response_status", 403);
        }

        String userId = audit.getString("user_id");
        boolean identityTrusted = audit.getBooleanValue("identity_trusted");
        String agentId = audit.getString("agent_id");

        // Re-serialize to ensure session_id is populated
        String finalJson = audit.toJSONString();

        auditChainService.writeAuditLog(finalJson, eventId, sessionId, timestampMs,
            userId, identityTrusted, agentId);

        // Track for dedup (bounded)
        if (seenEventIds.size() >= DEDUP_SET_MAX) {
            // Simple eviction: clear half the set when full
            int toRemove = DEDUP_SET_MAX / 2;
            Set<String> snapshot = new HashSet<>(seenEventIds);
            int removed = 0;
            for (String id : snapshot) {
                seenEventIds.remove(id);
                if (++removed >= toRemove) {
                    break;
                }
            }
        }
        seenEventIds.add(eventId);

        return true;
    }

    /**
     * Infer security events from access log response_code and response_code_details.
     * <p>Since Wasm plugins run in separate VMs, Dynamic Metadata written by ai-prompt-guard
     * / ai-pii-guard cannot be read by ai-agent-guard. We reconstruct the events here
     * based on which plugin blocked or processed the request.</p>
     */
    private void inferSecurityEvents(JSONObject audit, JSONObject accessLog) {
        String responseCodeDetails = accessLog.getString("response_code_details");
        // response_code is a string in access log (e.g. "403"), use string comparison
        String responseCodeStr = accessLog.getString("response_code");
        int responseCode = 0;
        try {
            if (responseCodeStr != null) {
                responseCode = Integer.parseInt(responseCodeStr.trim());
            }
        } catch (NumberFormatException e) {
            // leave as 0
        }
        com.alibaba.fastjson.JSONArray events = audit.getJSONArray("events");
        if (events == null) {
            events = new com.alibaba.fastjson.JSONArray();
        }

        boolean added = false;

        // ai-prompt-guard blocks with 403 and response_code_details contains "ai-prompt-guard"
        if (responseCode == 403 && responseCodeDetails != null
            && responseCodeDetails.contains("ai-prompt-guard")) {
            if (!hasSecurityEvent(events, "prompt_injection", "ai-prompt-guard")) {
                JSONObject event = new JSONObject();
                event.put("type", "prompt_injection");
                event.put("severity", "high");
                event.put("source", "ai-prompt-guard");
                event.put("score", 80);
                event.put("detail", "Detected prompt injection / jailbreak pattern in user message");
                events.add(event);
                added = true;
            }
        }

        // ai-pii-guard doesn't block (it masks), so we can't detect it from response code.
        // PII masking is transparent — no event inferred from access log alone.

        if (added) {
            audit.put("events", events);
            // Update record_types to include security_event
            com.alibaba.fastjson.JSONArray recordTypes = audit.getJSONArray("record_types");
            if (recordTypes == null) {
                recordTypes = new com.alibaba.fastjson.JSONArray();
                recordTypes.add("degraded");
            }
            boolean hasSecurityEvent = false;
            for (Object t : recordTypes) {
                if ("security_event".equals(t)) {
                    hasSecurityEvent = true;
                    break;
                }
            }
            if (!hasSecurityEvent) {
                recordTypes.add("security_event");
            }
            audit.put("record_types", recordTypes);
            audit.put("high_risk", true);
        }
    }

    private boolean hasSecurityEvent(com.alibaba.fastjson.JSONArray events, String type, String source) {
        for (Object item : events) {
            if (!(item instanceof JSONObject)) {
                continue;
            }
            JSONObject event = (JSONObject)item;
            if (type.equals(event.getString("type")) && source.equals(event.getString("source"))) {
                return true;
            }
        }
        return false;
    }
}
