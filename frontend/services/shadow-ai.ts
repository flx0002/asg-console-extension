import request from './request';
import { ShadowAiStatus, ShadowAiModeRequest, ShadowAiActionRequest, ShadowAiDetectedAccess } from '@/interfaces/shadow-ai';

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
