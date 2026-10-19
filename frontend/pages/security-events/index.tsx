/* eslint-disable max-len, no-nested-ternary */
/**
 * 安全事件 · 统一审计入口（挂在「日志与审计」顶级菜单下）
 *
 * 设计原则（对齐用户 Q4 sign-off + 3 项 UI 反馈）：
 *  - 命名：不再叫"AI 内容安全事件"（含影子 AI/Agent 后已跨内容安全域），改叫"安全事件"
 *  - 归属：菜单从 AI 内容安全 移到「日志与审计」顶级下，与审计链日志平级
 *  - 筛选维度：按业务而非插件，6 类业务场景 + 全部
 *  - 展示：表格里"业务场景"列显示中文 Tag，与筛选项一致；不再暴露英文插件名（运维视角在"详情"里保留）
 *  - 排序：时间列显式声明 sortDirections，并在 useMemo 里做一次默认倒序（避免 antd 排序图标视觉被 scroll 遮挡导致看似"点了不排序"）
 *  - 数据源：audit-chain-service.getAuditSessions + getAuditLogs(recordType='security_event')
 */
import React, { useState, useEffect, useMemo, useCallback } from 'react';
import {
  Card, Table, Tag, Button, Space, Select, DatePicker, Input, Row, Col, Statistic,
  Tooltip, message, Typography, Popconfirm, Drawer, Descriptions,
} from 'antd';
import {
  ReloadOutlined, DownloadOutlined, SearchOutlined,
} from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import { getAuditSessions, getAuditLogs } from '@/services/audit-chain-service';

const { RangePicker } = DatePicker;

/**
 * 6 类业务场景（用户 sign-off）：
 *   prompt-injection  提示注入 / 越狱 / 系统提示窃取
 *   keyword-filter    关键词过滤（request-block 插件）
 *   content-compliance 内容合规（词库命中 / 免责注入）
 *   pii-leak          PII 泄露（ai-pii-guard 插件）
 *   waf-attack        WAF 攻击（http-waf / ai-waf 插件）
 *   agent-shadow      Agent 与影子 AI（ai-agent-guard / shadow-ai-detect）
 */
const BUSINESS_META: Record<string, { zh: string; en: string; color: string }> = {
  'prompt-injection': { zh: '提示注入', en: 'Prompt Injection', color: 'magenta' },
  'keyword-filter': { zh: '关键词过滤', en: 'Keyword Filter', color: 'orange' },
  'content-compliance': { zh: '内容合规', en: 'Content Compliance', color: 'red' },
  'pii-leak': { zh: 'PII 泄露', en: 'PII Leak', color: 'geekblue' },
  'waf-attack': { zh: 'WAF 攻击', en: 'WAF Attack', color: 'volcano' },
  'agent-shadow': { zh: 'Agent 与影子 AI', en: 'Agent & Shadow AI', color: 'purple' },
};

/** 事件类型 → 业务场景 + 归属插件（保留 plugin 供详情面板/CSV 导出使用） */
const EVENT_TYPE_META: Record<string, { business: string; plugin: string }> = {
  prompt_injection: { business: 'prompt-injection', plugin: 'ai-prompt-guard' },
  prompt_deny: { business: 'prompt-injection', plugin: 'ai-prompt-guard' },
  keyword_filter: { business: 'keyword-filter', plugin: 'request-block' },
  keyword_block: { business: 'keyword-filter', plugin: 'request-block' },
  compliance_hit: { business: 'content-compliance', plugin: 'ai-prompt-guard' },
  compliance_advisory: { business: 'content-compliance', plugin: 'ai-prompt-guard' },
  waf_block: { business: 'waf-attack', plugin: 'waf' },
  waf_detect: { business: 'waf-attack', plugin: 'waf' },
  pii_hit: { business: 'pii-leak', plugin: 'ai-pii-guard' },
  pii_masked: { business: 'pii-leak', plugin: 'ai-pii-guard' },
  shadow_ai: { business: 'agent-shadow', plugin: 'ai-agent-guard' },
  agent_anomaly: { business: 'agent-shadow', plugin: 'ai-agent-guard' },
};

const BUSINESS_FILTERS = [
  { value: 'all', label: '全部业务' },
  ...Object.entries(BUSINESS_META).map(([k, v]) => ({ value: k, label: v.zh })),
];

const DIRECTION_FILTERS = [
  { value: 'all', label: '全部方向' },
  { value: 'request', label: '请求方向' },
  { value: 'response', label: '响应方向' },
];

const ACTION_FILTERS = [
  { value: 'all', label: '全部处置' },
  { value: 'blocked', label: '已拦截' },
  { value: 'alerted', label: '仅告警' },
  { value: 'passed', label: '放行' },
];

interface EventRow {
  key: string;
  tsMs: number;
  time: string;
  sessionId: string;
  business: string;             // 业务场景 key
  plugin: string;               // 归属插件（详情/导出用）
  eventType: string;
  direction: 'request' | 'response' | '-';
  rule: string;
  snippet: string;
  sourceIp: string;
  action: 'blocked' | 'alerted' | 'passed';
  detail: any;
}

const SecurityEventsPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const zh = (i18n.language || 'zh').startsWith('zh');

  const [businessFilter, setBusinessFilter] = useState<string>('all');
  const [directionFilter, setDirectionFilter] = useState<string>('all');
  const [actionFilter, setActionFilter] = useState<string>('all');
  const [range, setRange] = useState<[any, any] | null>(null);
  const [keyword, setKeyword] = useState<string>('');
  const [sortOrder, setSortOrder] = useState<'ascend' | 'descend'>('descend');
  const [drawer, setDrawer] = useState<{ open: boolean; row: EventRow | null }>({ open: false, row: null });

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
          // 后端同时返回 timestamp（ISO 字符串，如 2026-10-15T02:53:25Z）与 timestamp_ms（epoch 毫秒）；
          // 旧代码 Number(r.timestamp) 对 ISO 字符串求值得 NaN → 时间列全部渲染为“-”。优先取数值 timestamp_ms，回退解析 ISO。
          const tsMs: number = Number(r.timestamp_ms) || new Date(r.timestamp).getTime() || 0;
          // 记录级方向兑底（事件未带 direction 时）：旧数据用 step_type。
          const recDir: EventRow['direction'] = r.step_type === 'user_input' ? 'request'
            : r.step_type === 'assistant_output' ? 'response' : '-';
          const action: EventRow['action'] = r.action === 'blocked' ? 'blocked'
            : r.action === 'alerted' ? 'alerted' : 'passed';
          events.forEach((e: any, eIdx: number) => {
            const meta = EVENT_TYPE_META[e.type] || { business: 'unknown', plugin: 'unknown' };
            // 方向：优先取后端事件级 direction=request|response（围栏插件按命中阶段写入），
            // 回退记录级 step_type，仍无则“-”。统一审计入口以前拿不到 direction 是因为后端从未写该字段。
            const dir: EventRow['direction'] = (e.direction === 'request' || e.direction === 'response')
              ? e.direction : recDir;
            out.push({
              key: `${sid}-${rIdx}-${eIdx}`,
              tsMs,
              time: tsMs ? new Date(tsMs).toLocaleString('zh-CN', { hour12: false }) : '-',
              sessionId: sid,
              business: meta.business,
              plugin: meta.plugin,
              eventType: e.type || '-',
              direction: dir,
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
      onError: () => message.error(t('securityEvents.loadFailed', '加载失败')),
    },
  );

  useEffect(() => { loadEvents(); }, []);

  const filteredSorted = useMemo(() => {
    const kw = keyword.toLowerCase();
    const list = rows.filter((r) => {
      if (businessFilter !== 'all' && r.business !== businessFilter) return false;
      if (directionFilter !== 'all' && r.direction !== directionFilter) return false;
      if (actionFilter !== 'all' && r.action !== actionFilter) return false;
      if (range && range[0] && range[1]) {
        const start = range[0].valueOf();
        const end = range[1].valueOf();
        if (r.tsMs < start || r.tsMs > end) return false;
      }
      if (kw) {
        const hit = [r.sessionId, r.eventType, r.rule, r.snippet, r.sourceIp]
          .some((v) => String(v).toLowerCase().includes(kw));
        if (!hit) return false;
      }
      return true;
    });
    list.sort((a, b) => (sortOrder === 'descend' ? b.tsMs - a.tsMs : a.tsMs - b.tsMs));
    return list;
  }, [rows, businessFilter, directionFilter, actionFilter, range, keyword, sortOrder]);

  const kpi = useMemo(() => {
    let blocked = 0; let alerted = 0; let passed = 0; let inbound = 0; let outbound = 0;
    filteredSorted.forEach((r) => {
      if (r.action === 'blocked') blocked += 1;
      else if (r.action === 'alerted') alerted += 1;
      else passed += 1;
      // 请求/响应命中只统计真实带方向的事件；旧代码把 inbound 以外全归 outbound，
      // 导致无方向（“-”）事件被误计为“响应方向命中”。
      if (r.direction === 'request') inbound += 1;
      else if (r.direction === 'response') outbound += 1;
    });
    return {
      total: filteredSorted.length,
      blocked, alerted, passed, inbound, outbound,
    };
  }, [filteredSorted]);

  const handleExport = useCallback(() => {
    if (!filteredSorted.length) {
      message.warning(t('securityEvents.noData', '当前筛选无数据'));
      return;
    }
    const header = ['time', 'business', 'plugin', 'eventType', 'direction', 'rule', 'action', 'sourceIp', 'sessionId', 'snippet'];
    const lines = [header.join(',')];
    filteredSorted.forEach((r) => {
      const bizLabel = zh ? (BUSINESS_META[r.business]?.zh || r.business) : (BUSINESS_META[r.business]?.en || r.business);
      const cells = [r.time, bizLabel, r.plugin, r.eventType, r.direction, r.rule, r.action, r.sourceIp, r.sessionId, r.snippet]
        .map((c) => `"${String(c).replace(/"/g, '""')}"`);
      lines.push(cells.join(','));
    });
    const blob = new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `asg-security-events-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
    message.success(t('securityEvents.exportSuccess', '已导出 CSV'));
  }, [filteredSorted, t, zh]);

  const columns = [
    {
      title: t('securityEvents.col.time', '时间'),
      dataIndex: 'time',
      key: 'time',
      width: 180,
      sorter: (a: EventRow, b: EventRow) => a.tsMs - b.tsMs,
      sortDirections: ['descend', 'ascend'] as any,
      sortOrder,
    },
    {
      title: t('securityEvents.col.business', '业务场景'),
      dataIndex: 'business',
      key: 'business',
      width: 140,
      render: (v: string) => {
        const meta = BUSINESS_META[v];
        if (!meta) return <Tag>{v}</Tag>;
        return <Tag color={meta.color}>{zh ? meta.zh : meta.en}</Tag>;
      },
    },
    {
      title: t('securityEvents.col.eventType', '事件类型'),
      dataIndex: 'eventType',
      key: 'eventType',
      width: 150,
      render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
    },
    {
      title: t('securityEvents.col.direction', '检测方向'),
      dataIndex: 'direction',
      key: 'direction',
      width: 110,
      render: (v: string) => (v === 'request'
        ? <Tag color="blue">请求</Tag>
        : v === 'response' ? <Tag color="cyan">响应</Tag> : <Tag>-</Tag>),
    },
    {
      title: t('securityEvents.col.rule', '命中规则'),
      dataIndex: 'rule',
      key: 'rule',
      width: 200,
      ellipsis: true,
    },
    {
      title: t('securityEvents.col.snippet', '内容摘要'),
      dataIndex: 'snippet',
      key: 'snippet',
      ellipsis: true,
      render: (v: string) => (<Tooltip title={v}><span>{v || '-'}</span></Tooltip>),
    },
    {
      title: t('securityEvents.col.sourceIp', '源 IP'),
      dataIndex: 'sourceIp',
      key: 'sourceIp',
      width: 130,
    },
    {
      title: t('securityEvents.col.action', '处置结果'),
      dataIndex: 'action',
      key: 'action',
      width: 110,
      render: (v: string) => (v === 'blocked'
        ? <Tag color="red">拦截</Tag>
        : v === 'alerted' ? <Tag color="orange">告警</Tag> : <Tag color="green">放行</Tag>),
    },
    {
      title: t('securityEvents.col.session', '会话 ID'),
      dataIndex: 'sessionId',
      key: 'sessionId',
      width: 200,
      ellipsis: true,
      render: (v: string) => <Typography.Text copyable>{v}</Typography.Text>,
    },
    {
      title: t('securityEvents.col.detail', '详情'),
      key: 'detail',
      width: 80,
      fixed: 'right' as const,
      render: (_: any, r: EventRow) => (
        <Button type="link" size="small" onClick={() => setDrawer({ open: true, row: r })}>
          {t('securityEvents.view', '查看')}
        </Button>
      ),
    },
  ];

  return (
    <div style={{ padding: '0 0 24px' }}>
      {/* Zone 1: KPI 总览 */}
      <Card
        title={t('securityEvents.title', '安全事件')}
        style={{ marginBottom: 16 }}
        extra={(
          <Space>
            <Popconfirm
              title={t('securityEvents.refreshConfirm', '重新拉取最新事件？')}
              onConfirm={loadEvents}
              okText={t('securityEvents.confirm', '确定')}
              cancelText={t('securityEvents.cancel', '取消')}
            >
              <Button icon={<ReloadOutlined />} loading={loading}>
                {t('securityEvents.refresh', '刷新')}
              </Button>
            </Popconfirm>
            <Button icon={<DownloadOutlined />} onClick={handleExport}>
              {t('securityEvents.export', '导出 CSV')}
            </Button>
          </Space>
        )}
      >
        <Row gutter={16}>
          <Col span={4}><Card size="small"><Statistic title={t('securityEvents.kpi.total', '事件总量')} value={kpi.total} /></Card></Col>
          <Col span={4}><Card size="small"><Statistic title={t('securityEvents.kpi.blocked', '已拦截')} value={kpi.blocked} valueStyle={{ color: '#cf1322' }} /></Card></Col>
          <Col span={4}><Card size="small"><Statistic title={t('securityEvents.kpi.alerted', '仅告警')} value={kpi.alerted} valueStyle={{ color: '#fa8c16' }} /></Card></Col>
          <Col span={4}><Card size="small"><Statistic title={t('securityEvents.kpi.passed', '放行')} value={kpi.passed} valueStyle={{ color: '#389e0d' }} /></Card></Col>
          <Col span={4}><Card size="small"><Statistic title={t('securityEvents.kpi.inbound', '请求方向命中')} value={kpi.inbound} /></Card></Col>
          <Col span={4}><Card size="small"><Statistic title={t('securityEvents.kpi.outbound', '响应方向命中')} value={kpi.outbound} /></Card></Col>
        </Row>
      </Card>

      {/* Zone 2: 筛选面板 */}
      <Card style={{ marginBottom: 16 }}>
        <Space wrap size="middle">
          <Select
            value={businessFilter}
            onChange={setBusinessFilter}
            style={{ width: 200 }}
            options={BUSINESS_FILTERS}
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
            placeholder={t('securityEvents.search', '按规则/会话/IP/内容搜索')}
            prefix={<SearchOutlined />}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            allowClear
            style={{ width: 280 }}
          />
          <Button
            onClick={() => {
              setBusinessFilter('all');
              setDirectionFilter('all');
              setActionFilter('all');
              setRange(null);
              setKeyword('');
              setSortOrder('descend');
            }}
          >
            {t('securityEvents.clearFilters', '清空筛选')}
          </Button>
        </Space>
      </Card>

      {/* Zone 3: 事件表 · 分页 · 排序 */}
      <Card>
        <Table
          rowKey="key"
          dataSource={filteredSorted}
          columns={columns as any}
          loading={loading}
          size="small"
          scroll={{ x: 1500 }}
          onChange={(_p: any, _f: any, sorter: any) => {
            if (sorter && sorter.field === 'time' && sorter.order) {
              setSortOrder(sorter.order);
            } else if (sorter && sorter.field === 'time' && !sorter.order) {
              setSortOrder('descend');
            }
          }}
          pagination={{
            defaultPageSize: 20,
            showSizeChanger: true,
            pageSizeOptions: ['20', '50', '100', '200'],
            showQuickJumper: true,
            showTotal: (total, range2) =>
              `${t('securityEvents.total', '共')} ${total} ${t('securityEvents.records', '条')} · ${t(
                'securityEvents.current', '当前第',
              )} ${range2[0]}-${range2[1]} ${t('securityEvents.range', '条')}`,
          }}
          locale={{ emptyText: t('securityEvents.noData', '暂无数据') }}
        />
      </Card>

      {/* 详情抽屉：完整事件对象 + 归属插件（英文原值，运维回溯用） */}
      <Drawer
        open={drawer.open}
        title={t('securityEvents.detailTitle', '事件详情')}
        width={600}
        onClose={() => setDrawer({ open: false, row: null })}
      >
        {drawer.row && (
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('securityEvents.col.time', '时间')}>{drawer.row.time}</Descriptions.Item>
            <Descriptions.Item label={t('securityEvents.col.business', '业务场景')}>
              <Tag color={BUSINESS_META[drawer.row.business]?.color}>
                {zh ? BUSINESS_META[drawer.row.business]?.zh : BUSINESS_META[drawer.row.business]?.en}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('securityEvents.col.plugin', '归属插件')}>
              <Typography.Text code>{drawer.row.plugin}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label={t('securityEvents.col.eventType', '事件类型')}>
              <Typography.Text code>{drawer.row.eventType}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label={t('securityEvents.col.direction', '检测方向')}>{drawer.row.direction}</Descriptions.Item>
            <Descriptions.Item label={t('securityEvents.col.rule', '命中规则')}>{drawer.row.rule}</Descriptions.Item>
            <Descriptions.Item label={t('securityEvents.col.action', '处置结果')}>{drawer.row.action}</Descriptions.Item>
            <Descriptions.Item label={t('securityEvents.col.sourceIp', '源 IP')}>{drawer.row.sourceIp}</Descriptions.Item>
            <Descriptions.Item label={t('securityEvents.col.session', '会话 ID')}>
              <Typography.Text copyable>{drawer.row.sessionId}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label={t('securityEvents.col.rawEvent', '原始事件对象')}>
              <pre style={{ maxHeight: 300, overflow: 'auto', margin: 0, fontSize: 12 }}>
                {JSON.stringify(drawer.row.detail, null, 2)}
              </pre>
            </Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>
    </div>
  );
};

export default SecurityEventsPage;
