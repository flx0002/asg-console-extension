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
import lombok.NoArgsConstructor;

/**
 * Bypass collector NIC inventory view for the capture config page.
 */
@Data
@NoArgsConstructor
public class CaptureNicsResponse {

    /** False when the bypass collector could not be probed; nics is then empty. */
    private boolean agentOnline;

    private List<CaptureNic> nics;
}
