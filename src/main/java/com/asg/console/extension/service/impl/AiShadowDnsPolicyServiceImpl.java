/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.asg.console.extension.model.AiShadowDnsPolicy;
import com.asg.console.extension.repository.AiShadowDnsPolicyRepository;
import com.alibaba.higress.sdk.exception.ValidationException;

import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of {@link AiShadowDnsPolicyService}. The policy is a
 * single-row table: the first access lazily creates the default monitoring
 * policy so that the system degrades gracefully when MySQL is unavailable.
 */
@Slf4j
@Service
public class AiShadowDnsPolicyServiceImpl implements AiShadowDnsPolicyService {

    private AiShadowDnsPolicyRepository policyRepository;

    @Resource
    public void setPolicyRepository(AiShadowDnsPolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    @PostConstruct
    public void init() {
        try {
            getPolicy();
        } catch (Exception e) {
            log.warn("Failed to initialize shadow AI DNS policy: {}", e.getMessage());
        }
    }

    @Override
    public AiShadowDnsPolicy getPolicy() {
        AiShadowDnsPolicy policy = policyRepository.findById(1L).orElse(null);
        if (policy == null) {
            policy = new AiShadowDnsPolicy();
            policy.setMode(AiShadowDnsPolicy.MODE_MONITORING);
            policy.setAuthorizedDomains("");
            policy.setUpdatedAt(LocalDateTime.now());
            policy = policyRepository.save(policy);
            log.info("Initialized default shadow AI DNS policy: {}", policy.getMode());
        }
        return policy;
    }

    @Override
    public AiShadowDnsPolicy updatePolicy(String mode, List<String> authorizedDomains) {
        if (StringUtils.isBlank(mode)) {
            throw new ValidationException("mode must not be blank");
        }
        String normalizedMode = mode.trim().toLowerCase(Locale.ROOT);
        if (!AiShadowDnsPolicy.MODE_MONITORING.equals(normalizedMode)
            && !AiShadowDnsPolicy.MODE_ENFORCEMENT.equals(normalizedMode)) {
            throw new ValidationException("mode must be one of: monitoring, enforcement");
        }
        AiShadowDnsPolicy policy = getPolicy();
        policy.setMode(normalizedMode);
        policy.setAuthorizedDomains(normalizeDomains(authorizedDomains));
        policy.setUpdatedAt(LocalDateTime.now());
        policy = policyRepository.save(policy);
        log.info("Updated shadow AI DNS policy: mode={}, authorized={}", policy.getMode(), policy.getAuthorizedDomains());
        return policy;
    }

    private String normalizeDomains(List<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return "";
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String domain : domains) {
            if (StringUtils.isBlank(domain)) {
                continue;
            }
            String d = domain.trim().toLowerCase(Locale.ROOT).replaceAll("^\\*\\.", "");
            if (d.startsWith(".")) {
                d = d.substring(1);
            }
            normalized.add(d);
        }
        return String.join(",", normalized);
    }
}
