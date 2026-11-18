/* eslint-disable max-len */
/**
 * 监控看板共享组件（全量复刻原 Grafana 面板事项，全部中文，无第三方品牌字样）。
 * 纯前端渲染：通过 ai-dashboard service 查询 Prometheus。
 * mode='overview'：通用网关全部事项（原 main 面板：概览/负载/请求/WAF/XDS/服务排行）
 * mode='full'：在通用全部事项之上，追加 Token 与模型用量（原 AI 面板）
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Card, Col, Row, Statistic, Table, Tag, Alert, Spin, Empty, Segmented, Tooltip } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { Line, Column } from '@ant-design/charts';
import { queryProm, queryPromRange } from '@/services/ai-dashboard';

type RangeKey = '1h' | '6h' | '24h';

const RANGE_SECONDS: Record<RangeKey, number> = {
  '1h': 3600,
  '6h': 21600,
  '24h': 86400,
};

// 图表统一配色
const SERIES_COLORS = ['#1668dc', '#52c41a', '#fa8c16', '#722ed1', '#13c2c2', '#eb2f96', '#faad14'];

const fmtNum = (v: number): string => {
  if (!isFinite(v)) return '-';
  if (Math.abs(v) >= 100000000) return `${(v / 100000000).toFixed(2)}亿`;
  if (Math.abs(v) >= 10000) return `${(v / 10000).toFixed(2)}万`;
  if (Math.abs(v) >= 100) return v.toFixed(0);
  if (Math.abs(v) >= 1) return v.toFixed(2);
  return v.toFixed(3);
};

const fmtTime = (ts: number): string => {
  const d = new Date(ts * 1000);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};

/**
 * 把 envoy cluster 名整理成可读形式。
 * 兼容两种输入：ai_cluster 直接给 cluster 名；cluster_name 经 service 层拼接后带 `cluster_name=` 前缀。
 *   outbound|443||llm-x.internal.dns          -> llm-x.internal.dns:443
 *   cluster_name=outbound|443||llm-x...dns    -> llm-x...dns:443
 */
const prettyName = (name: string): string => {
  const m = name.match(/(?:^|[,\s])(?:cluster_name=)?outbound\|(\d+)\|\|([^,]*)/);
  return m ? `${m[2]}:${m[1]}` : name;
};

// 服务排行排除的内部 cluster
const CLUSTER_EXCLUDE =
  'cluster_name!="xds-grpc",cluster_name!="prometheus_stats",cluster_name!="agent",cluster_name!="BlackHoleCluster",cluster_name!="sds-grpc"';

interface StatCardProps {
  title: string;
  expr: string;
  suffix?: string;
  tip?: string;
  refreshKey?: number;
}

/** 单值统计卡：自取数，一个指标失败不影响整页 */
const StatCard: React.FC<StatCardProps> = ({ title, expr, suffix, tip, refreshKey = 0 }) => {
  const [value, setValue] = useState<number | null>(null);
  const [error, setError] = useState<string>('');
  const load = useCallback(() => {
    queryProm(expr)
      .then((series) => {
        const v = series.length ? series[0].points[0]?.value : 0;
        setValue(typeof v === 'number' && isFinite(v) ? v : 0);
        setError('');
      })
      .catch((e) => setError(e.message || '查询失败'));
  }, [expr]);
  useEffect(() => {
    load();
    const timer = window.setInterval(load, 30000);
    return () => window.clearInterval(timer);
  }, [load, refreshKey]);
  const body = error ? (
    <Tooltip title={error}><span style={{ color: '#ff4d4f' }}>查询失败</span></Tooltip>
  ) : value === null ? (
    <Spin size="small" />
  ) : (
    <Statistic value={fmtNum(value)} suffix={suffix} />
  );
  return (
    <Card size="small" title={tip ? <Tooltip title={tip}>{title}</Tooltip> : title}>
      {body}
    </Card>
  );
};

interface TrendChartProps {
  title: string;
  exprs: { label: string; expr: string }[];
  range: RangeKey;
  refreshKey: number;
  suffix?: string;
  chartType?: 'line' | 'column';
  emptyDesc?: string;
}

/** 时序趋势图：可叠多条序列，每个序列独立查询 */
const TrendChart: React.FC<TrendChartProps> = ({
  title,
  exprs,
  range,
  refreshKey,
  suffix,
  chartType = 'line',
  emptyDesc = '该时间范围内暂无数据',
}) => {
  const [series, setSeries] = useState<{ label: string; points: { time: number; value: number }[] }[]>([]);
  const [error, setError] = useState<string>('');
  const [loading, setLoading] = useState<boolean>(false);
  // 用内容签名而非数组引用做依赖，内联数组内容不变就不重复请求。
  const exprsKey = exprs.map((e) => `${e.label}|${e.expr}`).join('||');
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    const end = Math.floor(Date.now() / 1000);
    const start = end - RANGE_SECONDS[range];
    const step = range === '24h' ? 300 : range === '6h' ? 120 : 60;
    Promise.all(
      exprs.map((e) =>
        queryPromRange(e.expr, start, end, step).then((res) => ({ label: e.label, res })),
      ),
    )
      .then((all) => {
        if (cancelled) return;
        const merged: { label: string; points: { time: number; value: number }[] }[] = [];
        all.forEach(({ label, res }) => {
          if (!res.length) {
            merged.push({ label, points: [] });
            return;
          }
          if (res.length === 1) {
            merged.push({ label, points: res[0].points });
          } else {
            res.forEach((s) => merged.push({ label: `${label}·${s.name}`, points: s.points }));
          }
        });
        setSeries(merged);
        setError('');
      })
      .catch((e) => !cancelled && setError(e.message || '查询失败'))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [exprsKey, range, refreshKey]);

  const data = useMemo(() => {
    const rows: { time: string; series: string; value: number }[] = [];
    series.forEach((s) => {
      s.points.forEach((p) => rows.push({ time: fmtTime(p.time), series: s.label, value: p.value }));
    });
    return rows;
  }, [series]);

  const config = {
    data,
    xField: 'time',
    yField: 'value',
    seriesField: 'series',
    color: SERIES_COLORS,
    smooth: true,
    autoFit: true,
    height: 240,
    legend: { position: 'top' as const },
    yAxis: { label: { formatter: (v: string) => fmtNum(Number(v)) } },
    tooltip: {
      customContent: (_title: string, items: any[]) =>
        items
          .map((it) => `<div>${it.name || it.data?.series}: <b>${fmtNum(Number(it.value))}${suffix || ''}</b></div>`)
          .join(''),
    },
  };

  return (
    <Card
      size="small"
      title={title}
      loading={loading}
      extra={<span style={{ fontSize: 12, color: '#999' }}>{data.length ? `${data.length} 点` : '暂无数据'}</span>}
    >
      {error ? (
        <Alert type="error" showIcon message="查询失败" description={error} />
      ) : data.length === 0 ? (
        <Empty description={emptyDesc} image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : chartType === 'column' ? (
        <Column {...config} />
      ) : (
        <Line {...config} />
      )}
    </Card>
  );
};

interface TableCardProps {
  title: string;
  expr: string;
  nameLabel: string;
  valueLabel: string;
  refreshKey: number;
  suffix?: string;
}

/** 排行表：按向量值降序取前 10 */
const TableCard: React.FC<TableCardProps> = ({ title, expr, nameLabel, valueLabel, refreshKey, suffix }) => {
  const [rows, setRows] = useState<{ key: string; name: string; value: number }[]>([]);
  const [error, setError] = useState<string>('');
  useEffect(() => {
    let cancelled = false;
    queryProm(expr)
      .then((series) => {
        if (cancelled) return;
        const mapped = series
          .map((s) => ({ key: s.name, name: prettyName(s.name), value: s.points[0]?.value || 0 }))
          .sort((a, b) => b.value - a.value)
          .slice(0, 10);
        setRows(mapped);
        setError('');
      })
      .catch((e) => !cancelled && setError(e.message || '查询失败'));
    return () => {
      cancelled = true;
    };
  }, [expr, refreshKey]);
  const columns = [
    { title: nameLabel, dataIndex: 'name', key: 'name' },
    {
      title: valueLabel,
      dataIndex: 'value',
      key: 'value',
      render: (v: number) => <Tag color="blue">{fmtNum(v)}{suffix || ''}</Tag>,
    },
  ];
  return (
    <Card size="small" title={title}>
      {error ? (
        <Alert type="error" showIcon message={error} />
      ) : (
        <Table
          dataSource={rows}
          columns={columns}
          size="small"
          pagination={false}
          locale={{ emptyText: '暂无数据' }}
        />
      )}
    </Card>
  );
};

/** 分组标题 */
const SectionTitle: React.FC<{ title: string; subtitle?: string }> = ({ title, subtitle }) => (
  <div style={{ margin: '24px 0 12px', borderLeft: '3px solid #1668dc', paddingLeft: 10 }}>
    <span style={{ fontSize: 16, fontWeight: 600 }}>{title}</span>
    {subtitle && <span style={{ fontSize: 12, color: '#999', marginLeft: 10 }}>{subtitle}</span>}
  </div>
);

interface TrendDef {
  title: string;
  exprs: { label: string; expr: string }[];
  suffix?: string;
  chartType?: 'line' | 'column';
  emptyDesc?: string;
  span?: number;
}

export type DashboardMode = 'overview' | 'full';

const AiMetricsDashboard: React.FC<{ mode: DashboardMode }> = ({ mode }) => {
  const [range, setRange] = useState<RangeKey>('1h');
  const [refreshKey, setRefreshKey] = useState(0);
  const full = mode === 'full';

  // ---------- 总体概览 ----------
  const generalStats: StatCardProps[] = [
    { title: '下游请求总量', expr: 'round(sum(irate(envoy_http_downstream_rq_total[5m])),0.001)', suffix: ' req/s', tip: '最近 5 分钟每秒请求数' },
    { title: '下游请求成功率', expr: 'sum(irate(envoy_http_downstream_rq{response_code_class!="5xx"}[5m])) / sum(irate(envoy_http_downstream_rq[5m])) * 100', suffix: '%', tip: '非 5xx 响应占比' },
    { title: 'TCP 接收字节', expr: 'sum(irate(envoy_http_downstream_cx_rx_bytes_total[1m]))', suffix: ' B/s' },
    { title: '上游请求总量', expr: 'round(sum(irate(envoy_cluster_upstream_rq_total[5m])),0.001)', suffix: ' req/s' },
    { title: '上游请求成功率', expr: 'sum(irate(envoy_cluster_upstream_rq{response_code_class!="5xx"}[5m])) / sum(irate(envoy_cluster_upstream_rq[5m])) * 100', suffix: '%', tip: '非 5xx 响应占比' },
    { title: 'TCP 发送字节', expr: 'sum(irate(envoy_http_downstream_cx_tx_bytes_total[1m]))', suffix: ' B/s' },
  ];

  const generalTrends: TrendDef[] = [
    { title: '下游请求时长（P50）', exprs: [{ label: '请求时长', expr: 'histogram_quantile(0.50, sum(irate(envoy_http_downstream_rq_time_bucket[1m])) by (le))' }], suffix: ' s' },
    { title: '上游请求时长（P50）', exprs: [{ label: '请求时长', expr: 'histogram_quantile(0.50, sum(irate(envoy_cluster_upstream_rq_time_bucket[1m])) by (le))' }], suffix: ' s' },
  ];

  // ---------- 负载 ----------
  const workloadTrends: TrendDef[] = [
    { title: 'CPU 使用率', exprs: [{ label: 'CPU', expr: '100 * sum(irate(container_cpu_usage_seconds_total{container="higress-gateway"}[1m])) by (pod)' }], suffix: '%' },
    { title: '内存使用量', exprs: [{ label: '内存', expr: 'max(container_memory_working_set_bytes{container="higress-gateway"}) by (pod)' }], suffix: ' B' },
  ];

  // ---------- 请求速率 ----------
  const requestTrends: TrendDef[] = [
    { title: '下游 QPS（按响应类别）', exprs: [{ label: 'QPS', expr: 'sum(irate(envoy_http_downstream_rq[1m])) by (response_code_class)' }], suffix: ' req/s' },
    { title: '上游 QPS（按响应类别）', exprs: [{ label: 'QPS', expr: 'sum(irate(envoy_cluster_upstream_rq{cluster_name=~"outbound.*"}[1m])) by (response_code_class)' }], suffix: ' req/s' },
  ];

  // ---------- WAF ----------
  const wafEmpty = '当前环境未启用 WAF，暂无数据';
  const wafTrends: TrendDef[] = [
    { title: 'WAF 处理量', exprs: [{ label: '处理量', expr: 'sum(irate(waf_filter_tx_total[1m]))' }], emptyDesc: wafEmpty, span: 8 },
    { title: 'WAF 按阶段拒绝', exprs: [{ label: '拒绝数', expr: 'sum(irate(waf_filter_tx_interruptions[1m])) by (phase)' }], emptyDesc: wafEmpty, span: 8 },
    { title: 'WAF 按规则拒绝', exprs: [{ label: '拒绝数', expr: 'sum(irate(waf_filter_tx_interruptions[1m])) by (ruleid)' }], emptyDesc: wafEmpty, span: 8 },
  ];

  // ---------- XDS ----------
  const xdsTrends: TrendDef[] = [
    { title: '配置下发连接数', exprs: [{ label: '建连速率', expr: 'sum(irate(envoy_cluster_upstream_cx_total{cluster_name="xds-grpc"}[1m]))' }], span: 8 },
    { title: 'XDS 活跃连接数', exprs: [{ label: '连接数', expr: 'sum(envoy_cluster_upstream_cx_active{cluster_name="xds-grpc"})' }], span: 8 },
    { title: 'XDS 请求字节速率', exprs: [{ label: '字节速率', expr: 'max(rate(envoy_cluster_upstream_cx_rx_bytes_total{cluster_name="xds-grpc"}[1m]))' }], suffix: ' B/s', span: 8 },
  ];

  // ---------- 服务排行 ----------
  const topTables = [
    { title: 'QPS 前 10', expr: `topk(10, sum(irate(envoy_cluster_upstream_rq_total{${CLUSTER_EXCLUDE}}[5m])) by (cluster_name))`, nameLabel: '服务', valueLabel: 'QPS', suffix: ' req/s' },
    { title: '失败请求前 10', expr: `topk(10, sum(irate(envoy_cluster_upstream_rq{response_code_class=~"(4|5)xx",${CLUSTER_EXCLUDE}}[5m])) by (cluster_name))`, nameLabel: '服务', valueLabel: '请求数' },
    { title: '活跃连接前 10', expr: `topk(10, sum(envoy_cluster_upstream_cx_active{${CLUSTER_EXCLUDE}}) by (cluster_name))`, nameLabel: '服务', valueLabel: '连接数' },
    { title: '响应时长前 10（最慢）', expr: `topk(10, sum(irate(envoy_cluster_upstream_rq_time_sum{${CLUSTER_EXCLUDE}}[5m])) by (cluster_name) / sum(irate(envoy_cluster_upstream_rq_time_count{${CLUSTER_EXCLUDE}}[5m])) by (cluster_name))`, nameLabel: '服务', valueLabel: '时长', suffix: ' s' },
  ];

  // ---------- Token 与模型用量（仅完整页） ----------
  const tokenStats: StatCardProps[] = [
    { title: '输入 Token 速率', expr: 'sum(irate(route_upstream_model_consumer_metric_input_token{}[2m]))', suffix: ' tok/s' },
    { title: '输出 Token 速率', expr: 'sum(irate(route_upstream_model_consumer_metric_output_token{}[2m]))', suffix: ' tok/s' },
  ];

  const tokenTrends: TrendDef[] = [
    { title: 'Token 速率趋势', exprs: [
      { label: '输入 Token', expr: 'sum(irate(route_upstream_model_consumer_metric_input_token{}[2m]))' },
      { label: '输出 Token', expr: 'sum(irate(route_upstream_model_consumer_metric_output_token{}[2m]))' },
    ], suffix: ' tok/s' },
    { title: '各模型输入 Token 速率', exprs: [{ label: '输入 Token', expr: 'sum(irate(route_upstream_model_consumer_metric_input_token{}[2m])) by (ai_model)' }], suffix: ' tok/s' },
    { title: '各模型输出 Token 速率', exprs: [{ label: '输出 Token', expr: 'sum(irate(route_upstream_model_consumer_metric_output_token{}[2m])) by (ai_model)' }], suffix: ' tok/s' },
    { title: '各模型首 Token 延迟', exprs: [{ label: '首 Token 延迟', expr: 'sum(irate(route_upstream_model_consumer_metric_llm_first_token_duration[2m])) by (ai_model) / sum(irate(route_upstream_model_consumer_metric_llm_stream_duration_count[2m])) by (ai_model)' }], suffix: ' s' },
    { title: '各模型请求处理时长', exprs: [{ label: '处理时长', expr: 'sum(irate(route_upstream_model_consumer_metric_llm_service_duration[2m])) by (ai_model) / sum(irate(route_upstream_model_consumer_metric_llm_duration_count[2m])) by (ai_model)' }], suffix: ' s' },
  ];

  const tokenTables = [
    { title: '供应商用量（Token）', expr: 'sum by(ai_cluster) (increase(route_upstream_model_consumer_metric_input_token{}[1h]))', nameLabel: '供应商集群', valueLabel: 'Token 数' },
    { title: '模型用量（Token）', expr: 'sum by(ai_model) (increase(route_upstream_model_consumer_metric_input_token{}[1h]))', nameLabel: '模型', valueLabel: 'Token 数' },
    { title: '消费者用量（Token）', expr: 'sum by(ai_consumer) (increase(route_upstream_model_consumer_metric_input_token{}[1h]))', nameLabel: '消费者', valueLabel: 'Token 数' },
  ];

  // ---------- 通用渲染器 ----------
  const renderStats = (defs: StatCardProps[], span: number) => (
    <Row gutter={[16, 16]}>
      {defs.map((c) => (
        <Col key={c.title} xs={24} sm={12} lg={span}>
          <StatCard {...c} refreshKey={refreshKey} />
        </Col>
      ))}
    </Row>
  );

  const renderTrends = (defs: TrendDef[]) => (
    <Row gutter={[16, 16]}>
      {defs.map((d) => (
        <Col key={d.title} xs={24} lg={d.span || 12}>
          <TrendChart
            title={d.title}
            exprs={d.exprs}
            range={range}
            refreshKey={refreshKey}
            suffix={d.suffix}
            chartType={d.chartType}
            emptyDesc={d.emptyDesc}
          />
        </Col>
      ))}
    </Row>
  );

  return (
    <div style={{ padding: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <div>
          <h2 style={{ margin: 0 }}>{full ? 'AI 监控面板' : '网关监控面板'}</h2>
          <span style={{ color: '#999', fontSize: 12 }}>
            {full ? '通用网关指标 + Token 与模型用量' : '请求、负载、请求速率、WAF、配置下发与服务排行'} · 数据源 Prometheus · 每 30 秒自动刷新
          </span>
        </div>
        <div>
          <Segmented
            value={range}
            onChange={(v) => setRange(v as RangeKey)}
            options={[
              { label: '近 1 小时', value: '1h' },
              { label: '近 6 小时', value: '6h' },
              { label: '近 24 小时', value: '24h' },
            ]}
          />
          <ReloadOutlined
            style={{ marginLeft: 16, cursor: 'pointer', fontSize: 16 }}
            onClick={() => setRefreshKey((k) => k + 1)}
          />
        </div>
      </div>

      <SectionTitle title="总体概览" subtitle="上下游请求量、成功率、时延与字节收发" />
      {renderStats(generalStats, 4)}
      <div style={{ marginTop: 16 }}>{renderTrends(generalTrends)}</div>

      <SectionTitle title="负载" subtitle="网关容器的 CPU 与内存" />
      {renderTrends(workloadTrends)}

      <SectionTitle title="请求速率" subtitle="按响应类别区分的上下游 QPS" />
      {renderTrends(requestTrends)}

      <SectionTitle title="WAF 防护" subtitle="Web 应用防火墙拦截情况" />
      <Alert
        type="info"
        showIcon
        message="当前环境未启用 WAF，本组指标暂无数据；启用后会自动显示。"
        style={{ marginBottom: 16 }}
      />
      {renderTrends(wafTrends)}

      <SectionTitle title="配置下发（XDS）" subtitle="网关与配置中心的连接与流量" />
      {renderTrends(xdsTrends)}

      <SectionTitle title="服务排行" subtitle="按 QPS、失败请求、活跃连接、响应时长排序前 10" />
      <Row gutter={[16, 16]}>
        {topTables.map((t) => (
          <Col key={t.title} xs={24} lg={12}>
            <TableCard
              title={t.title}
              expr={t.expr}
              nameLabel={t.nameLabel}
              valueLabel={t.valueLabel}
              suffix={t.suffix}
              refreshKey={refreshKey}
            />
          </Col>
        ))}
      </Row>

      {full && (
        <>
          <SectionTitle title="Token 速率" subtitle="模型输入输出 Token 消耗速率" />
          {renderStats(tokenStats, 12)}
          <div style={{ marginTop: 16 }}>{renderTrends(tokenTrends)}</div>

          <SectionTitle title="用量排行" subtitle="按供应商集群、模型、消费者统计 Token 用量（近 1 小时）" />
          <Row gutter={[16, 16]}>
            {tokenTables.map((t) => (
              <Col key={t.title} xs={24} lg={8}>
                <TableCard
                  title={t.title}
                  expr={t.expr}
                  nameLabel={t.nameLabel}
                  valueLabel={t.valueLabel}
                  refreshKey={refreshKey}
                />
              </Col>
            ))}
          </Row>
        </>
      )}
    </div>
  );
};

export default AiMetricsDashboard;
