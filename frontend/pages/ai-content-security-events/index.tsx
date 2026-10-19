/* eslint-disable max-len, no-nested-ternary */
/**
 * AI 内容安全事件 · 独立审计页
 *
 * 设计原则（三权分立）：
 *  - 配置页只配置：ai-prompt-guard / ai-security-guard / ai-waf / ai-pii-guard / ai-keyword-filter
 *    五页均已移除"最近检测日志"区块。
 *  - 审计页只审计：本页统一聚合五类插件产生的 security_event，支持 KPI/筛选/排序/分页/导出。
 *  - 数据源：audit-chain-service.getAuditSessions + getAuditLogs(recordType='security_event')。
 */
import React, { useState, useEffect, useMemo, useCallback } from 'react';
import {
  Card, Table, Tag, Button, Space, Select, DatePicker, Input, Row, Col, Statistic,
  Tooltip, message, Typography, Popconfirm,
} from 'antd';
import {
  ReloadOutlined, DownloadOutlined, SearchOutlined,
} from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import { getAuditSessions, getAuditLogs } from '@/services/audit-chain-service';

const { RangePicker } = DatePicker;

// 事件来源插件（与配置页一一对应）+ 事件类型登记
const EVENT_TYPE_META: Record<string, { plugin: string; color: string }> = {
  prompt_injection: { plugin: 'ai-prompt-guard', color: 'magenta' },
  prompt_deny: { plugin: 'ai-prompt-guard', color: 'magenta' },
  keyword_filter: { plugin: 'request-block', color: 'orange' },
  keyword_block: { plugin: 'request-block', color: 'orange' },
  compliance_hit: { plugin: 'ai-prompt-guard', color: 'red' },
  compliance_advisory: { plugin: 'ai-prompt-guard', color: 'gold' },
  waf_block: { plugin: 'waf', color: 'volcano' },
  waf_detect: { plugin: 'waf', color: 'volcano' },
  pii_hit: { plugin: 'ai-pii-guard', color: 'geekblue' },
  pii_masked: { plugin: 'ai-pii-guard', color: 'geekblue' },
  shadow_ai: { plugin: 'ai-agent-guard', color: 'purple' },
};

const PLUGIN_FILTERS = [
  { value: 'all', label: '全部插件' },
  { value: 'ai-prompt-guard', label: '提示注入防护 / 合规防护' },
  { value: 'request-block', label: '关键词过滤' },
  { value: 'waf', label: 'AI WAF' },
  { value: 'ai-pii-guard', label: 'PII 防护' },
  { value: 'ai-agent-guard', label: '影子 AI' },
];

const DIRECTION_FILTERS = [
  { value: 'all', label: '全部方向' },
  { value: 'request', label: '请求方向 (inbound)' },
  { value: 'response', label: '响应方向 (outbound)' },
];

const ACTION_FILTERS = [
  { value: 'all', label: '全部处置' },
  { value: 'blocked', label: '已拦截' },
  { value: 'alerted', label: '仅告警' },
  { value: 'passed', label: '放行' },
];

interface EventRow {
  key: string;
  tsMs: number;                // 原始时间戳，用于排序
  time: string;                // 展示时间
  sessionId: string;
  plugin: string;              // 归属插件（用于筛选）
  eventType: string;           // 事件类型（原样保留）
  direction: 'request' | 'response' | '-';
  rule: string;                // 命中规则名或 id
  snippet: string;             // 命中内容摘要
  sourceIp: string;
  action: 'blocked' | 'alerted' | 'passed';
  detail: any;                 // 原始事件对象，用于详情弹窗
}

const AiContentSecurityEventsPage: React.FC = () => {
  const { t } = useTranslation();

  // 筛选态
  const [pluginFilter, setPluginFilter] = useState<string>('all');
  const [directionFilter, setDirectionFilter] = useState<string>('all');
  const [actionFilter, setActionFilter] = useState<string>('all');
  const [range, setRange] = useState<[any, any] | null>(null);
  const [keyword, setKeyword] = useState<string>('');

  // 全量数据（前端过滤+排序+分页，避免多次回源；数据量大时改后端分页）
  const [rows, setRows] = useState<EventRow[]>([]);

  const { run: loadEvents, loading } = useRequest(
    async () => {
      const sessionsRes: any = await getAuditSessions();
      const sessions = sessionsRes?.list || sessionsRes?.data?.list || sessionsRes || [];
      if (!Array.isArray(sessions) || sessions.length === 0) return [] as EventRow[];
      const validSessions = sessions.filter(
        (s: any) => s?.sessionId && /^[a-zA-Z0-9_-]{1,128}$/.test(s.sessionId),
      );
      const results = await Promise.all(
        validSessions.map((s: any) =>
          getAuditLogs({
            sessionId: s.sessionId,
            recordType: 'security_event',
            pageSize: 100,
          }).catch(() => null)),
      );
      const out: EventRow[] = [];
      results.forEach((res: any, sIdx: number) => {
        if (!res) return;
        const records = res?.list || res?.data?.list || [];
        const sid = validSessions[sIdx]?.sessionId || '-';
        records.forEach((r: any, rIdx: number) => {
          const events = r.events || [];
          const tsMs: number = r.timestamp ?? 0;
          const direction: EventRow['direction'] = r.step_type === 'user_input' ? 'request'
            : r.step_type === 'assistant_output' ? 'response' : '-';
          const action: EventRow['action'] = r.action === 'blocked' ? 'blocked'
            : r.action === 'alerted' ? 'alerted' : 'passed';
          events.forEach((e: any, eIdx: number) => {
            const meta = EVENT_TYPE_META[e.type] || { plugin: 'unknown', color: 'default' };
            out.push({
              key: `${sid}-${rIdx}-${eIdx}`,
              tsMs,
              time: tsMs ? new Date(tsMs).toLocaleString('zh-CN', { hour12: false }) : '-',
              sessionId: sid,
              plugin: meta.plugin,
              eventType: e.type || '-',
              direction,
              rule: e.rule || e.rule_id || e.name || '-',
              snippet: (e.detail || e.snippet || '').toString().slice(0, 80),
              sourceIp: r.source_ip || '-',
              action,
              detail: e,
            });
          });
        });
      });
      return out;
    },
    {
      manual: true,
      onSuccess: (list) => setRows(list || []),
      onError: () => message.error(t('aiContentSec.loadFailed', '加载失败')),
    },
  );

  useEffect(() => { loadEvents(); }, []);

  // 派生：过滤后集合（用于 KPI 与表）
  const filtered = useMemo(() => {
    return rows.filter((r) => {
      if (pluginFilter !== 'all' && r.plugin !== pluginFilter) return false;
      if (directionFilter !== 'all' && r.direction !== directionFilter) return false;
      if (actionFilter !== 'all' && r.action !== actionFilter) return false;
      if (range && range[0] && range[1]) {
        const start = range[0].valueOf();
        const end = range[1].valueOf();
        if (r.tsMs < start || r.tsMs > end) return false;
      }
      if (keyword) {
        const kw = keyword.toLowerCase();
        const hit = [r.sessionId, r.eventType, r.rule, r.snippet, r.sourceIp]
          .some((v) => String(v).toLowerCase().includes(kw));
        if (!hit) return false;
      }
      return true;
    });
  }, [rows, pluginFilter, directionFilter, actionFilter, range, keyword]);

  const kpi = useMemo(() => {
    let blocked = 0;
    let alerted = 0;
    let passed = 0;
    let inbound = 0;
    filtered.forEach((r) => {
      if (r.action === 'blocked') blocked += 1;
      else if (r.action === 'alerted') alerted += 1;
      else passed += 1;
      if (r.direction === 'request') inbound += 1;
    });
    return {
      total: filtered.length,
      blocked,
      alerted,
      passed,
      inbound,
      outbound: filtered.length - inbound,
    };
  }, [filtered]);

  const handleExport = useCallback(() => {
    if (!filtered.length) {
      message.warning(t('aiContentSec.noLogData', '当前筛选无数据'));
      return;
    }
    const header = ['time', 'sessionId', 'plugin', 'eventType', 'direction', 'rule', 'action', 'sourceIp', 'snippet'];
    const lines = [header.join(',')];
    filtered.forEach((r) => {
      const cells = [r.time, r.sessionId, r.plugin, r.eventType, r.direction, r.rule, r.action, r.sourceIp, r.snippet]
        .map((c) => `"${String(c).replace(/"/g, '""')}"`);
      lines.push(cells.join(','));
    });
    const blob = new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `asg-content-security-events-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
    message.success(t('aiContentSec.exportSuccess', '已导出 CSV'));
  }, [filtered, t]);

  const columns = [
    {
      title: t('aiContentSec.events.col.time', '时间'),
      dataIndex: 'time',
      key: 'time',
      width: 180,
      defaultSortOrder: 'descend' as const,
      sorter: (a: EventRow, b: EventRow) => a.tsMs - b.tsMs,
    },
    {
      title: t('aiContentSec.events.col.plugin', '来源插件'),
      dataIndex: 'plugin',
      key: 'plugin',
      width: 160,
      render: (v: string, r: EventRow) => {
        const color = EVENT_TYPE_META[r.eventType]?.color || 'default';
        return <Tag color={color}>{v}</Tag>;
      },
    },
    {
      title: t('aiContentSec.events.col.eventType', '事件类型'),
      dataIndex: 'eventType',
      key: 'eventType',
      width: 150,
      render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
    },
    {
      title: t('aiContentSec.events.col.direction', '检测方向'),
      dataIndex: 'direction',
      key: 'direction',
      width: 110,
      render: (v: string) => (v === 'request'
        ? <Tag color="blue">请求</Tag>
        : v === 'response' ? <Tag color="cyan">响应</Tag> : <Tag>-</Tag>),
    },
    {
      title: t('aiContentSec.events.col.rule', '命中规则'),
      dataIndex: 'rule',
      key: 'rule',
      width: 200,
      ellipsis: true,
    },
    {
      title: t('aiContentSec.events.col.snippet', '内容摘要'),
      dataIndex: 'snippet',
      key: 'snippet',
      ellipsis: true,
      render: (v: string) => (
        <Tooltip title={v}><span>{v || '-'}</span></Tooltip>
      ),
    },
    {
      title: t('aiContentSec.events.col.sourceIp', '源 IP'),
      dataIndex: 'sourceIp',
      key: 'sourceIp',
      width: 130,
    },
    {
      title: t('aiContentSec.events.col.action', '处置结果'),
      dataIndex: 'action',
      key: 'action',
      width: 110,
      filters: [
        { text: '已拦截', value: 'blocked' },
        { text: '仅告警', value: 'alerted' },
        { text: '放行', value: 'passed' },
      ],
      onFilter: (value: any, r: EventRow) => r.action === value,
      render: (v: string) => (v === 'blocked'
        ? <Tag color="red">拦截</Tag>
        : v === 'alerted' ? <Tag color="orange">告警</Tag> : <Tag color="green">放行</Tag>),
    },
    {
      title: t('aiContentSec.events.col.session', '会话 ID'),
      dataIndex: 'sessionId',
      key: 'sessionId',
      width: 200,
      ellipsis: true,
      render: (v: string) => <Typography.Text copyable>{v}</Typography.Text>,
    },
  ];

  return (
    <div style={{ padding: '0 0 24px' }}>
      {/* Zone 1: KPI 总览 */}
      <Card
        title={t('aiContentSec.events.title', 'AI 内容安全事件')}
        style={{ marginBottom: 16 }}
        extra={(
          <Space>
            <Popconfirm
              title={t('aiContentSec.events.refreshConfirm', '重新拉取最新事件？')}
              onConfirm={loadEvents}
              okText={t('aiContentSec.confirm', '确定')}
              cancelText={t('aiContentSec.cancel', '取消')}
            >
              <Button icon={<ReloadOutlined />} loading={loading}>
                {t('aiContentSec.events.refresh', '刷新')}
              </Button>
            </Popconfirm>
            <Button icon={<DownloadOutlined />} onClick={handleExport}>
              {t('aiContentSec.events.export', '导出 CSV')}
            </Button>
            <Button type="link" onClick={() => window.location.assign('/config-versions')}>
              {t('configVersion.entry', '配置版本')}
            </Button>
          </Space>
        )}
      >
        <Row gutter={16}>
          <Col span={4}><Card size="small"><Statistic title="事件总量" value={kpi.total} /></Card></Col>
          <Col span={4}><Card size="small"><Statistic title="已拦截" value={kpi.blocked} valueStyle={{ color: '#cf1322' }} /></Card></Col>
          <Col span={4}><Card size="small"><Statistic title="仅告警" value={kpi.alerted} valueStyle={{ color: '#fa8c16' }} /></Card></Col>
          <Col span={4}><Card size="small"><Statistic title="放行" value={kpi.passed} valueStyle={{ color: '#389e0d' }} /></Card></Col>
          <Col span={4}><Card size="small"><Statistic title="请求方向命中" value={kpi.inbound} /></Card></Col>
          <Col span={4}><Card size="small"><Statistic title="响应方向命中" value={kpi.outbound} /></Card></Col>
        </Row>
        <Typography.Text type="secondary" style={{ display: 'block', marginTop: 12, fontSize: 12 }}>
          {t('aiContentSec.events.hint',
            '本页为 AI 内容安全五个配置页的统一审计入口（三权分立：配置员与审计员权限分离），支持按插件 / 方向 / 处置 / 时间范围 / 关键字 多维筛选与排序。')}
        </Typography.Text>
      </Card>

      {/* Zone 2: 筛选面板 */}
      <Card style={{ marginBottom: 16 }}>
        <Space wrap size="middle">
          <Select
            value={pluginFilter}
            onChange={setPluginFilter}
            style={{ width: 240 }}
            options={PLUGIN_FILTERS}
          />
          <Select
            value={directionFilter}
            onChange={setDirectionFilter}
            style={{ width: 160 }}
            options={DIRECTION_FILTERS}
          />
          <Select
            value={actionFilter}
            onChange={setActionFilter}
            style={{ width: 140 }}
            options={ACTION_FILTERS}
          />
          <RangePicker showTime onChange={(v) => setRange(v as any)} />
          <Input
            placeholder={t('aiContentSec.events.search', '按规则/会话/IP/内容搜索')}
            prefix={<SearchOutlined />}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            allowClear
            style={{ width: 280 }}
          />
          <Button
            onClick={() => {
              setPluginFilter('all');
              setDirectionFilter('all');
              setActionFilter('all');
              setRange(null);
              setKeyword('');
            }}
          >
            {t('aiContentSec.events.clearFilters', '清空筛选')}
          </Button>
        </Space>
      </Card>

      {/* Zone 3: 事件表 · 分页 · 排序 */}
      <Card>
        <Table
          rowKey="key"
          dataSource={filtered}
          columns={columns as any}
          loading={loading}
          size="small"
          scroll={{ x: 1400 }}
          pagination={{
            defaultPageSize: 20,
            showSizeChanger: true,
            pageSizeOptions: ['20', '50', '100', '200'],
            showQuickJumper: true,
            showTotal: (total, range2) =>
              `${t('aiContentSec.events.total', '共')} ${total} ${t('aiContentSec.events.records', '条')} · ${t(
                'aiContentSec.events.current',
                '当前第',
              )} ${range2[0]}-${range2[1]} ${t('aiContentSec.events.range', '条')}`,
          }}
          locale={{ emptyText: t('aiContentSec.noLogData', '暂无数据') }}
        />
      </Card>
    </div>
  );
};

export default AiContentSecurityEventsPage;
