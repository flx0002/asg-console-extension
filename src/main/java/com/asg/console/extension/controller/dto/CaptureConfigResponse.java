/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.controller.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Shadow AI bypass capture configuration view. {@code interfaces == null} means
 * never configured (the collector keeps its local yaml); an empty list means
 * capture is explicitly turned off.
 */
@Data
@NoArgsConstructor
public class CaptureConfigResponse {

    private List<String> interfaces;

    /** Capture interface to reinjection control interface; "" means no injection. */
    private Map<String, String> controlInterfaces;

    private LocalDateTime updatedAt;
}
