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

import lombok.Data;

/**
 * Request body for updating the shadow AI DNS detection policy.
 */
@Data
public class DnsPolicyUpdateRequest {

    /** monitoring (record only) or enforcement (block unauthorized). */
    private String mode;

    /** Domains allowed even in enforcement mode. */
    private List<String> authorizedDomains;
}
