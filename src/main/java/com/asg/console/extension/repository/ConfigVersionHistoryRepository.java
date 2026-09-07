/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.asg.console.extension.model.ConfigVersionHistory;

/**
 * Repository for config version history (IR-028).
 */
public interface ConfigVersionHistoryRepository extends JpaRepository<ConfigVersionHistory, Long> {

    List<ConfigVersionHistory> findByCategoryAndObjectKeyOrderByVersionIdDesc(String category, String objectKey);

    Optional<ConfigVersionHistory> findByCategoryAndObjectKeyAndVersionId(String category, String objectKey,
        Long versionId);

    @Query("select max(v.versionId) from ConfigVersionHistory v where v.category = :category and v.objectKey = :objectKey")
    Long findMaxVersionId(@Param("category") String category, @Param("objectKey") String objectKey);

    List<ConfigVersionHistory> findByCategoryOrderByObjectKeyAscVersionIdDesc(String category);

    List<ConfigVersionHistory> findByCategoryAndObjectKeyAndVersionIdLessThanOrderByVersionIdDesc(String category,
        String objectKey, Long versionId);
}
