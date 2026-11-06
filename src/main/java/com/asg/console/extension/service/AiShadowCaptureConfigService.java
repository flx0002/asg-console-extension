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
import java.util.Map;

import com.asg.console.extension.controller.dto.CaptureNic;
import com.asg.console.extension.model.AiShadowCaptureConfig;

/**
 * Service for shadow AI bypass capture configuration (capture interfaces and
 * per-interface reinjection control ports of the bypass collector).
 */
public interface AiShadowCaptureConfigService {

    /** Returns the singleton row; never null (lazily created empty). */
    AiShadowCaptureConfig getConfig();

    /**
     * Persist the capture selection. Passing an empty list explicitly turns
     * capture off; the mapping entry value is the control interface, an empty
     * string means "do not inject", a missing key defaults to the same port.
     */
    AiShadowCaptureConfig updateConfig(List<String> interfaces, Map<String, String> controlInterfaces);

    /**
     * Probe the bypass collector for its current physical NIC inventory.
     * Returns null when the collector is unreachable; the caller degrades
     * gracefully instead of failing the page.
     */
    List<CaptureNic> probeNics();
}
