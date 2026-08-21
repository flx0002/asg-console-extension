/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

/**
 * Sink for audit log entries produced by the audit chain.
 *
 * <p>The audit chain primary store is Redis; implementations of this interface
 * persist entries to long-term storage (e.g. MySQL) asynchronously so that a
 * storage outage never blocks the gateway-facing write path (IR-015).</p>
 */
public interface AuditLogSink {

    /**
     * Accept a single audit log entry for persistence.
     *
     * @param auditEntryJson full JSON payload of the audit entry (same structure as Wasm plugin AuditLogEntry)
     * @param eventId event id
     * @param sessionId session id (may be virtual degraded_XXX)
     * @param timestampMs timestamp in milliseconds
     * @param userId user id (may be empty)
     * @param identityTrusted whether the identity is trusted
     * @param agentId agent id (may be empty)
     */
    void sink(String auditEntryJson, String eventId, String sessionId,
        long timestampMs, String userId, boolean identityTrusted, String agentId);
}
