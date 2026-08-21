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
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM agent_audit_log WHERE timestamp_ms < :cutoff LIMIT :limit", nativeQuery = true)
    int deleteExpiredBatch(@Param("cutoff") long cutoff, @Param("limit") int limit);
}
