import React, { useState, useCallback } from 'react';
import { Card, Table, Switch, Tag, Button, Modal, message, Statistic, Row, Col, Space, Spin, Empty, Tooltip } from 'antd';
import { SafetyCertificateOutlined, EyeOutlined, StopOutlined, WarningOutlined, ReloadOutlined } from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { getAiShadowStatus, setAiShadowMode, performAiShadowAction } from '@/services';
import { AiShadowStatus, AiShadowEntry } from '@/interfaces/ai-shadow';
import { useTranslation } from 'react-i18next';

const REFRESH_INTERVAL = 30000;

const AiShadowRoutePage: React.FC = () => {
  const { t } = useTranslation();
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  const { data: statusList, loading, error, refresh } = useRequest(() => getAiShadowStatus(), {
    pollingInterval: REFRESH_INTERVAL,
    pollingWhenHidden: false,
    onError: () => {},
  });

  const handleModeSwitch = useCallback(async (routeName: string, currentMode: string) => {
    const newMode = currentMode === 'monitoring' ? 'enforcement' : 'monitoring';
    try {
      await setAiShadowMode({ routeName, mode: newMode as 'monitoring' | 'enforcement' });
      message.success(t('aiShadow.modeSwitchSuccess'));
      refresh();
    } catch {
      message.error(t('aiShadow.actionFailed'));
    }
  }, [t, refresh]);

  const handleAction = useCallback(async (routeName: string, consumerName: string, action: 'authorize' | 'block') => {
    const isAuthorize = action === 'authorize';
    const title = isAuthorize ? t('aiShadow.authorizeConfirm') : t('aiShadow.blockConfirm');
    const content = isAuthorize
      ? t('aiShadow.authorizeConfirmMsg', { consumer: consumerName })
      : t('aiShadow.blockConfirmMsg', { consumer: consumerName });

    Modal.confirm({
      title,
      content,
      okText: isAuthorize ? t('aiShadow.authorize') : t('aiShadow.block'),
      okType: isAuthorize ? 'primary' : 'danger',
      cancelText: t('misc.cancel'),
      onOk: async () => {
        const actionKey = `${routeName}-${consumerName}`;
        setActionLoading(actionKey);
        try {
          await performAiShadowAction({ routeName, consumerName, action });
          message.success(t('aiShadow.actionSuccess'));
          refresh();
        } catch {
          message.error(t('aiShadow.actionFailed'));
        } finally {
          setActionLoading(null);
        }
      },
    });
  }, [t, refresh]);

  // Compute summary statistics for route-based view.
  // Authorized consumers are removed from the shadow AI list (IR-003), so
  // every entry here is an unauthorized access.
  const totalCalls = (statusList || []).reduce((sum, route) =>
    sum + route.aiShadowList.reduce((s, entry) => s + entry.requestCount, 0), 0);
  const shadowCount = (statusList || []).reduce((sum, route) =>
    sum + route.aiShadowList.length, 0);
  const blockedCount = (statusList || []).reduce((sum, route) =>
    sum + route.aiShadowList.filter(e => e.status === 'blocked').length, 0);

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
        <Empty description={t('aiShadow.noData')} />
      </div>
    );
  }

  const getColumns = (routeStatus: AiShadowStatus) => [
    {
      title: t('aiShadow.consumer'),
      dataIndex: 'consumer',
      key: 'consumer',
      render: (text: string) => ((!text || text === 'none') ? t('aiShadow.unknownSource') : text),
    },
    {
      title: t('aiShadow.model'),
      dataIndex: 'model',
      key: 'model',
    },
    {
      title: t('aiShadow.inputTokens'),
      dataIndex: 'inputTokens',
      key: 'inputTokens',
      render: (val: number) => val?.toLocaleString() ?? '-',
    },
    {
      title: t('aiShadow.outputTokens'),
      dataIndex: 'outputTokens',
      key: 'outputTokens',
      render: (val: number) => val?.toLocaleString() ?? '-',
    },
    {
      title: t('aiShadow.status'),
      dataIndex: 'consumer',
      key: 'status',
      render: () => <Tag color="red" icon={<WarningOutlined />}>{t('aiShadow.aiShadow')}</Tag>,
    },
    {
      title: t('aiShadow.action'),
      key: 'action',
      render: (_: unknown, record: AiShadowEntry) => {
        const actionKey = `${routeStatus.routeName}-${record.consumer}`;
        const isLoading = actionLoading === actionKey;
        return (
          <Button
            size="small"
            type="primary"
            icon={<SafetyCertificateOutlined />}
            loading={isLoading}
            onClick={() => handleAction(routeStatus.routeName, record.consumer, 'authorize')}
          >
            {t('aiShadow.authorize')}
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
              title={t('aiShadow.totalAiCalls')}
              value={totalCalls}
              prefix={<EyeOutlined />}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title={t('aiShadow.aiShadowCount')}
              value={shadowCount}
              valueStyle={{ color: '#cf1322' }}
              prefix={<WarningOutlined />}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title={t('aiShadow.blockedCount')}
              value={blockedCount}
              valueStyle={{ color: '#3f8600' }}
              prefix={<StopOutlined />}
            />
          </Card>
        </Col>
      </Row>

      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={refresh} loading={loading}>
            {t('aiShadow.refresh')}
          </Button>
          <span style={{ color: '#999', fontSize: 12 }}>{t('aiShadow.autoRefresh')}</span>
        </Space>
      </div>

      {(!statusList || statusList.length === 0) ? (
        <Card>
          <Empty description={t('aiShadow.noData')} />
        </Card>
      ) : (
        statusList.map((routeStatus) => {
          const isRouteEnforcement = routeStatus.mode === 'enforcement';
          return (
            <Card
              key={routeStatus.routeName}
              title={
                <Space>
                  <span>{t('aiShadow.routeName')}: {routeStatus.routeName}</span>
                  <Tag color={isRouteEnforcement ? 'red' : 'blue'}>
                    {isRouteEnforcement ? t('aiShadow.enforcementMode') : t('aiShadow.monitoringMode')}
                  </Tag>
                </Space>
              }
              extra={
                <Space>
                  <span style={{ fontSize: 13, color: '#666' }}>
                    {isRouteEnforcement ? t('aiShadow.enforcementDesc') : t('aiShadow.monitoringDesc')}
                  </span>
                  <Tooltip title={isRouteEnforcement ? t('aiShadow.enforcementMode') : t('aiShadow.monitoringMode')}>
                    <Switch
                      checked={isRouteEnforcement}
                      checkedChildren={t('aiShadow.enforcementMode')}
                      unCheckedChildren={t('aiShadow.monitoringMode')}
                      onChange={() => handleModeSwitch(routeStatus.routeName, routeStatus.mode)}
                    />
                  </Tooltip>
                </Space>
              }
              style={{ marginBottom: 16 }}
            >
              <Table
                dataSource={routeStatus.aiShadowList}
                columns={getColumns(routeStatus)}
                rowKey={(record) => `${record.consumer}-${record.model}`}
                pagination={false}
                size="small"
                locale={{ emptyText: t('aiShadow.noData') }}
              />
            </Card>
          );
        })
      )}
    </div>
  );
};

export default AiShadowRoutePage;
