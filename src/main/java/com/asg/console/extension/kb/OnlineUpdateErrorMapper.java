/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.kb;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.alibaba.higress.sdk.exception.ValidationException;

/**
 * DEF-011：在线更新拉取异常的分层映射。把 HTTP 客户端技术异常翻译成用户可读、可诊断的领域错误，
 * 区分「无法连接」「服务端返回 HTTP 错误（状态码）」「响应为空或格式非法」三类，避免一律报「无法连接」
 * 而误导排障方向。由 {@link KbOnlineClient} 的两个 fetch（AI 分类知识库 / 内容合规词库）共用；
 * 原始异常摘要交由调用方落日志，此处只产出对外文案（不含内部机制细节）。
 */
public final class OnlineUpdateErrorMapper {

    private OnlineUpdateErrorMapper() {
    }

    /** 把 fetch 阶段抛出的技术异常翻译成分层文案的 {@link ValidationException}（服务层原样透传该类型）。 */
    public static ValidationException toValidation(Throwable e, String label) {
        if (e instanceof ResourceAccessException) {
            // 连接被拒 / DNS 解析失败 / 读超时（RestTemplate 统一包装为 ResourceAccessException）
            return new ValidationException(connectMessage(label));
        }
        if (e instanceof RestClientResponseException) {
            // 服务端有响应但状态码为 4xx/5xx（如 404 缺路由、500）
            int status = ((RestClientResponseException) e).getRawStatusCode();
            return new ValidationException(httpMessage(label, status));
        }
        // 其余（响应体解析失败、非法/空 JSON 等）
        return new ValidationException(badResponseMessage(label));
    }

    public static String connectMessage(String label) {
        return "在线更新失败：无法连接 " + label + " 在线更新服务器（地址不可达 / 连接被拒 / 超时），"
            + "请检查服务器地址设置或改用离线导入";
    }

    public static String httpMessage(String label, int status) {
        return "在线更新失败：" + label + " 在线更新服务器返回错误（HTTP " + status + "），"
            + "请确认服务端点已就绪或改用离线导入";
    }

    public static String badResponseMessage(String label) {
        return "在线更新失败：" + label + " 在线更新服务器响应为空或格式非法，"
            + "请检查服务端返回内容或改用离线导入";
    }
}
