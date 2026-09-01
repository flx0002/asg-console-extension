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

export interface ShadowAiDetectEvent {
  id: number;
  eventTime: string | number[];
  detectType: string;
  domain: string;
  category?: string;
  riskLevel?: string;
  status?: string;
  source?: string;
  srcIp?: string;
  sessionId?: string;
  detail?: string;
  createdAt?: string | number[];
}

export interface ShadowAiDetectEventPage {
  items: ShadowAiDetectEvent[];
  total: number;
  page: number;
  size: number;
}

export interface ShadowAiDetectEventQuery {
  page?: number;
  size?: number;
  domain?: string;
  status?: string;
  category?: string;
  riskLevel?: string;
  source?: string;
}

// Long-format trend point for the detection trend chart (IR-004):
// { time: "MM-dd HH:00", status: 'blocked' | 'allowed' | 'monitored', count }
export interface ShadowAiTrendPoint {
  time: string;
  status: string;
  count: number;
}

// Authorized-domain view of the DNS/bypass policy (IR-003 unified authorization)
export interface ShadowAiDnsPolicyView {
  mode: string;
  domains: string[];
}

// Incremental update of the authorized-domain list (add or remove entries)
export interface ShadowAiAuthzUpdate {
  mode?: string;
  addDomains?: string[];
  removeDomains?: string[];
}
