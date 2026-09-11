import request from './request';
import {
  AiShadowStatus,
  AiShadowModeRequest,
  AiShadowActionRequest,
  AiShadowDetectedAccess,
  AiShadowDetectEventPage,
  AiShadowDetectEventQuery,
  AiShadowTrendPoint,
  AiShadowDnsPolicyView,
  AiShadowAuthzUpdate,
} from '@/interfaces/ai-shadow';

export const getAiShadowStatus = (): Promise<AiShadowStatus[]> => {
  return request.get<any, AiShadowStatus[]>('/v1/ai-shadow/status');
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

export const getAiShadowDetectedAccesses = (): Promise<AiShadowDetectedAccess[]> => {
  return request.get<any, AiShadowDetectedAccess[]>('/v1/ai-shadow/detected');
};

export const setAiShadowDetectMode = (mode: 'monitoring' | 'enforcement'): Promise<void> => {
  return request.put<any, any>('/v1/ai-shadow/detect-mode', { mode });
};

export const getAiShadowDetectMode = (): Promise<string> => {
  return request.get<any, string>('/v1/ai-shadow/detect-mode');
};

export const getAiShadowDetectEvents = (params: AiShadowDetectEventQuery = {}): Promise<AiShadowDetectEventPage> => {
  return request.get<any, AiShadowDetectEventPage>('/v1/ai-shadow/detect-events', { params });
};

export const getAiShadowDetectedTrend = (hours = 24): Promise<AiShadowTrendPoint[]> => {
  return request.get<any, AiShadowTrendPoint[]>('/v1/ai-shadow/detected-trend', { params: { hours } });
};

export const getAiShadowAuthorizedDomains = (): Promise<AiShadowDnsPolicyView> => {
  return request.get<any, AiShadowDnsPolicyView>('/v1/ai-shadow/authorized-domains');
};

export const updateAiShadowAuthorizedDomains = (payload: AiShadowAuthzUpdate): Promise<AiShadowDnsPolicyView> => {
  return request.put<any, AiShadowDnsPolicyView>('/v1/ai-shadow/authorized-domains', payload);
};
