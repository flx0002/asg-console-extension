/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.controller.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One physical NIC reported by the bypass collector.
 */
@Data
@NoArgsConstructor
public class CaptureNic {

    private String name;

    /** up or down (operstate). */
    private String oper;

    /** Link speed such as "1000Mb/s"; empty when the link is down. */
    private String speed;
}
