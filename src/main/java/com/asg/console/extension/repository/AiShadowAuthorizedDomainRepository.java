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
import org.springframework.stereotype.Repository;

import com.asg.console.extension.model.AiShadowAuthorizedDomain;

@Repository
public interface AiShadowAuthorizedDomainRepository extends JpaRepository<AiShadowAuthorizedDomain, Long> {

    Optional<AiShadowAuthorizedDomain> findByDomain(String domain);

    List<AiShadowAuthorizedDomain> findAllByOrderByCreatedAtAsc();
}
