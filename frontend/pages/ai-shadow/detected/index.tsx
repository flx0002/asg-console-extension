/* eslint-disable max-len, no-nested-ternary, @typescript-eslint/indent, react/jsx-indent */
import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { Card, Table, Switch, Tag, Button, message, Modal, Statistic, Row, Col, Space, Spin, Empty, Tooltip, Descriptions, Input, Segmented, Alert, DatePicker } from 'antd';
import { EyeOutlined, WarningOutlined, ReloadOutlined, LineChartOutlined, CheckOutlined, PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Line } from '@ant-design/charts';
import { useRequest } from 'ahooks';
import { getAiShadowDetectedAccesses, getAiShadowDetectedStats, setAiShadowDetectMode, getAiShadowDetectMode, getAiShadowDetectEvents, getAiShadowDetectedTrend, getAiShadowAuthorizedDomains, updateAiShadowAuthorizedDomains } from '@/services';
import { AiShadowDetectedAccess, AiShadowDetectEvent, AiShadowAuthzItem } from '@/interfaces/ai-shadow';
import { useTranslation } from 'react-i18next';
// antd 4 的 RangePicker 受控值必须是 moment 对象（项目未迁 dayjs），
// 只在自定义区间控件里用到，不另引日期库。
import moment from 'moment';

const REFRESH_INTERVAL = 30000;
const EVENT_PAGE_SIZE = 10;
const { RangePicker } = DatePicker;

const pad2 = (n: number) => String(n).padStart(2, '0');

// 趋势分级取色，与列表里的状态标签同色（阻断/监控/放行）
const TREND_STATUS_COLORS: Record<string, string> = {
  blocked: '#cf1322',
  monitored: '#1890ff',
  allowed: '#52c41a',
};
const TREND_STATUS_DEFAULT_COLOR = '#1890ff';

// 页面级时间范围：三档固定窗口 + 自定义区间。
// 它同时决定 KPI、趋势、事件明细三处口径，所以必须挂在页面层，
// 而不是塞进某一张卡片的 extra 里造成「只有那张图会变」的错觉。
type FixedRangeKey = 'today' | 'week' | 'month';
type TimeRangeKey = FixedRangeKey | 'custom';

// 三档时间窗，起点一律对齐浏览器本地零点。后端按调用方给的边界切桶
// （容器跑 UTC，不能让它自己按 JVM 时区对齐），所以这里必须给出本地零点。
const rangeWindow = (key: FixedRangeKey): { startTime: number; endTime: number } => {
  const endTime = Date.now();
  const days = key === 'today' ? 1 : key === 'week' ? 7 : 30;
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  start.setDate(start.getDate() - (days - 1));
  return { startTime: start.getTime(), endTime };
};

// 后端 MAX_TREND_WINDOW_MS = 31 天，超出会被静默截断起点：
// 界面显示的范围与实际统计范围就不再一致，所以前端先卡住。
const MAX_CUSTOM_DAYS = 31;

// 自定义区间按自然日取整（起点当日 00:00、终点当日 23:59:59.999）。
// 后端以传入值作起点切桶且不做对齐，跨天窗口必须给整日边界，
// 否则天桶从半天处开始，与前端按本地零点补的零轴错位、真实值落不到轴上。
const startOfDay = (ms: number): number => new Date(ms).setHours(0, 0, 0, 0);
const endOfDay = (ms: number): number => new Date(ms).setHours(23, 59, 59, 999);

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
  // 桶网格必须与后端同构：后端以调用方传入的 start 为起点（不做对齐）、按 step 递增。
  // 天桶在此再对齐到本地零点（与后端按产品时区取当日 00:00 同构）；
  // 小时桶不能独立按整点 floor，否则非整点时区（如 +05:30）会与后端网格错开最多
  // 59 分钟，补零后真实值全部落不到轴上、整条曲线被显示成全 0。
  const first = granularity === 'hour' ? start : new Date(start).setHours(0, 0, 0, 0);
  const axis: number[] = [];
  for (let ts = first; ts <= end; ts += step) axis.push(ts);
  return axis;
};

// 授权域名输入规范化：去空白、转小写、剥掉用户习惯性输入的 *. 通配前缀
// （后端 isDomainAuthorized 用后缀匹配实现子域包含，通配符写法会被当成非法域名）。
const normalizeAuthzInput = (raw: string): string => raw.trim().toLowerCase().replace(/^\*\.?/, '');

// 只接受后端 isDomainAuthorized 真能命中的写法。后端做的是字面后缀匹配，
// 含下划线的输入（chat_example_com 这类指标标签还原产物）永远匹配不到真实域名，
// 空标签（a..b / 结尾点 / 标签边界连字符）同理——放进来就是「授权成功但不生效」。
const isPlausibleDomain = (value: string): boolean => value.length > 0 && value.length <= 255
  && /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$/.test(value);

const formatDateTime = (ms?: number): string => {
  if (!ms) return '-';
  const d = new Date(Number(ms));
  if (isNaN(d.getTime())) return '-';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
};

// 后端 LocalDateTime 可能序列化为 [y,m,d,h,m,s,ns] 数组，统一转可读字符串
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
  // 页面级时间范围：KPI / 趋势 / 明细三处共用，改一处全页生效
  const [timeRange, setTimeRange] = useState<TimeRangeKey>('today');
  // 自定义区间（毫秒，闭区间）。仅在 timeRange === 'custom' 时参与取数
  const [customRange, setCustomRange] = useState<[number, number] | null>(null);

  // 取数窗口的唯一来源。windowKey 是给 refreshDeps 用的稳定标量键：
  // 直接用对象会让每次渲染都触发重新请求。
  const windowKey = timeRange === 'custom'
    ? `custom:${customRange?.[0] ?? 0}:${customRange?.[1] ?? 0}`
    : timeRange;
  const currentWindow = (): { startTime: number; endTime: number } => {
    if (timeRange !== 'custom') return rangeWindow(timeRange);
    if (customRange) return { startTime: startOfDay(customRange[0]), endTime: endOfDay(customRange[1]) };
    // 刚切到自定义还没选日期：先按近 7 天取数，避免整页空白
    return rangeWindow('week');
  };
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

  // 未授权访问列表、KPI、趋势三处共用同一个时间档位窗口，口径一致才不会出现
  // 「明细有数据、上面全 0」这种自相矛盾的画面。
  const { data: detectedList, loading: detectedLoading, refresh: refreshDetected } = useRequest(
    () => getAiShadowDetectedAccesses(currentWindow()),
    {
      refreshDeps: [windowKey],
      pollingInterval: REFRESH_INTERVAL,
      pollingWhenHidden: false,
      onError: () => {},
    },
  );

  // 必须拿到 loading / error：直接把缺失值 ?? 0，会在请求失败时静默显示成
  // 「没有检测到」，正是本次要修掉的原始症状（KPI 全 0 但明细有数据）。
  const { data: statsData, loading: statsLoading, error: statsError } = useRequest(
    () => getAiShadowDetectedStats(currentWindow()),
    {
      refreshDeps: [windowKey],
      pollingInterval: REFRESH_INTERVAL,
      pollingWhenHidden: false,
      onError: () => {},
    },
  );

  const { data: eventPageData, loading: eventsLoading, refresh: refreshEvents } = useRequest(
    () => {
      // 事件明细同样属于本页，必须跟随页面级时间范围：
      // 只有在下钻（点了趋势某个桶）时才用更窄的桶窗口覆盖。
      const win = eventTimeFilter
        ? { startTime: eventTimeFilter.startTime, endTime: eventTimeFilter.endTime }
        : currentWindow();
      return getAiShadowDetectEvents({
        page: eventPage,
        size: eventSize,
        ...win,
        ...(eventTimeFilter?.status ? { status: eventTimeFilter.status } : {}),
      });
    },
    {
      refreshDeps: [eventPage, eventSize, eventTimeFilter, windowKey],
      pollingInterval: REFRESH_INTERVAL,
      pollingWhenHidden: false,
      onError: () => {},
    },
  );

  // 检测趋势：时间窗由视图决定，粒度由后端按跨度自适应（≤2 天小时桶，否则天桶）
  const { data: trendData, loading: trendLoading } = useRequest(
    () => getAiShadowDetectedTrend(currentWindow()),
    {
      refreshDeps: [windowKey],
      pollingInterval: REFRESH_INTERVAL,
      pollingWhenHidden: false,
      onError: () => {},
    },
  );

  // Authorized domains of the DNS/bypass policy (IR-003 unified authorization entry).
  // 带着页面窗口一起取：每条授权要显示「窗口内命中多少流量」，窗口变了命中数就得重算。
  const { data: authzData, refresh: refreshAuthz } = useRequest(
    () => getAiShadowAuthorizedDomains(currentWindow()),
    {
      refreshDeps: [windowKey],
      onError: () => {},
    },
  );
  const [authzInput, setAuthzInput] = useState('');
  const [authzSearch, setAuthzSearch] = useState('');
  const [authzSelected, setAuthzSelected] = useState<string[]>([]);

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

  // 图例顺序即取色顺序：后端已按严重度定序，这里保持首次出现顺序
  const trendStatusKeys = useMemo(() => {
    const seen: string[] = [];
    trendPoints.forEach((p) => {
      if (!seen.includes(p.status)) seen.push(p.status);
    });
    return seen;
  }, [trendData]);

  // 存在任意状态系列才按多系列渲染（此时补零后的行才有 statusLabel 可用）
  const useStatusSeries = trendStatusKeys.length > 0;

  // 多系列摊平成 G2 需要的行。
  // 必须以完整桶轴为骨架逐桶补零：后端只回「有数据的桶」（Prometheus 仅对 series
  // 存在的求值点返回样本），若直接摊平 list，7/30 天窗口只要有一个桶有值，
  // 横轴就会塌成那几个桶（实测只剩 1 个点），与「窗口内其余天为 0」的语义不符。
  // 补零只针对出现过的 status，不凭空造图例项；完全无数据时退化为单系列零轴。
  const trendChartData = useMemo(() => {
    const keys = trendStatusKeys.length ? trendStatusKeys : [''];
    const hit = new Map<string, number>();
    trendPoints.forEach((p) => hit.set(`${p.timestamp}|${p.status}`, p.count));
    const rows: Record<string, string | number>[] = [];
    trendAxis.forEach((ts) => {
      const timeLabel = formatTooltipTime(ts, trendGranularity);
      keys.forEach((k) => {
        rows.push({
          timestamp: ts,
          timeLabel,
          statusLabel: k ? trendLabel(k) : '',
          statusKey: k,
          count: k ? (hit.get(`${ts}|${k}`) ?? 0) : 0,
        });
      });
    });
    return rows;
  }, [trendData, trendGranularity, trendAxis, trendStatusKeys, t]);

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

  // 换时间窗后：旧桶索引不再对应同一根轴，缩放回到全量；
  // 下钻窗口同样失效（它可能落在新窗口之外），必须清掉，
  // 否则明细会被一个已经不在页面口径内的桶卡住。
  useEffect(() => {
    trendZoomRef.current = null;
    setTrendZoom(null);
    setEventTimeFilter((prev) => (prev ? null : prev));
    setEventPage(0);
  }, [windowKey]);

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

  // 授权后该域名的访问从「检测到的AI服务访问」里消失，只在下方「授权域名管理」保留一条授权记录；
  // 撤销授权后它会重新出现。两张表各管一件事：同一域名不会在两处各有一行、更不会两处都能「移除」。
  const handleAuthorizeDomain = useCallback(async (domain: string, source: 'manual' | 'detected' = 'manual') => {
    if (!domain) return;
    try {
      await updateAiShadowAuthorizedDomains({ addDomains: [domain], source });
      message.success(t('aiShadow.authzSuccess', { domain }));
      refreshAuthz();
      refreshDetected();
    } catch {
      message.error(t('aiShadow.actionFailed'));
    }
  }, [t, refreshAuthz, refreshDetected]);

  // 移除授权会让该域名重新回到「未授权访问」列表，是影响安全判断的破坏性操作，
  // 单条与批量都必须先确认，避免误点即生效、且没有撤销入口。
  const handleRemoveDomain = useCallback((domain: string) => {
    Modal.confirm({
      title: t('aiShadow.deauthzConfirmSingle', { domain }),
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await updateAiShadowAuthorizedDomains({ removeDomains: [domain] });
          message.success(t('aiShadow.deauthzSuccess', { domain }));
          setAuthzSelected((keys) => keys.filter((k) => k !== domain));
          refreshAuthz();
          refreshDetected();
        } catch {
          message.error(t('aiShadow.actionFailed'));
        }
      },
    });
  }, [t, refreshAuthz, refreshDetected]);

  const handleAddDomain = useCallback(async () => {
    const domain = normalizeAuthzInput(authzInput);
    if (!domain) return;
    if (!isPlausibleDomain(domain)) {
      message.error(t('aiShadow.authzInvalidDomain'));
      return;
    }
    // 后端 isDomainAuthorized 是后缀匹配：已有 example.com 时再授权 api.example.com
    // 不报重复，但它永远会被父域覆盖，属于加了也没意义的冗余条目。
    const existing = authzData?.domains || [];
    const covered = existing.some((authorized) => {
      const base = normalizeAuthzInput(authorized);
      return base.length > 0 && (domain === base || domain.endsWith(`.${base}`));
    });
    if (covered) {
      message.warning(t('aiShadow.authzDuplicate'));
      return;
    }
    await handleAuthorizeDomain(domain, 'manual');
    setAuthzInput('');
  }, [authzInput, authzData, handleAuthorizeDomain, t]);

  const handleBatchDeauthz = useCallback((domains: string[]) => {
    if (!domains.length) return;
    Modal.confirm({
      title: t('aiShadow.deauthzConfirmBatch', { count: domains.length }),
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await updateAiShadowAuthorizedDomains({ removeDomains: domains });
          message.success(t('aiShadow.authzBatchDeauthzSuccess', { count: domains.length }));
          setAuthzSelected([]);
          refreshAuthz();
          refreshDetected();
        } catch {
          message.error(t('aiShadow.actionFailed'));
        }
      },
    });
  }, [t, refreshAuthz, refreshDetected]);

  // Compute summary statistics for detected access view
  // KPI 取后端在同一窗口上的聚合结果，不再由前端对列表求和：列表被授权过滤且分页截断，
  // 前端求和必然与事件明细对不上（这正是"明细有数据、KPI 全 0"的成因之一）。
  const trendRangeLabel = timeRange === 'today'
    ? t('aiShadow.trendRangeToday')
    : timeRange === 'week' ? t('aiShadow.trendRangeWeek')
      : timeRange === 'month' ? t('aiShadow.trendRangeMonth') : t('aiShadow.trendRangeCustom');

  // 筛选栏右侧的实际统计区间：固定档位也显示具体边界，
  // 避免「近 7 天」这类相对说法与后端实际取值产生歧义。
  const rangeSummary = useMemo(() => {
    const w = currentWindow();
    return `${formatDateTime(w.startTime)} ~ ${formatDateTime(w.endTime)}`;
  }, [windowKey]);

  const handleTimeRangeChange = useCallback((next: TimeRangeKey) => {
    setTimeRange(next);
    // 首次切到自定义且还没有区间时给一个可用初值（近 7 天），
    // 否则选择器为空、下方却在用上一档数据，界面自相矛盾。
    if (next === 'custom' && !customRange) {
      const w = rangeWindow('week');
      // 存整日边界，与 currentWindow() 的取整保持同一口径，
      // 否则选择器显示 12:30、实际统计到 23:59，控件与结果对不上。
      setCustomRange([startOfDay(w.startTime), endOfDay(w.endTime)]);
    }
  }, [customRange]);
  // 区分三种"空"：加载中（卡片骨架）、请求失败（显示 - 并告警）、窗口内真的没有（显示 0）。
  const statsUnavailable = Boolean(statsError);
  const statsDegraded = statsUnavailable || Boolean(statsData?.degraded);
  const totalDetected = statsUnavailable ? '-' : (statsData?.total ?? 0);
  const criticalDetected = statsUnavailable ? '-' : (statsData?.critical ?? 0);
  const highDetected = statsUnavailable ? '-' : (statsData?.high ?? 0);

  // 以下两个 useMemo 必须留在下方 `detectedLoading` 早返回之前：hook 数量在两次渲染间
  // 不一致会直接抛 React #310（首屏 `!detectedList` 必然成立，等于每次进页面都崩）。
  // 授权域名管理表：元数据来自后端 items，缺失时按 domains 兜底为「历史导入」
  const authzItems = useMemo<AiShadowAuthzItem[]>(() => {
    const items = authzData?.items;
    if (items && items.length) return items;
    return (authzData?.domains || []).map((domain) => ({
      domain,
      matchType: 'suffix',
      source: 'imported',
      createdAt: 0,
    }));
  }, [authzData]);

  const authzFiltered = useMemo(() => {
    const keyword = authzSearch.trim().toLowerCase();
    if (!keyword) return authzItems;
    return authzItems.filter((item) => item.domain.includes(keyword));
  }, [authzItems, authzSearch]);

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
        // 状态取值来自事件表，比 Prometheus 计数器更全（含 alerted / masked），
        // 不能再用「blocked 否则放行」的二值判断，否则已告警会被显示成允许。
        const meta: Record<string, { color: string; label: string }> = {
          blocked: { color: 'red', label: t('aiShadow.statusBlocked') },
          alerted: { color: 'volcano', label: t('aiShadow.statusAlerted') },
          monitored: { color: 'blue', label: t('aiShadow.statusMonitored') },
          masked: { color: 'purple', label: t('aiShadow.statusMasked') },
          allowed: { color: 'green', label: t('aiShadow.statusAllowed') },
        };
        const hit = meta[status] || { color: 'default', label: status || '-' };
        return <Tag color={hit.color}>{hit.label}</Tag>;
      },
    },
    {
      title: t('aiShadow.detectedAuthzState'),
      dataIndex: 'authorized',
      key: 'authorized',
      width: 110,
      render: (authorized: boolean) => (authorized
        ? <Tag color="green">{t('aiShadow.authzStateAuthorized')}</Tag>
        : <Tag color="orange">{t('aiShadow.authzStateUnauthorized')}</Tag>),
      sorter: (a: AiShadowDetectedAccess, b: AiShadowDetectedAccess) =>
        Number(a.authorized ?? false) - Number(b.authorized ?? false),
    },
    {
      title: t('aiShadow.detectedRequestCount'),
      dataIndex: 'requestCount',
      key: 'requestCount',
      render: (val: number) => val?.toLocaleString() ?? '-',
      sorter: (a: AiShadowDetectedAccess, b: AiShadowDetectedAccess) => a.requestCount - b.requestCount,
    },
    {
      title: t('aiShadow.detectedLastSeen'),
      dataIndex: 'lastSeen',
      key: 'lastSeen',
      width: 160,
      render: (value: number) => <span style={{ color: value ? undefined : '#bfbfbf' }}>{formatDateTime(value)}</span>,
      sorter: (a: AiShadowDetectedAccess, b: AiShadowDetectedAccess) => (a.lastSeen ?? 0) - (b.lastSeen ?? 0),
    },
    {
      title: t('aiShadow.detectedActions'),
      key: 'actions',
      width: 100,
      // 这张表只含未授权访问（已授权的域名归下方「授权域名管理」），所以只有一个动作。
      // 撤销授权必须留在授权表里做：同一动作两个入口会带来两套确认文案，
      // 用户也没法判断哪一处才是授权的真实状态。
      render: (_: unknown, record: AiShadowDetectedAccess) => (
        <Button
          type="link"
          size="small"
          icon={<CheckOutlined />}
          disabled={!enabled}
          onClick={() => handleAuthorizeDomain(record.sni, 'detected')}
        >
          {t('aiShadow.authzBtn')}
        </Button>
      ),
    },
  ];

  // 表格列定义不是 hook，放在早返回之后不影响 hooks 顺序
  const authzColumns = [
    {
      title: t('aiShadow.authzColDomain'),
      dataIndex: 'domain',
      key: 'domain',
      render: (value: string) => <span style={{ fontFamily: 'monospace' }}>{value}</span>,
    },
    {
      title: t('aiShadow.authzColMatch'),
      dataIndex: 'matchType',
      key: 'matchType',
      width: 150,
      render: (value: string) => (
        <Tooltip title={t('aiShadow.authzSuffixHint')}>
          <Tag color={value === 'suffix' ? 'blue' : 'default'}>
            {value === 'suffix' ? t('aiShadow.authzMatchSuffix') : t('aiShadow.authzMatchExact')}
          </Tag>
        </Tooltip>
      ),
    },
    {
      // 让「看起来授权了、实际一条也没覆盖」的空转条目在界面上直接暴露：
      // 授权列表里混入过从旧指标标签还原出来的下划线条目，它们匹配不到任何真实域名。
      title: (
        <Tooltip title={t('aiShadow.authzHitsHint')}>
          <span>{t('aiShadow.authzColHits')}</span>
        </Tooltip>
      ),
      dataIndex: 'hitCount',
      key: 'hitCount',
      width: 130,
      render: (value: number | null | undefined, record: AiShadowAuthzItem) => {
        // null 表示统计失败，与 0（确认没有流量）不是一回事，必须分开显示
        if (value === null || value === undefined) {
          return (
            <Tooltip title={t('aiShadow.authzHitsUnknown')}>
              <span style={{ color: '#bfbfbf' }}>-</span>
            </Tooltip>
          );
        }
        const domains = record.hitDomains || [];
        const tip = value === 0
          ? t('aiShadow.authzHitsZero')
          : t('aiShadow.authzHitsDetail', { domains: domains.join(', ') });
        return (
          <Tooltip title={tip}>
            <span style={{ color: value === 0 ? '#bfbfbf' : undefined, fontWeight: value > 0 ? 600 : undefined }}>
              {value.toLocaleString()}
            </span>
          </Tooltip>
        );
      },
      sorter: (a: AiShadowAuthzItem, b: AiShadowAuthzItem) => (a.hitCount ?? -1) - (b.hitCount ?? -1),
    },
    {
      title: t('aiShadow.authzColSource'),
      dataIndex: 'source',
      key: 'source',
      width: 140,
      render: (value: string) => {
        const meta: Record<string, { color: string; label: string }> = {
          manual: { color: 'green', label: t('aiShadow.authzSourceManual') },
          detected: { color: 'gold', label: t('aiShadow.authzSourceDetected') },
          imported: { color: 'default', label: t('aiShadow.authzSourceImported') },
        };
        const hit = meta[value] || meta.imported;
        return <Tag color={hit.color}>{hit.label}</Tag>;
      },
    },
    {
      title: t('aiShadow.authzColCreatedAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (value: number) => <span style={{ color: value ? undefined : '#bfbfbf' }}>{formatDateTime(value)}</span>,
    },
    {
      title: t('aiShadow.authzColActions'),
      key: 'actions',
      width: 100,
      render: (_: unknown, record: AiShadowAuthzItem) => (
        <Button
          type="link"
          size="small"
          danger
          disabled={!enabled}
          onClick={() => handleRemoveDomain(record.domain)}
        >
          {t('aiShadow.deauthzBtn')}
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
      {/* 页面级时间范围：同时作用于下方 KPI、趋势与明细三块。
          它原先挂在趋势卡片的 extra 里，会让人误以为只有那张图跟着变，
          而实际上 KPI 和明细也在一起变——口径控件必须放在它管辖的范围内。 */}
      <div style={{
        display: 'flex',
        alignItems: 'center',
        flexWrap: 'wrap',
        gap: 12,
        padding: '12px 16px',
        marginBottom: 16,
        background: '#fff',
        borderRadius: 8,
        boxShadow: '0 1px 2px rgba(0, 0, 0, 0.04)',
      }}>
        <span style={{ fontSize: 13, color: '#595959' }}>{t('aiShadow.timeRangeLabel')}</span>
        <Segmented
          value={timeRange}
          onChange={(v) => handleTimeRangeChange(v as TimeRangeKey)}
          options={[
            { value: 'today', label: t('aiShadow.trendRangeToday') },
            { value: 'week', label: t('aiShadow.trendRangeWeek') },
            { value: 'month', label: t('aiShadow.trendRangeMonth') },
            { value: 'custom', label: t('aiShadow.trendRangeCustom') },
          ]}
        />
        {timeRange === 'custom' ? (
          <RangePicker
            allowClear={false}
            style={{ width: 260 }}
            // antd 4 的受控值必须是 moment 对象，null 表示未选择
            value={customRange ? ([moment(customRange[0]), moment(customRange[1])] as any) : null}
            // 不允许选未来日期（窗口不会跑到未来）
            disabledDate={(d: any) => !!d && startOfDay(d.valueOf()) > startOfDay(Date.now())}
            onChange={(v: any) => {
              if (!v || !v[0] || !v[1]) return;
              const start = startOfDay(v[0].valueOf());
              const end = endOfDay(v[1].valueOf());
              // 超过后端窗口上限时后端会静默截断起点，界面与实际口径就会对不上，
              // 所以这里直接拒绝并提示，不放进一个"看起来生效其实被截断"的窗口。
              if (end - start > MAX_CUSTOM_DAYS * 86400000) {
                message.warning(t('aiShadow.timeRangeTooLong', { days: MAX_CUSTOM_DAYS }));
                return;
              }
              setCustomRange([start, end]);
            }}
          />
        ) : null}
        <span style={{ marginLeft: 'auto', fontSize: 12, color: '#8c8c8c' }}>
          {t('aiShadow.kpiWindowHint', { range: rangeSummary })}
        </span>
      </div>
      {statsDegraded ? (
        <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={t('aiShadow.trendDegraded')} />
      ) : null}
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card loading={statsLoading && !statsData}>
            <Statistic
              title={t('aiShadow.totalDetectedAccesses')}
              value={totalDetected}
              prefix={<EyeOutlined />}
            />
            {/* 未授权口径下的补充说明：已授权的访问不计入总量，
                这里点明排除了多少，避免用户把总量变小误读成「流量下降」。 */}
            {!statsUnavailable && (statsData?.authorizedTotal ?? 0) > 0 ? (
              <div style={{ marginTop: 4, fontSize: 12, color: '#8c8c8c' }}>
                {t('aiShadow.kpiAuthorizedHint', {
                  count: statsData?.authorizedTotal ?? 0,
                  domains: statsData?.authorizedDomainCount ?? 0,
                })}
              </div>
            ) : null}
          </Card>
        </Col>
        <Col span={8}>
          <Card loading={statsLoading && !statsData}>
            <Statistic
              title={t('aiShadow.criticalRiskAccesses')}
              value={criticalDetected}
              valueStyle={{ color: '#cf1322' }}
              prefix={<WarningOutlined />}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card loading={statsLoading && !statsData}>
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
        // 这里只读展示当前口径，选择器已上移到页面级筛选栏：
        // 时间范围同时影响 KPI 与明细，挂在单张卡片上会误导。
        extra={<Tag>{trendRangeLabel}</Tag>}
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
            <Tag>{trendRangeLabel}</Tag>
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
        <Space style={{ marginBottom: 12 }} wrap>
          <Input
            style={{ width: 320 }}
            value={authzInput}
            placeholder={t('aiShadow.authzAddPlaceholder')}
            onChange={(e) => setAuthzInput(e.target.value)}
            disabled={!enabled}
            onPressEnter={handleAddDomain}
            allowClear
          />
          <Button type="primary" icon={<PlusOutlined />} disabled={!enabled} onClick={handleAddDomain}>
            {t('aiShadow.authzAddBtn')}
          </Button>
          <Input.Search
            allowClear
            style={{ width: 220 }}
            placeholder={t('aiShadow.authzSearchPlaceholder')}
            value={authzSearch}
            onChange={(e) => setAuthzSearch(e.target.value)}
          />
          <Button
            danger
            disabled={!enabled || authzSelected.length === 0}
            onClick={() => handleBatchDeauthz(authzSelected)}
          >
            {t('aiShadow.authzBatchDeauthz', { count: authzSelected.length })}
          </Button>
        </Space>
        <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 8 }}>
          {t('aiShadow.authzSuffixHint')}
        </div>
        <Table
          dataSource={authzFiltered}
          columns={authzColumns}
          rowKey="domain"
          size="small"
          pagination={{ pageSize: 10, hideOnSinglePage: true, size: 'small' }}
          locale={{ emptyText: t('aiShadow.authzNoData') }}
          rowSelection={{
            selectedRowKeys: authzSelected,
            onChange: (keys) => setAuthzSelected(keys as string[]),
          }}
        />
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
