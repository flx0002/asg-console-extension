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
 * Request body of the shadow AI detect event reporting endpoint.
 */
@Data
public class DetectEventReportRequest {

    private List<DetectEvent> events;

    @Data
    public static class DetectEvent {

        /** Detection type, e.g. dns_shadow_ai. */
        private String detectType;

        /** Access domain (required). */
        private String domain;

        /** AI service category. */
        private String category;

        /** Risk level: high / medium / critical. */
        private String riskLevel;

        /** Handling result: allowed / blocked. */
        private String status;

        /** Event source: dns / gateway. */
        private String source;

        /** Client source IP. */
        private String srcIp;

        /** Correlated audit chain session ID. */
        private String sessionId;

        /** Extra detail (JSON text). */
        private String detail;

        /** Detection time in epoch millis (optional, server time if absent). */
        private Long eventTime;
    }
}
