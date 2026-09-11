export interface AiShadowEntry {
  consumer: string;
  model: string;
  inputTokens: number;
  outputTokens: number;
  requestCount: number;
  authorized: boolean;
}

export interface AiShadowStatus {
  routeName: string;
  mode: 'monitoring' | 'enforcement';
  authEnabled: boolean;
  authorizedConsumers: string[];
  aiShadowList: AiShadowEntry[];
}

export interface AiShadowModeRequest {
  routeName: string;
  mode: 'monitoring' | 'enforcement';
}

export interface AiShadowActionRequest {
  routeName: string;
  consumerName: string;
  action: 'authorize' | 'block';
}

export interface AiShadowDetectedAccess {
  sni: string;
  category: string;
  categoryLabel: string;
  riskLevel: string;
  status: string;
  requestCount: number;
}

export interface AiShadowDetectEvent {
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

export interface AiShadowDetectEventPage {
  items: AiShadowDetectEvent[];
  total: number;
  page: number;
  size: number;
}

export interface AiShadowDetectEventQuery {
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
export interface AiShadowTrendPoint {
  time: string;
  status: string;
  count: number;
}

// Authorized-domain view of the DNS/bypass policy (IR-003 unified authorization)
export interface AiShadowDnsPolicyView {
  mode: string;
  domains: string[];
}

// Incremental update of the authorized-domain list (add or remove entries)
export interface AiShadowAuthzUpdate {
  mode?: string;
  addDomains?: string[];
  removeDomains?: string[];
}
