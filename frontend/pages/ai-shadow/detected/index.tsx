/* eslint-disable max-len, no-nested-ternary, @typescript-eslint/indent, react/jsx-indent */
import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { Card, Table, Switch, Tag, Button, message, Statistic, Row, Col, Space, Spin, Empty, Tooltip, Descriptions, Input, Segmented, Alert } from 'antd';
import { EyeOutlined, WarningOutlined, ReloadOutlined, LineChartOutlined, CheckOutlined, PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Line } from '@ant-design/charts';
import { useRequest } from 'ahooks';
import { getAiShadowDetectedAccesses, setAiShadowDetectMode, getAiShadowDetectMode, getAiShadowDetectEvents, getAiShadowDetectedTrend, getAiShadowAuthorizedDomains, updateAiShadowAuthorizedDomains } from '@/services';
import { AiShadowDetectedAccess, AiShadowDetectEvent } from '@/interfaces/ai-shadow';
import { useTranslation } from 'react-i18next';

const REFRESH_INTERVAL = 30000;
const EVENT_PAGE_SIZE = 10;

const pad2 = (n: number) => String(n).padStart(2, '0');

// 趋势分级取色，与列表里的状态标签同色（阻断/监控/放行）
const TREND_STATUS_COLORS: Record<string, string> = {
  blocked: '#cf1322',
  monitored: '#1890ff',
  allowed: '#52c41a',
};
const TREND_STATUS_DEFAULT_COLOR = '#1890ff';

// 检测趋势的三种时间视图：今天按小时桶，其余按后端按跨度自适应的天桶
type TrendRangeKey = 'today' | 'week' | 'month';

// 三档时间窗，起点一律对齐浏览器本地零点。后端按调用方给的边界切桶
// （容器跑 UTC，不能让它自己按 JVM 时区对齐），所以这里必须给出本地零点。
const rangeWindow = (key: TrendRangeKey): { startTime: number; endTime: number } => {
  const endTime = Date.now();
  const days = key === 'today' ? 1 : key === 'week' ? 7 : 30;
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  start.setDate(start.getDate() - (days - 1));
  return { startTime: start.getTime(), endTime };
};

// 轴标签只留与粒度相关的部分：天桶看日期、小时桶看时刻，完整时间留给 tooltip
const formatAxisTime = (ts?: number | string, granularity?: string): string => {
  if (ts === undefined || ts === null || ts === '') return '-';
  const d = new Date(Number(ts));
  if (isNaN(d.getTime())) return '-';
  if (granularity === 'hour') {
    return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
  }
  return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
};

// tooltip 标题按粒度取舍：天桶的时刻恒为 00:00（纯噪音），只显示日期
const formatTooltipTime = (ts?: number | string, granularity?: string): string => {
  if (ts === undefined || ts === null || ts === '') return '-';
  const d = new Date(Number(ts));
  if (isNaN(d.getTime())) return '-';
  const day = `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
  if (granularity !== 'hour') return day;
  return `${day} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
};

// 下钻筛选标签上的时间（短格式，够用且不占位）
const formatFilterTime = (ms: number): string => {
  const d = new Date(ms);
  return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
};

// Y 轴大数缩写（与行为仪表盘风险趋势一致：≥1000 → 1.0k，≥100 万 → 1.0M）
const formatAxisNumber = (v: string): string => {
  const num = Number(v);
  if (isNaN(num)) return v;
  if (num >= 1000000) return `${(num / 1000000).toFixed(1)}M`;
  if (num >= 1000) return `${(num / 1000).toFixed(1)}k`;
  return v;
};

// 时间窗内一个点都没有时后端返回空 list（它只回实际有数据的桶），
// 这里按同样步长补一条零轴，否则整张卡片会退化成「暂无数据」，
// 分不清是「没检测到」还是「控件坏了」。
const buildZeroAxis = (start?: number, end?: number, granularity?: string): number[] => {
  if (start === undefined || end === undefined || !isFinite(start) || !isFinite(end) || end < start) return [];
  const step = granularity === 'hour' ? 3600000 : 86400000;
  // 桶起点对齐：小时桶对齐整点；天桶对齐本地零点
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

// 鍚庣 LocalDateTime 鍙兘搴忓垪鍖栦负 [y,m,d,h,m,s,ns] 鏁扮粍锛岀粺涓€杞彲璇诲瓧绗︿覆
const formatEventTime = (t: string | number[] | undefined): string => {
  if (!t) return '-';
  if (Array.isArray(t) && t.length >= 6) {
    const [y, m, d, h = 0, mi = 0, s = 0] = t;
    return `${y}-${pad2(m)}-${pad2(d)} ${pad2(h)}:${pad2(mi)}:${pad2(s)}`;
  }
  return String(t);
};

const parseDetail = (detail?: string): Record<string, unknown> | null => {
  if (!detail) return null;
  try {
    const parsed = JSON.parse(detail);
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch {
    return null;
  }
};

const AiShadowDetectedPage: React.FC<{ enabled?: boolean }> = ({ enabled = true }) => {
  const { t } = useTranslation();
  const [detectMode, setDetectModeState] = useState<string>('monitoring');
  const [eventPage, setEventPage] = useState(0);
  const [eventSize, setEventSize] = useState(EVENT_PAGE_SIZE);
  // 趋势点下钻的时间窗（点某个桶时写入）；null 表示事件列表不按时间过滤
  const [eventTimeFilter, setEventTimeFilter] = useState<
    { startTime: number; endTime: number; status?: string } | null
  >(null);
  const [trendRange, setTrendRange] = useState<TrendRangeKey>('today');
  // onEvent 在图表挂载时只绑一次，回调必须读 ref，否则拿到的是首次渲染的旧轴
  const trendAxisRef = useRef<number[]>([]);
  const trendGranularityRef = useRef<string>('day');
  const trendZoomRef = useRef<{ start: number; end: number } | null>(null);
  const [trendZoom, setTrendZoom] = useState<{ start: number; end: number } | null>(null);
  const trendChartRef = useRef<HTMLDivElement>(null);

  // Load current detect mode on mount（启用状态由外层统一入口管理）
  useEffect(() => {
    getAiShadowDetectMode().then((mode) => {
      if (mode === 'monitoring' || mode === 'enforcement') {
        setDetectModeState(mode);
      }
    }).catch(() => {});
  }, []);

  const { data: detectedList, loading: detectedLoading, refresh: refreshDetected } = useRequest(() => getAiShadowDetectedAccesses(), {
    pollingInterval: REFRESH_INTERVAL,
    pollingWhenHidden: false,
    onError: () => {},
  });

  const { data: eventPageData, loading: eventsLoading, refresh: refreshEvents } = useRequest(
    () => getAiShadowDetectEvents({
      page: eventPage,
      size: eventSize,
      ...(eventTimeFilter
        ? { startTime: eventTimeFilter.startTime, endTime: eventTimeFilter.endTime }
        : {}),
      ...(eventTimeFilter?.status ? { status: eventTimeFilter.status } : {}),
    }),
    {
      refreshDeps: [eventPage, eventSize, eventTimeFilter],
      pollingInterval: REFRESH_INTERVAL,
      pollingWhenHidden: false,
      onError: () => {},
    },
  );

  // 检测趋势：时间窗由视图决定，粒度由后端按跨度自适应（≤2 天小时桶，否则天桶）
  const { data: trendData, loading: trendLoading } = useRequest(
    () => getAiShadowDetectedTrend(rangeWindow(trendRange)),
    {
      refreshDeps: [trendRange],
      pollingInterval: REFRESH_INTERVAL,
      pollingWhenHidden: false,
      onError: () => {},
    },
  );

  // Authorized domains of the DNS/bypass policy (IR-003 unified authorization entry)
  const { data: authzData, refresh: refreshAuthz } = useRequest(() => getAiShadowAuthorizedDomains(), {
    onError: () => {},
  });
  const [authzInput, setAuthzInput] = useState('');

  // Map raw status values to localized labels and stable colors for the chart
  const trendLabel = (status: string) => {
    if (status === 'blocked') return t('aiShadow.statusBlocked');
    if (status === 'monitored') return t('aiShadow.statusMonitored');
    return t('aiShadow.statusAllowed');
  };

  const trendGranularity = trendData?.granularity || 'day';
  // 完整桶轴：后端只回有数据的桶，轴要按窗口补齐才能承载缩放索引与下钻换算
  const trendAxis = useMemo(
    () => buildZeroAxis(trendData?.start, trendData?.end, trendGranularity),
    [trendData, trendGranularity],
  );
  const trendPoints = trendData?.list || [];
  const useStatusSeries = trendPoints.length > 0;

  // 多系列摊平成 G2 需要的行；一个点都没有时退回单系列零轴，
  // 保证「没检测到」和「控件坏了」在图上能区分开
  const trendChartData = useMemo(() => {
    if (useStatusSeries) {
      return trendPoints.map((p) => ({
        timestamp: p.timestamp,
        timeLabel: formatTooltipTime(p.timestamp, trendGranularity),
        statusLabel: trendLabel(p.status),
        statusKey: p.status,
        count: p.count,
      }));
    }
    return trendAxis.map((ts) => ({
      timestamp: ts,
      timeLabel: formatTooltipTime(ts, trendGranularity),
      statusLabel: '',
      statusKey: '',
      count: 0,
    }));
  }, [trendData, trendGranularity, trendAxis, useStatusSeries, t]);

  // 图例顺序即取色顺序：后端已按严重度定序，这里保持首次出现顺序
  const trendStatusKeys = useMemo(() => {
    const seen: string[] = [];
    trendPoints.forEach((p) => {
      if (!seen.includes(p.status)) seen.push(p.status);
    });
    return seen;
  }, [trendData]);

  // 缩放窗口内的可见数据；下钻仍按全量桶轴换算时间窗，切片不影响下钻
  const zoomLo = trendZoom ? trendAxis[trendZoom.start] : undefined;
  const zoomHi = trendZoom ? trendAxis[trendZoom.end] : undefined;
  const trendVisibleData = zoomLo !== undefined && zoomHi !== undefined
    ? trendChartData.filter((d) => d.timestamp >= zoomLo && d.timestamp <= zoomHi)
    : trendChartData;

  useEffect(() => {
    trendAxisRef.current = trendAxis;
    trendGranularityRef.current = trendGranularity;
  }, [trendAxis, trendGranularity]);

  // 换时间窗后旧的桶索引窗口不再对应同一根轴，缩放必须回到全量
  useEffect(() => {
    trendZoomRef.current = null;
    setTrendZoom(null);
  }, [trendRange]);

  // 滚轮缩放：G2Plot 的 Line 没有 dataZoom，这里按桶索引收放可见窗口来达到同样的放大/缩小效果。
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

  // 点位下钻：点某个桶 → 下方事件列表按该桶时间窗（及该桶的状态系列）过滤
  const handleTrendEvent = (_chart: any, ev: any) => {
    if (ev?.type !== 'element:click') return;
    // element:click 的原始行在不同几何下位于 _origin 或 data 本身，两处都取一下
    const datum = ev?.data?.data?._origin ?? ev?.data?.data;
    const ts = Number(datum?.timestamp);
    if (!datum || isNaN(ts)) return;
    const axis = trendAxisRef.current;
    const idx = axis.indexOf(ts);
    // 桶宽直接取相邻桶差值（轴已按产品时区对齐），首桶退化到粒度步长
    const step = idx > 0
      ? axis[idx] - axis[idx - 1]
      : (trendGranularityRef.current === 'hour' ? 3600000 : 86400000);
    setEventTimeFilter({
      startTime: ts,
      endTime: ts + step - 1,
      ...(datum.statusKey ? { status: datum.statusKey } : {}),
    });
    setEventPage(0);
  };

  // 检测趋势图配置（yField=count，有数据时按状态分系列）
  const trendLineConfig = {
    data: trendVisibleData,
    xField: 'timestamp',
    yField: 'count',
    ...(useStatusSeries ? { seriesField: 'statusLabel' } : {}),
    smooth: true,
    height: 260,
    // 折线路径本身不是单点，须显式开启 point 几何才能按数据点点击下钻
    point: { size: 3, shape: 'circle' as const },
    xAxis: {
      label: { formatter: (v: string) => formatAxisTime(Number(v), trendGranularity) },
    },
    yAxis: {
      title: { text: t('aiShadow.trendCount') },
      min: 0,
      label: { formatter: (v: string) => formatAxisNumber(v) },
    },
    // 末位刻度居中对齐会超出画布右边缘（窄窗口下被裁切），补右侧留白
    appendPadding: [0, 16, 0, 0],
    // tooltip 项名称取 yField 字段名，用 alias 换成业务文案
    meta: { count: { alias: t('aiShadow.trendCount') } },
    tooltip: {
      // G2 的 TooltipTitle 签名是 (title, datum)，首个实参是 xField 原值
      title: (titleVal: string, datum: any) =>
        datum?.timeLabel || formatTooltipTime(Number(datum?.timestamp ?? titleVal), trendGranularity),
    },
    // 分级时顶部图例，单系列不展示图例
    legend: useStatusSeries ? { position: 'top' as const } : false,
    // color 回调拿不到整行数据，改用位置数组，顺序与摊平顺序同源
    color: useStatusSeries
      ? trendStatusKeys.map((k: string) => TREND_STATUS_COLORS[k] || TREND_STATUS_DEFAULT_COLOR)
      : TREND_STATUS_DEFAULT_COLOR,
    onEvent: handleTrendEvent,
  };

  const handleDetectModeSwitch = useCallback(async (currentMode: string) => {
    const newMode = currentMode === 'monitoring' ? 'enforcement' : 'monitoring';
    try {
      await setAiShadowDetectMode(newMode as 'monitoring' | 'enforcement');
      setDetectModeState(newMode);
      message.success(t('aiShadow.modeSwitchSuccess'));
    } catch {
      message.error(t('aiShadow.actionFailed'));
    }
  }, [t]);

  // Authorize a domain on the DNS/bypass policy; the entry disappears from the
  // detected list immediately (same "authorize = remove" semantics as the route page).
  const handleAuthorizeDomain = useCallback(async (domain: string) => {
    if (!domain) return;
    try {
      await updateAiShadowAuthorizedDomains({ addDomains: [domain] });
      message.success(t('aiShadow.authzSuccess', { domain }));
      refreshAuthz();
      refreshDetected();
    } catch {
      message.error(t('aiShadow.actionFailed'));
    }
  }, [t, refreshAuthz, refreshDetected]);

  const handleRemoveDomain = useCallback(async (domain: string) => {
    try {
      await updateAiShadowAuthorizedDomains({ removeDomains: [domain] });
      message.success(t('aiShadow.deauthzSuccess', { domain }));
      refreshAuthz();
      refreshDetected();
    } catch {
      message.error(t('aiShadow.actionFailed'));
    }
  }, [t, refreshAuthz, refreshDetected]);

  const handleAddDomain = useCallback(async () => {
    const domain = authzInput.trim().toLowerCase();
    if (!domain) return;
    await handleAuthorizeDomain(domain);
    setAuthzInput('');
  }, [authzInput, handleAuthorizeDomain]);

  // Compute summary statistics for detected access view
  const totalDetected = (detectedList || []).reduce((sum, item) => sum + item.requestCount, 0);
  const criticalDetected = (detectedList || []).filter(e => e.riskLevel === 'critical').reduce((sum, item) => sum + item.requestCount, 0);
  const highDetected = (detectedList || []).filter(e => e.riskLevel === 'high').reduce((sum, item) => sum + item.requestCount, 0);

  if (detectedLoading && !detectedList) {
    return (
      <div style={{ width: '100%', height: '50vh', display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
        <Spin size="large" />
      </div>
    );
  }

  const detectedColumns = [
    {
      title: t('aiShadow.detectedSni'),
      dataIndex: 'sni',
      key: 'sni',
      render: (text: string) => <span style={{ fontFamily: 'monospace' }}>{text}</span>,
    },
    {
      title: t('aiShadow.detectedCategory'),
      dataIndex: 'categoryLabel',
      key: 'categoryLabel',
    },
    {
      title: t('aiShadow.detectedRiskLevel'),
      dataIndex: 'riskLevel',
      key: 'riskLevel',
      render: (level: string) => {
        const colorMap: Record<string, string> = { critical: '#cf1322', high: '#fa541c', medium: '#faad14', low: '#52c41a' };
        const labelMap: Record<string, string> = {
          critical: t('aiShadow.riskCritical'),
          high: t('aiShadow.riskHigh'),
          medium: t('aiShadow.riskMedium'),
          low: t('aiShadow.riskLow'),
        };
        return <Tag color={colorMap[level] || 'default'}>{labelMap[level] || level}</Tag>;
      },
    },
    {
      title: t('aiShadow.detectedStatus'),
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => {
        const isBlocked = status === 'blocked';
        return (
          <Tag color={isBlocked ? 'red' : 'green'}>
            {isBlocked ? t('aiShadow.statusBlocked') : t('aiShadow.statusAllowed')}
          </Tag>
        );
      },
    },
    {
      title: t('aiShadow.detectedRequestCount'),
      dataIndex: 'requestCount',
      key: 'requestCount',
      render: (val: number) => val?.toLocaleString() ?? '-',
      sorter: (a: AiShadowDetectedAccess, b: AiShadowDetectedAccess) => a.requestCount - b.requestCount,
    },
    {
      title: t('aiShadow.detectedActions'),
      key: 'actions',
      width: 100,
      render: (_: unknown, record: AiShadowDetectedAccess) => (
        <Button
          type="link"
          size="small"
          icon={<CheckOutlined />}
          disabled={!enabled}
          onClick={() => handleAuthorizeDomain(record.sni)}
        >
          {t('aiShadow.authzBtn')}
        </Button>
      ),
    },
  ];

  const riskTag = (level?: string) => {
    const colorMap: Record<string, string> = { critical: '#cf1322', high: '#fa541c', medium: '#faad14', low: '#52c41a' };
    const labelMap: Record<string, string> = {
      critical: t('aiShadow.riskCritical'),
      high: t('aiShadow.riskHigh'),
      medium: t('aiShadow.riskMedium'),
      low: t('aiShadow.riskLow'),
    };
    return <Tag color={colorMap[level || ''] || 'default'}>{labelMap[level || ''] || level || '-'}</Tag>;
  };

  const statusTag = (status?: string) => {
    if (status === 'blocked') return <Tag color="red">{t('aiShadow.statusBlocked')}</Tag>;
    if (status === 'monitored') return <Tag color="blue">{t('aiShadow.statusMonitored')}</Tag>;
    if (status === 'allowed') return <Tag color="green">{t('aiShadow.statusAllowed')}</Tag>;
    return <Tag>{status || '-'}</Tag>;
  };

  const detailLabelMap: Record<string, string> = {
    protocol: t('aiShadow.eventProtocol'),
    method: t('aiShadow.eventMethod'),
    uri: t('aiShadow.eventUri'),
    srcPort: t('aiShadow.eventSrcPort'),
    dstPort: t('aiShadow.eventDstPort'),
    ja3: 'JA3',
    ja4: 'JA4',
  };

  const renderDetail = (detail?: string) => {
    const parsed = parseDetail(detail);
    if (!parsed) return <span style={{ color: '#999' }}>-</span>;
    const entries = Object.entries(parsed).filter(([, v]) => v !== '' && v !== null && v !== undefined);
    if (!entries.length) return <span style={{ color: '#999' }}>-</span>;
    return (
      <Descriptions size="small" column={1} bordered style={{ maxWidth: 640 }}>
        {entries.map(([k, v]) => (
          <Descriptions.Item key={k} label={detailLabelMap[k] || k}>
            <span style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{String(v)}</span>
          </Descriptions.Item>
        ))}
      </Descriptions>
    );
  };

  // Weak audit-chain link (IR-025/S5): handling audit + same-source host info
  const renderAuditLink = (record: AiShadowDetectEvent) => {
    const link = record.auditLink as
      | { handlingAudited?: boolean; auditAction?: string; auditEventId?: string; auditTimeMs?: number; hostEventCount?: number; hostLastEventMs?: number }
      | undefined;
    const hasSession = !!record.sessionId;
    if (!link && !hasSession) return null;
    const fmtMs = (ms?: number) => {
      if (!ms) return '-';
      const d = new Date(ms);
      return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
    };
    return (
      <Descriptions
        size="small"
        column={1}
        bordered
        style={{ maxWidth: 640, marginTop: record.detail ? 8 : 0 }}
        title={undefined}
      >
        {hasSession && (
          <Descriptions.Item label={t('aiShadow.auditSessionLabel')}>
            <span style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{record.sessionId}</span>
          </Descriptions.Item>
        )}
        {link && (
          <Descriptions.Item label={t('aiShadow.auditHandlingLabel')}>
            {link.handlingAudited
              ? <>
                  <Tag color={link.auditAction === 'authorize_domain' ? 'green' : 'orange'}>
                    {link.auditAction === 'authorize_domain' ? t('aiShadow.auditAuthorized') : t('aiShadow.auditDeauthorized')}
                  </Tag>
                  <span style={{ fontFamily: 'monospace' }}>{fmtMs(link.auditTimeMs)} 路 #{link.auditEventId}</span>
                </>
              : <span style={{ color: '#999' }}>{t('aiShadow.auditNoHandling')}</span>}
          </Descriptions.Item>
        )}
        {link && link.hostEventCount !== undefined && (
          <Descriptions.Item label={t('aiShadow.auditHostLabel')}>
            <span style={{ fontFamily: 'monospace' }}>
              {record.srcIp} 路 {t('aiShadow.auditHostEvents')} {link.hostEventCount} 路 {t('aiShadow.auditHostLast')} {fmtMs(link.hostLastEventMs)}
            </span>
          </Descriptions.Item>
        )}
      </Descriptions>
    );
  };

  const eventColumns = [
    {
      title: t('aiShadow.eventColTime'),
      dataIndex: 'eventTime',
      key: 'eventTime',
      width: 170,
      render: (val: AiShadowDetectEvent['eventTime']) => formatEventTime(val),
    },
    {
      title: t('aiShadow.eventColDomain'),
      dataIndex: 'domain',
      key: 'domain',
      render: (text: string) => <span style={{ fontFamily: 'monospace' }}>{text}</span>,
    },
    {
      title: t('aiShadow.eventColDetectType'),
      dataIndex: 'detectType',
      key: 'detectType',
      width: 150,
      render: (text: string) => <span style={{ fontFamily: 'monospace' }}>{text}</span>,
    },
    {
      title: t('aiShadow.eventColSource'),
      dataIndex: 'source',
      key: 'source',
      width: 90,
    },
    {
      title: t('aiShadow.eventColSrcIp'),
      dataIndex: 'srcIp',
      key: 'srcIp',
      width: 140,
      render: (text?: string) => <span style={{ fontFamily: 'monospace' }}>{text || '-'}</span>,
    },
    {
      title: t('aiShadow.detectedRiskLevel'),
      dataIndex: 'riskLevel',
      key: 'riskLevel',
      width: 90,
      render: (level: string) => riskTag(level),
    },
    {
      title: t('aiShadow.detectedStatus'),
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: string) => statusTag(status),
    },
  ];

  const isEnforcement = detectMode === 'enforcement';
  const eventItems = eventPageData?.items || [];
  const eventTotal = eventPageData?.total ?? 0;

  return (
    <div style={{ padding: '0 0 24px' }}>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card>
            <Statistic
              title={t('aiShadow.totalDetectedAccesses')}
              value={totalDetected}
              prefix={<EyeOutlined />}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title={t('aiShadow.criticalRiskAccesses')}
              value={criticalDetected}
              valueStyle={{ color: '#cf1322' }}
              prefix={<WarningOutlined />}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title={t('aiShadow.highRiskAccesses')}
              value={highDetected}
              valueStyle={{ color: '#fa541c' }}
              prefix={<WarningOutlined />}
            />
          </Card>
        </Col>
      </Row>

      <Card
        title={
          <Space>
            <LineChartOutlined />
            <span>{t('aiShadow.trendCardTitle')}</span>
          </Space>
        }
        extra={(
          <Segmented
            size="small"
            value={trendRange}
            onChange={(v) => setTrendRange(v as TrendRangeKey)}
            options={[
              { value: 'today', label: t('aiShadow.trendRangeToday') },
              { value: 'week', label: t('aiShadow.trendRangeWeek') },
              { value: 'month', label: t('aiShadow.trendRangeMonth') },
            ]}
          />
        )}
        style={{ marginBottom: 16 }}
      >
        {/* 滚轮缩放监听挂在这层：图表为空时节点也必须在，否则挂载阶段拿不到 DOM */}
        <div ref={trendChartRef}>
          {/* 数据源不可达与「没有检测到」都会得到空 list，必须显式区分，否则零轴会掩盖故障 */}
          {trendData?.degraded ? (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message={t('aiShadow.trendDegraded')}
            />
          ) : null}
          <Spin spinning={trendLoading}>
            {trendVisibleData.length > 0 ? (
              <Line {...trendLineConfig} />
            ) : (
              <Empty description={t('aiShadow.trendNoData')} style={{ padding: 60 }} />
            )}
          </Spin>
        </div>
      </Card>

      <Card
        title={
          <Space>
            <span>{t('aiShadow.detectedCardTitle')}</span>
            <Tag color={isEnforcement ? 'red' : 'blue'}>
              {isEnforcement ? t('aiShadow.enforcementMode') : t('aiShadow.monitoringMode')}
            </Tag>
          </Space>
        }
        extra={
          <Space>
            <span style={{ fontSize: 13, color: '#666' }}>
              {isEnforcement ? t('aiShadow.detectEnforcementDesc') : t('aiShadow.detectMonitoringDesc')}
            </span>
            <Tooltip title={isEnforcement ? t('aiShadow.enforcementMode') : t('aiShadow.monitoringMode')}>
              <Switch
                checked={isEnforcement}
                disabled={!enabled}
                checkedChildren={t('aiShadow.enforcementMode')}
                unCheckedChildren={t('aiShadow.monitoringMode')}
                onChange={() => handleDetectModeSwitch(detectMode)}
              />
            </Tooltip>
            <Button icon={<ReloadOutlined />} onClick={refreshDetected} loading={detectedLoading}>
              {t('aiShadow.refresh')}
            </Button>
          </Space>
        }
        style={{ marginBottom: 16 }}
      >
        <Table
          dataSource={detectedList || []}
          columns={detectedColumns}
          rowKey={(record) => `${record.sni}-${record.category}`}
          pagination={false}
          size="small"
          locale={{ emptyText: t('aiShadow.noDetectedData') }}
        />
      </Card>

      <Card
        title={
          <Space>
            <SafetyCertificateOutlined />
            <span>{t('aiShadow.authzCardTitle')}</span>
            <Tag color={authzData?.mode === 'enforcement' ? 'red' : 'blue'}>
              {authzData?.mode === 'enforcement' ? t('aiShadow.enforcementMode') : t('aiShadow.monitoringMode')}
            </Tag>
          </Space>
        }
        style={{ marginBottom: 16 }}
      >
        <Space style={{ marginBottom: 12 }}>
          <Input
            style={{ width: 320 }}
            value={authzInput}
            placeholder={t('aiShadow.authzAddPlaceholder')}
            onChange={(e) => setAuthzInput(e.target.value)}
            disabled={!enabled}
            onPressEnter={handleAddDomain}
          />
          <Button type="primary" icon={<PlusOutlined />} disabled={!enabled} onClick={handleAddDomain}>
            {t('aiShadow.authzAddBtn')}
          </Button>
        </Space>
        <div>
          {(authzData?.domains || []).length === 0 ? (
            <Empty description={t('aiShadow.authzNoData')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            (authzData?.domains || []).map((domain: string) => (
              <Tag
                key={domain}
                closable={enabled}
                onClose={(e) => { e.preventDefault(); handleRemoveDomain(domain); }}
                style={{ fontFamily: 'monospace', marginBottom: 4 }}
              >
                {domain}
              </Tag>
            ))
          )}
        </div>
      </Card>

      <Card
        title={(
          <Space>
            <span>{t('aiShadow.eventDetailCardTitle')}</span>
            {eventTimeFilter ? (
              <Tag
                color="blue"
                closable
                onClose={() => {
                  setEventTimeFilter(null);
                  setEventPage(0);
                }}
              >
                {`${t('aiShadow.eventTimeFilter')} ${formatFilterTime(eventTimeFilter.startTime)}`
                  + (eventTimeFilter.status ? ` · ${trendLabel(eventTimeFilter.status)}` : '')}
              </Tag>
            ) : null}
          </Space>
        )}
        extra={
          <Button icon={<ReloadOutlined />} onClick={refreshEvents} loading={eventsLoading}>
            {t('aiShadow.refresh')}
          </Button>
        }
      >
        <Table
          dataSource={eventItems}
          columns={eventColumns}
          rowKey={(record) => String(record.id)}
          size="small"
          loading={eventsLoading}
          expandable={{
            expandedRowRender: (record: AiShadowDetectEvent) => (
              <>
                {renderDetail(record.detail)}
                {renderAuditLink(record)}
              </>
            ),
            rowExpandable: (record: AiShadowDetectEvent) => !!record.detail || !!record.auditLink || !!record.sessionId,
          }}
          pagination={{
            current: eventPage + 1,
            pageSize: eventSize,
            total: eventTotal,
            showSizeChanger: true,
            pageSizeOptions: ['10', '20', '50'],
            onChange: (p: number, s: number) => {
              setEventPage(p - 1);
              setEventSize(s);
            },
            showTotal: (total: number) => t('aiShadow.eventTotalCount', { total }),
          }}
          locale={{ emptyText: t('aiShadow.eventNoData') }}
        />
      </Card>
    </div>
  );
};

export default AiShadowDetectedPage;
