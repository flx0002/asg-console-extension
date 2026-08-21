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

import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

/**
 * Audit chain service for session-level audit log query, execution chain tracing,
 * scheduled cleanup and export.
 */
public interface AuditChainService {

    /**
     * Get runtime audit configuration from Redis agent_audit_config Hash.
     *
     * @return config map
     */
    Map<String, Object> getAuditConfig();

    /**
     * Update audit configuration and increment config_version.
     *
     * @param config config map
     */
    void updateAuditConfig(Map<String, Object> config);

    /**
     * Get audit session list (normal sessions + degraded virtual sessions).
     *
     * @return session list
     */
    List<Map<String, Object>> getAuditSessions();

    /**
     * Query audit logs for a specific session with optional recordType filter.
     * Without filter: ZREVRANGE native pagination (exact total).
     * With filter: cursor pagination (total=-1, not exact).
     *
     * @param sessionId session id
     * @param page page number (1-based)
     * @param pageSize page size
     * @param recordType optional record type filter
     * @return result map containing list, total, page, pageSize, exact
     */
    Map<String, Object> getAuditLogs(String sessionId, int page, int pageSize, String recordType);

    /**
     * Get audit log statistics for a specific session.
     *
     * @param sessionId session id
     * @return stats map
     */
    Map<String, Object> getAuditStats(String sessionId);

    /**
     * Clear all audit logs for a specific session.
     *
     * @param sessionId session id
     */
    void clearSessionAuditLogs(String sessionId);

    /**
     * Get execution chain for a specific session (ascending by time).
     *
     * @param sessionId session id
     * @param page page number (1-based)
     * @param pageSize page size
     * @return result map containing list, total, page, pageSize
     */
    Map<String, Object> getSessionAuditChain(String sessionId, int page, int pageSize);

    /**
     * Get execution chain statistics for a specific session.
     *
     * @param sessionId session id
     * @return stats map
     */
    Map<String, Object> getSessionAuditStats(String sessionId);

    /**
     * Cleanup expired audit logs (per-Session SCAN + Pipeline batch DEL + async + distributed lock).
     */
    void cleanupExpiredLogs();

    /**
     * Export audit logs for a specific session (JSON/CSV).
     *
     * @param sessionId session id
     * @param format export format (json or csv)
     * @param response HTTP response to write to
     */
    void exportAuditLogs(String sessionId, String format, HttpServletResponse response);

    /**
     * Write a single audit log entry to Redis (ZSET + detail + indexes).
     * Used by the ai_log collector to mirror Wasm plugin's writeAuditToRedis.
     * Idempotent: re-writing the same eventId overwrites the detail and updates the ZSET score.
     *
     * @param auditEntryJson JSON string of the audit entry (same structure as Wasm plugin AuditLogEntry)
     * @param eventId event id
     * @param sessionId session id (may be virtual degraded_XXX for degraded mode)
     * @param timestampMs timestamp in milliseconds
     * @param userId user id (for index; empty or untrusted goes to untrusted bucket)
     * @param identityTrusted whether the identity is trusted
     * @param agentId agent id (for index; empty skips agent index)
     */
    void writeAuditLog(String auditEntryJson, String eventId, String sessionId,
        long timestampMs, String userId, boolean identityTrusted, String agentId);
}
