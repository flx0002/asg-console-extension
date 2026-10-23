/* eslint-disable max-len, no-nested-ternary */
import React, { useEffect, useRef, useState } from 'react';
import {
  Card, Row, Col, Statistic, Table, Tag, Button, Empty, Spin, Progress, Tooltip, Alert, Segmented,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { Line, Pie } from '@ant-design/charts';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'ice';
import { getBehaviorStats, getBehaviorTimeline } from '@/services';

const RISK_TYPE_KEYS: Record<string, string> = {
  identity_mismatch: 'behaviorAnalysis.alert.typeIdentityMismatch',
  behavior_rarity: 'behaviorAnalysis.alert.typeBehaviorRarity',
  chain_anomaly: 'behaviorAnalysis.alert.typeChainAnomaly',
  data_anomaly: 'behaviorAnalysis.alert.typeDataAnomaly',
  policy_anomaly: 'behaviorAnalysis.alert.typePolicyAnomaly',
  privilege_propagation: 'behaviorAnalysis.alert.typePrivilegePropagation',
};

// 趋势图分级系列取色，与告警列表的风险级别标签同色（紧急/高/中/低）
const TREND_LEVEL_COLORS: Record<string, string> = {
  critical: '#eb2f96',
  high: '#cf1322',
  medium: '#fa8c16',
  low: '#1677ff',
};
const TREND_LEVEL_DEFAULT_COLOR = '#1677ff';

// 风险趋势的三种时间视图（对齐参考站的告警趋势统计）：今天按小时桶，其余按后端自适应的天桶
type TrendRangeKey = 'today' | 'week' | 'month';

const rangeWindow = (key: TrendRangeKey): { startTime: number; endTime: number } => {
  const endTime = Date.now();
  if (key === 'today') {
    const start = new Date();
    start.setHours(0, 0, 0, 0);
    return { startTime: start.getTime(), endTime };
  }
  const days = key === 'week' ? 7 : 30;
  return { startTime: endTime - days * 86400000, endTime };
};

// 轴标签只留与粒度相关的部分：天桶只看日期、小时桶只看时刻，完整时间留给 tooltip。
// 日粒度下 00:00 恒为同一值、纯噪音，16 字长标签还会把末位刻度顶出画布右边缘
const formatAxisTime = (ts?: number | string, granularity?: string): string => {
  if (ts === undefined || ts === null || ts === '') return '-';
  const d = new Date(Number(ts));
  if (isNaN(d.getTime())) return '-';
  if (granularity === 'hour') {
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  }
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

// tooltip 标题按粒度取舍：天桶的时刻恒为 00:00（纯噪音），只显示日期；小时桶才带时分
const formatTooltipTime = (ts?: number | string, granularity?: string): string => {
  if (ts === undefined || ts === null || ts === '') return '-';
  const d = new Date(Number(ts));
  if (isNaN(d.getTime())) return '-';
  const day = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  if (granularity !== 'hour') return day;
  return `${day} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};

// 时间窗内一条告警都没有时后端返回空 list（它只在有桶时才生成轴）。
// 参考控件此时画的是「全 0 的完整轴」，这里按同样步长补一条零轴，
// 否则整张卡片会退化成「暂无数据」，分不清是"没告警"还是"控件坏了"。
const buildZeroAxis = (start: number, end: number, granularity?: string): number[] => {
  if (!isFinite(start) || !isFinite(end) || end < start) return [];
  const step = granularity === 'hour' ? 3600000 : 86400000;
  // 桶起点对齐：小时桶对齐整点；天桶对齐本地零点（与后端按产品时区取当日 00:00 同构）
  const first = granularity === 'hour'
    ? Math.floor(start / step) * step
    : new Date(start).setHours(0, 0, 0, 0);
  const last = granularity === 'hour'
    ? Math.floor(end / step) * step
    : new Date(end).setHours(0, 0, 0, 0);
  const axis: number[] = [];
  for (let ts = first; ts <= last; ts += step) axis.push(ts);
  return axis;
};

// Y 轴大数缩写（与参考控件一致：≥1000 → 1.0k，≥100 万 → 1.0M）
const formatAxisNumber = (v: string): string => {
  const num = Number(v);
  if (isNaN(num)) return v;
  if (num >= 1000000) return `${(num / 1000000).toFixed(1)}M`;
  if (num >= 1000) return `${(num / 1000).toFixed(1)}k`;
  return v;
};

const BehaviorDashboardPage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [stats, setStats] = useState<any>({});
  const [timeline, setTimeline] = useState<any[]>([]);
  // 分级后的多系列行（后端未返回分级时为空，图表退回总量单系列）
  const [trendSeries, setTrendSeries] = useState<any[]>([]);
  // 与 trendSeries 摊平同源的级别顺序，供 color 位置数组取色
  const [trendLevelKeys, setTrendLevelKeys] = useState<string[]>([]);
  const [trendLevelsTruncated, setTrendLevelsTruncated] = useState<boolean>(false);
  const [trendGranularity, setTrendGranularity] = useState<string>('');
  // onEvent 在 useChart 的挂载副作用里只绑一次（deps=[]）, 后续 changeData/update 不会重绑,
  // 所以事件回调必须读 ref, 否则刷新后拿到的是首次挂载时的旧轴与旧粒度
  const trendAxisRef = useRef<number[]>([]);
  const trendGranularityRef = useRef<string>('');
  const [trendRange, setTrendRange] = useState<TrendRangeKey>('today');
  // 滚轮缩放的可见桶窗口（trendAxisRef 的索引闭区间）；null 表示显示全部桶
  const [trendZoom, setTrendZoom] = useState<{ start: number; end: number } | null>(null);
  const trendZoomRef = useRef<{ start: number; end: number } | null>(null);
  const trendChartRef = useRef<HTMLDivElement>(null);
  const [topRiskUsers, setTopRiskUsers] = useState<any[]>([]);
  const [topRiskAgents, setTopRiskAgents] = useState<any[]>([]);

  // 加载统计
  const { run: loadStats, loading: statsLoading } = useRequest(
    () => getBehaviorStats(),
    {
      manual: true,
      onSuccess: (res) => {
        const raw = res?.data || res || {};
        // 适配后端响应（snake_case + kpi 嵌套）→ dashboard 期望的平铺 camelCase
        const mapped = {
          activeUsers: raw.user_profiles ?? raw.activeUsers ?? 0,
          activeAgents: raw.agent_profiles ?? raw.activeAgents ?? 0,
          // total_alerts 是累计值，卡片文案是「今日告警」，必须用后端按当日统计的 today_alerts
          todayAlerts: raw.today_alerts ?? 0,
          // 高风险会话数由后端按 session 去重给出，不再用 high+critical 告警条数相加
          highRiskSessions: raw.high_risk_sessions ?? 0,
          untrustedAlerts: raw.untrusted_alerts ?? 0,
          statsTruncated: raw.stats_truncated === true,
          alertTypeDistribution: raw.alerts_by_type ?? {},
          // KPI 原始对象：比率为 0~1，目标阈值由后端下发（此前前端硬编码了另一套口径）
          kpi: raw.kpi || {},
        };
        setStats(mapped);
        // Top 风险用户/智能体：后端返回 top_risk_users/top_risk_agents (snake_case)，
        // 字段为 entity_id/alert_count/avg_alert_risk_score/user_name → 映射为前端表格期望的 camelCase
        const rawTopUsers = raw.top_risk_users ?? raw.topRiskUsers ?? [];
        setTopRiskUsers((Array.isArray(rawTopUsers) ? rawTopUsers : []).map((u: any) => ({
          userId: u.entity_id ?? u.userId ?? u.user_id,
          userName: u.user_name ?? u.userName ?? u.entity_id ?? u.userId,
          avgRiskScore: u.avg_alert_risk_score ?? 0,
          totalAlerts: u.alert_count ?? u.totalAlerts ?? 0,
          totalSessions: u.total_sessions ?? u.totalSessions ?? null,
        })));
        const rawTopAgents = raw.top_risk_agents ?? raw.topRiskAgents ?? [];
        setTopRiskAgents((Array.isArray(rawTopAgents) ? rawTopAgents : []).map((a: any) => ({
          agentId: a.entity_id ?? a.agentId ?? a.agent_id,
          avgRiskScore: a.avg_alert_risk_score ?? 0,
          totalAlerts: a.alert_count ?? a.totalAlerts ?? 0,
        })));
      },
    },
  );

  // 加载风险趋势
  const { run: loadTimeline, loading: timelineLoading } = useRequest(
    // 时间窗由当前视图（今天/最近一周/最近一个月）决定；粒度仍交后端按跨度自适应
    (range: TrendRangeKey) => getBehaviorTimeline(rangeWindow(range)),
    {
      manual: true,
      onSuccess: (res, params) => {
        const data = res?.data || res || [];
        // 后端 getBehaviorTimeline 返回 { list: [{ timestamp, count }] }
        const rawList = Array.isArray(data) ? data : (data?.list || data?.points || []);
        const granularity = data?.granularity || 'day';
        // 空窗口补零轴：后端在没有任何告警时不返回桶，若直接用空 list 会把整张卡片退化成「暂无数据」。
        // 起点/终点优先取后端回传的实际窗口，缺失时退回本次请求的时间窗
        const reqWin = rangeWindow((params?.[0] as TrendRangeKey) ?? trendRange);
        const totalPoints = rawList.length
          ? rawList
          : buildZeroAxis(
            Number(data?.start) || reqWin.startTime,
            Number(data?.end) || reqWin.endTime,
            granularity,
          ).map((ts) => ({ timestamp: ts, count: 0 }));
        // 标准化字段：count → alertCount（供图表 yField 使用）
        setTimeline(totalPoints.map((p: any) => ({
          timestamp: p.timestamp,
          // 预先算出可读时间标签，供 tooltip 标题直接引用，不依赖回调入参结构
          timeLabel: formatTooltipTime(p.timestamp, granularity),
          alertCount: p.count ?? p.alertCount ?? 0,
        })));
        // 下钻要用的桶轴与粒度同时写进 ref（onEvent 闭包不会随重渲染更新）
        trendAxisRef.current = totalPoints.map((p: any) => Number(p.timestamp));
        // 分级系列摊平成 G2 多系列需要的行；后端超出聚合上限时返回空数组，图表退回单系列
        const rawLevels = Array.isArray(data?.levels) ? data.levels : [];
        setTrendGranularity(granularity);
        trendGranularityRef.current = granularity;
        setTrendLevelsTruncated(data?.levels_truncated === true);
        const flattened: any[] = [];
        const levelOrder: string[] = [];
        rawLevels.forEach((lv: any) => {
          const points = Array.isArray(lv.points) ? lv.points : [];
          // 无点的级别既不会进入分类 scale 的 domain, 也不能出现在 color 位置数组里
          if (!points.length) return;
          levelOrder.push(lv.level);
          const levelName = t(`behaviorAnalysis.alert.level${String(lv.level).charAt(0).toUpperCase() + String(lv.level).slice(1)}`);
          points.forEach((p: any) => {
            flattened.push({
              timestamp: p.timestamp,
              timeLabel: formatTooltipTime(p.timestamp, granularity),
              level: levelName,
              // 点击事件的 _origin 是整行数据，下钻需要未翻译的级别值作为筛选参数
              levelKey: lv.level,
              alertCount: p.count ?? 0,
            });
          });
        });
        setTrendSeries(flattened);
        setTrendLevelKeys(levelOrder);
        // 换了时间窗后旧的桶索引窗口不再对应同一根轴，缩放必须回到全量
        trendZoomRef.current = null;
        setTrendZoom(null);
      },
    },
  );

  // 加载最近告警的逻辑已移除（与告警中心页面重复，方案要求仪表盘不再展示最近告警）

  const refreshAll = () => {
    loadStats();
    loadTimeline(trendRange);
  };

  useEffect(() => {
    refreshAll();
  }, []);

  // 切换时间视图：重查该窗口的桶，粒度由后端按跨度自动选（≤2 天小时桶，否则天桶）
  const changeTrendRange = (key: TrendRangeKey) => {
    setTrendRange(key);
    loadTimeline(key);
  };

  // 滚轮缩放：G2Plot 的 Line 没有 dataZoom（参考站是 ECharts 的 inside 缩放），
  // 这里按桶索引收放可见窗口来达到同样的放大/缩小效果。
  // React 的 wheel 代理监听是 passive 的，必须用原生监听 + passive:false 才能拦住页面滚动。
  useEffect(() => {
    const el = trendChartRef.current;
    if (!el) return undefined;
    const onWheel = (e: WheelEvent) => {
      const total = trendAxisRef.current.length;
      if (total < 2) return;
      const cur = trendZoomRef.current || { start: 0, end: total - 1 };
      const span = cur.end - cur.start + 1;
      // 每档缩放 25%，最少保留 2 个桶
      const next = Math.min(total, Math.max(2, Math.round(span * (e.deltaY > 0 ? 1.25 : 0.8))));
      // 已到缩放上下限时交回页面滚动，否则鼠标停在图上会把整页滚动锁死
      if (next === span) return;
      e.preventDefault();
      // 以当前窗口中心为锚点收放，两端夹住不越界
      const center = (cur.start + cur.end) / 2;
      const start = Math.min(Math.max(0, Math.round(center - (next - 1) / 2)), total - next);
      const nextZoom = next >= total ? null : { start, end: start + next - 1 };
      trendZoomRef.current = nextZoom;
      setTrendZoom(nextZoom);
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, []);

  // 统计卡片
  const statCards = [
    { title: t('behaviorAnalysis.stats.activeUsers'), value: stats.activeUsers ?? 0, color: '#3f8600' },
    { title: t('behaviorAnalysis.stats.activeAgents'), value: stats.activeAgents ?? 0, color: '#1677ff' },
    { title: t('behaviorAnalysis.stats.todayAlerts'), value: stats.todayAlerts ?? 0, color: stats.todayAlerts > 0 ? '#cf1322' : '#3f8600' },
    { title: t('behaviorAnalysis.stats.highRiskSessions'), value: stats.highRiskSessions ?? 0, color: stats.highRiskSessions > 0 ? '#cf1322' : '#3f8600' },
  ];

  // KPI 指标（方案 6.2 KPI 体系）：单位与目标阈值均以后端 kpi 子对象为准
  const KPI_UNITS: Record<string, 'percent' | 'minutes' | 'days'> = {
    alertArrivalRate: 'percent',
    blockInterceptRate: 'percent',
    anomalyDetectRate: 'percent',
    falsePositiveRate: 'percent',
    avgDispositionMinutes: 'minutes',
    replaySuccessRate: 'percent',
    policyIterateDays: 'days',
  };
  const kpiRaw = stats.kpi || {};
  const kpiList = [
    { key: 'alertArrivalRate', label: t('behaviorAnalysis.stats.alertArrivalRate') },
    { key: 'blockInterceptRate', label: t('behaviorAnalysis.stats.blockInterceptRate') },
    { key: 'anomalyDetectRate', label: t('behaviorAnalysis.stats.anomalyDetectRate') },
    { key: 'falsePositiveRate', label: t('behaviorAnalysis.stats.falsePositiveRate') },
    { key: 'avgDispositionMinutes', label: t('behaviorAnalysis.stats.avgDispositionMinutes') },
    { key: 'replaySuccessRate', label: t('behaviorAnalysis.stats.replaySuccessRate') },
    { key: 'policyIterateDays', label: t('behaviorAnalysis.stats.policyIterateDays') },
  ].map((item) => ({
    ...item,
    value: kpiRaw[item.key],
    target: kpiRaw[`${item.key}Target`] || '',
    unit: KPI_UNITS[item.key] || 'percent',
  }));

  // 后端比率为 0~1，必须换算成百分比再展示；分钟/天数原样展示
  const renderKpiValue = (val: any, unit: string) => {
    if (val === undefined || val === null) return <span style={{ color: '#999' }}>-</span>;
    const num = typeof val === 'number' ? val : parseFloat(val);
    if (isNaN(num)) return <span style={{ color: '#999' }}>{String(val)}</span>;
    let text = num.toFixed(2);
    if (unit === 'percent') text = `${(num * 100).toFixed(1)}%`;
    if (unit === 'minutes') text = num.toFixed(1);
    if (unit === 'days') text = num.toFixed(0);
    return <span style={{ fontSize: 18, fontWeight: 600 }}>{text}</span>;
  };

  // 风险趋势图配置（后端 timeline 返回告警计数，yField=alertCount）
  const useLevelSeries = trendSeries.length > 0 && !trendLevelsTruncated;

  // 缩放窗口内的可见数据；下钻仍按全量桶轴（trendAxisRef）换算时间窗，切片不影响下钻
  const zoomLo = trendZoom ? trendAxisRef.current[trendZoom.start] : undefined;
  const zoomHi = trendZoom ? trendAxisRef.current[trendZoom.end] : undefined;
  const trendChartData = useLevelSeries ? trendSeries : timeline;
  const trendVisibleData = zoomLo !== undefined && zoomHi !== undefined
    ? trendChartData.filter((d) => Number(d.timestamp) >= zoomLo && Number(d.timestamp) <= zoomHi)
    : trendChartData;

  // 点位下钻：对齐参考控件，点某个时间点的折线节点→带时间窗与级别跳到告警列表
  const handleTrendEvent = (_chart: any, ev: any) => {
    if (ev?.type !== 'element:click') return;
    // element:click 的原始行在不同几何下位于 _origin 或 data 本身，两处都取一下
    const datum = ev?.data?.data?._origin ?? ev?.data?.data;
    const ts = Number(datum?.timestamp);
    if (!datum || isNaN(ts)) return;
    const axisTs = trendAxisRef.current;
    const idx = axisTs.indexOf(ts);
    // 桶终点优先取相邻桶起点（轴由后端按产品时区生成，比本地 +24h 更准），末桶则退化到粒度步长
    const end = idx >= 0 && idx + 1 < axisTs.length
      ? axisTs[idx + 1] - 1
      : ts + (trendGranularityRef.current === 'hour' ? 3600000 : 86400000) - 1;
    const params = new URLSearchParams({ startTime: String(ts), endTime: String(end) });
    if (datum.levelKey) params.set('riskLevel', String(datum.levelKey));
    navigate(`/behavior-analysis/alerts?${params.toString()}`);
  };

  const lineConfig = {
    data: trendVisibleData,
    xField: 'timestamp',
    yField: 'alertCount',
    ...(useLevelSeries ? { seriesField: 'level' } : {}),
    smooth: true,
    height: 240,
    // 折线本身的路径元素不是单点，须显式开启 point 几何才能按数据点点击下钻
    point: { size: 3, shape: 'circle' as const },
    // X 轴时间戳为毫秒数值，需格式化为日期展示（Issue 1：原显示 1782446400000）
    xAxis: {
      label: {
        formatter: (v: string) => formatAxisTime(Number(v), trendGranularity),
      },
    },
    yAxis: {
      title: { text: t('behaviorAnalysis.stats.riskTrendCount') },
      label: { formatter: (v: string) => formatAxisNumber(v) },
    },
    // 末位刻度居中对齐会超出画布右边缘（窄窗口下被裁切），补右侧留白；参考控件用 grid.right:5% 达到同样效果
    appendPadding: [0, 16, 0, 0],
    // tooltip 项名称取 yField 字段名，用 alias 换成业务文案（原来误用菜单名「告警中心」）
    meta: {
      alertCount: { alias: t('behaviorAnalysis.stats.riskTrendCount') },
    },
    tooltip: {
      // G2 的 TooltipTitle 签名是 (title, datum)，旧写法 (datum) => 里第一个实参其实是 xField 原值，
      // 再取 .timestamp 得到 undefined → Number(undefined)=NaN → 标题恒为 "-"
      title: (titleVal: string, datum: any) =>
        datum?.timeLabel || formatTooltipTime(Number(datum?.timestamp ?? titleVal), trendGranularity),
      // 不再自定义 fields/formatter：tooltip 项交回 G2 默认渲染取 yField 值，名称由上面的 meta.alias 提供
    },
    // 分级时顶部图例（与参考控件位置一致），单系列不展示图例
    legend: useLevelSeries ? { position: 'top' as const } : false,
    // color 回调实参不是整行数据：getMappingField 对 color 只取 [colorField || xField]，
    // getMappingFunction 再把入参装成 { level: 系列值 }，所以拿不到 levelKey；
    // 改用位置数组，顺序与摊平顺序同源（后端已按严重度定序，且无点级别不参与 domain）
    color: useLevelSeries
      ? trendLevelKeys.map((lv: string) => TREND_LEVEL_COLORS[lv] || TREND_LEVEL_DEFAULT_COLOR)
      : '#1677ff',
    onEvent: handleTrendEvent,
  };

  // 告警类型分布（饼图）
  const alertTypeData = (() => {
    const dist = stats.alertTypeDistribution;
    if (!dist || typeof dist !== 'object') return [];
    return Object.entries(dist).map(([type, count]) => ({
      type: t(RISK_TYPE_KEYS[type] || 'behaviorAnalysis.alert.riskType'),
      value: count as number,
    }));
  })();

  const pieConfig = {
    data: alertTypeData,
    angleField: 'value',
    colorField: 'type',
    height: 240,
    radius: 0.8,
    legend: { position: 'right' as const },
    label: { type: 'inner' as const, offset: '-30%', content: '{percentage}' },
  };

  // Top 风险用户列
  const userColumns = [
    {
      title: t('behaviorAnalysis.profile.user'),
      dataIndex: 'userId',
      key: 'userId',
      ellipsis: true,
      render: (v: string, r: any) => r.userName || v || '-',
    },
    {
      title: t('behaviorAnalysis.profile.avgRiskScore'),
      dataIndex: 'avgRiskScore',
      key: 'avgRiskScore',
      width: 130,
      render: (v: number) => {
        const val = v || 0;
        // 阈值语义用 strokeColor 表达；不能传 status=success，否则 antd 用 ✓ 图标替换百分比数字（分数就看不见了）
        const strokeColor = val >= 80 ? '#ff4d4f' : val >= 50 ? '#faad14' : '#52c41a';
        return (
          <Progress
            percent={Math.min(val, 100)}
            size="small"
            strokeColor={strokeColor}
            format={(p) => `${Number(p).toFixed(1)}%`}
          />
        );
      },
    },
    {
      title: t('behaviorAnalysis.profile.alerts'),
      dataIndex: 'totalAlerts',
      key: 'totalAlerts',
      width: 80,
      render: (v: number) => (v > 0 ? <Tag color="red">{v}</Tag> : <Tag color="green">0</Tag>),
    },
    {
      title: t('behaviorAnalysis.profile.sessions'),
      dataIndex: 'totalSessions',
      key: 'totalSessions',
      width: 80,
      // 画像缺失时后端返回 null，显示占位符而不是假的 0
      render: (v: number) => (v === null || v === undefined ? <span style={{ color: '#999' }}>-</span> : v),
    },
  ];

  // Top 风险智能体列
  const agentColumns = [
    {
      title: t('behaviorAnalysis.profile.agent'),
      dataIndex: 'agentId',
      key: 'agentId',
      ellipsis: true,
    },
    {
      title: t('behaviorAnalysis.profile.avgRiskScore'),
      dataIndex: 'avgRiskScore',
      key: 'avgRiskScore',
      width: 130,
      render: (v: number) => {
        const val = v || 0;
        // 阈值语义用 strokeColor 表达；不能传 status=success，否则 antd 用 ✓ 图标替换百分比数字（分数就看不见了）
        const strokeColor = val >= 80 ? '#ff4d4f' : val >= 50 ? '#faad14' : '#52c41a';
        return (
          <Progress
            percent={Math.min(val, 100)}
            size="small"
            strokeColor={strokeColor}
            format={(p) => `${Number(p).toFixed(1)}%`}
          />
        );
      },
    },
    {
      title: t('behaviorAnalysis.profile.alerts'),
      dataIndex: 'totalAlerts',
      key: 'totalAlerts',
      width: 80,
      render: (v: number) => (v > 0 ? <Tag color="red">{v}</Tag> : <Tag color="green">0</Tag>),
    },
  ];

  return (
    <div style={{ padding: '0 0 24px' }}>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontWeight: 500, fontSize: 15 }}>{t('behaviorAnalysis.title')}</span>
        <Button icon={<ReloadOutlined />} onClick={refreshAll} loading={statsLoading || timelineLoading}>
          {t('behaviorAnalysis.refresh')}
        </Button>
      </div>

      {/* 统计被截断时必须告知用户，避免把不完整的分项当成全量 */}
      {stats.statsTruncated ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={t('behaviorAnalysis.stats.truncated')}
        />
      ) : null}

      {/* 统计卡片区 */}
      <Spin spinning={statsLoading}>
        <Row gutter={16} style={{ marginBottom: 16 }}>
          {statCards.map((card, idx) => (
            <Col span={6} key={idx}>
              <Card size="small">
                <Statistic title={card.title} value={card.value} valueStyle={{ color: card.color }} />
              </Card>
            </Col>
          ))}
        </Row>
      </Spin>

      {/* KPI 指标行 */}
      <Card size="small" title={t('behaviorAnalysis.stats.kpi')} style={{ marginBottom: 16 }} loading={statsLoading}>
        <Row gutter={[16, 16]}>
          {kpiList.map((kpi) => (
            <Col span={Math.floor(24 / kpiList.length)} key={kpi.key}>
              <div style={{ textAlign: 'center' }}>
                <div style={{ color: '#999', fontSize: 12, marginBottom: 4 }}>{kpi.label}</div>
                <Tooltip title={kpi.target}>
                  {renderKpiValue(kpi.value, kpi.unit)}
                </Tooltip>
                <div style={{ color: '#bbb', fontSize: 11 }}>
                  {kpi.value === undefined || kpi.value === null
                    ? t('behaviorAnalysis.stats.notCollected')
                    : kpi.target}
                </div>
              </div>
            </Col>
          ))}
        </Row>
      </Card>

      {/* 风险趋势 + 告警类型分布 */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={14}>
          <Card
            title={t('behaviorAnalysis.stats.riskTrend')}
            size="small"
            extra={(
              <Segmented
                size="small"
                value={trendRange}
                onChange={(v) => changeTrendRange(v as TrendRangeKey)}
                options={[
                  { value: 'today', label: t('behaviorAnalysis.stats.rangeToday') },
                  { value: 'week', label: t('behaviorAnalysis.stats.rangeWeek') },
                  { value: 'month', label: t('behaviorAnalysis.stats.rangeMonth') },
                ]}
              />
            )}
          >
            {/* 滚轮缩放监听挂在这层：图表为空时节点也必须在，否则挂载阶段拿不到 DOM */}
            <div ref={trendChartRef}>
              <Spin spinning={timelineLoading}>
                {timeline.length > 0 ? (
                  <Line {...lineConfig} />
                ) : (
                  <Empty description={t('behaviorAnalysis.noData')} style={{ padding: 60 }} />
                )}
              </Spin>
            </div>
          </Card>
        </Col>
        <Col span={10}>
          <Card title={t('behaviorAnalysis.stats.alertTypeDistribution')} size="small">
            <Spin spinning={statsLoading}>
              {alertTypeData.length > 0 ? (
                <Pie {...pieConfig} />
              ) : (
                <Empty description={t('behaviorAnalysis.noData')} style={{ padding: 60 }} />
              )}
            </Spin>
          </Card>
        </Col>
      </Row>

      {/* Top 风险用户 + 智能体 */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={12}>
          <Card
            title={t('behaviorAnalysis.stats.topRiskUsers')}
            size="small"
            extra={stats.untrustedAlerts > 0 ? (
              <span style={{ color: '#999', fontSize: 12 }}>
                {t('behaviorAnalysis.stats.untrustedHint', { count: stats.untrustedAlerts })}
              </span>
            ) : null}
          >
            <Table
              dataSource={topRiskUsers}
              columns={userColumns}
              rowKey="userId"
              size="small"
              pagination={false}
              loading={statsLoading}
              locale={{ emptyText: <Empty description={t('behaviorAnalysis.noData')} /> }}
              onRow={(record: any) => ({
                onClick: () => navigate(`/behavior-analysis/profiles?tab=user&userId=${record.userId}`),
                style: { cursor: 'pointer' },
              })}
            />
          </Card>
        </Col>
        <Col span={12}>
          <Card title={t('behaviorAnalysis.stats.topRiskAgents')} size="small">
            <Table
              dataSource={topRiskAgents}
              columns={agentColumns}
              rowKey="agentId"
              size="small"
              pagination={false}
              loading={statsLoading}
              locale={{ emptyText: <Empty description={t('behaviorAnalysis.noData')} /> }}
              onRow={(record: any) => ({
                onClick: () => navigate(`/behavior-analysis/profiles?tab=agent&agentId=${record.agentId}`),
                style: { cursor: 'pointer' },
              })}
            />
          </Card>
        </Col>
      </Row>

      {/* 最近告警区块已移除：与告警中心页面重复，避免数据双源维护 */}
    </div>
  );
};

export default BehaviorDashboardPage;
