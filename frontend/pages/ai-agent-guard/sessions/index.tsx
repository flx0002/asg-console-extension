import React, { useState, useEffect, useRef } from 'react';
import {
  Card, Table, Tag, Space, Button, Progress, Statistic, Row, Col, Tooltip, Badge, Empty,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { getAgentGuardSessions } from '@/services';
import { useTranslation } from 'react-i18next';

interface SessionInfo {
  sessionId: string;
  riskScore: number;
  requestCount: number;
  stepCount: number;
  tokenCount: number;
  violationCount: number;
  lastActiveTime: string;
  createdAt: string;
  ttl: number;
}

const SessionsPage: React.FC = () => {
  const { t } = useTranslation();
  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  const logRefreshRef = useRef<NodeJS.Timeout>();

  const { run: loadSessions, loading: sessionsLoading, cancel: cancelSessions } = useRequest(
    () => getAgentGuardSessions(),
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || [];
        setSessions(Array.isArray(data) ? data : []);
      },
    },
  );

  useEffect(() => {
    loadSessions();
    logRefreshRef.current = setInterval(() => loadSessions(), 30000) as unknown as NodeJS.Timeout;
    return () => {
      if (logRefreshRef.current) clearInterval(logRefreshRef.current);
      cancelSessions();
    };
  }, []);

  const sessionColumns = [
    {
      title: t('agentGuard.sessionId'),
      dataIndex: 'sessionId',
      key: 'sessionId',
      ellipsis: true,
      width: 200,
      render: (v: string) => (
        <Tooltip title={v}>
          <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}</span>
        </Tooltip>
      ),
    },
    {
      title: t('agentGuard.riskScore'),
      dataIndex: 'riskScore',
      key: 'riskScore',
      width: 140,
      sorter: (a: SessionInfo, b: SessionInfo) => a.riskScore - b.riskScore,
      render: (v: number) => {
        let color = 'green';
        let status: 'success' | 'normal' | 'exception' = 'success';
        if (v >= 50) { color = 'orange'; status = 'normal'; }
        if (v >= 80) { color = 'red'; status = 'exception'; }
        return (
          <Space>
            <Progress percent={v} size="small" status={status} style={{ width: 80 }} />
            <Tag color={color}>{v}</Tag>
          </Space>
        );
      },
    },
    {
      title: t('agentGuard.requestCount'),
      dataIndex: 'requestCount',
      key: 'requestCount',
      width: 90,
      sorter: (a: SessionInfo, b: SessionInfo) => a.requestCount - b.requestCount,
    },
    {
      title: t('agentGuard.stepCount'),
      dataIndex: 'stepCount',
      key: 'stepCount',
      width: 80,
    },
    {
      title: t('agentGuard.tokenCount'),
      dataIndex: 'tokenCount',
      key: 'tokenCount',
      width: 100,
      render: (v: number) => v.toLocaleString(),
    },
    {
      title: t('agentGuard.violations'),
      dataIndex: 'violationCount',
      key: 'violationCount',
      width: 80,
      render: (v: number) => v > 0 ? <Tag color="red">{v}</Tag> : <Tag color="green">0</Tag>,
    },
    {
      title: t('agentGuard.ttl'),
      dataIndex: 'ttl',
      key: 'ttl',
      width: 80,
      render: (v: number) => {
        if (v < 0) return '-';
        const min = Math.floor(v / 60);
        const sec = v % 60;
        return min > 0 ? `${min}m${sec}s` : `${sec}s`;
      },
    },
  ];

  const stats = {
    totalSessions: sessions.length,
    activeSessions: sessions.filter(s => s.ttl > 0).length,
    highRiskSessions: sessions.filter(s => s.riskScore >= 80).length,
    totalViolations: sessions.reduce((sum, s) => sum + s.violationCount, 0),
  };

  return (
    <div style={{ padding: '0 0 24px' }}>
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card size="small"><Statistic title={t('agentGuard.totalSessions')} value={stats.totalSessions} /></Card>
        </Col>
        <Col span={6}>
          <Card size="small"><Statistic title={t('agentGuard.activeSessions')} value={stats.activeSessions} valueStyle={{ color: '#3f8600' }} /></Card>
        </Col>
        <Col span={6}>
          <Card size="small"><Statistic title={t('agentGuard.highRiskSessions')} value={stats.highRiskSessions} valueStyle={{ color: '#cf1322' }} /></Card>
        </Col>
        <Col span={6}>
          <Card size="small"><Statistic title={t('agentGuard.totalViolations')} value={stats.totalViolations} valueStyle={{ color: stats.totalViolations > 0 ? '#cf1322' : '#3f8600' }} /></Card>
        </Col>
      </Row>

      <Card>
        <div style={{ marginBottom: 16, textAlign: 'right' }}>
          <Button icon={<ReloadOutlined />} onClick={() => loadSessions()} loading={sessionsLoading}>
            {t('agentGuard.refresh')}
          </Button>
        </div>

        <Table
          dataSource={sessions}
          columns={sessionColumns}
          rowKey="sessionId"
          size="small"
          loading={sessionsLoading}
          pagination={{ pageSize: 10 }}
          locale={{ emptyText: <Empty description={t('agentGuard.noSessionData')} /> }}
        />
      </Card>
    </div>
  );
};

export default SessionsPage;
