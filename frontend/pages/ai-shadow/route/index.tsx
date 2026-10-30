import React, { useCallback, useState } from 'react';
import { Card, Table, Switch, Tag, Button, message, Modal, Input, Statistic, Row, Col, Space, Spin, Empty, Tooltip, Segmented, Alert } from 'antd';
import {
  SafetyCertificateOutlined, EyeOutlined, StopOutlined, WarningOutlined,
  ReloadOutlined, TeamOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { getAiShadowStatus, setAiShadowMode, performAiShadowAction, getAiShadowAdmissionEvents, getAiConsumerUsage } from '@/services';
import { AiShadowStatus, AiShadowAdmissionEvent, AiConsumerUsage } from '@/interfaces/ai-shadow';
import { useTranslation } from 'react-i18next';

const REFRESH_INTERVAL = 30000;

type RangeKey = 'today' | 'week' | 'month';

// 三档固定窗各覆盖多少天（含当天）。
const RANGE_DAYS: Record<RangeKey, number> = { today: 1, week: 7, month: 30 };

// 与「流量检测与审计」同一套窗口口径：三档固定窗、起点对齐浏览器本地零点。
// 两个 tab 看的是同一张事件表的两面，窗口算法如果各写一套，同一个"今天"会得出两个数字。
const rangeWindow = (key: RangeKey): { startTime: number; endTime: number } => {
  const endTime = Date.now();
  const days = RANGE_DAYS[key];
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  start.setDate(start.getDate() - (days - 1));
  return { startTime: start.getTime(), endTime };
};

const formatDateTime = (ms?: number | null): string => {
  if (!ms) return '-';
  const d = new Date(Number(ms));
  if (isNaN(d.getTime())) return '-';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
};

const AiShadowRoutePage: React.FC<{ enabled?: boolean }> = ({ enabled = true }) => {
  const { t } = useTranslation();
  const [range, setRange] = useState<RangeKey>('today');
  const [modeSwitching, setModeSwitching] = useState<string | null>(null);
  // 每个路由一个输入框：授权动作的作用域是「某个路由的消费者白名单」，
  // 一条输入没法同时指向两个路由，所以按路由名分开存。
  const [consumerInputs, setConsumerInputs] = useState<Record<string, string>>({});

  // 用量：本页的主体。数据源只有 Prometheus——被放行的请求才有计量，
  // 所以它回答的是「谁在用、用了多少」，回答不了「被拦了什么」。
  const { data: usage, loading: usageLoading, error: usageError, refresh: refreshUsage } = useRequest(
    () => getAiConsumerUsage(rangeWindow(range)),
    {
      refreshDeps: [range],
      pollingInterval: REFRESH_INTERVAL,
      pollingWhenHidden: false,
      onError: () => {},
    },
  );

  // 路由管控配置：模式开关与已授权消费者在这张卡上操作。
  // metrics=false：用量已由上面的接口单独取，这条链不必再为 Prometheus 的超时买单。
  const { data: statusList, loading: statusLoading, refresh: refreshStatus } = useRequest(
    () => getAiShadowStatus(false),
    {
      pollingInterval: REFRESH_INTERVAL,
      pollingWhenHidden: false,
      onError: () => {},
    },
  );

  // 准入违规明细：数据源是事件表（surface='route'）。
  // 被 key-auth 拦下的请求不产生计量，那条路永远看不到拦截——这是两套数据必须分开取的根本原因。
  const { data: admission, loading, error, refresh } = useRequest(
    () => getAiShadowAdmissionEvents(rangeWindow(range)),
    {
      refreshDeps: [range],
      pollingInterval: REFRESH_INTERVAL,
      pollingWhenHidden: false,
      onError: () => {},
    },
  );

  const handleModeSwitch = useCallback(async (routeName: string, currentMode: string) => {
    const newMode = currentMode === 'monitoring' ? 'enforcement' : 'monitoring';
    setModeSwitching(routeName);
    try {
      await setAiShadowMode({ routeName, mode: newMode as 'monitoring' | 'enforcement' });
      message.success(t('aiShadow.modeSwitchSuccess'));
      refreshStatus();
    } catch {
      message.error(t('aiShadow.actionFailed'));
    } finally {
      setModeSwitching(null);
    }
  }, [t, refreshStatus]);

  // 授权动作不能丢：它是本页唯一的写入口（把越权消费者加进该路由的 key-auth 白名单）。
  // 准入明细里能看到「谁不在白名单」，但事件表没有路由归属，所以这里按路由名手填消费者，
  // 而不是从某一行明细一键授权——凭据与路由的对应关系只有操作者知道。
  const handleAuthorizeConsumer = useCallback((routeName: string) => {
    const consumer = (consumerInputs[routeName] || '').trim();
    if (!consumer) {
      message.warning(t('aiShadow.authorizeConsumerRequired'));
      return;
    }
    Modal.confirm({
      title: t('aiShadow.authorizeConfirm'),
      content: t('aiShadow.authorizeConfirmMsg', { consumer }),
      okText: t('aiShadow.authorize'),
      cancelText: t('misc.cancel'),
      onOk: async () => {
        try {
          await performAiShadowAction({ routeName, consumerName: consumer, action: 'authorize' });
          message.success(t('aiShadow.actionSuccess'));
          setConsumerInputs((prev) => ({ ...prev, [routeName]: '' }));
          refreshStatus();
          refreshUsage();
        } catch {
          message.error(t('aiShadow.actionFailed'));
        }
      },
    });
  }, [consumerInputs, t, refreshStatus, refreshUsage]);

  const reasonLabel = (reason?: string | null): string => {
    if (!reason) return '-';
    if (reason === 'no_key') return t('aiShadow.reasonNoKey');
    if (reason === 'unknown_key') return t('aiShadow.reasonUnknownKey');
    if (reason === 'off_channel') return t('aiShadow.reasonOffChannel');
    if (reason.startsWith('not_in_allow')) {
      const consumer = reason.split(':')[1];
      return consumer
        ? t('aiShadow.reasonNotInAllowNamed', { consumer })
        : t('aiShadow.reasonNotInAllow');
    }
    return reason;
  };

  const statusTag = (status?: string | null) => {
    if (status === 'blocked') return <Tag color="red">{t('aiShadow.statusBlocked')}</Tag>;
    if (status === 'monitored') return <Tag color="blue">{t('aiShadow.statusMonitored')}</Tag>;
    if (status === 'allowed') return <Tag color="green">{t('aiShadow.statusAllowed')}</Tag>;
    return <Tag>{status || '-'}</Tag>;
  };

  const admissionColumns = [
    {
      title: t('aiShadow.admissionColDomain'),
      dataIndex: 'domain',
      key: 'domain',
      render: (text: string) => <span style={{ fontFamily: 'monospace' }}>{text}</span>,
    },
    {
      // 原因决定了处置方式：no_key 要发凭据、unknown_key 要换凭据、
      // not_in_allow 才是把该消费者加进白名单。混成一个「影子AI」标签就无从下手。
      title: t('aiShadow.admissionColReason'),
      dataIndex: 'reason',
      key: 'reason',
      width: 220,
      render: (reason?: string | null) => <span style={{ fontFamily: 'monospace' }}>{reasonLabel(reason)}</span>,
    },
    {
      title: t('aiShadow.admissionColStatus'),
      dataIndex: 'status',
      key: 'status',
      width: 120,
      render: (status?: string | null) => statusTag(status),
    },
    {
      title: t('aiShadow.admissionColCount'),
      dataIndex: 'count',
      key: 'count',
      width: 110,
      render: (val: number) => val?.toLocaleString() ?? '-',
      sorter: (a: AiShadowAdmissionEvent, b: AiShadowAdmissionEvent) => a.count - b.count,
    },
    {
      title: t('aiShadow.admissionColLastSeen'),
      dataIndex: 'lastSeen',
      key: 'lastSeen',
      width: 170,
      render: (value?: number | null) => (
        <span style={{ color: value ? undefined : '#bfbfbf' }}>{formatDateTime(value)}</span>
      ),
      sorter: (a: AiShadowAdmissionEvent, b: AiShadowAdmissionEvent) => (a.lastSeen ?? 0) - (b.lastSeen ?? 0),
    },
  ];

  const consumerColumns = [
    {
      title: t('aiShadow.usageColConsumer'),
      dataIndex: 'consumer',
      key: 'consumer',
      width: 160,
      render: (consumer: string, record: AiConsumerUsage) => (
        <Space size={4}>
          <span style={{ fontFamily: 'monospace' }}>{consumer}</span>
          {record.unauthenticated ? (
            <Tooltip title={t('aiShadow.usageUnauthenticatedHint')}>
              <Tag color="orange">{t('aiShadow.usageUnauthenticated')}</Tag>
            </Tooltip>
          ) : null}
        </Space>
      ),
    },
    {
      title: t('aiShadow.usageColRequests'),
      dataIndex: 'requestCount',
      key: 'requestCount',
      width: 110,
      render: (val: number) => (val ?? 0).toLocaleString(),
      sorter: (a: AiConsumerUsage, b: AiConsumerUsage) => (a.requestCount ?? 0) - (b.requestCount ?? 0),
    },
    {
      // Token 是计费口径，和请求数分开看：有的调用极短，有的极长，
      // 只看请求数会得出"这个人用得最多"的错误结论。
      title: t('aiShadow.usageColTokens'),
      key: 'tokens',
      width: 130,
      render: (_: unknown, record: AiConsumerUsage) => (
        <Tooltip title={`input ${(record.inputTokens ?? 0).toLocaleString()} / output ${(record.outputTokens ?? 0).toLocaleString()}`}>
          {((record.inputTokens ?? 0) + (record.outputTokens ?? 0)).toLocaleString()}
        </Tooltip>
      ),
      sorter: (a: AiConsumerUsage, b: AiConsumerUsage) =>
        ((a.inputTokens ?? 0) + (a.outputTokens ?? 0)) - ((b.inputTokens ?? 0) + (b.outputTokens ?? 0)),
    },
    {
      title: t('aiShadow.usageColModels'),
      dataIndex: 'models',
      key: 'models',
      width: 150,
      render: (models: string[]) => (models && models.length > 0
        ? <Space size={2} wrap>{models.map((m) => <Tag key={m}>{m}</Tag>)}</Space>
        : <span style={{ color: '#bfbfbf' }}>-</span>),
    },
    {
      // 一个消费者可能在多条路由上有用量，其中某几条并没有授权它。
      // 标红的那条就是越权证据，比一个笼统的"未授权"有用得多。
      title: t('aiShadow.usageColRoutes'),
      dataIndex: 'routes',
      key: 'routes',
      render: (routes: string[], record: AiConsumerUsage) => (
        (routes && routes.length > 0) ? (
          <Space size={2} wrap>
            {routes.map((route) => {
              const unauthorized = (record.unauthorizedRoutes || []).includes(route);
              return (
                <Tag key={route} color={unauthorized ? 'red' : 'default'} style={{ fontFamily: 'monospace' }}>
                  {route}
                </Tag>
              );
            })}
          </Space>
        ) : <span style={{ color: '#bfbfbf' }}>-</span>
      ),
    },
    {
      // 拦与监控并排：只有这两个数放在一起，"拦了一半、漏了一半"才看得出来。
      title: t('aiShadow.usageColDisposition'),
      key: 'disposition',
      width: 130,
      render: (_: unknown, record: AiConsumerUsage) => (
        <Space size={6}>
          <Tooltip title={t('aiShadow.usageBlockedHint')}>
            <span style={{ color: (record.blockedCount ?? 0) > 0 ? '#cf1322' : '#bfbfbf', fontWeight: (record.blockedCount ?? 0) > 0 ? 600 : 400 }}>
              {record.blockedCount ?? 0}
            </span>
          </Tooltip>
          <span style={{ color: '#d9d9d9' }}>/</span>
          <Tooltip title={t('aiShadow.usageMonitoredHint')}>
            <span style={{ color: (record.monitoredCount ?? 0) > 0 ? '#1677ff' : '#bfbfbf' }}>
              {record.monitoredCount ?? 0}
            </span>
          </Tooltip>
        </Space>
      ),
      sorter: (a: AiConsumerUsage, b: AiConsumerUsage) => (a.blockedCount ?? 0) - (b.blockedCount ?? 0),
    },
    {
      title: t('aiShadow.usageColAuth'),
      key: 'authorized',
      width: 110,
      render: (_: unknown, record: AiConsumerUsage) => {
        if (record.unauthenticated) {
          return <Tag color="orange">{t('aiShadow.usageNoIdentity')}</Tag>;
        }
        return record.authorized
          ? <Tag color="green">{t('aiShadow.usageAuthorized')}</Tag>
          : <Tag color="red">{t('aiShadow.usageUnauthorized')}</Tag>;
      },
    },
  ];

  const total = admission?.total ?? 0;
  const blocked = admission?.blocked ?? 0;
  const domainCount = admission?.domainCount ?? 0;
  const items = admission?.list || [];
  const degraded = Boolean(error) || Boolean(admission?.degraded);

  // 请求失败与「后端说观测组件不可用」是两回事，但对页面是同一个结论：这些数字不可信。
  // 必须让 KPI 显示 '-' 而不是 0——把"拿不到"渲染成"没有人用"，正是本页最容易犯的错。
  const usageBroken = Boolean(usageError) || usage?.usageAvailable === false;
  const consumerRows = usage?.consumers || [];

  return (
    <div style={{ padding: '0 0 24px' }}>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message={t('aiShadow.usageScopeHint')}
      />

      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
        <Space>
          <span style={{ fontSize: 13, color: '#595959' }}>{t('aiShadow.timeRangeLabel')}</span>
          <Segmented
            value={range}
            onChange={(v) => setRange(v as RangeKey)}
            options={[
              { value: 'today', label: t('aiShadow.trendRangeToday') },
              { value: 'week', label: t('aiShadow.trendRangeWeek') },
              { value: 'month', label: t('aiShadow.trendRangeMonth') },
            ]}
          />
          <Tooltip title={t('aiShadow.timeRangeScopeHint')}>
            <span style={{ fontSize: 12, color: '#8c8c8c' }}>{t('aiShadow.timeRangeScopeTag')}</span>
          </Tooltip>
        </Space>
        <Button
          icon={<ReloadOutlined />}
          onClick={() => { refresh(); refreshStatus(); refreshUsage(); }}
          loading={loading || statusLoading || usageLoading}
        >
          {t('aiShadow.refresh')}
        </Button>
      </div>

      {usageBroken ? (
        <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={t('aiShadow.usageUnavailable')} />
      ) : null}
      {degraded ? (
        <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={t('aiShadow.admissionDegraded')} />
      ) : null}

      {/* 用量总览：三个累计量 + 一个窗口量，标题上直接标口径，避免"总请求 516 / 事件 34"被读成矛盾。 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card>
            <Statistic
              title={(
                <Space size={4}>
                  <span>{t('aiShadow.usageKpiRequests')}</span>
                  <Tag color="default">{t('aiShadow.usageScopeCumulative')}</Tag>
                </Space>
              )}
              value={usageBroken ? '-' : (usage?.requestCount ?? 0)}
              prefix={<ThunderboltOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title={(
                <Space size={4}>
                  <span>{t('aiShadow.usageKpiTokens')}</span>
                  <Tag color="default">{t('aiShadow.usageScopeCumulative')}</Tag>
                </Space>
              )}
              value={usageBroken ? '-' : (usage?.totalTokens ?? 0)}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title={t('aiShadow.usageKpiConsumers')}
              value={usageBroken ? '-' : (usage?.consumerCount ?? 0)}
              suffix={usage ? `/ ${usage.authorizedCount ?? 0} ${t('aiShadow.usageAuthorized')}` : undefined}
              prefix={<TeamOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title={(
                <Space size={4}>
                  <span>{t('aiShadow.usageKpiDisposition')}</span>
                  <Tag color="orange">{t('aiShadow.usageScopeWindow')}</Tag>
                </Space>
              )}
              value={usageBroken ? '-' : (usage?.blockedCount ?? 0)}
              suffix={usage ? `/ ${usage.monitoredCount ?? 0}` : undefined}
              valueStyle={{ color: (usage?.blockedCount ?? 0) > 0 ? '#cf1322' : undefined }}
              prefix={<StopOutlined />}
            />
          </Card>
        </Col>
      </Row>

      <Card
        title={(
          <Space>
            <TeamOutlined />
            <span>{t('aiShadow.usageCardTitle')}</span>
            <Tooltip title={t('aiShadow.usageScopeHint')}>
              <Tag color="geekblue">{t('aiShadow.usageScopeTag')}</Tag>
            </Tooltip>
          </Space>
        )}
        style={{ marginBottom: 16 }}
      >
        {usageLoading && !usage ? (
          <div style={{ width: '100%', padding: 48, display: 'flex', justifyContent: 'center' }}>
            <Spin />
          </div>
        ) : (
          <Table
            dataSource={consumerRows}
            columns={consumerColumns}
            rowKey={(record) => record.consumer}
            size="small"
            pagination={{ pageSize: 10, hideOnSinglePage: true, size: 'small' }}
            locale={{ emptyText: t('aiShadow.usageNoData') }}
          />
        )}
      </Card>

      <Card
        title={(
          <Space>
            <span>{t('aiShadow.admissionCardTitle')}</span>
            <Tooltip title={t('aiShadow.admissionScopeHint')}>
              <Tag color="geekblue">{t('aiShadow.admissionScopeTag')}</Tag>
            </Tooltip>
          </Space>
        )}
        style={{ marginBottom: 16 }}
      >
        <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
          <Col span={8}>
            <Statistic
              title={t('aiShadow.admissionKpiTotal')}
              value={error ? '-' : total}
              prefix={<EyeOutlined />}
            />
          </Col>
          <Col span={8}>
            <Statistic
              title={t('aiShadow.admissionKpiBlocked')}
              value={error ? '-' : blocked}
              valueStyle={{ color: '#cf1322' }}
              prefix={<StopOutlined />}
            />
          </Col>
          <Col span={8}>
            <Statistic
              title={t('aiShadow.admissionKpiDomains')}
              value={error ? '-' : domainCount}
              prefix={<WarningOutlined />}
            />
          </Col>
        </Row>
        {loading && !admission ? (
          <div style={{ width: '100%', padding: 48, display: 'flex', justifyContent: 'center' }}>
            <Spin />
          </div>
        ) : (
          <Table
            dataSource={items}
            columns={admissionColumns}
            rowKey={(record) => `${record.domain}-${record.reason || ''}-${record.status || ''}`}
            size="small"
            pagination={{ pageSize: 10, hideOnSinglePage: true, size: 'small' }}
            locale={{ emptyText: t('aiShadow.admissionNoData') }}
          />
        )}
      </Card>

      <Card
        title={(
          <Space>
            <SafetyCertificateOutlined />
            <span>{t('aiShadow.routeControlTitle')}</span>
          </Space>
        )}
      >
        {(statusList || []).length === 0 ? (
          <Empty description={t('aiShadow.noData')} />
        ) : (statusList || []).map((routeStatus: AiShadowStatus) => {
          const isRouteEnforcement = routeStatus.mode === 'enforcement';
          return (
            <Card
              key={routeStatus.routeName}
              type="inner"
              title={(
                <Space>
                  <span>{t('aiShadow.routeName')}: {routeStatus.routeName}</span>
                  <Tag color={isRouteEnforcement ? 'red' : 'blue'}>
                    {isRouteEnforcement ? t('aiShadow.enforcementMode') : t('aiShadow.monitoringMode')}
                  </Tag>
                </Space>
              )}
              extra={(
                <Space>
                  <span style={{ fontSize: 13, color: '#666' }}>
                    {isRouteEnforcement ? t('aiShadow.enforcementDesc') : t('aiShadow.monitoringDesc')}
                  </span>
                  <Tooltip title={isRouteEnforcement ? t('aiShadow.enforcementMode') : t('aiShadow.monitoringMode')}>
                    <Switch
                      checked={isRouteEnforcement}
                      disabled={!enabled}
                      loading={modeSwitching === routeStatus.routeName}
                      checkedChildren={t('aiShadow.enforcementMode')}
                      unCheckedChildren={t('aiShadow.monitoringMode')}
                      onChange={() => handleModeSwitch(routeStatus.routeName, routeStatus.mode)}
                    />
                  </Tooltip>
                </Space>
              )}
              style={{ marginBottom: 12 }}
            >
              <Space size={[8, 8]} wrap>
                <span style={{ fontSize: 13, color: '#595959' }}>{t('aiShadow.authorizedConsumers')}:</span>
                {(routeStatus.authorizedConsumers || []).length === 0 ? (
                  <span style={{ fontSize: 13, color: '#bfbfbf' }}>{t('aiShadow.noAuthorizedConsumer')}</span>
                ) : (routeStatus.authorizedConsumers || []).map((consumer) => (
                  <Tag key={consumer} color="green">{consumer}</Tag>
                ))}
              </Space>
              <Space.Compact style={{ marginTop: 12, display: 'flex' }}>
                <Input
                  size="small"
                  style={{ width: 240 }}
                  placeholder={t('aiShadow.authorizeConsumerPlaceholder')}
                  value={consumerInputs[routeStatus.routeName] || ''}
                  disabled={!enabled}
                  onChange={(e) => setConsumerInputs((prev) => ({ ...prev, [routeStatus.routeName]: e.target.value }))}
                  onPressEnter={() => handleAuthorizeConsumer(routeStatus.routeName)}
                  allowClear
                />
                <Button
                  size="small"
                  type="primary"
                  icon={<SafetyCertificateOutlined />}
                  disabled={!enabled}
                  onClick={() => handleAuthorizeConsumer(routeStatus.routeName)}
                >
                  {t('aiShadow.authorize')}
                </Button>
              </Space.Compact>
            </Card>
          );
        })}
      </Card>
    </div>
  );
};

export default AiShadowRoutePage;
