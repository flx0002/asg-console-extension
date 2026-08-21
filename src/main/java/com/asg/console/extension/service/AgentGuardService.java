package com.asg.console.extension.service;

import java.util.List;
import java.util.Map;

/**
 * AI Agent Guard Service - 查询 Session 状态和审计日志
 */
public interface AgentGuardService {

    /**
     * 列出所有活跃 Session
     */
    List<Map<String, Object>> listSessions();

    /**
     * 获取指定 Session 的详细信息
     */
    Map<String, Object> getSession(String sessionId);

    /**
     * 获取审计日志（从 Redis List 中读取）
     */
    List<Map<String, Object>> getAuditLogs(int limit);

    /**
     * 删除指定 Session（清理测试数据）
     */
    void deleteSession(String sessionId);
}
