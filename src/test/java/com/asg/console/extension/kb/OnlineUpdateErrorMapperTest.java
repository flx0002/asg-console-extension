/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.alibaba.higress.sdk.exception.ValidationException;

/**
 * DEF-011：在线更新异常分层映射自测（不依赖 Spring/DB）。校验三类技术异常被分别译成
 * 可诊断文案（连接 / HTTP 状态 / 响应非法），且状态码被正确带出、彼此不混淆。
 */
class OnlineUpdateErrorMapperTest {

    @Test
    void connectionFailureMapsToCannotConnect() {
        ResourceAccessException e =
            new ResourceAccessException("connect refused", new java.net.ConnectException("Connection refused"));
        ValidationException ve = OnlineUpdateErrorMapper.toValidation(e, "词库");
        assertTrue(ve.getMessage().contains("无法连接"), "should say cannot connect");
        assertTrue(ve.getMessage().contains("词库"), "should carry label");
    }

    @Test
    void httpErrorCarriesStatusCode() {
        HttpClientErrorException e =
            new HttpClientErrorException(HttpStatus.NOT_FOUND, "404 Not Found");
        ValidationException ve = OnlineUpdateErrorMapper.toValidation(e, "KB");
        assertTrue(ve.getMessage().contains("HTTP 404"), "status code must surface: " + ve.getMessage());
        assertTrue(ve.getMessage().contains("KB"));
    }

    @Test
    void badResponseMapsToFormatError() {
        // 非连接、非 HTTP 状态类（如响应体解析失败）归入「响应为空或格式非法」
        ValidationException ve = OnlineUpdateErrorMapper.toValidation(new RuntimeException("boom"), "词库");
        assertTrue(ve.getMessage().contains("响应为空或格式非法"), ve.getMessage());
    }

    @Test
    void notCollapsedIntoCannotConnect() {
        // 关键回归：HTTP 4xx 绝不能被误报为「无法连接」
        String httpMsg = OnlineUpdateErrorMapper.httpMessage("KB", 404);
        assertTrue(!httpMsg.contains("无法连接"), "HTTP-error text must not claim connectivity failure");
        String badMsg = OnlineUpdateErrorMapper.badResponseMessage("KB");
        assertTrue(!badMsg.contains("无法连接"), "format-error text must not claim connectivity failure");
    }

    @Test
    void emptyBodyProducesBadResponse() {
        // 直接取 badResponseMessage 的稳定性（label 拼接入文案）
        assertEquals(OnlineUpdateErrorMapper.badResponseMessage("词库"),
            OnlineUpdateErrorMapper.toValidation(new IllegalStateException("格式错误"), "词库").getMessage());
    }
}
