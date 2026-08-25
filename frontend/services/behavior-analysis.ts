import request from './request';

// 行为分析配置
export const getBehaviorConfig = (): Promise<any> => {
  return request.get('/v1/behavior-analysis/config');
};

export const updateBehaviorConfig = (config: any): Promise<any> => {
  return request.put('/v1/behavior-analysis/config', config);
};

// 画像查询
export const listUserProfiles = (params: {
  page?: number;
  pageSize?: number;
}): Promise<any> => {
  return request.get('/v1/behavior-analysis/profiles/users', { params });
};

export const getUserProfile = (userId: string): Promise<any> => {
  return request.get(`/v1/behavior-analysis/profiles/users/${encodeURIComponent(userId)}`);
};

export const listAgentProfiles = (params: {
  page?: number;
  pageSize?: number;
}): Promise<any> => {
  return request.get('/v1/behavior-analysis/profiles/agents', { params });
};

export const getAgentProfile = (agentId: string): Promise<any> => {
  return request.get(`/v1/behavior-analysis/profiles/agents/${encodeURIComponent(agentId)}`);
};

// 告警查询
export const getAlerts = (params: {
  status?: string;
  riskType?: string;
  riskLevel?: string;
  userId?: string;
  agentId?: string;
  startTime?: number;
  endTime?: number;
  page?: number;
  pageSize?: number;
}): Promise<any> => {
  return request.get('/v1/behavior-analysis/alerts', { params });
};

export const getAlertDetail = (alertId: string): Promise<any> => {
  return request.get(`/v1/behavior-analysis/alerts/${encodeURIComponent(alertId)}`);
};

// 告警处置
export const updateAlertDisposition = (
  alertId: string,
  data: {
    disposition: string;
    note?: string;
    ttl?: number;
    dispositionBy?: string;
  },
): Promise<any> => {
  return request.put(`/v1/behavior-analysis/alerts/${encodeURIComponent(alertId)}/disposition`, data);
};

// 统计 & 趋势
export const getBehaviorStats = (params?: {
  startTime?: number;
  endTime?: number;
}): Promise<any> => {
  return request.get('/v1/behavior-analysis/stats', { params });
};

export const getBehaviorTimeline = (params: {
  startTime?: number;
  endTime?: number;
  granularity?: string;
}): Promise<any> => {
  return request.get('/v1/behavior-analysis/timeline', { params });
};

// 会话图谱
export const getSessionGraph = (
  sessionId: string,
  params?: { maxNodes?: number },
): Promise<any> => {
  return request.get(`/v1/behavior-analysis/session-graph/${encodeURIComponent(sessionId)}`, { params });
};

// 基线查询 & 重建
export const getBaseline = (
  entityType: string,
  entityId: string,
): Promise<any> => {
  return request.get(`/v1/behavior-analysis/baselines/${entityType}/${encodeURIComponent(entityId)}`);
};

export const rebuildBaseline = (
  entityType: string,
  entityId: string,
): Promise<any> => {
  return request.post(`/v1/behavior-analysis/baselines/${entityType}/${encodeURIComponent(entityId)}/rebuild`);
};
