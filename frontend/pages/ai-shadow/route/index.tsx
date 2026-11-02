import React, { useCallback, useMemo, useState } from 'react';
import {
  Alert, Button, Card, Checkbox, Col, DatePicker, Drawer, Empty, Input, Modal, Row,
  Segmented, Space, Statistic, Switch, Table, Tag, Tooltip, message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ReloadOutlined } from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import moment from 'moment';
import {
  getAiShadowStatus, setAiShadowMode, getAiConsumerUsage, getAiShadowConsumers, replaceAiShadowConsumers,
} from '@/services';
import type { AiShadowStatus, AiConsumerUsage } from '@/interfaces/ai-shadow';

const REFRESH_INTERVAL = 30000;
const { RangePicker } = DatePicker;

type FixedRangeKey = 'today' | 'week' | 'month';
type RangeKey = FixedRangeKey | 'custom';

const RANGE_DAYS: Record<FixedRangeKey, number> = { today: 1, week: 7, month: 30 };

// 与「流量检测与审计」同一套窗口口径：起点对齐浏览器本地零点。
// 两个 tab 看的是同一张事件表的两面，窗口算法各写一套会让同一个"今天"得出两个数字。
const rangeWindow = (key: FixedRangeKey): { startTime: number; endTime: number } => {
  const endTime = Date.now();
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  start.setDate(start.getDate() - (RANGE_DAYS[key] - 1));
  return { startTime: start.getTime(), endTime };
};

const startOfDay = (ms: number): number => new Date(ms).setHours(0, 0, 0, 0);
const endOfDay = (ms: number): number => new Date(ms).setHours(23, 59, 59, 999);

// 后端 MAX_TREND_WINDOW_MS = 31 天，超出会被静默截断起点：界面显示的范围与实际
// 统计范围就不再一致，所以前端先卡住。
const MAX_CUSTOM_DAYS = 31;

// Prometheus 启动参数 retention=6h。违规事件落在 MySQL（完整保留），
// 但请求数与 Token 只能覆盖最近 6 小时，超出部分必须如实标注。
const USAGE_RETENTION_HOURS = 6;
const USAGE_RETENTION_MS = USAGE_RETENTION_HOURS * 3600 * 1000;

interface RouteRow extends AiShadowStatus {
  requestCount: number;
  callers: AiConsumerUsage[];
  unauthorizedCallers: AiConsumerUsage[];
  unauthorizedRequests: number;
}

const AiShadowRoutePage: React.FC<{ enabled?: boolean }> = () => {
  const { t } = useTranslation();
  const [range, setRange] = useState<RangeKey>('today');
  // 自定义区间（毫秒，闭区间）。仅在 range === 'custom' 时参与取数。
  const [customRange, setCustomRange] = useState<[number, number] | null>(null);
  const [keyword, setKeyword] = useState('');
  const [filter, setFilter] = useState<'all' | 'unauthorized' | 'deny'>('all');
  const [drawerRow, setDrawerRow] = useState<RouteRow | null>(null);
  const [checked, setChecked] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [switching, setSwitching] = useState<string | null>(null);

  // 取数窗口的唯一来源。windowKey 是给 refreshDeps 用的稳定标量键：
  // 直接用对象会让每次渲染都触发重新请求。
  const currentWindow = useCallback((): { startTime: number; endTime: number } => {
    if (range !== 'custom') {
      return rangeWindow(range);
    }
    if (customRange) {
      return { startTime: startOfDay(customRange[0]), endTime: endOfDay(customRange[1]) };
    }
    // 刚切到自定义还没选日期：先按近 7 天取数，避免整页空白
    return rangeWindow('week');
  }, [range, customRange]);

  const windowKey = range === 'custom'
    ? `custom:${customRange?.[0] ?? 0}:${customRange?.[1] ?? 0}`
    : range;

  const rangeSummary = useMemo(() => {
    const { startTime, endTime } = currentWindow();
    return `${moment(startTime).format('YYYY-MM-DD HH:mm')} ~ ${moment(endTime).format('YYYY-MM-DD HH:mm')}`;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [windowKey]);

  // 窗口跨度超过指标保留期时，用量只是"最近 6 小时"的切片，页面必须提示。
  const usageTruncated = useMemo(() => {
    const { startTime, endTime } = currentWindow();
    return endTime - startTime > USAGE_RETENTION_MS;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [windowKey]);

  // 路由清单：后端会顺带对账（缺 key-auth 实例自动纳管、空名单落默认拒绝），
  // 所以这里拿到的每一条都是「已纳管」状态，页面不需要再区分未纳管。
  const { data: statusList, loading, refresh } = useRequest(() => getAiShadowStatus(false), {
    pollingInterval: REFRESH_INTERVAL,
    pollingWhenHidden: false,
    onError: () => {},
  });

  // 用量：数据源只有 Prometheus。ai_route 标签与 resourceName 同值，才能把「谁用过这条路由」对上。
  const { data: usage } = useRequest(() => getAiConsumerUsage(currentWindow()), {
    refreshDeps: [windowKey],
    pollingInterval: REFRESH_INTERVAL,
    pollingWhenHidden: false,
    onError: () => {},
  });

  const { data: allConsumers, refresh: refreshConsumers } = useRequest(() => getAiShadowConsumers(), {
    onError: () => {},
  });

  // 用量不可用时不能显示 0：那会被读成「没人用」，与事实相反。
  const usageBroken = !!usage && usage.usageAvailable === false;

  // 每次调用次数（消费者维度），授权抽屉里用来区分「授权了但没人用」和「没授权却一直用」。
  const callCountByConsumer = useMemo(() => {
    const map: Record<string, number> = {};
    (usage?.consumers || []).forEach((item) => {
      map[item.consumer] = (map[item.consumer] || 0) + (item.requestCount || 0);
    });
    return map;
  }, [usage]);

  const rows = useMemo<RouteRow[]>(() => {
    const consumers = usage?.consumers || [];
    const list = (statusList || []).map((status) => {
      // 用量按 Ingress 资源名归属，路由名与它不同名，必须用 resourceName 对齐。
      const callers = consumers.filter((item) => (item.routes || []).includes(status.resourceName || ''));
      const unauthorizedCallers = callers.filter(
        (item) => !(status.authorizedConsumers || []).includes(item.consumer),
      );
      return {
        ...status,
        requestCount: callers.reduce((sum, item) => sum + (item.requestCount || 0), 0),
        callers,
        unauthorizedCallers,
        unauthorizedRequests: unauthorizedCallers.reduce((sum, item) => sum + (item.requestCount || 0), 0),
      };
    });
    return list.sort((left, right) => right.unauthorizedRequests - left.unauthorizedRequests);
  }, [statusList, usage]);

  const visibleRows = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    return rows.filter((row) => {
      if (kw && !`${row.routeName} ${row.resourceName || ''}`.toLowerCase().includes(kw)) {
        return false;
      }
      if (filter === 'unauthorized') {
        return row.unauthorizedCallers.length > 0;
      }
      if (filter === 'deny') {
        return !!row.defaultDeny;
      }
      return true;
    });
  }, [rows, keyword, filter]);

  const unauthorizedRouteCount = rows.filter((row) => row.unauthorizedCallers.length > 0).length;
  const denyRouteCount = rows.filter((row) => !!row.defaultDeny).length;

  // 全路由汇总。这里有一个必须显式呈现的口径差：
  // 表格只列 AI 路由，而 Prometheus 的 requestCount 是全部 ai_route 之和——手工 Ingress
  // 和公网反代也在里面。直接用后者当「AI 路由请求」会让表格与汇总对不上，所以拆成两个
  // 数字：AI 路由合计 + 不在 AI 路由上的流量（即绕过 AI 路由的量）。
  const totals = useMemo(() => {
    const aiRequests = rows.reduce((sum, row) => sum + row.requestCount, 0);
    return {
      routes: rows.length,
      denyRoutes: denyRouteCount,
      requests: aiRequests,
      unauthorized: rows.reduce((sum, row) => sum + row.unauthorizedRequests, 0),
      bypass: Math.max(0, (usage?.requestCount ?? 0) - aiRequests),
      // 拦截/放行来自事件表（surface=route 全量），不按路由归属——事件表没有路由名字段。
      blocked: usage?.blockedCount ?? 0,
      monitored: usage?.monitoredCount ?? 0,
    };
  }, [rows, denyRouteCount, usage]);

  const handleRangeChange = useCallback((next: RangeKey) => {
    setRange(next);
    // 首次切到自定义且还没有区间时给一个可用初值（近 7 天），避免整页空白
    if (next === 'custom' && !customRange) {
      const week = rangeWindow('week');
      setCustomRange([week.startTime, week.endTime]);
    }
  }, [customRange]);

  const toggleMode = useCallback(
    (row: RouteRow) => {
      const target = row.mode === 'monitoring' ? 'enforcement' : 'monitoring';
      const toGuard = target === 'enforcement';
      const noAuthorized = (row.authorizedConsumers || []).length === 0;
      Modal.confirm({
        title: toGuard ? t('aiShadow.modeToGuardTitle') : t('aiShadow.modeToMonitorTitle'),
        content: (
          <div>
            <div>{toGuard ? t('aiShadow.modeToGuardImpact') : t('aiShadow.modeToMonitorImpact')}</div>
            {toGuard && noAuthorized && (
              <div style={{ marginTop: 8, color: '#c0392b' }}>{t('aiShadow.modeAllBlockedWarn')}</div>
            )}
          </div>
        ),
        okText: t('aiShadow.action'),
        cancelText: t('misc.cancel'),
        okButtonProps: { danger: toGuard },
        onOk: async () => {
          setSwitching(row.routeName);
          try {
            await setAiShadowMode({ routeName: row.routeName, mode: target });
            message.success(t('aiShadow.modeSwitchSuccess'));
            refresh();
          } catch {
            message.error(t('aiShadow.actionFailed'));
          } finally {
            setSwitching(null);
          }
        },
      });
    },
    [t, refresh],
  );

  const openDrawer = useCallback(
    (row: RouteRow) => {
      setDrawerRow(row);
      setChecked([...(row.authorizedConsumers || [])]);
      refreshConsumers();
    },
    [refreshConsumers],
  );

  // 保存提交的是「完整名单」而不是差异：逐个 ADD/REMOVE 在中途失败时会留下半完成状态。
  const saveDrawer = useCallback(async () => {
    if (!drawerRow) {
      return;
    }
    setSaving(true);
    try {
      await replaceAiShadowConsumers({ routeName: drawerRow.routeName, consumerNames: checked });
      message.success(t('aiShadow.drawerSaved'));
      setDrawerRow(null);
      refresh();
    } catch {
      message.error(t('aiShadow.actionFailed'));
    } finally {
      setSaving(false);
    }
  }, [drawerRow, checked, t, refresh]);

  const drawerOptions = useMemo(() => {
    const names = new Set<string>(allConsumers || []);
    (drawerRow?.authorizedConsumers || []).forEach((name) => names.add(name));
    (drawerRow?.callers || []).forEach((item) => names.add(item.consumer));
    return Array.from(names).sort();
  }, [allConsumers, drawerRow]);

  const columns: ColumnsType<RouteRow> = [
    {
      title: t('aiShadow.colRoute'),
      dataIndex: 'routeName',
      width: '32%',
      render: (_value, row) => (
        <div>
          <Space size={6}>
            <span style={{ fontWeight: 500 }}>{row.routeName}</span>
            <Tag color="blue">{t('aiShadow.routeTab')}</Tag>
          </Space>
          <div style={{ color: '#8f959e', fontSize: 12 }}>{row.resourceName}</div>
        </div>
      ),
    },
    {
      title: t('aiShadow.colAllow'),
      dataIndex: 'authorizedConsumers',
      width: '22%',
      render: (_value, row) => {
        const names = row.authorizedConsumers || [];
        if (names.length === 0) {
          return (
            <Tooltip title={t('aiShadow.statusDefaultDenyHint')}>
              <span style={{ color: '#c0392b', fontWeight: 500 }}>{t('aiShadow.statusDefaultDeny')}</span>
            </Tooltip>
          );
        }
        return (
          <Space size={[4, 4]} wrap>
            {names.map((name) => (
              <Tag key={name}>{name}</Tag>
            ))}
          </Space>
        );
      },
    },
    {
      title: t('aiShadow.colMode'),
      dataIndex: 'mode',
      width: '16%',
      render: (_value, row) => {
        const guard = row.mode === 'enforcement';
        return (
          <Space size={6}>
            <Switch
              size="small"
              checked={guard}
              loading={switching === row.routeName}
              onChange={() => toggleMode(row)}
            />
            <span style={{ fontSize: 12, color: guard ? '#c0392b' : '#8f959e' }}>
              {guard ? t('aiShadow.enforcementMode') : t('aiShadow.monitoringMode')}
            </span>
          </Space>
        );
      },
    },
    {
      title: t('aiShadow.colRequests'),
      dataIndex: 'requestCount',
      align: 'right',
      width: '12%',
      sorter: (left, right) => left.requestCount - right.requestCount,
      render: (value: number) => (usageBroken ? <span style={{ color: '#b0b5bb' }}>-</span> : value.toLocaleString()),
    },
    {
      title: t('aiShadow.colUnauthorized'),
      dataIndex: 'unauthorizedRequests',
      align: 'right',
      width: '12%',
      sorter: (left, right) => left.unauthorizedRequests - right.unauthorizedRequests,
      render: (_value, row) => {
        if (usageBroken) {
          return <span style={{ color: '#b0b5bb' }}>-</span>;
        }
        if (row.unauthorizedCallers.length === 0) {
          return <span style={{ color: '#b0b5bb' }}>0</span>;
        }
        // 数字是「这条路由上未授权调用方发起的请求数」：管控模式下会被拦下，检测模式下只记录。
        return (
          <Tooltip title={t('aiShadow.unauthorizedHint')}>
            <span style={{ color: '#c0392b', fontWeight: 500 }}>
              {row.unauthorizedRequests.toLocaleString()}
            </span>
            <div style={{ fontSize: 11, color: '#8f959e' }}>
              {row.unauthorizedCallers.length} {t('aiShadow.callerCount')}
            </div>
          </Tooltip>
        );
      },
    },
    {
      title: t('aiShadow.colActions'),
      align: 'right',
      width: '6%',
      render: (_value, row) => (
        <Button size="small" onClick={() => openDrawer(row)}>
          {t('aiShadow.authorize')}
        </Button>
      ),
    },
  ];

  const overviewCell = (
    title: string,
    value: number,
    options?: { danger?: boolean; suffix?: React.ReactNode; hint?: string },
  ) => {
    const cell = (
      <div style={{ padding: '12px 16px', background: '#fafafa', borderRadius: 8 }}>
        <Statistic
          title={<span style={{ fontSize: 12, color: '#8c8c8c' }}>{title}</span>}
          // 用量不可用时统一显示 '-'：显示 0 会被读成「没人用」，与事实相反。
          value={usageBroken ? '-' : value.toLocaleString()}
          suffix={options?.suffix}
          valueStyle={{
            fontSize: 20,
            color: options?.danger && value > 0 ? '#c0392b' : undefined,
          }}
        />
      </div>
    );
    return (
      <Col flex="1 1 150px">
        {options?.hint ? <Tooltip title={options.hint}>{cell}</Tooltip> : cell}
      </Col>
    );
  };

  return (
    <Card
      title={t('aiShadow.routeControlTitle')}
      extra={(
        <Button icon={<ReloadOutlined />} onClick={() => refresh()}>
          {t('aiShadow.refresh')}
        </Button>
      )}
    >
      {/* 页面级时间范围：同时决定汇总、请求数、未授权数三处口径，
          所以放在表格上方，而不是塞进某张卡片的 extra 造成"只有那块会变"的错觉。 */}
      <div style={{
        display: 'flex',
        alignItems: 'center',
        flexWrap: 'wrap',
        gap: 12,
        marginBottom: 12,
      }}
      >
        <span style={{ fontSize: 13, color: '#595959' }}>{t('aiShadow.timeRangeLabel')}</span>
        <Segmented
          value={range}
          onChange={(value) => handleRangeChange(value as RangeKey)}
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
            style={{ width: 260 }}
            // antd 4 的受控值必须是 moment 对象，null 表示未选择
            value={customRange ? ([moment(customRange[0]), moment(customRange[1])] as any) : null}
            disabledDate={(d: any) => !!d && startOfDay(d.valueOf()) > startOfDay(Date.now())}
            onChange={(v: any) => {
              if (!v || !v[0] || !v[1]) {
                return;
              }
              const start = startOfDay(v[0].valueOf());
              const end = endOfDay(v[1].valueOf());
              // 超过后端窗口上限时后端会静默截断起点，界面与实际口径就会对不上。
              if (end - start > MAX_CUSTOM_DAYS * 86400000) {
                message.warning(t('aiShadow.timeRangeTooLong', { days: MAX_CUSTOM_DAYS }));
                return;
              }
              setCustomRange([start, end]);
            }}
          />
        ) : null}
        <span style={{ marginLeft: 'auto', fontSize: 12, color: '#8c8c8c' }}>{rangeSummary}</span>
      </div>

      {usageTruncated && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={t('aiShadow.usageRetentionWarn', { hours: USAGE_RETENTION_HOURS })}
        />
      )}

      <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
        {overviewCell(t('aiShadow.routeOverviewRoutes'), totals.routes, {
          suffix: totals.denyRoutes > 0
            ? (
              <span style={{ fontSize: 12, color: '#c0392b' }}>
                · {t('aiShadow.routeOverviewDenySuffix', { count: totals.denyRoutes })}
              </span>
            )
            : undefined,
        })}
        {overviewCell(t('aiShadow.routeOverviewRequests'), totals.requests)}
        {overviewCell(t('aiShadow.routeOverviewUnauthorized'), totals.unauthorized, { danger: true })}
        {overviewCell(t('aiShadow.routeOverviewBypass'), totals.bypass, {
          danger: true,
          hint: t('aiShadow.routeOverviewBypassHint'),
        })}
        {overviewCell(t('aiShadow.routeOverviewBlocked'), totals.blocked)}
        {overviewCell(t('aiShadow.routeOverviewMonitored'), totals.monitored)}
      </Row>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message={t('aiShadow.routeListHint')}
      />

      <Space style={{ marginBottom: 12 }} wrap>
        <Input
          allowClear
          style={{ width: 240 }}
          placeholder={t('aiShadow.searchPlaceholder')}
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
        />
        <Segmented
          value={filter}
          onChange={(value) => setFilter(value as typeof filter)}
          options={[
            { label: `${t('aiShadow.filterAll')} (${rows.length})`, value: 'all' },
            { label: `${t('aiShadow.filterUnauthorized')} (${unauthorizedRouteCount})`, value: 'unauthorized' },
            { label: `${t('aiShadow.filterDefaultDeny')} (${denyRouteCount})`, value: 'deny' },
          ]}
        />
      </Space>

      <Table<RouteRow>
        rowKey="routeName"
        size="middle"
        loading={loading}
        columns={columns}
        dataSource={visibleRows}
        pagination={{
          defaultPageSize: 20,
          showSizeChanger: true,
          pageSizeOptions: ['20', '50', '100'],
          hideOnSinglePage: true,
        }}
        locale={{ emptyText: <Empty description={t('aiShadow.noData')} /> }}
        expandable={{
          expandedRowRender: (row) => {
            if (row.callers.length === 0) {
              return <div style={{ color: '#8f959e' }}>{t('aiShadow.expandNoCaller')}</div>;
            }
            return (
              <Table<AiConsumerUsage>
                rowKey="consumer"
                size="small"
                pagination={false}
                dataSource={row.callers}
                columns={[
                  {
                    title: t('aiShadow.usageColConsumer'),
                    dataIndex: 'consumer',
                    render: (value: string) => (
                      <Space size={6}>
                        <span>{value}</span>
                        {value === 'none' && <Tag>{t('aiShadow.usageNoIdentity')}</Tag>}
                      </Space>
                    ),
                  },
                  {
                    title: t('aiShadow.colRequests'),
                    dataIndex: 'requestCount',
                    align: 'right',
                    render: (value: number) => (value || 0).toLocaleString(),
                  },
                  {
                    title: t('aiShadow.colAllow'),
                    align: 'right',
                    render: (_value, caller) => (
                      (row.authorizedConsumers || []).includes(caller.consumer)
                        ? <Tag color="green">{t('aiShadow.usageAuthorized')}</Tag>
                        : <Tag color="red">{t('aiShadow.callersUnauthorized')}</Tag>
                    ),
                  },
                ]}
              />
            );
          },
        }}
      />

      <Drawer
        width={440}
        open={!!drawerRow}
        onClose={() => setDrawerRow(null)}
        title={t('aiShadow.drawerTitle')}
        footer={(
          <div style={{ textAlign: 'right' }}>
            <Space>
              <Button onClick={() => setDrawerRow(null)}>{t('misc.cancel')}</Button>
              <Button type="primary" loading={saving} onClick={saveDrawer}>
                {t('aiShadow.drawerSave')}
              </Button>
            </Space>
          </div>
        )}
      >
        {drawerRow && (
          <div>
            <div style={{ marginBottom: 12, color: '#595959' }}>
              {t('aiShadow.routeName')}: <Tag>{drawerRow.routeName}</Tag>
            </div>
            <Alert
              type={checked.length === 0 ? 'warning' : 'info'}
              showIcon
              style={{ marginBottom: 12 }}
              message={checked.length === 0 ? t('aiShadow.statusDefaultDeny') : t('aiShadow.drawerHint')}
              description={checked.length === 0 ? t('aiShadow.drawerEmptyWarn') : undefined}
            />
            <Checkbox.Group
              value={checked}
              onChange={(values) => setChecked(values as string[])}
              style={{ display: 'block' }}
            >
              <Space direction="vertical" size={10} style={{ width: '100%' }}>
                {drawerOptions.map((name) => (
                  <Checkbox key={name} value={name} style={{ width: '100%' }}>
                    <span style={{ marginRight: 8 }}>{name}</span>
                    <span style={{ color: '#8f959e', fontSize: 12 }}>
                      {(callCountByConsumer[name] || 0).toLocaleString()} {t('aiShadow.colRequests')}
                    </span>
                  </Checkbox>
                ))}
              </Space>
            </Checkbox.Group>
            {drawerOptions.length === 0 && <Empty description={t('aiShadow.noData')} />}
          </div>
        )}
      </Drawer>
    </Card>
  );
};

export default AiShadowRoutePage;
