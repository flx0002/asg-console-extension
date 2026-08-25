import React, { useEffect, useState } from 'react';
import {
  Card, Row, Col, Statistic, Table, Tag, Button, Empty, Spin, Progress, Tooltip,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { Line, Pie } from '@ant-design/charts';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'ice';
import { getBehaviorStats, getBehaviorTimeline } from '@/services';

const RISK_TYPE_KEYS: Record<string, string> = {
  identity_mismatch: 'behaviorAnalysis.alert.typeIdentityMismatch',
  behavior_rarity: 'behaviorAnalysis.alert.typeBehaviorRarity',
  chain_anomaly: 'behaviorAnalysis.alert.typeChainAnomaly',
  data_anomaly: 'behaviorAnalysis.alert.typeDataAnomaly',
  policy_anomaly: 'behaviorAnalysis.alert.typePolicyAnomaly',
  privilege_propagation: 'behaviorAnalysis.alert.typePrivilegePropagation',
};

const formatTime = (ts?: number | string): string => {
  if (ts === undefined || ts === null || ts === '') return '-';
  // Redis Hash 数值字段以字符串存储，需先 Number() 转换，否则 new Date("1782446400000") 返回 Invalid Date
  const d = new Date(Number(ts));
  if (isNaN(d.getTime())) return '-';
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};

const BehaviorDashboardPage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [stats, setStats] = useState<any>({});
  const [timeline, setTimeline] = useState<any[]>([]);
  const [topRiskUsers, setTopRiskUsers] = useState<any[]>([]);
  const [topRiskAgents, setTopRiskAgents] = useState<any[]>([]);

  // 加载统计
  const { run: loadStats, loading: statsLoading } = useRequest(
    () => getBehaviorStats(),
    {
      manual: true,
      onSuccess: (res) => {
        const raw = res?.data || res || {};
        // 适配后端响应（snake_case + kpi 嵌套）→ dashboard 期望的平铺 camelCase
        const kpi = raw.kpi || {};
        const alertsByLevel = raw.alerts_by_level || {};
        const highCount = Number(alertsByLevel.high || 0);
        const criticalCount = Number(alertsByLevel.critical || 0);
        const mapped = {
          activeUsers: raw.user_profiles ?? raw.activeUsers ?? 0,
          activeAgents: raw.agent_profiles ?? raw.activeAgents ?? 0,
          todayAlerts: raw.total_alerts ?? raw.todayAlerts ?? 0,
          highRiskSessions: highCount + criticalCount,
          alertTypeDistribution: raw.alerts_by_type ?? raw.alertTypeDistribution ?? {},
          // KPI 指标（方案 6.2，后端嵌入 kpi 子对象）
          alertArrivalRate: kpi.alertArrivalRate ?? raw.alertArrivalRate,
          blockInterceptRate: kpi.blockInterceptRate ?? raw.blockInterceptRate,
          anomalyDetectRate: kpi.anomalyDetectRate ?? raw.anomalyDetectRate,
          falsePositiveRate: kpi.falsePositiveRate ?? raw.falsePositiveRate,
          avgDispositionMinutes: kpi.avgDispositionMinutes ?? raw.avgDispositionMinutes,
          replaySuccessRate: kpi.replaySuccessRate ?? raw.replaySuccessRate,
          policyIterateDays: kpi.policyIterateDays ?? raw.policyIterateDays,
        };
        setStats(mapped);
        // Top 风险用户/智能体：后端返回 top_risk_users/top_risk_agents (snake_case)，
        // 字段为 entity_id/alert_count/avg_risk_score/user_name → 映射为前端表格期望的 camelCase
        const rawTopUsers = raw.top_risk_users ?? raw.topRiskUsers ?? [];
        setTopRiskUsers((Array.isArray(rawTopUsers) ? rawTopUsers : []).map((u: any) => ({
          userId: u.entity_id ?? u.userId ?? u.user_id,
          userName: u.user_name ?? u.userName ?? u.entity_id ?? u.userId,
          avgRiskScore: u.avg_risk_score ?? u.avgRiskScore ?? 0,
          totalAlerts: u.alert_count ?? u.totalAlerts ?? 0,
          totalSessions: u.total_sessions ?? u.totalSessions ?? 0,
        })));
        const rawTopAgents = raw.top_risk_agents ?? raw.topRiskAgents ?? [];
        setTopRiskAgents((Array.isArray(rawTopAgents) ? rawTopAgents : []).map((a: any) => ({
          agentId: a.entity_id ?? a.agentId ?? a.agent_id,
          avgRiskScore: a.avg_risk_score ?? a.avgRiskScore ?? 0,
          totalAlerts: a.alert_count ?? a.totalAlerts ?? 0,
        })));
      },
    },
  );

  // 加载风险趋势
  const { run: loadTimeline, loading: timelineLoading } = useRequest(
    () => getBehaviorTimeline({ granularity: 'hour' }),
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || [];
        // 后端 getBehaviorTimeline 返回 { list: [{ timestamp, count }] }
        const rawList = Array.isArray(data) ? data : (data?.list || data?.points || []);
        // 标准化字段：count → alertCount（供图表 yField 使用）
        setTimeline(rawList.map((p: any) => ({
          timestamp: p.timestamp,
          alertCount: p.count ?? p.alertCount ?? 0,
        })));
      },
    },
  );

  // 加载最近告警的逻辑已移除（与告警中心页面重复，方案要求仪表盘不再展示最近告警）

  const refreshAll = () => {
    loadStats();
    loadTimeline();
  };

  useEffect(() => {
    refreshAll();
  }, []);

  // 统计卡片
  const statCards = [
    { title: t('behaviorAnalysis.stats.activeUsers'), value: stats.activeUsers ?? 0, color: '#3f8600' },
    { title: t('behaviorAnalysis.stats.activeAgents'), value: stats.activeAgents ?? 0, color: '#1677ff' },
    { title: t('behaviorAnalysis.stats.todayAlerts'), value: stats.todayAlerts ?? 0, color: stats.todayAlerts > 0 ? '#cf1322' : '#3f8600' },
    { title: t('behaviorAnalysis.stats.highRiskSessions'), value: stats.highRiskSessions ?? 0, color: stats.highRiskSessions > 0 ? '#cf1322' : '#3f8600' },
  ];

  // KPI 指标（方案 6.2 KPI 体系）
  const kpiList = [
    { key: 'alertArrivalRate', label: t('behaviorAnalysis.stats.alertArrivalRate'), value: stats.alertArrivalRate, target: '>=95%' },
    { key: 'blockInterceptRate', label: t('behaviorAnalysis.stats.blockInterceptRate'), value: stats.blockInterceptRate, target: '>=90%' },
    { key: 'anomalyDetectRate', label: t('behaviorAnalysis.stats.anomalyDetectRate'), value: stats.anomalyDetectRate, target: '>=85%' },
    { key: 'falsePositiveRate', label: t('behaviorAnalysis.stats.falsePositiveRate'), value: stats.falsePositiveRate, target: '<=15%' },
    { key: 'avgDispositionMinutes', label: t('behaviorAnalysis.stats.avgDispositionMinutes'), value: stats.avgDispositionMinutes, target: '<30min' },
    { key: 'replaySuccessRate', label: t('behaviorAnalysis.stats.replaySuccessRate'), value: stats.replaySuccessRate, target: '>=99%' },
    { key: 'policyIterateDays', label: t('behaviorAnalysis.stats.policyIterateDays'), value: stats.policyIterateDays, target: '<=7d' },
  ];

  const renderKpiValue = (val: any, target: string) => {
    if (val === undefined || val === null) return <span style={{ color: '#999' }}>-</span>;
    const num = typeof val === 'number' ? val : parseFloat(val);
    if (isNaN(num)) return <span style={{ color: '#999' }}>{String(val)}</span>;
    const isPercent = target.includes('%') || target.includes('率') || (typeof val === 'string' && val.includes('%'));
    return (
      <Tooltip title={`${target}`}>
        <span style={{ fontSize: 18, fontWeight: 600 }}>{isPercent ? `${num.toFixed(1)}%` : num.toFixed(2)}</span>
      </Tooltip>
    );
  };

  // 风险趋势图配置（后端 timeline 返回告警计数，yField=alertCount）
  const lineConfig = {
    data: timeline,
    xField: 'timestamp',
    yField: 'alertCount',
    smooth: true,
    height: 240,
    // X 轴时间戳为毫秒数值，需格式化为日期展示（Issue 1：原显示 1782446400000）
    xAxis: {
      label: {
        formatter: (v: string) => formatTime(Number(v)),
      },
    },
    yAxis: { label: { formatter: (v: string) => v } },
    tooltip: {
      fields: ['alertCount', 'timestamp'],
      formatter: (datum: any) => ({
        name: t('behaviorAnalysis.alert.title'),
        value: `${datum?.alertCount ?? 0} @ ${formatTime(Number(datum?.timestamp))}`,
      }),
    },
    color: '#1677ff',
  };

  // 告警类型分布（饼图）
  const alertTypeData = (() => {
    const dist = stats.alertTypeDistribution;
    if (!dist || typeof dist !== 'object') return [];
    return Object.entries(dist).map(([type, count]) => ({
      type: t(RISK_TYPE_KEYS[type] || 'behaviorAnalysis.alert.riskType'),
      value: count as number,
    }));
  })();

  const pieConfig = {
    data: alertTypeData,
    angleField: 'value',
    colorField: 'type',
    height: 240,
    radius: 0.8,
    legend: { position: 'right' as const },
    label: { type: 'inner' as const, offset: '-30%', content: '{percentage}' },
  };

  // Top 风险用户列
  const userColumns = [
    {
      title: t('behaviorAnalysis.profile.user'),
      dataIndex: 'userId',
      key: 'userId',
      ellipsis: true,
      render: (v: string, r: any) => r.userName || v || '-',
    },
    {
      title: t('behaviorAnalysis.profile.avgRiskScore'),
      dataIndex: 'avgRiskScore',
      key: 'avgRiskScore',
      width: 130,
      render: (v: number) => {
        const val = v || 0;
        let status: 'success' | 'normal' | 'exception' = 'success';
        if (val >= 50) status = 'normal';
        if (val >= 80) status = 'exception';
        return <Progress percent={Math.min(val, 100)} size="small" status={status} />;
      },
    },
    {
      title: t('behaviorAnalysis.profile.alerts'),
      dataIndex: 'totalAlerts',
      key: 'totalAlerts',
      width: 80,
      render: (v: number) => (v > 0 ? <Tag color="red">{v}</Tag> : <Tag color="green">0</Tag>),
    },
    {
      title: t('behaviorAnalysis.profile.sessions'),
      dataIndex: 'totalSessions',
      key: 'totalSessions',
      width: 80,
    },
  ];

  // Top 风险智能体列
  const agentColumns = [
    {
      title: t('behaviorAnalysis.profile.agent'),
      dataIndex: 'agentId',
      key: 'agentId',
      ellipsis: true,
    },
    {
      title: t('behaviorAnalysis.profile.avgRiskScore'),
      dataIndex: 'avgRiskScore',
      key: 'avgRiskScore',
      width: 130,
      render: (v: number) => {
        const val = v || 0;
        let status: 'success' | 'normal' | 'exception' = 'success';
        if (val >= 50) status = 'normal';
        if (val >= 80) status = 'exception';
        return <Progress percent={Math.min(val, 100)} size="small" status={status} />;
      },
    },
    {
      title: t('behaviorAnalysis.profile.alerts'),
      dataIndex: 'totalAlerts',
      key: 'totalAlerts',
      width: 80,
      render: (v: number) => (v > 0 ? <Tag color="red">{v}</Tag> : <Tag color="green">0</Tag>),
    },
  ];

  return (
    <div style={{ padding: '0 0 24px' }}>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontWeight: 500, fontSize: 15 }}>{t('behaviorAnalysis.title')}</span>
        <Button icon={<ReloadOutlined />} onClick={refreshAll} loading={statsLoading || timelineLoading}>
          {t('behaviorAnalysis.refresh')}
        </Button>
      </div>

      {/* 统计卡片区 */}
      <Spin spinning={statsLoading}>
        <Row gutter={16} style={{ marginBottom: 16 }}>
          {statCards.map((card, idx) => (
            <Col span={6} key={idx}>
              <Card size="small">
                <Statistic title={card.title} value={card.value} valueStyle={{ color: card.color }} />
              </Card>
            </Col>
          ))}
        </Row>
      </Spin>

      {/* KPI 指标行 */}
      <Card size="small" title={t('behaviorAnalysis.stats.kpi')} style={{ marginBottom: 16 }} loading={statsLoading}>
        <Row gutter={[16, 16]}>
          {kpiList.map((kpi) => (
            <Col span={Math.floor(24 / kpiList.length)} key={kpi.key}>
              <div style={{ textAlign: 'center' }}>
                <div style={{ color: '#999', fontSize: 12, marginBottom: 4 }}>{kpi.label}</div>
                {renderKpiValue(kpi.value, kpi.target)}
                <div style={{ color: '#bbb', fontSize: 11 }}>{kpi.target}</div>
              </div>
            </Col>
          ))}
        </Row>
      </Card>

      {/* 风险趋势 + 告警类型分布 */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={14}>
          <Card title={t('behaviorAnalysis.stats.riskTrend')} size="small">
            <Spin spinning={timelineLoading}>
              {timeline.length > 0 ? (
                <Line {...lineConfig} />
              ) : (
                <Empty description={t('behaviorAnalysis.noData')} style={{ padding: 60 }} />
              )}
            </Spin>
          </Card>
        </Col>
        <Col span={10}>
          <Card title={t('behaviorAnalysis.stats.alertTypeDistribution')} size="small">
            <Spin spinning={statsLoading}>
              {alertTypeData.length > 0 ? (
                <Pie {...pieConfig} />
              ) : (
                <Empty description={t('behaviorAnalysis.noData')} style={{ padding: 60 }} />
              )}
            </Spin>
          </Card>
        </Col>
      </Row>

      {/* Top 风险用户 + 智能体 */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={12}>
          <Card title={t('behaviorAnalysis.stats.topRiskUsers')} size="small">
            <Table
              dataSource={topRiskUsers}
              columns={userColumns}
              rowKey="userId"
              size="small"
              pagination={false}
              loading={statsLoading}
              locale={{ emptyText: <Empty description={t('behaviorAnalysis.noData')} /> }}
              onRow={(record: any) => ({
                onClick: () => navigate(`/behavior-analysis/profiles?tab=user&userId=${record.userId}`),
                style: { cursor: 'pointer' },
              })}
            />
          </Card>
        </Col>
        <Col span={12}>
          <Card title={t('behaviorAnalysis.stats.topRiskAgents')} size="small">
            <Table
              dataSource={topRiskAgents}
              columns={agentColumns}
              rowKey="agentId"
              size="small"
              pagination={false}
              loading={statsLoading}
              locale={{ emptyText: <Empty description={t('behaviorAnalysis.noData')} /> }}
              onRow={(record: any) => ({
                onClick: () => navigate(`/behavior-analysis/profiles?tab=agent&agentId=${record.agentId}`),
                style: { cursor: 'pointer' },
              })}
            />
          </Card>
        </Col>
      </Row>

      {/* 最近告警区块已移除：与告警中心页面重复，避免数据双源维护 */}
    </div>
  );
};

export default BehaviorDashboardPage;
