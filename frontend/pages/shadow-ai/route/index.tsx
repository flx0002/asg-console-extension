import React, { useState, useCallback } from 'react';
import { Card, Table, Switch, Tag, Button, Modal, message, Statistic, Row, Col, Space, Spin, Empty, Tooltip } from 'antd';
import { SafetyCertificateOutlined, EyeOutlined, StopOutlined, CheckCircleOutlined, WarningOutlined, ReloadOutlined } from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { getShadowAiStatus, setShadowAiMode, performShadowAiAction } from '@/services';
import { ShadowAiStatus, ShadowAiEntry } from '@/interfaces/shadow-ai';
import { useTranslation } from 'react-i18next';

const REFRESH_INTERVAL = 30000;

const ShadowAiRoutePage: React.FC = () => {
  const { t } = useTranslation();
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  const { data: statusList, loading, error, refresh } = useRequest(() => getShadowAiStatus(), {
    pollingInterval: REFRESH_INTERVAL,
    pollingWhenHidden: false,
    onError: () => {},
  });

  const handleModeSwitch = useCallback(async (routeName: string, currentMode: string) => {
    const newMode = currentMode === 'monitoring' ? 'enforcement' : 'monitoring';
    try {
      await setShadowAiMode({ routeName, mode: newMode as 'monitoring' | 'enforcement' });
      message.success(t('shadowAi.modeSwitchSuccess'));
      refresh();
    } catch {
      message.error(t('shadowAi.actionFailed'));
    }
  }, [t, refresh]);

  const handleAction = useCallback(async (routeName: string, consumerName: string, action: 'authorize' | 'block') => {
    const isAuthorize = action === 'authorize';
    const title = isAuthorize ? t('shadowAi.authorizeConfirm') : t('shadowAi.blockConfirm');
    const content = isAuthorize
      ? t('shadowAi.authorizeConfirmMsg', { consumer: consumerName })
      : t('shadowAi.blockConfirmMsg', { consumer: consumerName });

    Modal.confirm({
      title,
      content,
      okText: isAuthorize ? t('shadowAi.authorize') : t('shadowAi.block'),
      okType: isAuthorize ? 'primary' : 'danger',
      cancelText: t('misc.cancel'),
      onOk: async () => {
        const actionKey = `${routeName}-${consumerName}`;
        setActionLoading(actionKey);
        try {
          await performShadowAiAction({ routeName, consumerName, action });
          message.success(t('shadowAi.actionSuccess'));
          refresh();
        } catch {
          message.error(t('shadowAi.actionFailed'));
        } finally {
          setActionLoading(null);
        }
      },
    });
  }, [t, refresh]);

  // Compute summary statistics for route-based view
  const totalCalls = (statusList || []).reduce((sum, route) =>
    sum + route.shadowAiList.reduce((s, entry) => s + entry.requestCount, 0), 0);
  const shadowCount = (statusList || []).reduce((sum, route) =>
    sum + route.shadowAiList.filter(e => !e.authorized).length, 0);
  const authorizedCount = (statusList || []).reduce((sum, route) =>
    sum + route.shadowAiList.filter(e => e.authorized).length, 0);

  if (loading && !statusList) {
    return (
      <div style={{ width: '100%', height: '50vh', display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
        <Spin size="large" />
      </div>
    );
  }

  if (error && !statusList) {
    return (
      <div style={{ width: '100%', height: '50vh', display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
        <Empty description={t('shadowAi.noData')} />
      </div>
    );
  }

  const getColumns = (routeStatus: ShadowAiStatus) => [
    {
      title: t('shadowAi.consumer'),
      dataIndex: 'consumer',
      key: 'consumer',
      render: (text: string) => (!text || text === 'none') ? t('shadowAi.unknownSource') : text,
    },
    {
      title: t('shadowAi.model'),
      dataIndex: 'model',
      key: 'model',
    },
    {
      title: t('shadowAi.inputTokens'),
      dataIndex: 'inputTokens',
      key: 'inputTokens',
      render: (val: number) => val?.toLocaleString() ?? '-',
    },
    {
      title: t('shadowAi.outputTokens'),
      dataIndex: 'outputTokens',
      key: 'outputTokens',
      render: (val: number) => val?.toLocaleString() ?? '-',
    },
    {
      title: t('shadowAi.status'),
      dataIndex: 'authorized',
      key: 'authorized',
      render: (authorized: boolean) => authorized
        ? <Tag color="green" icon={<CheckCircleOutlined />}>{t('shadowAi.authorized')}</Tag>
        : <Tag color="red" icon={<WarningOutlined />}>{t('shadowAi.shadowAi')}</Tag>,
    },
    {
      title: t('shadowAi.action'),
      key: 'action',
      render: (_: unknown, record: ShadowAiEntry) => {
        const actionKey = `${routeStatus.routeName}-${record.consumer}`;
        const isLoading = actionLoading === actionKey;
        return record.authorized
          ? (
            <Button
              size="small"
              danger
              icon={<StopOutlined />}
              loading={isLoading}
              onClick={() => handleAction(routeStatus.routeName, record.consumer, 'block')}
            >
              {t('shadowAi.block')}
            </Button>
          )
          : (
            <Button
              size="small"
              type="primary"
              icon={<SafetyCertificateOutlined />}
              loading={isLoading}
              onClick={() => handleAction(routeStatus.routeName, record.consumer, 'authorize')}
            >
              {t('shadowAi.authorize')}
            </Button>
          );
      },
    },
  ];

  return (
    <div style={{ padding: '0 0 24px' }}>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card>
            <Statistic
              title={t('shadowAi.totalAiCalls')}
              value={totalCalls}
              prefix={<EyeOutlined />}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title={t('shadowAi.shadowAiCount')}
              value={shadowCount}
              valueStyle={{ color: '#cf1322' }}
              prefix={<WarningOutlined />}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title={t('shadowAi.authorizedCount')}
              value={authorizedCount}
              valueStyle={{ color: '#3f8600' }}
              prefix={<CheckCircleOutlined />}
            />
          </Card>
        </Col>
      </Row>

      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={refresh} loading={loading}>
            {t('shadowAi.refresh')}
          </Button>
          <span style={{ color: '#999', fontSize: 12 }}>{t('shadowAi.autoRefresh')}</span>
        </Space>
      </div>

      {(!statusList || statusList.length === 0) ? (
        <Card>
          <Empty description={t('shadowAi.noData')} />
        </Card>
      ) : (
        statusList.map((routeStatus) => {
          const isRouteEnforcement = routeStatus.mode === 'enforcement';
          return (
            <Card
              key={routeStatus.routeName}
              title={
                <Space>
                  <span>{t('shadowAi.routeName')}: {routeStatus.routeName}</span>
                  <Tag color={isRouteEnforcement ? 'red' : 'blue'}>
                    {isRouteEnforcement ? t('shadowAi.enforcementMode') : t('shadowAi.monitoringMode')}
                  </Tag>
                </Space>
              }
              extra={
                <Space>
                  <span style={{ fontSize: 13, color: '#666' }}>
                    {isRouteEnforcement ? t('shadowAi.enforcementDesc') : t('shadowAi.monitoringDesc')}
                  </span>
                  <Tooltip title={isRouteEnforcement ? t('shadowAi.enforcementMode') : t('shadowAi.monitoringMode')}>
                    <Switch
                      checked={isRouteEnforcement}
                      checkedChildren={t('shadowAi.enforcementMode')}
                      unCheckedChildren={t('shadowAi.monitoringMode')}
                      onChange={() => handleModeSwitch(routeStatus.routeName, routeStatus.mode)}
                    />
                  </Tooltip>
                </Space>
              }
              style={{ marginBottom: 16 }}
            >
              <Table
                dataSource={routeStatus.shadowAiList}
                columns={getColumns(routeStatus)}
                rowKey={(record) => `${record.consumer}-${record.model}`}
                pagination={false}
                size="small"
                locale={{ emptyText: t('shadowAi.noData') }}
              />
            </Card>
          );
        })
      )}
    </div>
  );
};

export default ShadowAiRoutePage;
