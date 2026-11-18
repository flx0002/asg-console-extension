/* eslint-disable max-len, no-nested-ternary, @typescript-eslint/indent, react/jsx-indent */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert, Button, Card, Col, DatePicker, Empty, Input, Modal, Row, Segmented, Select, Space, Table, Tag, Tooltip,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ReloadOutlined } from '@ant-design/icons';
import { Column } from '@ant-design/charts';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import moment from 'moment';
import {
  getAiConsumerUsage, getAiShadowAuthorizedDomains, getAiShadowCaptureConfig, getAiShadowCaptureNics,
  getAiShadowDetectEvents, getAiShadowDetectMode, getAiShadowDetectedStats, getAiShadowDetectedTrend,
  getAiShadowEnabled, getAiShadowStatus, updateAiShadowAuthorizedDomains,
} from '@/services';
import type { AiConsumerUsage, AiShadowDetectEvent, AiShadowTrendResponse } from '@/interfaces/ai-shadow';
import { eventReasonLabel, formatEventTime, renderEventDetail } from '@/components/ai-shadow-events';
import { isPlausibleDomain, normalizeAuthzInput } from '@/components/ai-shadow-detected-view';

const REFRESH_INTERVAL = 30000;
const DEFAULT_PAGE_SIZE = 20;
const { RangePicker } = DatePicker;

type RangeKey = 'today' | 'week' | 'month' | 'custom';
type ChannelKey = 'all' | 'shadow' | 'route';
type StatusKey = 'all' | 'blocked' | 'recorded';
type RiskKey = 'all' | 'critical' | 'high' | 'medium' | 'low';
type ReasonKey = 'all' | 'no_key' | 'unknown_key' | 'not_in_allow' | 'off_channel'
  | 'ai_domain_match' | 'policy_violation' | 'policy_blocked';

const RANGE_DAYS: Record<Exclude<RangeKey, 'custom'>, number> = { today: 1, week: 7, month: 30 };
const MAX_CUSTOM_DAYS = 31;

// 与配置页、路由页同一套窗口算法：起点对齐浏览器本地零点。
const rangeWindow = (key: Exclude<RangeKey, 'custom'>): { startTime: number; endTime: number } => {
  const endTime = Date.now();
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  start.setDate(start.getDate() - (RANGE_DAYS[key] - 1));
  return { startTime: start.getTime(), endTime };
};

const startOfDay = (ms: number): number => new Date(ms).setHours(0, 0, 0, 0);
const endOfDay = (ms: number): number => new Date(ms).setHours(23, 59, 59, 999);

const pad2 = (n: number): string => (n < 10 ? `0${n}` : String(n));

// 趋势横轴：小时桶只显示时:分，天桶显示月-日。
const formatAxisTime = (ts: number, granularity?: string): string => {
  const d = new Date(ts);
  return granularity === 'hour'
    ? `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
    : `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
};

const statusChannelLabel = (surface?: string): 'shadow' | 'route' => (surface === 'route' ? 'route' : 'shadow');

interface TrendRow {
  timestamp: number;
  channelKey: string;
  channel: string;
  count: number;
}

type DiagState = 'ok' | 'warn' | 'bad';
interface DiagRow {
  state: DiagState;
  name: string;
  text: string;
  actionText?: string;
  onAction?: () => void;
}

// 接口失败与「没有配置」必须分开：失败时返回 ok=false，自检据此标「读取失败」，
// 不能把一次请求错误读成「没开 / 没配」。
const probe = async <T,>(promise: Promise<T>): Promise<{ ok: boolean; value?: T }> => {
  try {
    return { ok: true, value: await promise };
  } catch {
    return { ok: false };
  }
};

// 图表组件单独拎出来并 memo：父组件每 30 秒轮询一次都会重渲染，
// 若它跟着重渲染，G2 会重新挂载并重放一次入场动画，用户眼前那根柱子在原地闪。
// 只有 data 真的变了（trendRows 换引用）才需要重画。
const TrendChart = React.memo(({ config }: { config: Record<string, any> }) => <Column {...config} />);
// memo 包出来的组件没有名字，DevTools 里会显示成 Anonymous。
TrendChart.displayName = 'AiShadowTrendChart';

const AiShadowEventsPage: React.FC = () => {
  const { t } = useTranslation();
  const [range, setRange] = useState<RangeKey>('today');
  const [customRange, setCustomRange] = useState<[number, number] | null>(null);
  const [channel, setChannel] = useState<ChannelKey>('all');
  const [status, setStatus] = useState<StatusKey>('all');
  const [risk, setRisk] = useState<RiskKey>('all');
  const [reason, setReason] = useState<ReasonKey>('all');
  // 输入框里的草稿与已提交的查询条件分开：边打边查会让每敲一个字母都打一次库。
  const [domainInput, setDomainInput] = useState('');
  const [domain, setDomain] = useState('');
  const [srcIpInput, setSrcIpInput] = useState('');
  const [srcIp, setSrcIp] = useState('');
  // 点趋势某一根柱子写进来的时间窗；null 表示不按桶过滤。
  const [drill, setDrill] = useState<{ startTime: number; endTime: number } | null>(null);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE);

  // 已确定的查询窗口。刚切到「自定义」还没确定日期时保持上一个窗口不动，
  // 确定日期后才按新区间取数；windowKey 不变就不会触发下面各请求重查。
  // 今天/本周/本月是滚动窗口（终点=now），每次取数都要现算；只有自定义区间是固定值。
  const [appliedWindow, setAppliedWindow] = useState<{ key: RangeKey; startTime: number; endTime: number }>(
    () => ({ key: 'today', ...rangeWindow('today') }),
  );
  const windowKey = appliedWindow.key === 'custom'
    ? `custom:${appliedWindow.startTime}:${appliedWindow.endTime}`
    : appliedWindow.key;

  const currentWindow = useCallback((): { startTime: number; endTime: number } => {
    if (appliedWindow.key === 'custom') {
      return { startTime: appliedWindow.startTime, endTime: appliedWindow.endTime };
    }
    return rangeWindow(appliedWindow.key);
  }, [appliedWindow]);

  // 「切换窗口」在一处收敛：UI 选中态与取数窗口一起改，自检里的「换到本周」直达也走这里。
  const switchTo = useCallback((key: Exclude<RangeKey, 'custom'>) => {
    setRange(key);
    setAppliedWindow({ key, ...rangeWindow(key) });
    setPage(0);
    setDrill(null);
  }, []);

  // 每次渲染现算，不记进 state/memo：今天/本周/本月是滚动窗口，记下来摘要会一直停在切换那一刻。
  const rangeSummary = `${moment(currentWindow().startTime).format('YYYY-MM-DD HH:mm')} ~ ${moment(currentWindow().endTime).format('YYYY-MM-DD HH:mm')}`;

  // KPI 与两张分面卡都读同一份统计：一次请求就能拿到两侧各自的构成
  // （shadowTotal / admissionTotal / blockedTotal / reason 分面），不必分两次取再对上。
  const { data: stats, loading: statsLoading, error: statsError, refresh: refreshStats } = useRequest(
    () => getAiShadowDetectedStats({ ...currentWindow(), surface: 'shadow' }),
    {
      refreshDeps: [windowKey],
      pollingInterval: REFRESH_INTERVAL,
      pollingWhenHidden: false,
      onError: () => {},
    },
  );

  // 趋势按通道取两份再合并：后端一次只回一个 surface，而这里要的是「同一根轴上的两组柱」。
  const { data: shadowTrend, error: shadowTrendError, refresh: refreshShadowTrend } = useRequest(
    () => getAiShadowDetectedTrend({ ...currentWindow(), surface: 'shadow' }),
    {
      refreshDeps: [windowKey], pollingInterval: REFRESH_INTERVAL, pollingWhenHidden: false, onError: () => {},
    },
  );
  const { data: routeTrend, error: routeTrendError, refresh: refreshRouteTrend } = useRequest(
    () => getAiShadowDetectedTrend({ ...currentWindow(), surface: 'route' }),
    {
      refreshDeps: [windowKey], pollingInterval: REFRESH_INTERVAL, pollingWhenHidden: false, onError: () => {},
    },
  );

  // 两条通道的曲线是拼在一根轴上的：任一侧取数失败，那一侧就是一条恒为 0 的柱子，
  // 和「这段时间真的没有准入事件」长得一模一样。所以必须把失败单独记下来给提示。
  const trendBroken = !!shadowTrendError || !!routeTrendError;

  // 用量来自 Prometheus（只有 6 小时），与事件表不是同一个数据源，所以单独一张卡。
  const { data: usage, error: usageError, refresh: refreshUsage } = useRequest(() => getAiConsumerUsage(currentWindow()), {
    refreshDeps: [windowKey], pollingInterval: REFRESH_INTERVAL, pollingWhenHidden: false, onError: () => {},
  });
  const { data: statusList } = useRequest(() => getAiShadowStatus(false), {
    pollingInterval: REFRESH_INTERVAL, pollingWhenHidden: false, onError: () => {},
  });

  const eventWindow = useCallback((): { startTime: number; endTime: number } => {
    if (drill) return { startTime: drill.startTime, endTime: drill.endTime };
    return currentWindow();
  }, [drill, currentWindow]);

  const { data: eventPage, loading: eventsLoading, error: eventsError, refresh: refreshEvents } = useRequest(
    () => {
      const win = eventWindow();
      return getAiShadowDetectEvents({
        ...win,
        page,
        size,
        ...(channel === 'all' ? {} : { surface: channel }),
        // 「仅记录」不是单个枚举值：基础流量侧写 allowed、准入侧写 monitored，
        // 只传一个会漏掉另一侧的放行事件，所以两侧一起传。
        // 处置列把「非 blocked」一律显示为「仅记录」，这个过滤条件必须与它同口径：
        // 事件表还有 alerted / masked 两种状态，漏掉它们会让明细里出现「过滤后看不到、
        // 但 KPI 算在里面」的行。
        ...(status === 'all' ? {} : { status: status === 'blocked' ? 'blocked' : 'monitored,allowed,alerted,masked' }),
        ...(risk === 'all' ? {} : { riskLevel: risk }),
        ...(reason === 'all' ? {} : { reason }),
        ...(domain ? { domain } : {}),
        ...(srcIp ? { srcIp } : {}),
      });
    },
    {
      refreshDeps: [windowKey, drill, channel, status, risk, reason, domain, srcIp, page, size],
      pollingInterval: REFRESH_INTERVAL,
      pollingWhenHidden: false,
      onError: () => {},
    },
  );

  // 一键自检：全复用已有只读接口，不写任何状态。从配置页第 4 步带 #selfcheck 进来时自动跑一次。
  const [diagRows, setDiagRows] = useState<DiagRow[] | null>(null);
  const [diagRunning, setDiagRunning] = useState(false);
  const [diagRequested] = useState<boolean>(() => window.location.hash === '#selfcheck');

  // 总开关关闭期间冻结写操作，与配置页「关闭时授权按钮不可点」同一条不变式。
  // 读不到状态时按不可写处理：接口失败期间不该把配置写进一个可能已停用的功能。
  const {
    data: switchState, error: switchError, refresh: refreshSwitchState,
  } = useRequest(() => getAiShadowEnabled(), {
    // 读取失败时按钮按不可写处理，必须能自愈：跟着页面 30 秒轮询一起重试，另给一个手动入口。
    pollingInterval: REFRESH_INTERVAL,
    pollingWhenHidden: false,
    onError: () => {},
  });
  // ahooks 轮询失败会保留上次成功的 data，只看 switchState 会让读取失败期间
  // 仍按「开启」放行写入；成功后 ahooks 会清 error，按钮随下一轮轮询自愈。
  const canEditWhitelist = !switchError && switchState === true;
  // 「读不到状态」与「明确关闭」是两回事：前者的提示不能说成总开关关了。
  const whitelistBlockedText = switchError ? t('aiShadow.switchReadFailed') : t('aiShadow.eventActionDisabled');

  // 白名单清单只用于判断某行是否已经加过，避免重复给按钮。
  const { data: authzData, refresh: refreshAuthz } = useRequest(() => getAiShadowAuthorizedDomains(), { onError: () => {} });
  const whitelisted = useMemo(() => {
    const items = authzData?.items;
    // items:[] 是 truthy，不能直接用 || 短路掉 domains（降级响应里 domains 才是有效清单）。
    const names = items && items.length > 0 ? items.map((item) => item.domain) : (authzData?.domains ?? []);
    return new Set(names.map((name) => normalizeAuthzInput(name)));
  }, [authzData]);

  // 白名单按域名放行，命中父域即覆盖子域：按钮不能只比精确串，
  // 否则 api.openai.com 在已放行 openai.com 时还会让人再点一次「加入」。
  const isWhitelisted = useCallback((domain: string): boolean => {
    let name = domain;
    while (name) {
      if (whitelisted.has(name)) return true;
      const dot = name.indexOf('.');
      if (dot < 0) break;
      name = name.slice(dot + 1);
    }
    return false;
  }, [whitelisted]);

  const gotoConfig = useCallback((hash = '') => {
    window.location.href = `/ai-shadow/config${hash}`;
  }, []);

  const addToWhitelist = useCallback((rawDomain: string) => {
    if (!canEditWhitelist) {
      message.warning(whitelistBlockedText);
      return;
    }
    // 事件里的域名来自抓包解析出的 SNI/Host，属于外部输入：先按白名单同一套规则归一并校验，
    // 否则带端口或 *. 前缀的串会写进白名单却不命中，看起来「已放行」实际仍在被记录。
    const domain = normalizeAuthzInput(rawDomain);
    if (!isPlausibleDomain(domain)) {
      message.error(t('aiShadow.authzInvalidDomain'));
      return;
    }
    Modal.confirm({
      title: t('aiShadow.eventWhitelistTitle'),
      content: t('aiShadow.eventWhitelistContent', { domain }),
      okText: t('aiShadow.eventWhitelistOk'),
      cancelText: t('misc.cancel'),
      onOk: async () => {
        try {
          // source='detected' 是授权接口为「从检测结果一键授权」预留的来源标记。
          await updateAiShadowAuthorizedDomains({ addDomains: [domain], source: 'detected' });
          message.success(t('aiShadow.eventWhitelistSuccess', { domain }));
          refreshAuthz();
          refreshEvents();
        } catch {
          message.error(t('aiShadow.actionFailed'));
        }
      },
    });
  }, [t, refreshAuthz, refreshEvents, canEditWhitelist, whitelistBlockedText]);

  const gotoAuthorize = useCallback((row: AiShadowDetectEvent) => {
    // 事件表迄今不记录路由归属（route_hint 全为 NULL）。退而给一条「命中依据」当线索，
    // 让用户知道是从哪条事件跳过来的；但它不是路由名，配置页只作提示、不进搜索框。
    const clue = (row.routeHint || '').trim()
      || (row.domain ? `host=${row.domain}` : '')
      || (row.sessionReason || row.reason ? `reason=${row.sessionReason || row.reason}` : '');
    window.location.href = `/ai-shadow/config?tab=route${clue ? `&hint=${encodeURIComponent(clue)}` : ''}`;
  }, []);

  const runSelfCheck = useCallback(async () => {
    setDiagRunning(true);
    const [en, md, dom, cfg, nics] = await Promise.all([
      probe(getAiShadowEnabled()),
      probe(getAiShadowDetectMode()),
      probe(getAiShadowAuthorizedDomains()),
      probe(getAiShadowCaptureConfig()),
      probe(getAiShadowCaptureNics()),
    ]);
    const readFailed = (name: string): DiagRow => ({
      state: 'bad', name, text: t('aiShadow.diagReadFailed'),
      actionText: t('aiShadow.diagGoConfig'), onAction: () => gotoConfig(),
    });
    const rows: DiagRow[] = [];
    rows.push(windowKey === 'today'
      ? {
        state: 'warn', name: t('aiShadow.diagRangeName'), text: t('aiShadow.diagRangeToday'),
        actionText: t('aiShadow.diagGoWeek'),
        onAction: () => { switchTo('week'); },
      }
      : { state: 'ok', name: t('aiShadow.diagRangeName'), text: t('aiShadow.diagRangeWide') });

    if (!en.ok) {
      rows.push(readFailed(t('aiShadow.diagSwitchName')));
    } else if (en.value === false) {
      rows.push({
        state: 'bad', name: t('aiShadow.diagSwitchName'), text: t('aiShadow.diagSwitchOff'),
        actionText: t('aiShadow.diagGoEnable'), onAction: () => gotoConfig(),
      });
    } else {
      rows.push({ state: 'ok', name: t('aiShadow.diagSwitchName'), text: t('aiShadow.diagSwitchOn') });
    }

    if (!md.ok) {
      rows.push(readFailed(t('aiShadow.diagModeName')));
    } else {
      rows.push({
        state: 'warn', name: t('aiShadow.diagModeName'),
        text: md.value === 'enforcement' ? t('aiShadow.diagModeEnforce') : t('aiShadow.diagModeMonitor'),
        actionText: t('aiShadow.diagGoConfig'), onAction: () => gotoConfig(),
      });
    }

    if (!dom.ok) {
      rows.push(readFailed(t('aiShadow.diagWhitelistName')));
    } else {
      const items = dom.value?.items;
      const count = items && items.length > 0 ? items.length : (dom.value?.domains?.length ?? 0);
      rows.push({
        state: 'warn', name: t('aiShadow.diagWhitelistName'), text: t('aiShadow.diagWhitelistHit', { count }),
        actionText: t('aiShadow.diagGoWhitelist'), onAction: () => gotoConfig('?tab=basic'),
      });
    }

    // 采集行只依据自己的两个接口；总开关接口失败不该把「采集器离线」这个根因盖掉。
    if (!nics.ok || !cfg.ok) {
      rows.push(readFailed(t('aiShadow.diagCaptureName')));
    } else if (!en.ok) {
      // 总开关读失败：采集是否被门控未知，不能报「正在采集」。
      rows.push({
        state: 'warn', name: t('aiShadow.diagCaptureName'), text: t('aiShadow.diagReadFailed'),
        actionText: t('aiShadow.diagGoCapture'), onAction: () => gotoConfig('?tab=capture'),
      });
    } else if (en.value === false) {
      rows.push({
        state: 'bad', name: t('aiShadow.diagCaptureName'), text: t('aiShadow.diagCaptureOff'),
        actionText: t('aiShadow.diagGoEnable'), onAction: () => gotoConfig(),
      });
    } else if (nics.value?.agentOnline === false) {
      rows.push({
        state: 'warn', name: t('aiShadow.diagCaptureName'), text: t('aiShadow.diagCaptureOffline'),
        actionText: t('aiShadow.diagGoCapture'), onAction: () => gotoConfig('?tab=capture'),
      });
    } else if (cfg.value?.interfaces != null && cfg.value.interfaces.length === 0) {
      // 显式 [] 才是「主动关采集 / 勾了又全取消」。
      rows.push({
        state: 'warn', name: t('aiShadow.diagCaptureName'), text: t('aiShadow.diagCaptureNoPort'),
        actionText: t('aiShadow.diagGoCapture'), onAction: () => gotoConfig('?tab=capture'),
      });
    } else if (cfg.value?.interfaces == null) {
      // null = 从未在此页面配置，采集器沿用设备默认口，不算缺配置；
      // 误报「没配采集口」会把用户导向一次不必要的下发，反而改掉默认配置。
      rows.push({
        state: 'ok', name: t('aiShadow.diagCaptureName'), text: t('aiShadow.diagCaptureLocalDefault'),
        actionText: t('aiShadow.diagGoCapture'), onAction: () => gotoConfig('?tab=capture'),
      });
    } else {
      rows.push({
        state: 'ok', name: t('aiShadow.diagCaptureName'),
        text: t('aiShadow.diagCaptureOn', { count: cfg.value?.interfaces?.length ?? 0 }),
        actionText: t('aiShadow.diagGoCapture'), onAction: () => gotoConfig('?tab=capture'),
      });
    }
    setDiagRows(rows);
    setDiagRunning(false);
  }, [t, windowKey, gotoConfig, switchTo]);

  // StrictMode 下挂载 effect 会跑两次，用 ref 守住「自动只跑一次」。
  const diagAutoRan = useRef(false);
  useEffect(() => {
    if (diagRequested && !diagAutoRan.current) {
      diagAutoRan.current = true;
      runSelfCheck();
    }
  }, [diagRequested, runSelfCheck]);

  const total = (stats?.shadowTotal ?? 0) + (stats?.admissionTotal ?? 0);
  const blockedTotal = stats?.blockedTotal ?? 0;
  const recordedTotal = Math.max(0, total - blockedTotal);
  const blockRate = total > 0 ? (blockedTotal / total) * 100 : 0;
  // 统计接口失败时不能把 0 当成「没有影子AI」：那会把故障读成安全。
  const statsBroken = !!statsError || !!stats?.degraded;

  // 流量卡：纳管路由请求只算落在已纳管 AI 路由上的部分，剩下的都算非纳管。
  // 逐路由取值必须用 routeRequestCounts——requestCount 是跨路由合计，
  // 拿它加到每条路由上会让合计悄悄超过总量。
  const traffic = useMemo(() => {
    const consumers = usage?.consumers || [];
    const managed = new Set((statusList || []).map((s) => s.resourceName || '').filter(Boolean));
    let managedRequests = 0;
    let unauthorized = 0;
    consumers.forEach((item) => {
      const per = item.routeRequestCounts || {};
      Object.keys(per).forEach((routeName) => {
        if (managed.has(routeName)) managedRequests += per[routeName] || 0;
      });
      (item.unauthorizedRoutes || []).forEach((routeName) => {
        unauthorized += per[routeName] || 0;
      });
    });
    return {
      managedRequests,
      unauthorized,
      bypass: Math.max(0, (usage?.requestCount ?? 0) - managedRequests),
      routeCount: (statusList || []).length,
      denyRoutes: (statusList || []).filter((s) => s.defaultDeny).length,
      // 取数失败也算「不可用」：接口挂了与「真的没有流量」都是 0，只有这句话能把它们分开。
      available: !usageError && (usage ? usage.usageAvailable !== false : true),
    };
  }, [usage, statusList, usageError]);

  // 桶宽只由粒度决定：小时桶 1h，天桶 1d。趋势表与柱子下钻共用同一个值。
  const trendGranularity = shadowTrend?.granularity || routeTrend?.granularity || 'day';
  const step = trendGranularity === 'hour' ? 3600000 : 86400000;

  // 两组趋势合并成一张长表：G2 的堆叠柱需要「每个桶 × 每个系列」的行。
  // 必须按窗口补齐空桶：后端只回「有事件的桶」，直接摊平会让 09:00 和 11:00 看起来相邻，
  // 中间那两个什么都没有的小时被静默抹掉，时间轴就成了假的。
  //
  // 轮询每 30 秒回来一份新对象，即使数值一模一样——数组引用变了，图表就重画。
  // 所以先用「数值签名」比对：签名一致直接返回上一份数组，图表拿到的 props 不变。
  const trendCache = useRef<{ key: string; rows: TrendRow[] }>({ key: '', rows: [] });
  const trendRows = useMemo<TrendRow[]>(() => {
    const base = shadowTrend || routeTrend;
    if (!base || !base.start || !base.end) {
      if (trendCache.current.key === '') return trendCache.current.rows;
      trendCache.current = { key: '', rows: [] };
      return trendCache.current.rows;
    }
    const shadowName = t('aiShadow.channelShadow');
    const routeName = t('aiShadow.channelRoute');
    const sig = [
      base.start,
      base.end,
      base.granularity,
      shadowName,
      (shadowTrend?.list || []).map((p) => `${p.timestamp}:${p.count}`).join(','),
      (routeTrend?.list || []).map((p) => `${p.timestamp}:${p.count}`).join(','),
    ].join('|');
    if (trendCache.current.key === sig) return trendCache.current.rows;

    const toMap = (resp?: AiShadowTrendResponse) => {
      const map = new Map<string, number>();
      // 后端按「时间桶 × status」返回多行，同一 timestamp 会有 blocked/allowed/monitored 各一行。
      // 这里必须累加：用 set 覆盖会让该桶只剩排序最后那个 status 的计数，
      // 影子 AI 通道的旁路事件（含几百条 blocked）会整批从趋势里消失，而 KPI 仍显示全量。
      (resp?.list || []).forEach((p) => {
        const key = `${p.timestamp}`;
        map.set(key, (map.get(key) ?? 0) + p.count);
      });
      return map;
    };
    const shadowHit = toMap(shadowTrend);
    const routeHit = toMap(routeTrend);
    // channelKey 才是判断依据：拿 legend 上的中文文案做等值比较，一换语言就失灵。
    const rows: TrendRow[] = [];
    for (let ts = base.start; ts <= base.end; ts += step) {
      rows.push({ timestamp: ts, channelKey: 'shadow', channel: shadowName, count: shadowHit.get(`${ts}`) ?? 0 });
      rows.push({ timestamp: ts, channelKey: 'route', channel: routeName, count: routeHit.get(`${ts}`) ?? 0 });
    }
    trendCache.current = { key: sig, rows };
    return rows;
  }, [shadowTrend, routeTrend, t, step]);

  const handleColumnClick = useCallback((_chart: any, ev: any) => {
    if (ev?.type !== 'element:click') return;
    const datum = ev?.data?.data?._origin ?? ev?.data?.data;
    const ts = Number(datum?.timestamp);
    if (!datum || isNaN(ts)) return;
    const picked = datum.channelKey === 'route' ? 'route' : 'shadow';
    setDrill({ startTime: ts, endTime: ts + step - 1 });
    setChannel(picked);
    setPage(0);
  }, [step]);

  // config 必须是稳定引用，否则上面那个 memo 组件白 memo。
  const columnConfig = useMemo(() => ({
    data: trendRows,
    isStack: true,
    xField: 'timestamp',
    yField: 'count',
    seriesField: 'channel',
    height: 240,
    columnWidthRatio: 0.45,
    xAxis: { label: { formatter: (v: string) => formatAxisTime(Number(v), trendGranularity) } },
    yAxis: { min: 0, title: { text: t('aiShadow.trendCount') } },
    legend: { position: 'top' as const },
    color: ['#1677ff', '#fa8c16'],
    tooltip: {
      title: (titleVal: string) => moment(Number(titleVal)).format('YYYY-MM-DD HH:mm'),
    },
    onEvent: handleColumnClick,
  }), [trendRows, trendGranularity, handleColumnClick, t]);

  // 分面卡右上角的「查看」把筛选器切到该通道，再滚到明细——比在筛选器里自己找更快。
  const focusChannel = useCallback((key: ChannelKey) => {
    setChannel(key);
    setPage(0);
    const el = document.getElementById('ai-shadow-events-list');
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, []);

  const kpiCell = (
    title: string,
    value: React.ReactNode,
    foot: React.ReactNode,
    extra?: { accent?: string; raw?: boolean },
  ) => (
    <Card loading={statsLoading && !stats} bodyStyle={{ padding: '14px 18px' }} style={{ height: '100%' }}>
      <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 6 }}>{title}</div>
      <div style={{ fontSize: 26, fontWeight: 600, lineHeight: 1.2, color: extra?.accent }}>
        {extra?.raw ? value : Number(value).toLocaleString()}
      </div>
      <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: 6, minHeight: 20 }}>{foot}</div>
    </Card>
  );

  const facetCell = (title: string, value: number, danger?: boolean) => (
    <div style={{ flex: '1 1 0', minWidth: 0 }}>
      <div style={{ fontSize: 12, color: '#8c8c8c' }}>{title}</div>
      <div style={{ fontSize: 22, fontWeight: 600, marginTop: 4, color: danger && value > 0 ? '#cf1322' : undefined }}>
        {Number(value || 0).toLocaleString()}
      </div>
    </div>
  );

  // 消费者用量 KPI：请求/Token/消费者是「累计」口径，被拦/被监控是「所选范围」口径，
  // 两种口径必须标出来，否则同一行里的数字会被读成同一时间窗（usageScopeHint 有完整解释）。
  // Prometheus 保留窗口：超过该小时数的区间里，用量侧只剩最近这段（usageRetentionWarn 有完整解释）。
  const PROM_RETENTION_HOURS = 6;
  const usageKpiCell = (title: string, scope: string, value: React.ReactNode, danger?: boolean) => (
    <div style={{ flex: '1 1 0', minWidth: 0 }}>
      <div style={{ fontSize: 12, color: '#8c8c8c' }}>
        {title}
        <Tag style={{ marginInlineStart: 6, marginInlineEnd: 0, fontSize: 11, lineHeight: '16px' }}>{scope}</Tag>
      </div>
      <div style={{ fontSize: 22, fontWeight: 600, marginTop: 4, color: danger ? '#cf1322' : undefined }}>{value}</div>
    </div>
  );

  const usageColumns: ColumnsType<AiConsumerUsage> = [
    {
      title: t('aiShadow.usageColConsumer'),
      dataIndex: 'consumer',
      render: (value: string, row) => (row.unauthenticated ? (
        <Space size={4}>
          <span>{t('aiShadow.usageNoIdentity')}</span>
          <Tooltip title={t('aiShadow.usageUnauthenticatedHint')}>
            <Tag style={{ marginInlineEnd: 0 }}>{t('aiShadow.usageUnauthenticated')}</Tag>
          </Tooltip>
        </Space>
      ) : value),
    },
    {
      title: t('aiShadow.usageColRequests'),
      dataIndex: 'requestCount',
      align: 'right',
      render: (value: number) => Number(value || 0).toLocaleString(),
    },
    {
      title: t('aiShadow.usageColTokens'),
      key: 'tokens',
      align: 'right',
      render: (_: unknown, row) => (
        <Tooltip title={`${t('aiShadow.inputTokens')} ${row.inputTokens || 0} · ${t('aiShadow.outputTokens')} ${row.outputTokens || 0}`}>
          <span>{Number(row.inputTokens || 0).toLocaleString()} / {Number(row.outputTokens || 0).toLocaleString()}</span>
        </Tooltip>
      ),
    },
    {
      title: t('aiShadow.usageColModels'),
      dataIndex: 'models',
      render: (models: string[]) => (models || []).map((m) => <Tag key={m}>{m}</Tag>),
    },
    {
      title: t('aiShadow.usageColRoutes'),
      key: 'routes',
      render: (_: unknown, row) => {
        const per = row.routeRequestCounts || {};
        const names = Object.keys(per);
        if (names.length === 0) return '-';
        return names.map((name) => {
          const overreach = (row.unauthorizedRoutes || []).includes(name);
          return (
            <Tooltip key={name} title={overreach ? t('aiShadow.usageUnauthorized') : undefined}>
              <Tag color={overreach ? 'red' : undefined}>{name} ({per[name]})</Tag>
            </Tooltip>
          );
        });
      },
    },
    {
      title: t('aiShadow.usageColDisposition'),
      key: 'disposition',
      align: 'right',
      render: (_: unknown, row) => (
        <span>
          <Tooltip title={t('aiShadow.usageBlockedHint')}>
            <span style={{ color: (row.blockedCount || 0) > 0 ? '#cf1322' : undefined }}>{Number(row.blockedCount || 0).toLocaleString()}</span>
          </Tooltip>
          {' / '}
          <Tooltip title={t('aiShadow.usageMonitoredHint')}>
            <span>{Number(row.monitoredCount || 0).toLocaleString()}</span>
          </Tooltip>
        </span>
      ),
    },
    {
      title: t('aiShadow.usageColAuth'),
      key: 'auth',
      render: (_: unknown, row) => {
        if (row.unauthenticated) return <Tag style={{ marginInlineEnd: 0 }}>{t('aiShadow.usageUnauthenticated')}</Tag>;
        return row.authorized
          ? <Tag color="green" style={{ marginInlineEnd: 0 }}>{t('aiShadow.usageAuthorized')}</Tag>
          : <Tag color="red" style={{ marginInlineEnd: 0 }}>{t('aiShadow.usageUnauthorized')}</Tag>;
      },
    },
  ];

  const columns: ColumnsType<AiShadowDetectEvent> = [
    {
      title: t('aiShadow.eventColTime'),
      dataIndex: 'eventTime',
      key: 'eventTime',
      width: 162,
      // 折叠后用会话末次时间作主时间；跨时段的会话在下方补一行首末区间。
      render: (value: AiShadowDetectEvent['eventTime'], row: AiShadowDetectEvent) => (
        <span>
          {row.lastSeen ? moment(row.lastSeen).format('YYYY-MM-DD HH:mm:ss') : formatEventTime(value)}
          {row.firstSeen && row.lastSeen && row.lastSeen > row.firstSeen && (
            <div style={{ fontSize: 11, color: '#8c8c8c' }}>
              {moment(row.firstSeen).format('HH:mm:ss')} ~ {moment(row.lastSeen).format('HH:mm:ss')}
            </div>
          )}
        </span>
      ),
    },
    {
      // 一条告警代表一个会话：命中数是被折叠掉的流水条数，流水表本身没删。
      title: t('aiShadow.eventColHits'),
      key: 'hitCount',
      width: 92,
      render: (_: unknown, row: AiShadowDetectEvent) => (
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>{Number(row.hitCount ?? 1).toLocaleString()}</span>
      ),
    },
    {
      title: t('aiShadow.colChannel'),
      dataIndex: 'surface',
      key: 'surface',
      width: 104,
      render: (value?: string) => (statusChannelLabel(value) === 'route'
        ? <Tag color="orange">{t('aiShadow.channelRoute')}</Tag>
        : <Tag color="blue">{t('aiShadow.channelShadow')}</Tag>),
    },
    {
      title: t('aiShadow.eventColStatus'),
      dataIndex: 'status',
      key: 'status',
      width: 92,
      render: (value: string | undefined, row: AiShadowDetectEvent) => ((row.sessionStatus ?? value) === 'blocked'
        ? <Tag color="red">{t('aiShadow.eventStatusBlocked')}</Tag>
        : <Tag>{t('aiShadow.statusRecorded')}</Tag>),
    },
    {
      title: t('aiShadow.colRisk'),
      dataIndex: 'riskLevel',
      key: 'riskLevel',
      width: 84,
      render: (value: string | undefined, row: AiShadowDetectEvent) => {
        const level = row.sessionRiskLevel ?? value;
        if (!level) return <span style={{ color: '#bfbfbf' }}>-</span>;
        if (level === 'critical') return <Tag color="red">{t('aiShadow.riskCritical')}</Tag>;
        if (level === 'high') return <Tag color="orange">{t('aiShadow.riskHigh')}</Tag>;
        if (level === 'medium') return <Tag color="gold">{t('aiShadow.riskMedium')}</Tag>;
        return <Tag>{t('aiShadow.riskLow')}</Tag>;
      },
    },
    {
      title: t('aiShadow.eventColReason'),
      dataIndex: 'reason',
      key: 'reason',
      width: 180,
      render: (value: string | undefined, row: AiShadowDetectEvent) => (
        <span>{eventReasonLabel(t, row.sessionReason ?? value)}</span>
      ),
    },
    {
      title: t('aiShadow.eventColDomain'),
      dataIndex: 'domain',
      key: 'domain',
      render: (value?: string) => <span style={{ fontFamily: 'monospace' }}>{value || '-'}</span>,
    },
    {
      title: t('aiShadow.eventColSrcIp'),
      dataIndex: 'srcIp',
      key: 'srcIp',
      width: 136,
      render: (value?: string) => <span style={{ fontFamily: 'monospace' }}>{value || '-'}</span>,
    },
    {
      title: t('aiShadow.eventColActions'),
      key: 'actions',
      width: 190,
      render: (_: unknown, row: AiShadowDetectEvent) => {
        const domain = normalizeAuthzInput(row.domain || '');
        const isRoute = statusChannelLabel(row.surface) === 'route';
        return (
          <Space size={4} wrap>
            {domain ? (
              isWhitelisted(domain)
                ? <Tag color="green">{t('aiShadow.eventAlreadyWhitelisted')}</Tag>
                : (
                  <Tooltip title={canEditWhitelist || switchState === undefined ? '' : whitelistBlockedText}>
                    {/* 禁用按钮本身不触发鼠标事件，Tooltip 要挂在外层 span 上才显示得出来。 */}
                    <span>
                      <Button size="small" disabled={!canEditWhitelist} onClick={() => addToWhitelist(domain)}>
                        {t('aiShadow.eventAddWhitelist')}
                      </Button>
                    </span>
                  </Tooltip>
                )
            ) : null}
            {isRoute ? (
              <Button type="link" size="small" style={{ padding: 0 }} onClick={() => gotoAuthorize(row)}>
                {t('aiShadow.eventAuthorizeCaller')}
              </Button>
            ) : null}
            {!domain && !isRoute ? <span style={{ color: '#bfbfbf' }}>-</span> : null}
          </Space>
        );
      },
    },
  ];

  const items = eventPage?.items || [];
  const eventTotal = eventPage?.total ?? 0;
  // 只有首屏拿到结果（或明确失败）后才按「0 条」显示自查卡：
  // useRequest 的 loading 在 30 秒轮询期间也会变 true，拿它判显隐会让卡片每轮闪一下。
  const showDiagCard = ((!!eventPage || !!eventsError) && eventTotal === 0) || diagRequested || !!diagRows;

  return (
    <div style={{ padding: '0 0 24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 10, marginBottom: 14 }}>
        <span style={{ fontSize: 16, fontWeight: 600 }}>{t('aiShadow.eventsPageTitle')}</span>
        {/* 状态胶囊：说明「为什么已拦截是 0」不必写一段话——现在是仅记录模式。 */}
        <Tooltip title={t('aiShadow.gotoConfigHint')}>
          <Tag
            color="green"
            style={{ cursor: 'pointer', marginInlineEnd: 0 }}
            onClick={() => { window.location.href = '/ai-shadow/config'; }}
          >
            ● {t('aiShadow.chipMonitoring')}
          </Tag>
        </Tooltip>
        <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <Segmented
            value={range}
            onChange={(value) => {
              const key = value as RangeKey;
              if (key !== 'custom') {
                switchTo(key);
                return;
              }
              setRange('custom');
              // 自定义：确定日期前不动视图；此前已确定过日期区间就继续按该区间展示
              if (customRange) {
                setAppliedWindow({ key: 'custom', startTime: customRange[0], endTime: customRange[1] });
                setPage(0);
                setDrill(null);
              }
            }}
            options={[
              { value: 'today', label: t('aiShadow.trendRangeToday') },
              { value: 'week', label: t('aiShadow.trendRangeWeek') },
              { value: 'month', label: t('aiShadow.trendRangeMonth') },
              { value: 'custom', label: t('aiShadow.trendRangeCustom') },
            ]}
          />
          {range === 'custom' ? (
            <RangePicker
              allowClear={false}
              style={{ width: 250 }}
              value={customRange ? ([moment(customRange[0]), moment(customRange[1])] as any) : null}
              disabledDate={(d: any) => !!d && startOfDay(d.valueOf()) > startOfDay(Date.now())}
              onChange={(v: any) => {
                if (!v || !v[0] || !v[1]) return;
                const start = startOfDay(v[0].valueOf());
                const end = endOfDay(v[1].valueOf());
                // 超过后端窗口上限时后端会静默截断起点，界面口径就与实际不符
                if (end - start > MAX_CUSTOM_DAYS * 86400000) {
                  message.warning(t('aiShadow.timeRangeTooLong', { days: MAX_CUSTOM_DAYS }));
                  return;
                }
                setCustomRange([start, end]);
                setAppliedWindow({ key: 'custom', startTime: start, endTime: end });
                setPage(0);
                setDrill(null);
              }}
            />
          ) : null}
          <span style={{ fontSize: 12, color: '#8c8c8c' }}>{rangeSummary}</span>
          <Button
            icon={<ReloadOutlined />}
            onClick={() => {
              // 与日期过滤同范围：只刷 refreshDeps 含 windowKey 的 5 个请求。
              // 通道状态 / 总开关 / 白名单清单不读日期，不跟着这个按钮重取。
              refreshStats();
              refreshShadowTrend();
              refreshRouteTrend();
              refreshUsage();
              refreshEvents();
            }}
            loading={eventsLoading}
          >
            {t('aiShadow.refresh')}
          </Button>
        </div>
      </div>

      {statsBroken ? (
        <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={t('aiShadow.trendDegraded')} />
      ) : null}

      {switchError ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={t('aiShadow.switchReadFailed')}
          description={(
            <Button size="small" onClick={refreshSwitchState}>{t('aiShadow.switchRetry')}</Button>
          )}
        />
      ) : null}

      {showDiagCard ? (
        <Card
          size="small"
          style={{ marginBottom: 12 }}
          title={t('aiShadow.diagTitle')}
          extra={(
            <Button type="primary" size="small" loading={diagRunning} onClick={runSelfCheck}>
              {diagRunning
                ? t('aiShadow.diagRunning')
                : diagRows ? t('aiShadow.diagRerun') : t('aiShadow.diagRun')}
            </Button>
          )}
        >
          {!diagRows ? (
            <ul style={{ paddingLeft: 18, margin: 0, color: '#595959', fontSize: 12 }}>
              <li style={{ margin: '4px 0' }}>{t('aiShadow.diagStatic1')}</li>
              <li style={{ margin: '4px 0' }}>{t('aiShadow.diagStatic2')}</li>
              <li style={{ margin: '4px 0' }}>{t('aiShadow.diagStatic3')}</li>
              <li style={{ margin: '4px 0' }}>{t('aiShadow.diagStatic4')}</li>
              <li style={{ margin: '4px 0' }}>{t('aiShadow.diagStatic5')}</li>
            </ul>
          ) : (
            <div>
              <div style={{ padding: '7px 10px', marginBottom: 8, background: '#e6f4ff', border: '1px solid #bae0ff', borderRadius: 4, fontSize: 12 }}>
                <b>{t('aiShadow.diagSummary', {
                  ok: diagRows.filter((r) => r.state === 'ok').length,
                  warn: diagRows.filter((r) => r.state === 'warn').length,
                  bad: diagRows.filter((r) => r.state === 'bad').length,
                })}</b>
              </div>
              {diagRows.map((row) => (
                <div
                  key={row.name}
                  style={{ display: 'flex', alignItems: 'flex-start', gap: 9, padding: '7px 0', borderBottom: '1px dashed #f0f0f0', fontSize: 12 }}
                >
                  <Tag color={row.state === 'ok' ? 'green' : row.state === 'warn' ? 'orange' : 'red'}>
                    {row.state === 'ok' ? t('aiShadow.diagOk') : row.state === 'warn' ? t('aiShadow.diagWarn') : t('aiShadow.diagBad')}
                  </Tag>
                  <span style={{ flex: 1 }}><b>{row.name}</b>：{row.text}</span>
                  {row.actionText && row.onAction ? (
                    <Button size="small" onClick={row.onAction}>{row.actionText}</Button>
                  ) : null}
                </div>
              ))}
            </div>
          )}
          <div style={{ marginTop: 10, fontSize: 12, color: '#8c8c8c' }}>{t('aiShadow.diagFooter')}</div>
        </Card>
      ) : null}

      <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
        <Col span={6}>
          {kpiCell(
            t('aiShadow.kpiShadowEvents'),
            total,
            <Space size={6} style={{ fontSize: 12 }}>
              <Tag color="blue" style={{ cursor: 'pointer', marginInlineEnd: 0 }} onClick={() => focusChannel('shadow')}>
                {t('aiShadow.channelShadow')} {stats?.shadowTotal ?? 0}
              </Tag>
              <span style={{ color: '#bfbfbf' }}>+</span>
              <Tag color="orange" style={{ cursor: 'pointer', marginInlineEnd: 0 }} onClick={() => focusChannel('route')}>
                {t('aiShadow.channelRoute')} {stats?.admissionTotal ?? 0}
              </Tag>
            </Space>,
          )}
        </Col>
        <Col span={6}>
          {kpiCell(
            t('aiShadow.kpiBlocked'),
            blockedTotal,
            blockedTotal === 0 ? t('aiShadow.kpiNoneBlocked') : t('aiShadow.kpiBlockedFoot'),
            { accent: blockedTotal > 0 ? '#cf1322' : undefined },
          )}
        </Col>
        <Col span={6}>
          {kpiCell(t('aiShadow.kpiRecorded'), recordedTotal, t('aiShadow.kpiRecordedFoot'), { accent: '#d46b08' })}
        </Col>
        <Col span={6}>
          {kpiCell(
            t('aiShadow.kpiBlockRate'),
            `${blockRate.toFixed(1)}%`,
            total === 0
              ? t('aiShadow.kpiRateNoEvent')
              : blockRate === 0
                ? t('aiShadow.kpiRateZero')
                : t('aiShadow.kpiRateLow'),
            { raw: true },
          )}
        </Col>
      </Row>

      <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
        <Col span={12}>
          <Card
            size="small"
            loading={statsLoading && !stats}
            style={{ height: '100%' }}
            title={(
              <Space size={8}>
                <span>{t('aiShadow.facetDiscovery')}</span>
                <span style={{ fontSize: 12, fontWeight: 400, color: '#8c8c8c' }}>
                  {t('aiShadow.facetCount', { count: stats?.shadowTotal ?? 0 })}
                </span>
              </Space>
            )}
            extra={(
              <Button type="link" size="small" onClick={() => focusChannel('shadow')}>
                {t('aiShadow.facetView')}
              </Button>
            )}
          >
            <div style={{ display: 'flex', gap: 12 }}>
              {facetCell(t('aiShadow.facetDomains'), stats?.domainCount ?? 0)}
              {facetCell(t('aiShadow.facetCritical'), stats?.critical ?? 0, true)}
              {facetCell(t('aiShadow.facetHigh'), stats?.high ?? 0, true)}
            </div>
          </Card>
        </Col>
        <Col span={12}>
          <Card
            size="small"
            loading={statsLoading && !stats}
            style={{ height: '100%' }}
            title={(
              <Space size={8}>
                <span>{t('aiShadow.facetAdmission')}</span>
                <span style={{ fontSize: 12, fontWeight: 400, color: '#8c8c8c' }}>
                  {t('aiShadow.facetCount', { count: stats?.admissionTotal ?? 0 })}
                </span>
              </Space>
            )}
            extra={(
              <Button type="link" size="small" onClick={() => focusChannel('route')}>
                {t('aiShadow.facetView')}
              </Button>
            )}
          >
            <div style={{ display: 'flex', gap: 12 }}>
              {facetCell(t('aiShadow.facetNoKey'), stats?.noKeyCount ?? 0)}
              {facetCell(t('aiShadow.facetUnknownKey'), stats?.unknownKeyCount ?? 0)}
              {facetCell(t('aiShadow.facetNotInAllow'), stats?.notInAllowCount ?? 0)}
            </div>
          </Card>
        </Col>
      </Row>

      <Card
        size="small"
        style={{ marginBottom: 12 }}
        title={(
          <Space size={8}>
            <span>{t('aiShadow.trafficTitle')}</span>
            {/* 数据源只有 6 小时：不标出来，「近 30 天」就会被读成全窗口统计。 */}
            <Tooltip title={t('aiShadow.trafficScopeHint')}>
              <Tag style={{ marginInlineEnd: 0 }}>{t('aiShadow.trafficScopeTag')}</Tag>
            </Tooltip>
          </Space>
        )}
        extra={<span style={{ fontSize: 12, color: '#8c8c8c' }}>{rangeSummary}</span>}
      >
        <div style={{ display: 'flex', gap: 12 }}>
          {facetCell(t('aiShadow.trafficRoutes'), traffic.available ? traffic.managedRequests : 0)}
          {facetCell(t('aiShadow.trafficUnauthorized'), traffic.available ? traffic.unauthorized : 0, true)}
          {facetCell(t('aiShadow.trafficBypass'), traffic.available ? traffic.bypass : 0, true)}
          <div style={{ flex: '1 1 0', minWidth: 0 }}>
            <div style={{ fontSize: 12, color: '#8c8c8c' }}>{t('aiShadow.trafficRouteCount')}</div>
            <div style={{ fontSize: 22, fontWeight: 600, marginTop: 4 }}>
              {traffic.routeCount}
              {traffic.denyRoutes > 0 ? (
                <span style={{ fontSize: 12, fontWeight: 400, color: '#cf1322', marginLeft: 8 }}>
                  {t('aiShadow.trafficDeny', { count: traffic.denyRoutes })}
                </span>
              ) : null}
            </div>
          </div>
        </div>
        {!traffic.available ? (
          <Alert type="warning" showIcon style={{ marginTop: 10 }} message={t('aiShadow.trafficUnavailable')} />
        ) : null}
      </Card>

      <Card
        size="small"
        style={{ marginBottom: 12 }}
        title={(
          <Space size={8}>
            <span>{t('aiShadow.usageCardTitle')}</span>
            <Tooltip title={t('aiShadow.usageScopeHint')}>
              <Tag style={{ marginInlineEnd: 0 }}>{t('aiShadow.usageScopeTag')}</Tag>
            </Tooltip>
          </Space>
        )}
      >
        {!traffic.available ? (
          <Alert type="warning" showIcon style={{ marginBottom: 10 }} message={t('aiShadow.usageUnavailable')} />
        ) : null}
        {usage && usage.eventWindowHours > PROM_RETENTION_HOURS ? (
          <Alert type="warning" showIcon style={{ marginBottom: 10 }} message={t('aiShadow.usageRetentionWarn', { hours: PROM_RETENTION_HOURS })} />
        ) : null}
        <div style={{ display: 'flex', gap: 12, marginBottom: 12 }}>
          {usageKpiCell(
            t('aiShadow.usageKpiRequests'),
            t('aiShadow.usageScopeCumulative'),
            traffic.available ? Number(usage?.requestCount || 0).toLocaleString() : '-',
          )}
          {usageKpiCell(
            t('aiShadow.usageKpiTokens'),
            t('aiShadow.usageScopeCumulative'),
            traffic.available ? Number(usage?.totalTokens || 0).toLocaleString() : '-',
          )}
          {usageKpiCell(
            t('aiShadow.usageKpiConsumers'),
            t('aiShadow.usageScopeCumulative'),
            traffic.available ? `${usage?.consumerCount ?? 0} / ${usage?.authorizedCount ?? 0}` : '- / -',
          )}
          {usageKpiCell(
            t('aiShadow.usageKpiDisposition'),
            t('aiShadow.usageScopeWindow'),
            traffic.available ? `${usage?.blockedCount ?? 0} / ${usage?.monitoredCount ?? 0}` : '- / -',
            traffic.available && (usage?.blockedCount ?? 0) > 0,
          )}
        </div>
        <Table<AiConsumerUsage>
          dataSource={usage?.consumers || []}
          columns={usageColumns}
          rowKey={(record) => record.consumer}
          size="small"
          pagination={false}
          locale={{ emptyText: t('aiShadow.usageNoData') }}
        />
      </Card>

      <Card
        size="small"
        style={{ marginBottom: 12 }}
        title={(
          <Space size={8}>
            <span>{t('aiShadow.trendTitle')}</span>
            <span style={{ fontSize: 12, fontWeight: 400, color: '#8c8c8c' }}>{t('aiShadow.trendByChannel')}</span>
            {drill ? (
              <Tag
                color="blue"
                closable
                onClose={() => { setDrill(null); setPage(0); }}
              >
                {moment(drill.startTime).format('MM-DD HH:mm')}
              </Tag>
            ) : null}
          </Space>
        )}
      >
        {trendBroken ? (
          <Alert type="warning" showIcon style={{ marginBottom: 10 }} message={t('aiShadow.trendChannelDegraded')} />
        ) : null}
        {trendRows.length > 0
          ? <TrendChart config={columnConfig} />
          : <Empty description={t('aiShadow.trendNoData')} style={{ padding: 48 }} />}
      </Card>

      <Card
        size="small"
        title={t('aiShadow.eventsListTitle', { total: eventTotal })}
        id="ai-shadow-events-list"
      >
        <Space style={{ marginBottom: 12 }} wrap size={8}>
          <Select
            value={channel}
            style={{ width: 132 }}
            onChange={(value) => { setChannel(value as ChannelKey); setPage(0); }}
            options={[
              { value: 'all', label: `${t('aiShadow.filterChannel')}：${t('aiShadow.filterAll')}` },
              { value: 'shadow', label: t('aiShadow.channelShadow') },
              { value: 'route', label: t('aiShadow.channelRoute') },
            ]}
          />
          <Select
            value={status}
            style={{ width: 132 }}
            onChange={(value) => { setStatus(value as StatusKey); setPage(0); }}
            options={[
              { value: 'all', label: `${t('aiShadow.eventColStatus')}：${t('aiShadow.filterAll')}` },
              { value: 'blocked', label: t('aiShadow.eventStatusBlocked') },
              { value: 'recorded', label: t('aiShadow.statusRecorded') },
            ]}
          />
          <Select
            value={risk}
            style={{ width: 118 }}
            onChange={(value) => { setRisk(value as RiskKey); setPage(0); }}
            options={[
              { value: 'all', label: `${t('aiShadow.colRisk')}：${t('aiShadow.filterAll')}` },
              { value: 'critical', label: t('aiShadow.riskCritical') },
              { value: 'high', label: t('aiShadow.riskHigh') },
              { value: 'medium', label: t('aiShadow.riskMedium') },
              { value: 'low', label: t('aiShadow.riskLow') },
            ]}
          />
          <Select
            value={reason}
            style={{ width: 148 }}
            onChange={(value) => { setReason(value as ReasonKey); setPage(0); }}
            options={[
              { value: 'all', label: `${t('aiShadow.eventColReason')}：${t('aiShadow.filterAll')}` },
              { value: 'no_key', label: t('aiShadow.reasonNoKey') },
              { value: 'unknown_key', label: t('aiShadow.reasonUnknownKey') },
              { value: 'not_in_allow', label: t('aiShadow.reasonNotInAllow') },
              { value: 'off_channel', label: t('aiShadow.reasonOffChannel') },
              { value: 'ai_domain_match', label: t('aiShadow.reasonAiDomainMatch') },
              { value: 'policy_violation', label: t('aiShadow.reasonPolicyViolation') },
              { value: 'policy_blocked', label: t('aiShadow.reasonPolicyBlocked') },
            ]}
          />
          <Input.Search
            allowClear
            style={{ width: 200 }}
            placeholder={t('aiShadow.eventsDomainPlaceholder')}
            value={domainInput}
            onChange={(e) => setDomainInput(e.target.value)}
            onSearch={(value) => { setDomain(value.trim()); setPage(0); }}
          />
          <Input.Search
            allowClear
            style={{ width: 176 }}
            placeholder={t('aiShadow.srcIpPlaceholder')}
            value={srcIpInput}
            onChange={(e) => setSrcIpInput(e.target.value)}
            onSearch={(value) => { setSrcIp(value.trim()); setPage(0); }}
          />
        </Space>

        {eventsError && (
          <Alert type="error" showIcon style={{ marginBottom: 12 }} message={t('aiShadow.eventsFailed')} />
        )}

        {/* 明细表只有首次加载才盖遮罩：30 秒轮询每次都盖一下会不停闪，
            而且盖着的时候没法点开一行看详情。手动刷新的反馈在右上角按钮上。 */}
        <Table<AiShadowDetectEvent>
          dataSource={items}
          columns={columns}
          rowKey={(record) => String(record.id)}
          size="small"
          loading={eventsLoading && !eventPage}
          expandable={{
            expandedRowRender: (record) => renderEventDetail(t, record.detail),
            rowExpandable: (record) => !!record.detail,
          }}
          pagination={{
            current: page + 1,
            pageSize: size,
            total: eventTotal,
            showSizeChanger: true,
            pageSizeOptions: ['20', '50', '100'],
            onChange: (nextPage: number, nextSize: number) => { setPage(nextPage - 1); setSize(nextSize); },
            showTotal: (value: number) => t('aiShadow.eventTotalCount', { total: value }),
          }}
          locale={{ emptyText: t('aiShadow.eventsEmptyRange') }}
        />
        {items.length > 0 ? (
          <div style={{ marginTop: 10, fontSize: 12, color: '#8c8c8c' }}>
            {t('aiShadow.eventWhitelistNote')}
          </div>
        ) : null}
      </Card>
    </div>
  );
};

export default AiShadowEventsPage;
