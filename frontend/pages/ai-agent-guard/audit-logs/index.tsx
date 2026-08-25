import React, { useState, useEffect, useRef } from 'react';
import {
  Card, Table, Tag, Space, Button, Tooltip, Badge, Empty,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { getAgentGuardAuditLogs } from '@/services';
import { useTranslation } from 'react-i18next';

interface AuditLogEntry {
  sessionId: string;
  riskScore: number;
  requestCount: number;
  stepCount: number;
  violationCount: number;
  lastActiveTime: string;
  createdAt: string;
  ttl: number;
  source: string;
}

const AuditLogsPage: React.FC = () => {
  const { t } = useTranslation();
  const [auditLogs, setAuditLogs] = useState<AuditLogEntry[]>([]);
  const logRefreshRef = useRef<NodeJS.Timeout>();

  const { run: loadAuditLogs, loading: logsLoading, cancel: cancelAuditLogs } = useRequest(
    () => getAgentGuardAuditLogs(100),
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || [];
        setAuditLogs(Array.isArray(data) ? data : []);
      },
    },
  );

  useEffect(() => {
    loadAuditLogs();
    logRefreshRef.current = setInterval(() => loadAuditLogs(), 30000) as unknown as NodeJS.Timeout;
    return () => {
      if (logRefreshRef.current) clearInterval(logRefreshRef.current);
      cancelAuditLogs();
    };
  }, []);

  const auditLogColumns = [
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
      width: 100,
      sorter: (a: AuditLogEntry, b: AuditLogEntry) => (a.riskScore || 0) - (b.riskScore || 0),
      render: (v: number) => {
        const color = v >= 80 ? 'red' : v >= 50 ? 'orange' : 'green';
        return <Tag color={color}>{v ?? 0}</Tag>;
      },
    },
    {
      title: t('agentGuard.requestCount'),
      dataIndex: 'requestCount',
      key: 'requestCount',
      width: 90,
    },
    {
      title: t('agentGuard.stepCount'),
      dataIndex: 'stepCount',
      key: 'stepCount',
      width: 80,
    },
    {
      title: t('agentGuard.violations'),
      dataIndex: 'violationCount',
      key: 'violationCount',
      width: 80,
      render: (v: number) => v > 0 ? <Badge count={v} style={{ backgroundColor: '#ff4d4f' }} /> : 0,
    },
    {
      title: t('agentGuard.lastActiveTime'),
      dataIndex: 'lastActiveTime',
      key: 'lastActiveTime',
      width: 160,
      render: (v: string) => v || '-',
    },
    {
      title: t('agentGuard.createdAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (v: string) => v || '-',
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

  return (
    <div style={{ padding: '0 0 24px' }}>
      <Card>
        <div style={{ marginBottom: 16, textAlign: 'right' }}>
          <Button icon={<ReloadOutlined />} onClick={() => loadAuditLogs()} loading={logsLoading}>
            {t('agentGuard.refresh')}
          </Button>
        </div>
        <Table
          dataSource={auditLogs}
          columns={auditLogColumns}
          rowKey="sessionId"
          size="small"
          loading={logsLoading}
          pagination={{ pageSize: 20 }}
          locale={{ emptyText: <Empty description={t('agentGuard.noLogData')} /> }}
        />
      </Card>
    </div>
  );
};

export default AuditLogsPage;
