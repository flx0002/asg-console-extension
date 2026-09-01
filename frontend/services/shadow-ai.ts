import request from './request';
import { ShadowAiStatus, ShadowAiModeRequest, ShadowAiActionRequest, ShadowAiDetectedAccess, ShadowAiDetectEventPage, ShadowAiDetectEventQuery, ShadowAiTrendPoint, ShadowAiDnsPolicyView, ShadowAiAuthzUpdate } from '@/interfaces/shadow-ai';

export const getShadowAiStatus = (): Promise<ShadowAiStatus[]> => {
  return request.get<any, ShadowAiStatus[]>('/v1/shadow-ai/status');
};

export const getShadowAiRouteStatus = (routeName: string): Promise<ShadowAiStatus> => {
  return request.get<any, ShadowAiStatus>(`/v1/shadow-ai/status/${encodeURIComponent(routeName)}`);
};

export const setShadowAiMode = (payload: ShadowAiModeRequest): Promise<ShadowAiStatus> => {
  return request.put<any, ShadowAiStatus>('/v1/shadow-ai/mode', payload);
};

export const performShadowAiAction = (payload: ShadowAiActionRequest): Promise<ShadowAiStatus> => {
  return request.put<any, ShadowAiStatus>('/v1/shadow-ai/action', payload);
};

export const getShadowAiDetectedAccesses = (): Promise<ShadowAiDetectedAccess[]> => {
  return request.get<any, ShadowAiDetectedAccess[]>('/v1/shadow-ai/detected');
};

export const setShadowAiDetectMode = (mode: 'monitoring' | 'enforcement'): Promise<void> => {
  return request.put<any, void>('/v1/shadow-ai/detect-mode', { mode });
};

export const getShadowAiDetectMode = (): Promise<string> => {
  return request.get<any, string>('/v1/shadow-ai/detect-mode');
};

export const getShadowAiDetectEvents = (params: ShadowAiDetectEventQuery = {}): Promise<ShadowAiDetectEventPage> => {
  return request.get<any, ShadowAiDetectEventPage>('/v1/shadow-ai/detect-events', { params });
};

export const getShadowAiDetectedTrend = (hours = 24): Promise<ShadowAiTrendPoint[]> => {
  return request.get<any, ShadowAiTrendPoint[]>('/v1/shadow-ai/detected-trend', { params: { hours } });
};

export const getShadowAiAuthorizedDomains = (): Promise<ShadowAiDnsPolicyView> => {
  return request.get<any, ShadowAiDnsPolicyView>('/v1/shadow-ai/authorized-domains');
};

export const updateShadowAiAuthorizedDomains = (payload: ShadowAiAuthzUpdate): Promise<ShadowAiDnsPolicyView> => {
  return request.put<any, ShadowAiDnsPolicyView>('/v1/shadow-ai/authorized-domains', payload);
};
