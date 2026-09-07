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

import org.springframework.data.jpa.repository.JpaRepository;

import com.asg.console.extension.model.ConfigBackupLog;

/**
 * Repository for whole-machine backup operation log (IR-028).
 */
public interface ConfigBackupLogRepository extends JpaRepository<ConfigBackupLog, Long> {

    List<ConfigBackupLog> findTop50ByOrderByCreatedAtDesc();
}
