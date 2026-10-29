import React, { useCallback, useState } from 'react';
import { Card, Table, Switch, Tag, Button, message, Modal, Input, Statistic, Row, Col, Space, Spin, Empty, Tooltip, Segmented, Alert } from 'antd';
import { SafetyCertificateOutlined, EyeOutlined, StopOutlined, WarningOutlined, ReloadOutlined } from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { getAiShadowStatus, setAiShadowMode, performAiShadowAction, getAiShadowAdmissionEvents } from '@/services';
import { AiShadowStatus, AiShadowAdmissionEvent } from '@/interfaces/ai-shadow';
import { useTranslation } from 'react-i18next';

const REFRESH_INTERVAL = 30000;

type RangeKey = 'today' | 'week' | 'month';

// 与「流量检测与审计」同一套窗口口径：三档固定窗、起点对齐浏览器本地零点。
// 两个 tab 看的是同一张事件表的两面，窗口算法如果各写一套，同一个"今天"会得出两个数字。
const rangeWindow = (key: RangeKey): { startTime: number; endTime: number } => {
  const endTime = Date.now();
  const days = key === 'today' ? 1 : key === 'week' ? 7 : 30;
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

  // 路由管控配置：模式开关与已授权消费者在这张卡上操作，与准入明细分开取数。
  // metrics=false：本页不再展示 Token 用量，没必要为它付出 Prometheus 的超时代价。
  const { data: statusList, loading: statusLoading, refresh: refreshStatus } = useRequest(
    () => getAiShadowStatus(false),
    {
      pollingInterval: REFRESH_INTERVAL,
      pollingWhenHidden: false,
      onError: () => {},
    },
  );

  // 准入违规明细：唯一数据源是事件表（surface='route'）。
  // 不用 Prometheus token 指标——被 key-auth 拦下的请求不产生计量，那条路永远看不到拦截。
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
        } catch {
          message.error(t('aiShadow.actionFailed'));
        }
      },
    });
  }, [consumerInputs, t, refreshStatus]);

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

  const columns = [
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

  const total = admission?.total ?? 0;
  const blocked = admission?.blocked ?? 0;
  const domainCount = admission?.domainCount ?? 0;
  const items = admission?.list || [];
  const degraded = Boolean(error) || Boolean(admission?.degraded);

  return (
    <div style={{ padding: '0 0 24px' }}>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message={t('aiShadow.admissionScopeHint')}
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
        </Space>
        <Button icon={<ReloadOutlined />} onClick={() => { refresh(); refreshStatus(); }} loading={loading || statusLoading}>
          {t('aiShadow.refresh')}
        </Button>
      </div>

      {degraded ? (
        <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={t('aiShadow.admissionDegraded')} />
      ) : null}

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card>
            <Statistic
              title={t('aiShadow.admissionKpiTotal')}
              value={error ? '-' : total}
              prefix={<EyeOutlined />}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title={t('aiShadow.admissionKpiBlocked')}
              value={error ? '-' : blocked}
              valueStyle={{ color: '#cf1322' }}
              prefix={<StopOutlined />}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title={t('aiShadow.admissionKpiDomains')}
              value={error ? '-' : domainCount}
              prefix={<WarningOutlined />}
            />
          </Card>
        </Col>
      </Row>

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
        {loading && !admission ? (
          <div style={{ width: '100%', padding: 48, display: 'flex', justifyContent: 'center' }}>
            <Spin />
          </div>
        ) : (
          <Table
            dataSource={items}
            columns={columns}
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
