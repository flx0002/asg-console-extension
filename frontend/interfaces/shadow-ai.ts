export interface ShadowAiEntry {
  consumer: string;
  model: string;
  inputTokens: number;
  outputTokens: number;
  requestCount: number;
  authorized: boolean;
}

export interface ShadowAiStatus {
  routeName: string;
  mode: 'monitoring' | 'enforcement';
  authEnabled: boolean;
  authorizedConsumers: string[];
  shadowAiList: ShadowAiEntry[];
}

export interface ShadowAiModeRequest {
  routeName: string;
  mode: 'monitoring' | 'enforcement';
}

export interface ShadowAiActionRequest {
  routeName: string;
  consumerName: string;
  action: 'authorize' | 'block';
}

export interface ShadowAiDetectedAccess {
  sni: string;
  category: string;
  categoryLabel: string;
  riskLevel: string;
  status: string;
  requestCount: number;
}
