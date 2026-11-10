/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.asg.console.extension.model.AgentAuditLog;

@Repository
public interface AgentAuditLogRepository extends JpaRepository<AgentAuditLog, Long> {

    /**
     * Find already-persisted event ids for dedup before batch insert.
     */
    List<AgentAuditLog> findByEventIdIn(Collection<String> eventIds);

    /**
     * Delete a bounded batch of entries older than the given epoch-millisecond
     * cutoff (retention policy, IR-057). Bounded deletes keep transactions
     * short on large tables; callers loop until the returned count is below
     * the batch size.
     *
     * @return number of deleted rows
     */
    // Data-plane rows only; admin_operation rows expire on their own (longer) cutoff.
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM agent_audit_log WHERE timestamp_ms < :cutoff "
        + "AND (record_types IS NULL OR record_types NOT LIKE '%admin_operation%') LIMIT :limit",
        nativeQuery = true)
    int deleteExpiredBatch(@Param("cutoff") long cutoff, @Param("limit") int limit);

    /** Admin-operation rows use an independent, longer retention window. */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM agent_audit_log WHERE timestamp_ms < :cutoff "
        + "AND record_types LIKE '%admin_operation%' LIMIT :limit", nativeQuery = true)
    int deleteExpiredAdminBatch(@Param("cutoff") long cutoff, @Param("limit") int limit);

    /**
     * Read-only admin-operation query straight from MySQL. The Redis session list only
     * contains auto-agent_* sessions, so admin_ops is invisible on the existing audit page.
     * action / operator / client_ip live inside raw_json, hence the full-text filters.
     */
    @Query(value = "SELECT id, created_at, timestamp_ms, session_id, user_id, record_types, raw_json "
        + "FROM agent_audit_log WHERE record_types LIKE '%admin_operation%' "
        + "AND timestamp_ms >= COALESCE(:startMs, 0) "
        + "AND timestamp_ms <= COALESCE(:endMs, 4102444800000) "
        + "AND (:action = '' OR raw_json LIKE CONCAT('%', :action, '%') ESCAPE '!') "
        + "AND (:keyword = '' OR raw_json LIKE CONCAT('%', :keyword, '%') ESCAPE '!') "
        + "ORDER BY timestamp_ms DESC LIMIT :rowOffset, :rowLimit", nativeQuery = true)
    List<Object[]> findAdminOperations(@Param("startMs") Long startMs, @Param("endMs") Long endMs,
        @Param("action") String action, @Param("keyword") String keyword,
        @Param("rowOffset") int rowOffset, @Param("rowLimit") int rowLimit);

    @Query(value = "SELECT COUNT(*) FROM agent_audit_log WHERE record_types LIKE '%admin_operation%' "
        + "AND timestamp_ms >= COALESCE(:startMs, 0) "
        + "AND timestamp_ms <= COALESCE(:endMs, 4102444800000) "
        + "AND (:action = '' OR raw_json LIKE CONCAT('%', :action, '%') ESCAPE '!') "
        + "AND (:keyword = '' OR raw_json LIKE CONCAT('%', :keyword, '%') ESCAPE '!')", nativeQuery = true)
    Number countAdminOperations(@Param("startMs") Long startMs, @Param("endMs") Long endMs,
        @Param("action") String action, @Param("keyword") String keyword);
}
