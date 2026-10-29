export interface AiShadowEntry {
  consumer: string;
  model: string;
  inputTokens: number;
  outputTokens: number;
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
  // Epoch millis of the last event for this domain inside the queried window.
  lastSeen?: number;
  // Authorized domains stay in this list (full-volume scope) and are only flagged.
  authorized?: boolean;
}

// KPI summary over the same window as the trend and the event list.
export interface AiShadowDetectedStats {
  start: number;
  end: number;
  total: number;
  critical: number;
  high: number;
  blocked: number;
  domainCount: number;
  // Subset of `total` / `domainCount` contributed by already-authorized domains.
  authorizedTotal?: number;
  authorizedDomainCount?: number;
  // Event totals of BOTH tabs, independent of the surface filter the caller applied.
  // The detection page uses admissionTotal to point at the other tab instead of
  // pretending admission violations do not exist.
  admissionTotal?: number;
  shadowTotal?: number;
  degraded?: boolean;
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
  // Surface decides tab membership: 'route' (managed-channel admission) or 'shadow'
  // (off-channel discovery). Never re-derive it from detectType in the UI.
  surface?: string;
  verdict?: string;
  // no_key / unknown_key / not_in_allow:<consumer> / off_channel.
  reason?: string;
  routeHint?: string;
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
  // 'shadow' for the detection tab, 'route' for the admission view.
  surface?: string;
  // Epoch millis; both bounds are needed to narrow the list to one trend bucket.
  startTime?: number;
  endTime?: number;
}

// One row of the managed-channel admission table: the same (domain, reason, status)
// collapses into a single line, because what matters for handling is the reason.
export interface AiShadowAdmissionEvent {
  domain: string;
  // no_key / unknown_key / not_in_allow:<consumer>; null when the reporter sent none.
  reason?: string | null;
  // blocked / monitored / allowed
  status?: string | null;
  count: number;
  // Epoch millis of the last event in this group inside the queried window.
  lastSeen?: number | null;
}

export interface AiShadowAdmissionResponse {
  start: number;
  end: number;
  total: number;
  blocked: number;
  domainCount: number;
  list: AiShadowAdmissionEvent[];
  // true when the aggregation itself failed, which is NOT the same as an empty window.
  degraded?: boolean;
}

// Long-format trend point for the detection trend chart (IR-004):
// { timestamp: epoch millis of the bucket start, status: 'blocked' | 'allowed' | 'monitored', count }
export interface AiShadowTrendPoint {
  timestamp: number;
  status: string;
  count: number;
}

// Trend payload: the window actually served and the bucket granularity chosen for its span.
export interface AiShadowTrendResponse {
  start: number;
  end: number;
  granularity: 'hour' | 'day';
  // true when the trend source itself was unreachable, which is distinct from an empty window
  degraded?: boolean;
  list: AiShadowTrendPoint[];
}

// Authorized-domain view of the DNS/bypass policy (IR-003 unified authorization)
export interface AiShadowDnsPolicyView {
  mode: string;
  domains: string[];
  // Per-domain metadata for the management table; entries without metadata
  // (legacy rows written straight into the policy) come back as source='imported'.
  items?: AiShadowAuthzItem[];
}

export interface AiShadowAuthzItem {
  domain: string;
  // 'suffix' means authorizing example.com also covers api.example.com.
  matchType: string;
  note?: string | null;
  source?: string | null;
  createdBy?: string | null;
  // Epoch millis; 0 when unknown.
  createdAt?: number;
  // Traffic covered by this single entry inside the queried window.
  // null means the count could not be computed (shown as '-'), which is NOT the same as 0.
  hitCount?: number | null;
  // Detected domains this entry currently covers; empty means the entry matches nothing.
  hitDomains?: string[] | null;
}

// Incremental update of the authorized-domain list (add or remove entries)
export interface AiShadowAuthzUpdate {
  mode?: string;
  addDomains?: string[];
  removeDomains?: string[];
  // 'manual' when added from the form, 'detected' when authorized from a detection row.
  source?: 'manual' | 'detected';
}
