/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.asg.console.extension.service.AuditChainService;

/** Registers the console-wide admin-operation audit interceptor. */
@Configuration
public class AsgWebMvcConfiguration implements WebMvcConfigurer {

    private final AuditChainService auditChainService;

    public AsgWebMvcConfiguration(AuditChainService auditChainService) {
        this.auditChainService = auditChainService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminOperationAuditInterceptor(auditChainService))
            .addPathPatterns("/v1/**");
    }
}
