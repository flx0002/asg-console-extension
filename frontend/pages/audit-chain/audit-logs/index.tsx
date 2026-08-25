import React, { useState, useEffect, useRef } from 'react';
import {
  Card, Table, Tag, Space, Button, Tooltip, Badge, Empty, Progress, Statistic, Row, Col,
  Switch, Select, InputNumber, Divider, message, Modal,
} from 'antd';
import {
  ReloadOutlined, RollbackOutlined, DownloadOutlined, DeleteOutlined,
} from '@ant-design/icons';
import { useRequest } from 'ahooks';
import {
  getAuditChainConfig, updateAuditChainConfig, getAuditSessions, getAuditLogs,
  getAuditStats, clearSessionAuditLogs, exportAuditLogs,
} from '@/services';
import { useTranslation } from 'react-i18next';

interface SessionInfo {
  sessionId: string;
  mode?: string;
  riskScore: number;
  requestCount: number;
  stepCount: number;
  violationCount: number;
  lastActiveTime: string;
  createdAt: string;
}

interface AuditLogEntry {
  id?: string;
  time?: string;
  timestamp?: string;
  step?: number;
  stepIndex?: number;
  type?: string;
  recordType?: string;
  tool?: string;
  toolName?: string;
  riskBefore?: number;
  riskAfter?: number;
  token?: number;
  tokenUsage?: number;
  eventCount?: number;
  events?: any;
  status?: string;
  action?: string;
  [key: string]: any;
}

interface AuditConfig {
  enabled: boolean;
  recordTypes: {
    normal: boolean;
    blocked: boolean;
    degraded: boolean;
    security_event: boolean;
  };
  retention: {
    max_days: number;
  };
  payloadMode: string;
}

const defaultConfig: AuditConfig = {
  enabled: false,
  recordTypes: { normal: true, blocked: true, degraded: true, security_event: true },
  retention: { max_days: 30 },
  payloadMode: 'full',
};

const RECORD_TYPE_COLORS: Record<string, string> = {
  normal: 'green',
  blocked: 'red',
  degraded: 'orange',
  security_event: 'volcano',
};

const AuditLogsPage: React.FC = () => {
  const { t } = useTranslation();
  const [view, setView] = useState<'list' | 'detail'>('list');
  const [selectedSession, setSelectedSession] = useState<string>('');
  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  const [logs, setLogs] = useState<AuditLogEntry[]>([]);
  const [logsTotal, setLogsTotal] = useState<number>(0);
  const [config, setConfig] = useState<AuditConfig>({ ...defaultConfig });
  const [stats, setStats] = useState<any>({});
  const [filterType, setFilterType] = useState<string | undefined>(undefined);
  const [page, setPage] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(20);
  const logRefreshRef = useRef<NodeJS.Timeout>();

  // 加载审计配置
  const { run: loadConfig, loading: configLoading, cancel: cancelConfig } = useRequest(
    () => getAuditChainConfig(),
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || {};
        const enabled = data.enabled === true || data.enabled === 'true';
        setConfig({
          enabled,
          recordTypes: {
            normal: data.record_types_normal === true || data.record_types_normal === 'true' || (data.recordTypes?.normal ?? true),
            blocked: data.record_types_blocked === true || data.record_types_blocked === 'true' || (data.recordTypes?.blocked ?? true),
            degraded: data.record_types_degraded === true || data.record_types_degraded === 'true' || (data.recordTypes?.degraded ?? true),
            security_event: data.record_types_security_event === true || data.record_types_security_event === 'true' || (data.recordTypes?.security_event ?? true),
          },
          retention: { max_days: parseInt(data.max_days) || (data.retention?.max_days ?? 30) },
          payloadMode: data.payload_mode || (data.payloadMode ?? 'full'),
        });
      },
    },
  );

  // 加载 Session 列表
  const { run: loadSessions, loading: sessionsLoading, cancel: cancelSessions } = useRequest(
    () => getAuditSessions(),
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || [];
        setSessions(Array.isArray(data) ? data : []);
      },
    },
  );

  // 加载审计日志
  const { run: loadLogs, loading: logsLoading } = useRequest(
    (sid: string, p: number, ps: number, rt?: string) => getAuditLogs({
      sessionId: sid, page: p, pageSize: ps, recordType: rt,
    }),
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || [];
        const list = Array.isArray(data) ? data : (data?.list || data?.items || []);
        setLogs(list);
        setLogsTotal(res?.total || data?.total || list.length);
      },
    },
  );

  // 加载统计
  const { run: loadStats } = useRequest(
    (sid: string) => getAuditStats(sid),
    {
      manual: true,
      onSuccess: (res) => {
        setStats(res?.data || res || {});
      },
    },
  );

  // 保存配置
  const [saving, setSaving] = useState(false);
  const saveConfig = async (cfg: AuditConfig) => {
    setSaving(true);
    try {
      const flatConfig: Record<string, any> = {
        enabled: cfg.enabled,
        record_types_normal: cfg.recordTypes.normal,
        record_types_blocked: cfg.recordTypes.blocked,
        record_types_degraded: cfg.recordTypes.degraded,
        record_types_security_event: cfg.recordTypes.security_event,
        max_days: cfg.retention.max_days,
        payload_mode: cfg.payloadMode,
      };
      await updateAuditChainConfig(flatConfig);
      message.success(t('auditChain.saveSuccess'));
    } catch (e) {
      message.error(t('auditChain.saveFailed'));
    } finally {
      setSaving(false);
    }
  };

  // 删除 Session 审计日志
  const { run: deleteLogs } = useRequest(
    (sid: string) => clearSessionAuditLogs(sid),
    {
      manual: true,
      onSuccess: () => {
        message.success(t('auditChain.deleteSuccess'));
        if (view === 'detail' && selectedSession) {
          setView('list');
          setSelectedSession('');
          loadSessions();
        }
      },
    },
  );

  // 导出审计日志
  const { run: doExport, loading: exporting } = useRequest(
    (sid: string, format: string) => exportAuditLogs(sid, format),
    {
      manual: true,
      onSuccess: (res: any) => {
        const blob = res instanceof Blob ? res : new Blob([JSON.stringify(res)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `audit-${selectedSession}.${res instanceof Blob ? 'csv' : 'json'}`;
        a.click();
        URL.revokeObjectURL(url);
      },
    },
  );

  useEffect(() => {
    loadConfig();
    loadSessions();
    logRefreshRef.current = setInterval(() => loadSessions(), 30000) as unknown as NodeJS.Timeout;
    return () => {
      if (logRefreshRef.current) clearInterval(logRefreshRef.current);
      cancelConfig();
      cancelSessions();
    };
  }, []);

  // 进入详情
  const enterDetail = (sid: string) => {
    setSelectedSession(sid);
    setView('detail');
    setPage(1);
    setFilterType(undefined);
    loadLogs(sid, 1, pageSize, undefined);
    loadStats(sid);
  };

  // 返回列表
  const backToList = () => {
    setView('list');
    setSelectedSession('');
    setLogs([]);
    loadSessions();
  };

  // 筛选变化
  useEffect(() => {
    if (view === 'detail' && selectedSession) {
      loadLogs(selectedSession, page, pageSize, filterType);
    }
  }, [page, pageSize, filterType]);

  const updateRecordType = (key: keyof AuditConfig['recordTypes'], val: boolean) => {
    setConfig(prev => ({ ...prev, recordTypes: { ...prev.recordTypes, [key]: val } }));
  };

  // Session 列表列定义
  const sessionColumns = [
    {
      title: t('auditChain.sessionId'),
      dataIndex: 'sessionId',
      key: 'sessionId',
      ellipsis: true,
      width: 220,
      render: (v: string) => (
        <Tooltip title={v}>
          <span style={{ fontFamily: 'monospace', fontSize: 12, color: '#1677ff', cursor: 'pointer' }}>{v}</span>
        </Tooltip>
      ),
    },
    {
      title: t('auditChain.mode'),
      dataIndex: 'mode',
      key: 'mode',
      width: 100,
      render: (v: string) => {
        if (!v) return '-';
        const isDegraded = v === 'degraded' || v === 'degrade';
        return <Tag color={isDegraded ? 'orange' : 'green'}>{isDegraded ? t('auditChain.degraded') : t('auditChain.normal')}</Tag>;
      },
    },
    {
      title: t('auditChain.riskScore'),
      dataIndex: 'riskScore',
      key: 'riskScore',
      width: 160,
      sorter: (a: SessionInfo, b: SessionInfo) => (a.riskScore || 0) - (b.riskScore || 0),
      render: (v: number) => {
        const val = v || 0;
        let status: 'success' | 'normal' | 'exception' = 'success';
        if (val >= 50) status = 'normal';
        if (val >= 80) status = 'exception';
        return (
          <Space>
            <Progress percent={val} size="small" status={status} style={{ width: 90 }} />
            <Tag color={val >= 80 ? 'red' : val >= 50 ? 'orange' : 'green'}>{val}</Tag>
          </Space>
        );
      },
    },
    {
      title: t('auditChain.stepCount'),
      dataIndex: 'stepCount',
      key: 'stepCount',
      width: 80,
    },
    {
      title: t('auditChain.requestCount'),
      dataIndex: 'requestCount',
      key: 'requestCount',
      width: 90,
    },
    {
      title: t('auditChain.violationCount'),
      dataIndex: 'violationCount',
      key: 'violationCount',
      width: 90,
      render: (v: number) => v > 0 ? <Badge count={v} style={{ backgroundColor: '#ff4d4f' }} /> : <Tag color="green">0</Tag>,
    },
    {
      title: t('auditChain.lastActiveTime'),
      dataIndex: 'lastActiveTime',
      key: 'lastActiveTime',
      width: 170,
      render: (v: string) => v || '-',
    },
    {
      title: t('auditChain.createdAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 170,
      render: (v: string) => v || '-',
    },
  ];

  // 审计日志列定义
  const logColumns = [
    {
      title: t('auditChain.time'),
      dataIndex: 'time',
      key: 'time',
      width: 170,
      render: (_: any, r: AuditLogEntry) => r.time || r.timestamp || '-',
    },
    {
      title: t('auditChain.step'),
      dataIndex: 'step',
      key: 'step',
      width: 70,
      render: (_: any, r: AuditLogEntry) => r.step ?? r.step_index ?? r.stepIndex ?? '-',
    },
    {
      title: t('auditChain.type'),
      dataIndex: 'type',
      key: 'type',
      width: 110,
      render: (_: any, r: AuditLogEntry) => {
        let tp = r.type || r.recordType || (Array.isArray(r.record_types) ? r.record_types[0] : 'normal');
        if (typeof tp !== 'string') tp = 'normal';
        return <Tag color={RECORD_TYPE_COLORS[tp] || 'default'}>{t(`auditChain.${tp === 'security_event' ? 'securityEvent' : tp}`)}</Tag>;
      },
    },
    {
      title: t('auditChain.tool'),
      dataIndex: 'tool',
      key: 'tool',
      width: 140,
      ellipsis: true,
      render: (_: any, r: AuditLogEntry) => r.tool || r.tool_name || r.toolName || '-',
    },
    {
      title: t('auditChain.riskChange'),
      key: 'riskChange',
      width: 140,
      render: (_: any, r: AuditLogEntry) => {
        const before = r.riskBefore ?? r.risk_score_before ?? 0;
        const after = r.riskAfter ?? r.risk_score ?? 0;
        const delta = r.risk_increment ?? (after - before);
        const isHigh = delta > 20;
        return (
          <Space size={4}>
            <span>{before}</span>
            <span>→</span>
            <span style={{ color: isHigh ? '#ff4d4f' : undefined, fontWeight: isHigh ? 600 : 400 }}>{after}</span>
            {delta !== 0 && (
              <Tag color={isHigh ? 'red' : delta > 0 ? 'orange' : 'green'} style={{ marginLeft: 4 }}>
                {delta > 0 ? '+' : ''}{delta}
              </Tag>
            )}
          </Space>
        );
      },
    },
    {
      title: t('auditChain.token'),
      key: 'token',
      width: 90,
      render: (_: any, r: AuditLogEntry) => {
        const token = r.token ?? r.tokenUsage ?? ((r.input_token ?? 0) + (r.output_token ?? 0));
        return token.toLocaleString();
      },
    },
    {
      title: t('auditChain.eventCount'),
      key: 'eventCount',
      width: 90,
      render: (_: any, r: AuditLogEntry) => {
        const events = r.events;
        if (Array.isArray(events)) return events.length;
        if (events && typeof events === 'object') return 1;
        if (typeof events === 'number') return events;
        return r.eventCount ?? 0;
      },
    },
    {
      title: t('auditChain.status'),
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (_: any, r: AuditLogEntry) => {
        const st = r.status || r.action || 'passed';
        const isBlocked = st === 'blocked' || st === 'block';
        return <Tag color={isBlocked ? 'red' : 'green'}>{isBlocked ? t('auditChain.blocked') : t('auditChain.passed')}</Tag>;
      },
    },
  ];

  // 统计数据
  const listStats = {
    totalSessions: sessions.length,
    activeToday: sessions.filter(s => {
      if (!s.lastActiveTime) return false;
      const d = new Date(s.lastActiveTime);
      const now = new Date();
      return d.toDateString() === now.toDateString();
    }).length,
    blockedToday: sessions.reduce((sum, s) => sum + (s.violationCount || 0), 0),
    highRiskSessions: sessions.filter(s => (s.riskScore || 0) >= 80).length,
  };

  const handleDeleteSession = (sid: string) => {
    Modal.confirm({
      title: t('auditChain.confirmDelete'),
      content: t('auditChain.deleteSession') + ': ' + sid,
      okType: 'danger',
      onOk: () => deleteLogs(sid),
    });
  };

  const handleExport = (format: string) => {
    doExport(selectedSession, format);
  };

  // ========== 第一层：Session 列表 ==========
  if (view === 'list') {
    return (
      <div style={{ padding: '0 0 24px' }}>
        {/* 配置区 */}
        <Card style={{ marginBottom: 16 }} loading={configLoading} title={t('auditChain.config')}>
          <Space size="large" wrap align="center">
            <Space>
              <span style={{ fontWeight: 500 }}>{t('auditChain.enabled')}</span>
              <Switch
                checked={config.enabled}
                onChange={v => setConfig(prev => ({ ...prev, enabled: v }))}
                checkedChildren={t('auditChain.enabled')}
                unCheckedChildren={t('auditChain.enabled')}
              />
            </Space>
            <Divider type="vertical" />
            <Space>
              <span style={{ fontWeight: 500 }}>{t('auditChain.recordTypes')}:</span>
              <Space size="middle">
                <Space size={4}>
                  <Switch size="small" checked={config.recordTypes.normal} onChange={v => updateRecordType('normal', v)} />
                  <span>{t('auditChain.normal')}</span>
                </Space>
                <Space size={4}>
                  <Switch size="small" checked={config.recordTypes.blocked} onChange={v => updateRecordType('blocked', v)} />
                  <span>{t('auditChain.blocked')}</span>
                </Space>
                <Space size={4}>
                  <Switch size="small" checked={config.recordTypes.degraded} onChange={v => updateRecordType('degraded', v)} />
                  <span>{t('auditChain.degraded')}</span>
                </Space>
                <Space size={4}>
                  <Switch size="small" checked={config.recordTypes.security_event} onChange={v => updateRecordType('security_event', v)} />
                  <span>{t('auditChain.securityEvent')}</span>
                </Space>
              </Space>
            </Space>
            <Divider type="vertical" />
            <Space>
              <span style={{ fontWeight: 500 }}>{t('auditChain.payloadMode')}</span>
              <Select
                value={config.payloadMode}
                onChange={v => setConfig(prev => ({ ...prev, payloadMode: v }))}
                style={{ width: 110 }}
                options={[
                  { value: 'full', label: t('auditChain.payloadModeFull') },
                  { value: 'summary', label: t('auditChain.payloadModeSummary') },
                  { value: 'none', label: t('auditChain.payloadModeNone') },
                ]}
              />
              <Tooltip title={t('auditChain.payloadModeTip')}>
                <span style={{ color: '#999', cursor: 'help' }}>?</span>
              </Tooltip>
            </Space>
            <Divider type="vertical" />
            <Space>
              <span style={{ fontWeight: 500 }}>{t('auditChain.retention')}</span>
              <InputNumber
                value={config.retention.max_days}
                onChange={v => setConfig(prev => ({ ...prev, retention: { max_days: v ?? 30 } }))}
                min={1}
                max={365}
                addonAfter={t('auditChain.maxDays')}
                style={{ width: 140 }}
              />
            </Space>
            <Button type="primary" loading={saving} onClick={() => saveConfig(config)}>{t('auditChain.save')}</Button>
          </Space>
        </Card>

        {/* 统计卡片 */}
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={6}>
            <Card size="small"><Statistic title={t('auditChain.totalSessions')} value={listStats.totalSessions} /></Card>
          </Col>
          <Col span={6}>
            <Card size="small"><Statistic title={t('auditChain.activeToday')} value={listStats.activeToday} valueStyle={{ color: '#3f8600' }} /></Card>
          </Col>
          <Col span={6}>
            <Card size="small"><Statistic title={t('auditChain.blockedToday')} value={listStats.blockedToday} valueStyle={{ color: listStats.blockedToday > 0 ? '#cf1322' : '#3f8600' }} /></Card>
          </Col>
          <Col span={6}>
            <Card size="small"><Statistic title={t('auditChain.highRiskSessions')} value={listStats.highRiskSessions} valueStyle={{ color: listStats.highRiskSessions > 0 ? '#cf1322' : '#3f8600' }} /></Card>
          </Col>
        </Row>

        {/* Session 列表 */}
        <Card>
          <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={{ fontWeight: 500, fontSize: 15 }}>{t('auditChain.sessionList')}</span>
            <Button icon={<ReloadOutlined />} onClick={() => loadSessions()} loading={sessionsLoading}>
              {t('auditChain.refresh')}
            </Button>
          </div>
          <Table
            dataSource={sessions}
            columns={sessionColumns}
            rowKey="sessionId"
            size="small"
            loading={sessionsLoading}
            pagination={{ pageSize: 10 }}
            locale={{ emptyText: <Empty description={t('auditChain.noData')} /> }}
            onRow={(record: SessionInfo) => ({
              onClick: () => enterDetail(record.sessionId),
              style: { cursor: 'pointer' },
            })}
          />
        </Card>
      </div>
    );
  }

  // ========== 第二层：Session 审计日志详情 ==========
  return (
    <div style={{ padding: '0 0 24px' }}>
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
          <Space>
            <Button icon={<RollbackOutlined />} onClick={backToList}>{t('auditChain.backToList')}</Button>
            <span style={{ fontFamily: 'monospace', fontSize: 13, color: '#666' }}>{selectedSession}</span>
          </Space>
          <Space>
            <Button icon={<DownloadOutlined />} loading={exporting} onClick={() => handleExport('json')}>{t('auditChain.exportJson')}</Button>
            <Button icon={<DownloadOutlined />} loading={exporting} onClick={() => handleExport('csv')}>{t('auditChain.exportCsv')}</Button>
            <Button danger icon={<DeleteOutlined />} onClick={() => handleDeleteSession(selectedSession)}>{t('auditChain.deleteSession')}</Button>
          </Space>
        </div>

        {/* 筛选栏 */}
        <div style={{ marginBottom: 16 }}>
          <Space>
            <span style={{ fontWeight: 500 }}>{t('auditChain.filter')}:</span>
            <Select
              allowClear
              value={filterType}
              onChange={v => { setFilterType(v); setPage(1); }}
              placeholder={t('auditChain.allTypes')}
              style={{ width: 180 }}
              options={[
                { value: 'normal', label: t('auditChain.normal') },
                { value: 'blocked', label: t('auditChain.blocked') },
                { value: 'degraded', label: t('auditChain.degraded') },
                { value: 'security_event', label: t('auditChain.securityEvent') },
              ]}
            />
            {filterType && (
              <span style={{ color: '#faad14', fontSize: 12 }}>{t('auditChain.filterPaginationWarning')}</span>
            )}
          </Space>
        </div>

        <Table
          dataSource={logs}
          columns={logColumns}
          rowKey={(r: AuditLogEntry, i?: number) => r.id || String(i)}
          size="small"
          loading={logsLoading}
          expandable={{
            expandedRowRender: (record: AuditLogEntry) => (
              <pre style={{ margin: 0, padding: 12, background: '#fafafa', fontSize: 12, maxHeight: 300, overflow: 'auto' }}>
                {JSON.stringify(record, null, 2)}
              </pre>
            ),
            rowExpandable: () => true,
          }}
          pagination={{
            current: page,
            pageSize: pageSize,
            total: filterType ? undefined : logsTotal,
            showSizeChanger: true,
            showTotal: (total) => `${t('auditChain.total')}: ${total}`,
            onChange: (p, ps) => { setPage(p); setPageSize(ps); },
          }}
          locale={{ emptyText: <Empty description={t('auditChain.noData')} /> }}
        />
      </Card>
    </div>
  );
};

export default AuditLogsPage;
