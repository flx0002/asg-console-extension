/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.config;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.asg.console.extension.service.AuditChainService;
import com.asg.console.extension.util.AdminOperatorResolver;

/** Registers the console-wide admin-operation audit interceptor and wires the operator resolver. */
@Configuration
public class AsgWebMvcConfiguration implements WebMvcConfigurer, ApplicationContextAware {

    private final AuditChainService auditChainService;

    public AsgWebMvcConfiguration(AuditChainService auditChainService) {
        this.auditChainService = auditChainService;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        AdminOperatorResolver.setApplicationContext(applicationContext);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminOperationAuditInterceptor(auditChainService))
            .addPathPatterns("/v1/**");
    }
}
