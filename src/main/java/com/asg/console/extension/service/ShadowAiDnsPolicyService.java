/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

import java.util.List;

import com.asg.console.extension.model.ShadowAiDnsPolicy;

/**
 * Service for shadow AI DNS detection policy (monitoring / enforcement mode
 * and authorized domains).
 */
public interface ShadowAiDnsPolicyService {

    ShadowAiDnsPolicy getPolicy();

    ShadowAiDnsPolicy updatePolicy(String mode, List<String> authorizedDomains);
}
