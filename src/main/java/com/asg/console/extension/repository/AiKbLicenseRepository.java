/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.asg.console.extension.model.AiKbLicense;

/** KB 授权状态仓储（单行表 id=1）。 */
public interface AiKbLicenseRepository extends JpaRepository<AiKbLicense, Long> {
}
