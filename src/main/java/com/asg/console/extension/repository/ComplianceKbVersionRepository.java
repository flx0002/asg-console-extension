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

import com.asg.console.extension.model.ComplianceKbVersion;

/** 内容合规词库版本仓储（镜像 {@link AiKbVersionRepository}）。 */
public interface ComplianceKbVersionRepository extends JpaRepository<ComplianceKbVersion, Long> {

    /** 当前生效版本（至多一行）。 */
    Optional<ComplianceKbVersion> findFirstByStatusOrderByCreatedAtDesc(String status);

    /** 指定状态的全部版本（用于把旧 active 批量降级）。 */
    List<ComplianceKbVersion> findAllByStatus(String status);

    /** 版本历史（新→旧）。 */
    List<ComplianceKbVersion> findAllByOrderByVersionNoDesc();

    /** 按版本号定位（回滚用，版本号唯一）。 */
    Optional<ComplianceKbVersion> findByVersionNo(Long versionNo);

    /** 最大版本号，用于递增。 */
    Optional<ComplianceKbVersion> findFirstByOrderByVersionNoDesc();
}
