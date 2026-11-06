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
 * Update request for the bypass capture configuration.
 */
@Data
@NoArgsConstructor
public class CaptureConfigUpdateRequest {

    /** Selected capture interfaces; an empty list turns capture off. */
    private List<String> interfaces;

    /** Capture interface to reinjection control interface; "" means no injection. */
    private Map<String, String> controlInterfaces;
}
