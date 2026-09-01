/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.controller.dto;

import java.util.List;
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Shadow AI DNS detection policy view.
 */
@Data
@NoArgsConstructor
public class DnsPolicyResponse {

    /** monitoring (record only) or enforcement (block unauthorized). */
    private String mode;

    /** Domains allowed even in enforcement mode. */
    private List<String> authorizedDomains;

    /**
     * Gateway-side AI domain category library from the shadow-ai-detect global
     * plugin configuration (IR-001 alignment): items contain name, label,
     * risk_level, domains, suffixes. Consumed by the bypass collector so its
     * classification matches the gateway; null when unavailable.
     */
    private List<Map<String, Object>> categories;
}
