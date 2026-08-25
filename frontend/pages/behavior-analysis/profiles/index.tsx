import React, { useEffect, useState } from 'react';
import {
  Card, Table, Tag, Space, Button, Empty, Drawer, Tabs, Descriptions, Progress, Tooltip,
  Input, Modal, message, Spin,
} from 'antd';
import {
  ReloadOutlined, EyeOutlined, NodeIndexOutlined,
} from '@ant-design/icons';
import { Column } from '@ant-design/charts';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'ice';
import {
  listUserProfiles, getUserProfile, listAgentProfiles, getAgentProfile,
  getBaseline, rebuildBaseline,
} from '@/services';

interface UserProfile {
  userId?: string;
  user_id?: string;
  userName?: string;
  user_name?: string;
  userDept?: string;
  user_dept?: string;
  userRole?: string;
  user_role?: string;
  totalSessions?: number;
  total_sessions?: number;
  totalRequests?: number;
  total_requests?: number;
  totalTokens?: number;
  total_tokens?: number;
  totalViolations?: number;
  total_violations?: number;
  totalAlerts?: number;
  total_alerts?: number;
  avgRiskScore?: number;
  avg_risk_score?: number;
  maxRiskScore?: number;
  max_risk_score?: number;
  riskLevel?: string;
  risk_level?: string;
  firstSeen?: number;
  first_seen?: number;
  lastSeen?: number;
  last_seen?: number;
  commonModels?: string;
  common_models?: string;
  commonTools?: string;
  common_tools?: string;
  commonAgents?: string;
  common_agents?: string;
  commonSourceIps?: string;
  common_source_ips?: string;
  commonAccessHours?: string;
  common_access_hours?: string;
}

interface AgentProfile {
  agentId?: string;
  agent_id?: string;
  agentOwner?: string;
  agent_owner?: string;
  agentType?: string;
  agent_type?: string;
  totalSessions?: number;
  total_sessions?: number;
  totalCalls?: number;
  total_calls?: number;
  totalTokens?: number;
  total_tokens?: number;
  avgChainLength?: number;
  avg_chain_length?: number;
  commonTools?: string;
  common_tools?: string;
  commonCallers?: string;
  common_callers?: string;
  commonModels?: string;
  common_models?: string;
  privilegedTools?: string;
  privileged_tools?: string;
  riskTags?: string;
  risk_tags?: string;
  supportsAutonomy?: boolean;
  supports_autonomy?: boolean;
  avgRiskScore?: number;
  avg_risk_score?: number;
  totalAlerts?: number;
  total_alerts?: number;
  firstSeen?: number;
  first_seen?: number;
  lastSeen?: number;
  last_seen?: number;
}

const RISK_LEVEL_COLORS: Record<string, string> = {
  low: 'green',
  medium: 'blue',
  medium_high: 'orange',
  high: 'red',
};

// 统一字段读取（后端可能返回 snake_case 或 camelCase）
const pick = (obj: any, ...keys: string[]) => {
  for (const k of keys) {
    if (obj && obj[k] !== undefined && obj[k] !== null) return obj[k];
  }
  return undefined;
};

const formatTime = (ts?: number | string): string => {
  if (ts === undefined || ts === null || ts === '') return '-';
  // Redis Hash 中 first_seen/last_seen 以字符串存储（如 "1782446400000"），
  // new Date("1782446400000") 在多数引擎返回 Invalid Date → NaN，需先 Number()
  const d = new Date(Number(ts));
  if (isNaN(d.getTime())) return '-';
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};

const parseJsonArray = (val: any): any[] => {
  if (!val) return [];
  if (Array.isArray(val)) return val;
  if (typeof val === 'string') {
    try {
      const parsed = JSON.parse(val);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }
  return [];
};

const BehaviorProfilesPage: React.FC = () => {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const initialTab = searchParams.get('tab') === 'agent' ? 'agent' : 'user';

  const [activeTab, setActiveTab] = useState<'user' | 'agent'>(initialTab);
  const [userProfiles, setUserProfiles] = useState<UserProfile[]>([]);
  const [agentProfiles, setAgentProfiles] = useState<AgentProfile[]>([]);
  const [userPage, setUserPage] = useState<number>(1);
  const [agentPage, setAgentPage] = useState<number>(1);
  const [pageSize] = useState<number>(20);

  const [drawerVisible, setDrawerVisible] = useState<boolean>(false);
  const [currentUser, setCurrentUser] = useState<UserProfile | null>(null);
  const [currentAgent, setCurrentAgent] = useState<AgentProfile | null>(null);
  const [userBaseline, setUserBaseline] = useState<any>(null);
  const [agentBaseline, setAgentBaseline] = useState<any>(null);

  // 加载用户画像列表
  const { run: loadUsers, loading: usersLoading } = useRequest(
    () => listUserProfiles({ page: userPage, pageSize }),
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || [];
        const list = Array.isArray(data) ? data : (data?.list || data?.items || []);
        setUserProfiles(list);
      },
    },
  );

  // 加载智能体画像列表
  const { run: loadAgents, loading: agentsLoading } = useRequest(
    () => listAgentProfiles({ page: agentPage, pageSize }),
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || [];
        const list = Array.isArray(data) ? data : (data?.list || data?.items || []);
        setAgentProfiles(list);
      },
    },
  );

  // 加载用户画像详情
  const { run: loadUserDetail, loading: userDetailLoading } = useRequest(
    (userId: string) => getUserProfile(userId),
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || {};
        setCurrentUser(data);
        setUserBaseline(null);
        setDrawerVisible(true);
        loadUserBaseline(data.userId || data.user_id);
      },
    },
  );

  // 加载智能体画像详情
  const { run: loadAgentDetail, loading: agentDetailLoading } = useRequest(
    (agentId: string) => getAgentProfile(agentId),
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || {};
        setCurrentAgent(data);
        setAgentBaseline(null);
        setDrawerVisible(true);
        loadAgentBaseline(data.agentId || data.agent_id);
      },
    },
  );

  // 加载用户基线
  const { run: loadUserBaseline } = useRequest(
    (userId: string) => getBaseline('user', userId),
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || null;
        setUserBaseline(data && !Array.isArray(data) ? data : null);
      },
    },
  );

  // 加载智能体基线
  const { run: loadAgentBaseline } = useRequest(
    (agentId: string) => getBaseline('agent', agentId),
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || null;
        setAgentBaseline(data && !Array.isArray(data) ? data : null);
      },
    },
  );

  // 重建基线
  const { run: doRebuildBaseline, loading: rebuilding } = useRequest(
    (entityType: string, entityId: string) => rebuildBaseline(entityType, entityId),
    {
      manual: true,
      onSuccess: () => {
        message.success(t('behaviorAnalysis.baseline.rebuildSuccess'));
        if (activeTab === 'user' && currentUser) {
          const uid = pick(currentUser, 'userId', 'user_id');
          loadUserBaseline(uid);
        } else if (currentAgent) {
          const aid = pick(currentAgent, 'agentId', 'agent_id');
          loadAgentBaseline(aid);
        }
      },
    },
  );

  useEffect(() => {
    if (activeTab === 'user') loadUsers();
    else loadAgents();
  }, [activeTab, userPage, agentPage]);

  // 处理 URL 参数（从仪表盘跳转）
  useEffect(() => {
    const tab = searchParams.get('tab');
    const userId = searchParams.get('userId');
    const agentId = searchParams.get('agentId');
    if (tab === 'agent') {
      setActiveTab('agent');
      if (agentId) loadAgentDetail(agentId);
    } else if (tab === 'user' || userId) {
      setActiveTab('user');
      if (userId) loadUserDetail(userId);
    }
    // 清理 URL 参数避免重复触发
    if (userId || agentId) {
      setSearchParams({});
    }
  }, []);

  const handleRebuildBaseline = () => {
    Modal.confirm({
      title: t('behaviorAnalysis.baseline.rebuild'),
      content: t('behaviorAnalysis.baseline.rebuildConfirm'),
      onOk: () => {
        if (activeTab === 'user' && currentUser) {
          doRebuildBaseline('user', pick(currentUser, 'userId', 'user_id'));
        } else if (currentAgent) {
          doRebuildBaseline('agent', pick(currentAgent, 'agentId', 'agent_id'));
        }
      },
    });
  };

  // 用户列定义
  const userColumns = [
    {
      title: t('behaviorAnalysis.profile.user'),
      key: 'user',
      ellipsis: true,
      render: (_: any, r: UserProfile) => {
        const uid = pick(r, 'userId', 'user_id');
        const name = pick(r, 'userName', 'user_name');
        return (
          <Tooltip title={uid}>
            <span>{name || uid || '-'}</span>
          </Tooltip>
        );
      },
    },
    {
      title: t('behaviorAnalysis.profile.userDept') + '/' + t('behaviorAnalysis.profile.userRole'),
      key: 'dept',
      width: 160,
      render: (_: any, r: UserProfile) => {
        const dept = pick(r, 'userDept', 'user_dept') || '-';
        const role = pick(r, 'userRole', 'user_role') || '-';
        return <Space size={4}><Tag>{dept}</Tag><Tag color="blue">{role}</Tag></Space>;
      },
    },
    {
      title: t('behaviorAnalysis.profile.sessions'),
      key: 'sessions',
      width: 80,
      render: (_: any, r: UserProfile) => pick(r, 'totalSessions', 'total_sessions') || 0,
    },
    {
      title: t('behaviorAnalysis.profile.requests'),
      key: 'requests',
      width: 80,
      render: (_: any, r: UserProfile) => pick(r, 'totalRequests', 'total_requests') || 0,
    },
    {
      title: t('behaviorAnalysis.profile.tokens'),
      key: 'tokens',
      width: 110,
      render: (_: any, r: UserProfile) => {
        const v = pick(r, 'totalTokens', 'total_tokens') || 0;
        return v.toLocaleString();
      },
    },
    {
      title: t('behaviorAnalysis.profile.violations'),
      key: 'violations',
      width: 80,
      render: (_: any, r: UserProfile) => {
        const v = pick(r, 'totalViolations', 'total_violations') || 0;
        return v > 0 ? <Tag color="red">{v}</Tag> : <Tag color="green">0</Tag>;
      },
    },
    {
      title: t('behaviorAnalysis.profile.alerts'),
      key: 'alerts',
      width: 80,
      render: (_: any, r: UserProfile) => {
        const v = pick(r, 'totalAlerts', 'total_alerts') || 0;
        return v > 0 ? <Tag color="red">{v}</Tag> : <Tag color="green">0</Tag>;
      },
    },
    {
      title: t('behaviorAnalysis.profile.avgRiskScore'),
      key: 'avgRisk',
      width: 140,
      render: (_: any, r: UserProfile) => {
        const val = Number(pick(r, 'avgRiskScore', 'avg_risk_score')) || 0;
        let status: 'success' | 'normal' | 'exception' = 'success';
        if (val >= 50) status = 'normal';
        if (val >= 80) status = 'exception';
        // 显示数值（Issue 7：原仅有绿色短条无值），Number() 兼容后端字符串存储
        return <Progress percent={Math.min(val, 100)} size="small" status={status} format={(p) => `${val.toFixed(1)}`} />;
      },
    },
    {
      title: t('behaviorAnalysis.profile.riskLevel'),
      key: 'riskLevel',
      width: 90,
      render: (_: any, r: UserProfile) => {
        const lvl = pick(r, 'riskLevel', 'risk_level') || 'low';
        return <Tag color={RISK_LEVEL_COLORS[lvl] || 'default'}>{lvl}</Tag>;
      },
    },
    {
      title: t('behaviorAnalysis.profile.lastSeen'),
      key: 'lastSeen',
      width: 145,
      render: (_: any, r: UserProfile) => formatTime(pick(r, 'lastSeen', 'last_seen')),
    },
    {
      title: t('behaviorAnalysis.alert.action'),
      key: 'action',
      width: 100,
      render: (_: any, r: UserProfile) => {
        const uid = pick(r, 'userId', 'user_id');
        return (
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => loadUserDetail(uid)}>
            {t('behaviorAnalysis.profile.viewDetail')}
          </Button>
        );
      },
    },
  ];

  // 智能体列定义
  const agentColumns = [
    {
      title: t('behaviorAnalysis.profile.agent'),
      key: 'agent',
      ellipsis: true,
      render: (_: any, r: AgentProfile) => {
        const aid = pick(r, 'agentId', 'agent_id');
        return <Tooltip title={aid}><span>{aid || '-'}</span></Tooltip>;
      },
    },
    {
      title: t('behaviorAnalysis.profile.agentOwner'),
      key: 'owner',
      width: 120,
      ellipsis: true,
      render: (_: any, r: AgentProfile) => pick(r, 'agentOwner', 'agent_owner') || '-',
    },
    {
      title: t('behaviorAnalysis.profile.agentType'),
      key: 'type',
      width: 90,
      render: (_: any, r: AgentProfile) => {
        const tp = pick(r, 'agentType', 'agent_type') || 'chat';
        return <Tag color="blue">{tp}</Tag>;
      },
    },
    {
      title: t('behaviorAnalysis.profile.sessions'),
      key: 'sessions',
      width: 80,
      render: (_: any, r: AgentProfile) => pick(r, 'totalSessions', 'total_sessions') || 0,
    },
    {
      title: t('behaviorAnalysis.profile.requests'),
      key: 'calls',
      width: 90,
      render: (_: any, r: AgentProfile) => pick(r, 'totalCalls', 'total_calls') || 0,
    },
    {
      title: t('behaviorAnalysis.profile.tokens'),
      key: 'tokens',
      width: 110,
      render: (_: any, r: AgentProfile) => {
        const v = pick(r, 'totalTokens', 'total_tokens') || 0;
        return v.toLocaleString();
      },
    },
    {
      title: t('behaviorAnalysis.profile.alerts'),
      key: 'alerts',
      width: 80,
      render: (_: any, r: AgentProfile) => {
        const v = pick(r, 'totalAlerts', 'total_alerts') || 0;
        return v > 0 ? <Tag color="red">{v}</Tag> : <Tag color="green">0</Tag>;
      },
    },
    {
      title: t('behaviorAnalysis.profile.avgRiskScore'),
      key: 'avgRisk',
      width: 140,
      render: (_: any, r: AgentProfile) => {
        const val = Number(pick(r, 'avgRiskScore', 'avg_risk_score')) || 0;
        let status: 'success' | 'normal' | 'exception' = 'success';
        if (val >= 50) status = 'normal';
        if (val >= 80) status = 'exception';
        return <Progress percent={Math.min(val, 100)} size="small" status={status} format={(p) => `${val.toFixed(1)}`} />;
      },
    },
    {
      title: t('behaviorAnalysis.profile.lastSeen'),
      key: 'lastSeen',
      width: 145,
      render: (_: any, r: AgentProfile) => formatTime(pick(r, 'lastSeen', 'last_seen')),
    },
    {
      title: t('behaviorAnalysis.alert.action'),
      key: 'action',
      width: 100,
      render: (_: any, r: AgentProfile) => {
        const aid = pick(r, 'agentId', 'agent_id');
        return (
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => loadAgentDetail(aid)}>
            {t('behaviorAnalysis.profile.viewDetail')}
          </Button>
        );
      },
    },
  ];

  // 渲染频率 Top 列表
  const renderFreqList = (val: any, label: string) => {
    const arr = parseJsonArray(val);
    if (arr.length === 0) return null;
    return (
      <div style={{ marginBottom: 12 }}>
        <div style={{ color: '#999', fontSize: 12, marginBottom: 4 }}>{label}</div>
        <Space wrap>
          {arr.slice(0, 5).map((item: any, idx: number) => {
            const name = typeof item === 'string' ? item : (item.name || item.model || item.tool || JSON.stringify(item));
            const count = typeof item === 'object' ? (item.count || item.freq || item.frequency) : null;
            return <Tag key={idx} color="blue">{name}{count !== null ? ` (${count})` : ''}</Tag>;
          })}
        </Space>
      </div>
    );
  };

  // 访问时段柱状图数据
  const accessHoursData = (() => {
    if (!currentUser) return [];
    const arr = parseJsonArray(pick(currentUser, 'commonAccessHours', 'common_access_hours'));
    return arr.map((v: any, idx: number) => ({
      hour: `${String(idx).padStart(2, '0')}h`,
      count: typeof v === 'number' ? v : (typeof v === 'object' ? (v.count || 0) : 0),
    }));
  })();

  const hourChartConfig = {
    data: accessHoursData,
    xField: 'hour',
    yField: 'count',
    height: 200,
    color: '#1677ff',
  };

  return (
    <div style={{ padding: '0 0 24px' }}>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontWeight: 500, fontSize: 15 }}>{t('behaviorAnalysis.profile.title')}</span>
        <Button
          icon={<ReloadOutlined />}
          onClick={() => activeTab === 'user' ? loadUsers() : loadAgents()}
          loading={usersLoading || agentsLoading}
        >
          {t('behaviorAnalysis.refresh')}
        </Button>
      </div>

      <Card>
        <Tabs
          activeKey={activeTab}
          onChange={(k) => setActiveTab(k as 'user' | 'agent')}
          items={[
            {
              key: 'user',
              label: t('behaviorAnalysis.profile.tabUser'),
              children: (
                <Table
                  dataSource={userProfiles}
                  columns={userColumns}
                  rowKey={(r: UserProfile) => pick(r, 'userId', 'user_id') || ''}
                  size="small"
                  loading={usersLoading}
                  scroll={{ x: 1200 }}
                  pagination={{
                    current: userPage,
                    pageSize: pageSize,
                    onChange: (p) => setUserPage(p),
                    showTotal: (tot) => `${t('behaviorAnalysis.total')}: ${tot}`,
                  }}
                  locale={{ emptyText: <Empty description={t('behaviorAnalysis.noData')} /> }}
                />
              ),
            },
            {
              key: 'agent',
              label: t('behaviorAnalysis.profile.tabAgent'),
              children: (
                <Table
                  dataSource={agentProfiles}
                  columns={agentColumns}
                  rowKey={(r: AgentProfile) => pick(r, 'agentId', 'agent_id') || ''}
                  size="small"
                  loading={agentsLoading}
                  scroll={{ x: 1200 }}
                  pagination={{
                    current: agentPage,
                    pageSize: pageSize,
                    onChange: (p) => setAgentPage(p),
                    showTotal: (tot) => `${t('behaviorAnalysis.total')}: ${tot}`,
                  }}
                  locale={{ emptyText: <Empty description={t('behaviorAnalysis.noData')} /> }}
                />
              ),
            },
          ]}
        />
      </Card>

      {/* 画像详情抽屉 */}
      <Drawer
        title={t('behaviorAnalysis.profile.viewDetail')}
        open={drawerVisible}
        onClose={() => setDrawerVisible(false)}
        width={760}
        destroyOnClose
        extra={
          <Button
            icon={<NodeIndexOutlined />}
            onClick={handleRebuildBaseline}
            loading={rebuilding}
          >
            {t('behaviorAnalysis.baseline.rebuild')}
          </Button>
        }
      >
        {activeTab === 'user' && currentUser ? (
          <Spin spinning={userDetailLoading}>
            <Descriptions column={2} bordered size="small" title={t('behaviorAnalysis.profile.basicInfo')}>
              <Descriptions.Item label={t('behaviorAnalysis.profile.userId')}>
                {pick(currentUser, 'userId', 'user_id') || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.userName')}>
                {pick(currentUser, 'userName', 'user_name') || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.userDept')}>
                {pick(currentUser, 'userDept', 'user_dept') || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.userRole')}>
                {pick(currentUser, 'userRole', 'user_role') || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.firstSeen')}>
                {formatTime(pick(currentUser, 'firstSeen', 'first_seen'))}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.lastSeen')}>
                {formatTime(pick(currentUser, 'lastSeen', 'last_seen'))}
              </Descriptions.Item>
            </Descriptions>

            <Descriptions column={3} bordered size="small" title={t('behaviorAnalysis.profile.statistics')} style={{ marginTop: 16 }}>
              <Descriptions.Item label={t('behaviorAnalysis.profile.sessions')}>
                {pick(currentUser, 'totalSessions', 'total_sessions') || 0}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.requests')}>
                {pick(currentUser, 'totalRequests', 'total_requests') || 0}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.tokens')}>
                {(pick(currentUser, 'totalTokens', 'total_tokens') || 0).toLocaleString()}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.violations')}>
                {pick(currentUser, 'totalViolations', 'total_violations') || 0}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.alerts')}>
                {pick(currentUser, 'totalAlerts', 'total_alerts') || 0}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.maxRiskScore')}>
                {pick(currentUser, 'maxRiskScore', 'max_risk_score') || 0}
              </Descriptions.Item>
            </Descriptions>

            <div style={{ marginTop: 16 }}>
              <div style={{ fontWeight: 500, marginBottom: 8 }}>{t('behaviorAnalysis.profile.behaviorProfile')}</div>
              {renderFreqList(pick(currentUser, 'commonModels', 'common_models'), t('behaviorAnalysis.profile.commonModels'))}
              {renderFreqList(pick(currentUser, 'commonTools', 'common_tools'), t('behaviorAnalysis.profile.commonTools'))}
              {renderFreqList(pick(currentUser, 'commonAgents', 'common_agents'), t('behaviorAnalysis.profile.commonAgents'))}
              {renderFreqList(pick(currentUser, 'commonSourceIps', 'common_source_ips'), t('behaviorAnalysis.profile.commonSourceIps'))}
              {accessHoursData.length > 0 && (
                <div style={{ marginTop: 12 }}>
                  <div style={{ color: '#999', fontSize: 12, marginBottom: 4 }}>{t('behaviorAnalysis.profile.commonAccessHours')}</div>
                  <Column {...hourChartConfig} />
                </div>
              )}
            </div>

            {/* 基线对比 */}
            {userBaseline && (
              <div style={{ marginTop: 16 }}>
                <div style={{ fontWeight: 500, marginBottom: 8 }}>{t('behaviorAnalysis.profile.baselineCompare')}</div>
                <Descriptions column={3} bordered size="small">
                  <Descriptions.Item label={t('behaviorAnalysis.profile.currentValue') + ' / ' + t('behaviorAnalysis.profile.baselineValue')}>
                    {t('behaviorAnalysis.profile.avgRiskScore')}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('behaviorAnalysis.baseline.sampleCount')}>
                    {pick(userBaseline, 'sampleCount', 'sample_count') || 0}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('behaviorAnalysis.profile.deviation')}>
                    {(() => {
                      const cur = pick(currentUser, 'avgRiskScore', 'avg_risk_score') || 0;
                      const base = pick(userBaseline, 'avgRiskScore', 'avg_risk_score') || 0;
                      const dev = cur - base;
                      return <Tag color={Math.abs(dev) > 20 ? 'red' : 'green'}>{dev > 0 ? '+' : ''}{dev.toFixed(2)}</Tag>;
                    })()}
                  </Descriptions.Item>
                </Descriptions>
              </div>
            )}
          </Spin>
        ) : activeTab === 'agent' && currentAgent ? (
          <Spin spinning={agentDetailLoading}>
            <Descriptions column={2} bordered size="small" title={t('behaviorAnalysis.profile.basicInfo')}>
              <Descriptions.Item label={t('behaviorAnalysis.profile.agentId')}>
                {pick(currentAgent, 'agentId', 'agent_id') || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.agentOwner')}>
                {pick(currentAgent, 'agentOwner', 'agent_owner') || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.agentType')}>
                {pick(currentAgent, 'agentType', 'agent_type') || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.supportsAutonomy')}>
                {pick(currentAgent, 'supportsAutonomy', 'supports_autonomy') ? <Tag color="orange">{t('behaviorAnalysis.profile.supportsAutonomy')}</Tag> : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.firstSeen')}>
                {formatTime(pick(currentAgent, 'firstSeen', 'first_seen'))}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.lastSeen')}>
                {formatTime(pick(currentAgent, 'lastSeen', 'last_seen'))}
              </Descriptions.Item>
            </Descriptions>

            <Descriptions column={3} bordered size="small" title={t('behaviorAnalysis.profile.statistics')} style={{ marginTop: 16 }}>
              <Descriptions.Item label={t('behaviorAnalysis.profile.sessions')}>
                {pick(currentAgent, 'totalSessions', 'total_sessions') || 0}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.requests')}>
                {pick(currentAgent, 'totalCalls', 'total_calls') || 0}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.tokens')}>
                {(pick(currentAgent, 'totalTokens', 'total_tokens') || 0).toLocaleString()}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.alerts')}>
                {pick(currentAgent, 'totalAlerts', 'total_alerts') || 0}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.profile.avgRiskScore')}>
                {pick(currentAgent, 'avgRiskScore', 'avg_risk_score') || 0}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.baseline.avgChainLength')}>
                {pick(currentAgent, 'avgChainLength', 'avg_chain_length') || 0}
              </Descriptions.Item>
            </Descriptions>

            <div style={{ marginTop: 16 }}>
              <div style={{ fontWeight: 500, marginBottom: 8 }}>{t('behaviorAnalysis.profile.behaviorProfile')}</div>
              {renderFreqList(pick(currentAgent, 'commonTools', 'common_tools'), t('behaviorAnalysis.profile.commonTools'))}
              {renderFreqList(pick(currentAgent, 'commonCallers', 'common_callers'), t('behaviorAnalysis.profile.commonCallers'))}
              {renderFreqList(pick(currentAgent, 'commonModels', 'common_models'), t('behaviorAnalysis.profile.commonModels'))}
              {renderFreqList(pick(currentAgent, 'privilegedTools', 'privileged_tools'), t('behaviorAnalysis.profile.privilegedTools'))}
              {renderFreqList(pick(currentAgent, 'riskTags', 'risk_tags'), t('behaviorAnalysis.profile.riskTags'))}
            </div>
          </Spin>
        ) : (
          <Empty description={t('behaviorAnalysis.noData')} />
        )}
      </Drawer>
    </div>
  );
};

export default BehaviorProfilesPage;
