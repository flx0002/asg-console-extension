import React, { useState, useEffect } from 'react';
import {
  Card, Tag, Space, Button, Input, Statistic, Row, Col, Empty, Timeline, Pagination, Spin, message,
} from 'antd';
import {
  SearchOutlined, ReloadOutlined,
} from '@ant-design/icons';
import { useRequest } from 'ahooks';
import {
  getSessionAuditChain, getSessionAuditStats,
} from '@/services';
import { useTranslation } from 'react-i18next';

interface ChainStep {
  stepIndex?: number;
  step?: number;
  timestamp?: string;
  time?: string;
  type?: string;
  recordType?: string;
  toolName?: string;
  tool?: string;
  riskBefore?: number;
  riskAfter?: number;
  tokenUsage?: number;
  token?: number;
  securityEvents?: any[];
  action?: string;
  status?: string;
  [key: string]: any;
}

interface ChainStats {
  totalSteps?: number;
  timeRange?: { start?: string; end?: string };
  blockedCount?: number;
  violationCount?: number;
  totalToken?: number;
}

const RECORD_TYPE_COLORS: Record<string, string> = {
  normal: 'green',
  blocked: 'red',
  degraded: 'orange',
  security_event: 'volcano',
};

const AuditChainPage: React.FC = () => {
  const { t } = useTranslation();
  const [searchInput, setSearchInput] = useState<string>('');
  const [sessionId, setSessionId] = useState<string>('');
  const [steps, setSteps] = useState<ChainStep[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [stats, setStats] = useState<ChainStats>({});
  const [page, setPage] = useState<number>(1);
  const [pageSize] = useState<number>(50);

  const { run: loadChain, loading: chainLoading } = useRequest(
    (sid: string, p: number, ps: number) => getSessionAuditChain({ sessionId: sid, page: p, pageSize: ps }),
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || {};
        const list = Array.isArray(data) ? data : (data?.list || data?.items || data?.steps || []);
        setSteps(list);
        setTotal(res?.total || data?.total || list.length);
      },
      onError: () => {
        setSteps([]);
        setTotal(0);
      },
    },
  );

  const { run: loadStats, loading: statsLoading } = useRequest(
    (sid: string) => getSessionAuditStats(sid),
    {
      manual: true,
      onSuccess: (res) => {
        setStats(res?.data || res || {});
      },
      onError: () => setStats({}),
    },
  );

  const doSearch = () => {
    const sid = searchInput.trim();
    if (!sid) {
      message.warning(t('auditChain.searchSession'));
      return;
    }
    setSessionId(sid);
    setPage(1);
    loadChain(sid, 1, pageSize);
    loadStats(sid);
  };

  // 翻页
  useEffect(() => {
    if (sessionId) {
      loadChain(sessionId, page, pageSize);
    }
  }, [page]);

  const timeRangeText = () => {
    const tr = stats.timeRange;
    if (!tr || (!tr.start && !tr.end)) return '-';
    return `${tr.start || '-'} ~ ${tr.end || '-'}`;
  };

  // 渲染时间线节点
  const renderTimelineItem = (step: ChainStep, idx: number) => {
    const before = step.riskBefore ?? step.risk_score_before ?? 0;
    const after = step.riskAfter ?? step.risk_score ?? 0;
    const delta = step.risk_increment ?? (after - before);
    const isHighRisk = delta > 20 || step.high_risk === true;
    let tp = step.type || step.recordType || (Array.isArray(step.record_types) ? step.record_types[0] : 'normal');
    if (typeof tp !== 'string') tp = 'normal';
    const tool = step.toolName || step.tool || step.tool_name || '-';
    const token = step.tokenUsage ?? step.token ?? ((step.input_token ?? 0) + (step.output_token ?? 0));
    const events = step.securityEvents || step.events || [];
    const eventList = Array.isArray(events) ? events : (events && typeof events === 'object' ? [events] : []);
    const st = step.status || step.action || 'passed';
    const isBlocked = st === 'blocked' || st === 'block';
    const stepNo = step.stepIndex ?? step.step ?? step.step_index ?? (idx + 1);

    const dot = (
      <div
        style={{
          width: 12, height: 12, borderRadius: '50%',
          background: isHighRisk ? '#ff4d4f' : isBlocked ? '#ff4d4f' : '#1677ff',
          boxShadow: isHighRisk ? '0 0 0 4px rgba(255,77,79,0.2)' : undefined,
          animation: isHighRisk ? 'audit-chain-pulse 1.5s infinite' : undefined,
        }}
      />
    );

    return (
      <Timeline.Item key={idx} dot={dot}>
        <div style={{ paddingBottom: 8 }}>
          <Space size={8} wrap align="center">
            <Tag color="blue">#{stepNo}</Tag>
            <span style={{ color: '#999', fontSize: 12 }}>{step.timestamp || step.time || '-'}</span>
            <Tag color={RECORD_TYPE_COLORS[tp] || 'default'}>
              {t(`auditChain.${tp === 'security_event' ? 'securityEvent' : tp}`)}
            </Tag>
            <span style={{ fontWeight: 500 }}>{tool}</span>
          </Space>
          <div style={{ marginTop: 6 }}>
            <Space size={16} wrap>
              <span>
                {t('auditChain.riskBefore')}: <b>{before}</b>
                <span style={{ margin: '0 4px' }}>→</span>
                {t('auditChain.riskAfter')}: <b style={{ color: isHighRisk ? '#ff4d4f' : undefined }}>{after}</b>
                {delta !== 0 && (
                  <Tag color={isHighRisk ? 'red' : delta > 0 ? 'orange' : 'green'} style={{ marginLeft: 8 }}>
                    {t('auditChain.riskIncrement')}: {delta > 0 ? '+' : ''}{delta}
                  </Tag>
                )}
              </span>
              <span>{t('auditChain.tokenUsage')}: {token.toLocaleString()}</span>
              <Tag color={isBlocked ? 'red' : 'green'}>
                {isBlocked ? t('auditChain.blocked') : t('auditChain.passed')}
              </Tag>
            </Space>
          </div>
          {eventList.length > 0 && (
            <div style={{ marginTop: 6 }}>
              <Space size={4} wrap>
                <span style={{ fontSize: 12, color: '#999' }}>{t('auditChain.securityEvents')}:</span>
                {eventList.map((e: any, i: number) => (
                  <Tag key={i} color="volcano" style={{ fontSize: 12 }}>{e.type || e.detail || JSON.stringify(e)}</Tag>
                ))}
              </Space>
            </div>
          )}
        </div>
      </Timeline.Item>
    );
  };

  const hasData = sessionId && steps.length > 0;

  return (
    <div style={{ padding: '0 0 24px' }}>
      <style>{`
        @keyframes audit-chain-pulse {
          0% { box-shadow: 0 0 0 0 rgba(255,77,79,0.6); }
          70% { box-shadow: 0 0 0 8px rgba(255,77,79,0); }
          100% { box-shadow: 0 0 0 0 rgba(255,77,79,0); }
        }
      `}</style>

      {/* 搜索栏 */}
      <Card style={{ marginBottom: 16 }}>
        <Space>
          <Input
            value={searchInput}
            onChange={e => setSearchInput(e.target.value)}
            placeholder={t('auditChain.searchSession')}
            style={{ width: 400 }}
            onPressEnter={doSearch}
            allowClear
          />
          <Button type="primary" icon={<SearchOutlined />} onClick={doSearch} loading={chainLoading}>
            {t('auditChain.search')}
          </Button>
          {sessionId && (
            <Button icon={<ReloadOutlined />} onClick={() => { loadChain(sessionId, page, pageSize); loadStats(sessionId); }} loading={chainLoading}>
              {t('auditChain.refresh')}
            </Button>
          )}
        </Space>
      </Card>

      {!sessionId ? (
        <Card>
          <Empty description={t('auditChain.searchSession')} />
        </Card>
      ) : (
        <>
          {/* 概览卡片 */}
          <Spin spinning={statsLoading}>
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col span={5}>
                <Card size="small"><Statistic title={t('auditChain.totalSteps')} value={stats.totalSteps ?? 0} /></Card>
              </Col>
              <Col span={7}>
                <Card size="small">
                  <Statistic title={t('auditChain.timeRange')} value={timeRangeText()} valueStyle={{ fontSize: 13 }} />
                </Card>
              </Col>
              <Col span={4}>
                <Card size="small"><Statistic title={t('auditChain.blockedCount')} value={stats.blockedCount ?? 0} valueStyle={{ color: (stats.blockedCount ?? 0) > 0 ? '#cf1322' : '#3f8600' }} /></Card>
              </Col>
              <Col span={4}>
                <Card size="small"><Statistic title={t('auditChain.violationCount')} value={stats.violationCount ?? 0} valueStyle={{ color: (stats.violationCount ?? 0) > 0 ? '#cf1322' : '#3f8600' }} /></Card>
              </Col>
              <Col span={4}>
                <Card size="small"><Statistic title={t('auditChain.totalToken')} value={stats.totalToken ?? 0} /></Card>
              </Col>
            </Row>
          </Spin>

          {/* 时间线 */}
          <Card title={t('auditChain.timeline')}>
            <Spin spinning={chainLoading}>
              {hasData ? (
                <>
                  <Timeline>{steps.map((s, i) => renderTimelineItem(s, i))}</Timeline>
                  <div style={{ textAlign: 'right', marginTop: 16 }}>
                    <Pagination
                      current={page}
                      pageSize={pageSize}
                      total={total}
                      onChange={(p) => setPage(p)}
                      showTotal={(tot) => `${t('auditChain.total')}: ${tot}`}
                      showSizeChanger={false}
                    />
                  </div>
                </>
              ) : (
                <Empty description={t('auditChain.noData')} />
              )}
            </Spin>
          </Card>
        </>
      )}
    </div>
  );
};

export default AuditChainPage;
