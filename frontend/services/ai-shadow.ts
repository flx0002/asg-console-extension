/* eslint-disable max-len, no-nested-ternary, @typescript-eslint/no-invalid-void-type */
import request from './request';
import { AiConsumerUsageReport, AiShadowStatus, AiShadowModeRequest, AiShadowActionRequest, AiShadowBatchActionRequest, AiShadowDetectedAccess, AiShadowDetectedStats, AiShadowDetectEventPage, AiShadowDetectEventQuery, AiShadowTrendResponse, AiShadowDnsPolicyView, AiShadowAuthzUpdate, AiShadowAdmissionResponse, AiShadowCaptureConfig, AiShadowCaptureConfigUpdate, AiShadowCaptureNics } from '@/interfaces/ai-shadow';

// metrics=false 让后端跳过 Prometheus：路由管控页只用模式与授权列表，
// 不该被观测组件的连接/读取超时拖住。
export const getAiShadowStatus = (includeMetrics = true): Promise<AiShadowStatus[]> => {
  return request.get<any, AiShadowStatus[]>("/v1/ai-shadow/status", { params: { metrics: includeMetrics } });
};

export const getAiShadowRouteStatus = (routeName: string): Promise<AiShadowStatus> => {
  return request.get<any, AiShadowStatus>(`/v1/ai-shadow/status/${encodeURIComponent(routeName)}`);
};

export const setAiShadowMode = (payload: AiShadowModeRequest): Promise<AiShadowStatus> => {
  return request.put<any, AiShadowStatus>('/v1/ai-shadow/mode', payload);
};

export const performAiShadowAction = (payload: AiShadowActionRequest): Promise<AiShadowStatus> => {
  return request.put<any, AiShadowStatus>('/v1/ai-shadow/action', payload);
};

// Window-scoped variant: the list, the KPI cards and the trend all aggregate the
// same detection-event table, so they must be queried with the same window.
export const getAiShadowDetectedAccesses = (
  params: { startTime?: number; endTime?: number; surface?: string } = {},
): Promise<AiShadowDetectedAccess[]> => {
  return request.get<any, AiShadowDetectedAccess[]>('/v1/ai-shadow/detected', { params });
};

export const getAiShadowDetectedStats = (params: { startTime: number; endTime: number; surface?: string }):
Promise<AiShadowDetectedStats> => {
  return request.get<any, AiShadowDetectedStats>('/v1/ai-shadow/detected-stats', { params });
};

export const setAiShadowDetectMode = (mode: 'monitoring' | 'enforcement'): Promise<void> => {
  return request.put<any, void>('/v1/ai-shadow/detect-mode', { mode });
};

export const getAiShadowDetectMode = (): Promise<string> => {
  return request.get<any, string>('/v1/ai-shadow/detect-mode');
};

export const getAiShadowEnabled = async (): Promise<boolean> => {
  const res = await request.get<any, any>('/v1/ai-shadow/enabled');
  // 现网返回裸布尔值；响应拦截器只在 data 为真值时解包，所以后端若改回
  // {success:true,data:false}，关闭态会连包装体一起交给调用方，必须认出来。
  if (typeof res === 'boolean') return res;
  if (res && typeof res === 'object' && typeof res.data === 'boolean') return res.data;
  // 其余形态（错误页 HTML、异常包装、字段被改名）一律当读取失败抛出：
  // 静默按「开启」处理会让界面显示运行中、并把停用期间的白名单写操作放过去。
  throw new Error('unexpected /v1/ai-shadow/enabled response');
};

export const setAiShadowEnabled = (enabled: boolean): Promise<boolean> => {
  return request.put<any, boolean>('/v1/ai-shadow/enabled', { enabled });
};

export const getAiShadowDetectEvents = (params: AiShadowDetectEventQuery = {}): Promise<AiShadowDetectEventPage> => {
  return request.get<any, AiShadowDetectEventPage>('/v1/ai-shadow/detect-events', { params });
};

// Trend over an explicit window (epoch millis). The backend picks hourly buckets for
// spans up to two days and daily buckets beyond, and reports the choice back.
export const getAiShadowDetectedTrend = (params: { startTime: number; endTime: number; surface?: string }):
Promise<AiShadowTrendResponse> => {
  return request.get<any, AiShadowTrendResponse>('/v1/ai-shadow/detected-trend', { params });
};

// Managed-channel admission rows (surface='route'). The route tab reads this instead of
// Prometheus token counters: rejected requests never produce a token metric, so the
// counter side can only ever show the violations that were allowed through.
export const getAiShadowAdmissionEvents = (params: { startTime?: number; endTime?: number } = {}):
Promise<AiShadowAdmissionResponse> => {
  return request.get<any, AiShadowAdmissionResponse>('/v1/ai-shadow/admission-events', { params });
};

// Consumer usage + admission profile. Separate call from getAiShadowAdmissionEvents
// because the two read different backends (Prometheus vs event table).
export const getAiConsumerUsage = (params: { startTime?: number; endTime?: number } = {}):
Promise<AiConsumerUsageReport> => {
  return request.get<any, AiConsumerUsageReport>('/v1/ai-shadow/consumer-usage', { params });
};

export const getAiShadowAuthorizedDomains = (
  // Window used to compute each entry's hitCount; omit for the backend default window.
  params: { startTime?: number; endTime?: number } = {},
): Promise<AiShadowDnsPolicyView> => {
  return request.get<any, AiShadowDnsPolicyView>('/v1/ai-shadow/authorized-domains', { params });
};

export const updateAiShadowAuthorizedDomains = (payload: AiShadowAuthzUpdate): Promise<AiShadowDnsPolicyView> => {
  return request.put<any, AiShadowDnsPolicyView>('/v1/ai-shadow/authorized-domains', payload);
};

// All callers that can be authorized on a route (the unauthenticated bucket is excluded).
export const getAiShadowConsumers = (): Promise<string[]> => {
  return request.get<any, string[]>('/v1/ai-shadow/consumers');
};

// The drawer submits its final checked set; an empty array falls back to default-deny.
// Per-consumer ADD/REMOVE would leave half-applied state when one call fails midway.
export const replaceAiShadowConsumers = (payload: AiShadowBatchActionRequest): Promise<AiShadowStatus> => {
  return request.put<any, AiShadowStatus>('/v1/ai-shadow/action/batch', payload);
};

// ---- 旁路采集（asg-bypass-guard 镜像口/回注口配置）----

export const getAiShadowCaptureConfig = (): Promise<AiShadowCaptureConfig> => {
  return request.get<any, AiShadowCaptureConfig>('/v1/ai-shadow/capture/config');
};

export const updateAiShadowCaptureConfig = (payload: AiShadowCaptureConfigUpdate): Promise<AiShadowCaptureConfig> => {
  return request.put<any, AiShadowCaptureConfig>('/v1/ai-shadow/capture/config', payload);
};

export const getAiShadowCaptureNics = (): Promise<AiShadowCaptureNics> => {
  return request.get<any, AiShadowCaptureNics>('/v1/ai-shadow/capture/nics');
};
