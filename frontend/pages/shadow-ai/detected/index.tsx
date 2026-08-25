import React, { useState, useEffect, useCallback } from 'react';
import { Card, Table, Switch, Tag, Button, message, Statistic, Row, Col, Space, Spin, Empty, Tooltip } from 'antd';
import { EyeOutlined, WarningOutlined, ReloadOutlined } from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { getShadowAiDetectedAccesses, setShadowAiDetectMode, getShadowAiDetectMode } from '@/services';
import { ShadowAiDetectedAccess } from '@/interfaces/shadow-ai';
import { useTranslation } from 'react-i18next';

const REFRESH_INTERVAL = 30000;

const ShadowAiDetectedPage: React.FC = () => {
  const { t } = useTranslation();
  const [detectMode, setDetectModeState] = useState<string>('monitoring');

  // Load current detect mode on mount
  useEffect(() => {
    getShadowAiDetectMode().then((mode) => {
      if (mode === 'monitoring' || mode === 'enforcement') {
        setDetectModeState(mode);
      }
    }).catch(() => {});
  }, []);

  const { data: detectedList, loading: detectedLoading, refresh: refreshDetected } = useRequest(() => getShadowAiDetectedAccesses(), {
    pollingInterval: REFRESH_INTERVAL,
    pollingWhenHidden: false,
    onError: () => {},
  });

  const handleDetectModeSwitch = useCallback(async (currentMode: string) => {
    const newMode = currentMode === 'monitoring' ? 'enforcement' : 'monitoring';
    try {
      await setShadowAiDetectMode(newMode as 'monitoring' | 'enforcement');
      setDetectModeState(newMode);
      message.success(t('shadowAi.modeSwitchSuccess'));
    } catch {
      message.error(t('shadowAi.actionFailed'));
    }
  }, [t]);

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
      title: t('shadowAi.detectedSni'),
      dataIndex: 'sni',
      key: 'sni',
      render: (text: string) => <span style={{ fontFamily: 'monospace' }}>{text}</span>,
    },
    {
      title: t('shadowAi.detectedCategory'),
      dataIndex: 'categoryLabel',
      key: 'categoryLabel',
    },
    {
      title: t('shadowAi.detectedRiskLevel'),
      dataIndex: 'riskLevel',
      key: 'riskLevel',
      render: (level: string) => {
        const colorMap: Record<string, string> = { critical: '#cf1322', high: '#fa541c', medium: '#faad14', low: '#52c41a' };
        const labelMap: Record<string, string> = {
          critical: t('shadowAi.riskCritical'),
          high: t('shadowAi.riskHigh'),
          medium: t('shadowAi.riskMedium'),
          low: t('shadowAi.riskLow'),
        };
        return <Tag color={colorMap[level] || 'default'}>{labelMap[level] || level}</Tag>;
      },
    },
    {
      title: t('shadowAi.detectedStatus'),
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => {
        const isBlocked = status === 'blocked';
        return (
          <Tag color={isBlocked ? 'red' : 'green'}>
            {isBlocked ? t('shadowAi.statusBlocked') : t('shadowAi.statusAllowed')}
          </Tag>
        );
      },
    },
    {
      title: t('shadowAi.detectedRequestCount'),
      dataIndex: 'requestCount',
      key: 'requestCount',
      render: (val: number) => val?.toLocaleString() ?? '-',
      sorter: (a: ShadowAiDetectedAccess, b: ShadowAiDetectedAccess) => a.requestCount - b.requestCount,
    },
  ];

  const isEnforcement = detectMode === 'enforcement';

  return (
    <div style={{ padding: '0 0 24px' }}>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card>
            <Statistic
              title={t('shadowAi.totalDetectedAccesses')}
              value={totalDetected}
              prefix={<EyeOutlined />}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title={t('shadowAi.criticalRiskAccesses')}
              value={criticalDetected}
              valueStyle={{ color: '#cf1322' }}
              prefix={<WarningOutlined />}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title={t('shadowAi.highRiskAccesses')}
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
            <span>{t('shadowAi.detectedCardTitle')}</span>
            <Tag color={isEnforcement ? 'red' : 'blue'}>
              {isEnforcement ? t('shadowAi.enforcementMode') : t('shadowAi.monitoringMode')}
            </Tag>
          </Space>
        }
        extra={
          <Space>
            <span style={{ fontSize: 13, color: '#666' }}>
              {isEnforcement ? t('shadowAi.detectEnforcementDesc') : t('shadowAi.detectMonitoringDesc')}
            </span>
            <Tooltip title={isEnforcement ? t('shadowAi.enforcementMode') : t('shadowAi.monitoringMode')}>
              <Switch
                checked={isEnforcement}
                checkedChildren={t('shadowAi.enforcementMode')}
                unCheckedChildren={t('shadowAi.monitoringMode')}
                onChange={() => handleDetectModeSwitch(detectMode)}
              />
            </Tooltip>
            <Button icon={<ReloadOutlined />} onClick={refreshDetected} loading={detectedLoading}>
              {t('shadowAi.refresh')}
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
          locale={{ emptyText: t('shadowAi.noDetectedData') }}
        />
      </Card>
    </div>
  );
};

export default ShadowAiDetectedPage;
