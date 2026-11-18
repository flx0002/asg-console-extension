/**
 * AI 监控看板数据服务
 *
 * 数据源：console 后端原生反代 /grafana/** → Grafana datasource proxy → Prometheus。
 * 不走 Grafana dashboard 模板，前端直接查询 PromQL 自行渲染，因此标题/图例全中文，
 * 且不依赖 console 内置的 Higress dashboard（后者每次启动会被 2 号仓模板按 title 覆盖）。
 *
 * 同源请求会自动携带 console 登录 cookie（_hi_sess），无需在此处理鉴权。
 * datasource uid 不硬编码：重装后 Grafana 会重新分配 uid，按 name=Prometheus 动态解析。
 */

const GRAFANA_PROXY = '/grafana/api/datasources';

let cachedPromUid: string | null = null;
let uidPromise: Promise<string> | null = null;

/**
 * 解析 Prometheus datasource uid（带缓存与并发合并）。
 */
async function resolvePromUid(): Promise<string> {
  if (cachedPromUid) return cachedPromUid;
  if (uidPromise) return uidPromise;
  uidPromise = fetch(GRAFANA_PROXY, { credentials: 'same-origin' })
    .then((resp) => {
      if (!resp.ok) throw new Error(`加载 Grafana 数据源失败: HTTP ${resp.status}`);
      return resp.json();
    })
    .then((list: any[]) => {
      const ds = (list || []).find(
        (d) => d && (d.name === 'Prometheus' || d.type === 'prometheus'),
      );
      if (!ds || !ds.uid) throw new Error('未找到 Prometheus 数据源');
      cachedPromUid = ds.uid;
      return ds.uid;
    })
    .finally(() => {
      uidPromise = null;
    });
  return uidPromise;
}

export interface MetricPoint {
  time: number;
  value: number;
}

export interface MetricSeries {
  name: string;
  points: MetricPoint[];
}

/**
 * 执行即时 PromQL 查询，返回序列列表。
 * 失败时抛出带上下文的错误，交由页面展示错误态，避免静默空白。
 */
export async function queryProm(expr: string): Promise<MetricSeries[]> {
  const uid = await resolvePromUid();
  const url = `${GRAFANA_PROXY}/proxy/uid/${encodeURIComponent(uid)}/api/v1/query`;
  const resp = await fetch(`${url}?query=${encodeURIComponent(expr)}`, {
    credentials: 'same-origin',
  });
  if (!resp.ok) throw new Error(`PromQL 查询失败: HTTP ${resp.status}`);
  const body = await resp.json();
  if (body.status !== 'success') {
    throw new Error(body.error || 'PromQL 查询返回失败');
  }
  const results = (body.data && body.data.result) || [];
  return results.map((item: any) => {
    const metric = item.metric || {};
    // 序列名：优先取聚合 label，否则用全部 label 拼可读名
    const name =
      metric.ai_model ||
      metric.ai_consumer ||
      metric.ai_cluster ||
      metric.response_code_class ||
      metric.pod ||
      metric.phase ||
      metric.ruleid ||
      Object.keys(metric)
        .filter((k) => k !== '__name__')
        .map((k) => `${k}=${metric[k]}`)
        .join(', ') ||
      '值';
    const values =
      item.value && Array.isArray(item.value)
        ? [item.value]
        : (item.values || []);
    return {
      name,
      points: values.map((v: any[]) => ({
        time: Number(v[0]),
        value: Number(v[1]),
      })),
    };
  });
}

/**
 * 执行范围 PromQL 查询（用于时序趋势图）。
 */
export async function queryPromRange(
  expr: string,
  start: number,
  end: number,
  step = 60,
): Promise<MetricSeries[]> {
  const uid = await resolvePromUid();
  const url = `${GRAFANA_PROXY}/proxy/uid/${encodeURIComponent(uid)}/api/v1/query_range`;
  const params =
    `query=${encodeURIComponent(expr)}` +
    `&start=${start}&end=${end}&step=${step}`;
  const resp = await fetch(`${url}?${params}`, {
    credentials: 'same-origin',
  });
  if (!resp.ok) throw new Error(`PromQL 范围查询失败: HTTP ${resp.status}`);
  const body = await resp.json();
  if (body.status !== 'success') {
    throw new Error(body.error || 'PromQL 范围查询返回失败');
  }
  const results = (body.data && body.data.result) || [];
  return results.map((item: any) => {
    const metric = item.metric || {};
    const name =
      metric.ai_model ||
      metric.ai_consumer ||
      metric.ai_cluster ||
      metric.response_code_class ||
      metric.pod ||
      Object.keys(metric)
        .filter((k) => k !== '__name__')
        .map((k) => `${k}=${metric[k]}`)
        .join(', ') ||
      '值';
    return {
      name,
      points: (item.values || []).map((v: any[]) => ({
        time: Number(v[0]),
        value: Number(v[1]),
      })),
    };
  });
}
