export interface AiShadowStatus {
  routeName: string;
  // Ingress resource name; same value as the Prometheus ai_route label, used to align usage.
  resourceName?: string;
  mode: 'monitoring' | 'enforcement';
  authEnabled: boolean;
  // True when the key-auth instance exists and is enabled.
  managed?: boolean;
  // True when nobody is authorized: every identified caller is rejected.
  defaultDeny?: boolean;
  authorizedConsumers: string[];
}

// Replace the whole authorized list in one call. An empty array means default-deny.
export interface AiShadowBatchActionRequest {
  routeName: string;
  consumerNames: string[];
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
  // Blocked events of BOTH surfaces. The KPI reads this instead of `blocked`, because
  // `blocked` is scoped to the requested surface and would read 0 on the discovery side —
  // which is where the "nine out of ten shadow AI never got stopped" fact lives.
  blockedTotal?: number;
  // Admission-side breakdown by reason. These three tiles are the route surface's own
  // composition, the way domainCount/critical/high are the discovery surface's.
  noKeyCount?: number;
  unknownKeyCount?: number;
  notInAllowCount?: number;
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
  // no_key / unknown_key / not_in_allow:<consumer> / off_channel /
  // ai_domain_match / policy_violation / policy_blocked.
  reason?: string;
  routeHint?: string;
  // ---- 会话折叠展示字段（后端按 session_id 聚合，流水本身逐条保留）----
  // 这一行代表多少条原始事件；1 表示该会话只有一条流水。
  hitCount?: number;
  // Epoch millis of the first / last event in this session.
  firstSeen?: number;
  lastSeen?: number;
  // 会话内最严重的处置 / 最高风险；缺省时回退到代表行的 status / riskLevel。
  sessionStatus?: string;
  sessionRiskLevel?: string;
  // 会话内最近一条非空 reason；由折叠 SQL 返回，优先于代表行的 reason 展示。
  sessionReason?: string;
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
  // 'shadow' for the discovery surface, 'route' for the admission surface.
  surface?: string;
  // Matched by prefix, so 'not_in_allow' covers every 'not_in_allow:<consumer>' row.
  reason?: string;
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

// One consumer's usage and admission profile: who uses the AI, how much, and
// whether the traffic was blocked. Usage comes from Prometheus (admitted
// requests only); blocked/monitored counts come from the event table.
export interface AiConsumerUsage {
  consumer: string;
  // True for the aggregate "none" bucket: traffic without an identity, not a person.
  unauthenticated: boolean;
  requestCount: number;
  inputTokens: number;
  outputTokens: number;
  models: string[];
  routes: string[];
  // Route resource name -> requests this consumer made on that route.
  // requestCount is the total across all routes, so per-route attribution must read
  // this map instead; reusing requestCount adds the same calls to every route the
  // consumer touched and the sum silently exceeds the total.
  routeRequestCounts?: Record<string, number>;
  // Routes this consumer called but is not authorized on. Non-empty means overreach.
  unauthorizedRoutes: string[];
  // True only when the consumer is authorized on every route it actually called.
  authorized: boolean;
  blockedCount: number;
  monitoredCount: number;
}

export interface AiConsumerUsageReport {
  requestCount: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  // Identified consumers only (the "none" bucket is excluded).
  consumerCount: number;
  authorizedCount: number;
  unauthenticatedRequestCount: number;
  blockedCount: number;
  monitoredCount: number;
  consumers: AiConsumerUsage[];
  // False when Prometheus is unreachable: zeros mean "unavailable", not "nobody used it".
  usageAvailable: boolean;
  // 'window' — the increment inside the requested window; Prometheus keeps only 6h.
  usageScope: string;
  // Window (hours) the blocked/monitored counts were computed over. Different
  // scope from usageScope on purpose; the UI labels them separately.
  eventWindowHours: number;
}

// ---- 旁路采集（asg-bypass-guard 镜像口/回注口配置）----

export interface AiShadowCaptureNic {
  name: string;
  // up / down (operstate)
  oper: string;
  // Link speed such as "1000Mb/s"; empty when the link is down.
  speed: string;
}

export interface AiShadowCaptureNics {
  // False when the bypass collector could not be probed; nics is then empty.
  agentOnline: boolean;
  nics: AiShadowCaptureNic[];
}

export interface AiShadowCaptureConfig {
  // null = never configured (collector keeps its local yaml); [] = capture off.
  interfaces: string[] | null;
  // Capture port -> reinject control port; "" means no injection, missing key means same port.
  controlInterfaces: Record<string, string>;
  updatedAt?: string;
}

export interface AiShadowCaptureConfigUpdate {
  interfaces: string[];
  controlInterfaces: Record<string, string>;
}
