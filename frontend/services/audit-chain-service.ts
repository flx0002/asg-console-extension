import request from './request';

// 审计配置
export const getAuditChainConfig = (): Promise<any> => {
  return request.get('/v1/audit-chain/config');
};

export const updateAuditChainConfig = (config: any): Promise<any> => {
  return request.put('/v1/audit-chain/config', config);
};

// Session 列表
export const getAuditSessions = (): Promise<any> => {
  return request.get('/v1/audit-chain/sessions');
};

// 审计日志查询
export const getAuditLogs = (params: {
  sessionId: string;
  page?: number;
  pageSize?: number;
  recordType?: string;
}): Promise<any> => {
  return request.get('/v1/audit-chain/logs', { params });
};

// 审计日志统计
export const getAuditStats = (sessionId: string): Promise<any> => {
  return request.get('/v1/audit-chain/logs/stats', { params: { sessionId } });
};

// 删除指定 Session 审计日志
export const clearSessionAuditLogs = (sessionId: string): Promise<any> => {
  return request.delete(`/v1/audit-chain/logs/session/${encodeURIComponent(sessionId)}`);
};

// 执行链追踪
export const getSessionAuditChain = (params: {
  sessionId: string;
  page?: number;
  pageSize?: number;
}): Promise<any> => {
  const { sessionId, ...rest } = params;
  return request.get(`/v1/audit-chain/chain/${encodeURIComponent(sessionId)}`, { params: rest });
};

// 执行链统计
export const getSessionAuditStats = (sessionId: string): Promise<any> => {
  return request.get(`/v1/audit-chain/chain/${encodeURIComponent(sessionId)}/stats`);
};

// 手动触发清理
export const cleanupExpiredLogs = (): Promise<any> => {
  return request.post('/v1/audit-chain/cleanup');
};

// 导出审计日志
export const exportAuditLogs = (sessionId: string, format: string = 'json'): Promise<any> => {
  return request.get(`/v1/audit-chain/export/${encodeURIComponent(sessionId)}`, {
    params: { format },
    responseType: 'blob',
  });
};
