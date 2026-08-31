import React, { useState, useEffect, useCallback } from 'react';
import { Card, Table, Switch, Tag, Button, message, Statistic, Row, Col, Space, Spin, Empty, Tooltip, Descriptions } from 'antd';
import { EyeOutlined, WarningOutlined, ReloadOutlined } from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { getShadowAiDetectedAccesses, setShadowAiDetectMode, getShadowAiDetectMode, getShadowAiDetectEvents } from '@/services';
import { ShadowAiDetectedAccess, ShadowAiDetectEvent } from '@/interfaces/shadow-ai';
import { useTranslation } from 'react-i18next';

const REFRESH_INTERVAL = 30000;
const EVENT_PAGE_SIZE = 10;

const pad2 = (n: number) => String(n).padStart(2, '0');

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

const ShadowAiDetectedPage: React.FC = () => {
  const { t } = useTranslation();
  const [detectMode, setDetectModeState] = useState<string>('monitoring');
  const [eventPage, setEventPage] = useState(0);
  const [eventSize, setEventSize] = useState(EVENT_PAGE_SIZE);

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

  const { data: eventPageData, loading: eventsLoading, refresh: refreshEvents } = useRequest(
    () => getShadowAiDetectEvents({ page: eventPage, size: eventSize }),
    {
      refreshDeps: [eventPage, eventSize],
      pollingInterval: REFRESH_INTERVAL,
      pollingWhenHidden: false,
      onError: () => {},
    },
  );

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

  const riskTag = (level?: string) => {
    const colorMap: Record<string, string> = { critical: '#cf1322', high: '#fa541c', medium: '#faad14', low: '#52c41a' };
    const labelMap: Record<string, string> = {
      critical: t('shadowAi.riskCritical'),
      high: t('shadowAi.riskHigh'),
      medium: t('shadowAi.riskMedium'),
      low: t('shadowAi.riskLow'),
    };
    return <Tag color={colorMap[level || ''] || 'default'}>{labelMap[level || ''] || level || '-'}</Tag>;
  };

  const statusTag = (status?: string) => {
    if (status === 'blocked') return <Tag color="red">{t('shadowAi.statusBlocked')}</Tag>;
    if (status === 'monitored') return <Tag color="blue">{t('shadowAi.statusMonitored')}</Tag>;
    if (status === 'allowed') return <Tag color="green">{t('shadowAi.statusAllowed')}</Tag>;
    return <Tag>{status || '-'}</Tag>;
  };

  const detailLabelMap: Record<string, string> = {
    protocol: t('shadowAi.eventProtocol'),
    method: t('shadowAi.eventMethod'),
    uri: t('shadowAi.eventUri'),
    srcPort: t('shadowAi.eventSrcPort'),
    dstPort: t('shadowAi.eventDstPort'),
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

  const eventColumns = [
    {
      title: t('shadowAi.eventColTime'),
      dataIndex: 'eventTime',
      key: 'eventTime',
      width: 170,
      render: (val: ShadowAiDetectEvent['eventTime']) => formatEventTime(val),
    },
    {
      title: t('shadowAi.eventColDomain'),
      dataIndex: 'domain',
      key: 'domain',
      render: (text: string) => <span style={{ fontFamily: 'monospace' }}>{text}</span>,
    },
    {
      title: t('shadowAi.eventColDetectType'),
      dataIndex: 'detectType',
      key: 'detectType',
      width: 150,
      render: (text: string) => <span style={{ fontFamily: 'monospace' }}>{text}</span>,
    },
    {
      title: t('shadowAi.eventColSource'),
      dataIndex: 'source',
      key: 'source',
      width: 90,
    },
    {
      title: t('shadowAi.eventColSrcIp'),
      dataIndex: 'srcIp',
      key: 'srcIp',
      width: 140,
      render: (text?: string) => <span style={{ fontFamily: 'monospace' }}>{text || '-'}</span>,
    },
    {
      title: t('shadowAi.detectedRiskLevel'),
      dataIndex: 'riskLevel',
      key: 'riskLevel',
      width: 90,
      render: (level: string) => riskTag(level),
    },
    {
      title: t('shadowAi.detectedStatus'),
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

      <Card
        title={t('shadowAi.eventDetailCardTitle')}
        extra={
          <Button icon={<ReloadOutlined />} onClick={refreshEvents} loading={eventsLoading}>
            {t('shadowAi.refresh')}
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
            expandedRowRender: (record: ShadowAiDetectEvent) => renderDetail(record.detail),
            rowExpandable: (record: ShadowAiDetectEvent) => !!record.detail,
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
            showTotal: (total: number) => t('shadowAi.eventTotalCount', { total }),
          }}
          locale={{ emptyText: t('shadowAi.eventNoData') }}
        />
      </Card>
    </div>
  );
};

export default ShadowAiDetectedPage;
