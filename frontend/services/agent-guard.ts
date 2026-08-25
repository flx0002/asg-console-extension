import request from './request';

// Agent Guard - Session 监控
export const getAgentGuardSessions = (): Promise<any> => {
  return request.get('/v1/agent-guard/sessions');
};

export const getAgentGuardSession = (sessionId: string): Promise<any> => {
  return request.get(`/v1/agent-guard/sessions/${encodeURIComponent(sessionId)}`);
};

export const deleteAgentGuardSession = (sessionId: string): Promise<any> => {
  return request.delete(`/v1/agent-guard/sessions/${encodeURIComponent(sessionId)}`);
};

// Agent Guard - 审计日志
export const getAgentGuardAuditLogs = (limit: number = 100): Promise<any> => {
  return request.get('/v1/agent-guard/audit-logs', { params: { limit } });
};
