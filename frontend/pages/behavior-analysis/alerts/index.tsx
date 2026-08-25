import React, { useEffect, useState } from 'react';
import {
  Card, Table, Tag, Space, Button, Empty, Drawer, Descriptions, Input, Select, DatePicker,
  Modal, Form, InputNumber, message, Tooltip, Alert, Spin,
} from 'antd';
import {
  ReloadOutlined, EyeOutlined, ExclamationCircleOutlined,
} from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'ice';
import { getAlerts, getAlertDetail, updateAlertDisposition } from '@/services';

interface AlertItem {
  alertId: string;
  timestamp?: number;
  riskType?: string;
  riskLevel?: string;
  riskScore?: number;
  userId?: string;
  userName?: string;
  agentId?: string;
  sessionId?: string;
  traceId?: string;
  title?: string;
  description?: string;
  status?: string;
  disposition?: string;
  evidence?: any;
  relatedLogs?: string[];
}

const { RangePicker } = DatePicker;

const RISK_LEVEL_COLORS: Record<string, string> = {
  low: 'blue',
  medium: 'orange',
  high: 'red',
  critical: 'magenta',
};

const RISK_TYPE_KEYS: Record<string, string> = {
  identity_mismatch: 'behaviorAnalysis.alert.typeIdentityMismatch',
  behavior_rarity: 'behaviorAnalysis.alert.typeBehaviorRarity',
  chain_anomaly: 'behaviorAnalysis.alert.typeChainAnomaly',
  data_anomaly: 'behaviorAnalysis.alert.typeDataAnomaly',
  policy_anomaly: 'behaviorAnalysis.alert.typePolicyAnomaly',
  privilege_propagation: 'behaviorAnalysis.alert.typePrivilegePropagation',
};

const STATUS_KEYS: Record<string, string> = {
  open: 'behaviorAnalysis.alert.statusOpen',
  acknowledged: 'behaviorAnalysis.alert.statusAcknowledged',
  resolved: 'behaviorAnalysis.alert.statusResolved',
  false_positive: 'behaviorAnalysis.alert.statusFalsePositive',
};

const formatTime = (ts?: number | string): string => {
  if (ts === undefined || ts === null || ts === '') return '-';
  // Redis Hash 时间戳以字符串存储，需 Number() 转换，否则 new Date("1782446400000") 返回 Invalid Date
  const d = new Date(Number(ts));
  if (isNaN(d.getTime())) return '-';
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`;
};

// 后端告警 Hash 返回 snake_case 字段（alert_id/risk_type/...），前端 AlertItem 为 camelCase，
// 此处统一映射；同时把 related_logs（JSON 字符串数组）解析为 string[]
const mapAlert = (raw: any): AlertItem => {
  if (!raw || typeof raw !== 'object') return raw;
  let relatedLogs: string[] | undefined;
  const rlRaw = raw.related_logs ?? raw.relatedLogs;
  if (typeof rlRaw === 'string') {
    try {
      const parsed = JSON.parse(rlRaw);
      relatedLogs = Array.isArray(parsed) ? parsed : undefined;
    } catch { relatedLogs = undefined; }
  } else if (Array.isArray(rlRaw)) {
    relatedLogs = rlRaw;
  }
  return {
    alertId: raw.alert_id ?? raw.alertId,
    timestamp: raw.timestamp !== undefined && raw.timestamp !== null ? Number(raw.timestamp) : undefined,
    riskType: raw.risk_type ?? raw.riskType,
    riskLevel: raw.risk_level ?? raw.riskLevel,
    riskScore: raw.risk_score !== undefined && raw.risk_score !== null ? Number(raw.risk_score) : raw.riskScore,
    userId: raw.user_id ?? raw.userId,
    userName: raw.user_name ?? raw.userName,
    agentId: raw.agent_id ?? raw.agentId,
    sessionId: raw.session_id ?? raw.sessionId,
    traceId: raw.trace_id ?? raw.traceId,
    title: raw.title,
    description: raw.description,
    status: raw.status,
    disposition: raw.disposition,
    evidence: raw.evidence,
    relatedLogs,
  } as AlertItem;
};

const BehaviorAlertsPage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const initialAlertId = searchParams.get('alertId') || '';

  const [alerts, setAlerts] = useState<AlertItem[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [page, setPage] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(20);
  const [filterStatus, setFilterStatus] = useState<string | undefined>(undefined);
  const [filterRiskType, setFilterRiskType] = useState<string | undefined>(undefined);
  const [filterRiskLevel, setFilterRiskLevel] = useState<string | undefined>(undefined);
  const [filterTimeRange, setFilterTimeRange] = useState<[number, number] | undefined>(undefined);

  const [drawerVisible, setDrawerVisible] = useState<boolean>(false);
  const [currentAlert, setCurrentAlert] = useState<AlertItem | null>(null);
  const [disposeModalVisible, setDisposeModalVisible] = useState<boolean>(false);
  const [disposeForm] = Form.useForm();
  const [disposingAlert, setDisposingAlert] = useState<AlertItem | null>(null);

  // 加载告警列表
  const { run: loadAlerts, loading: loading } = useRequest(
    () => {
      const params: any = { page, pageSize };
      if (filterStatus) params.status = filterStatus;
      if (filterRiskType) params.riskType = filterRiskType;
      if (filterRiskLevel) params.riskLevel = filterRiskLevel;
      if (filterTimeRange) {
        params.startTime = filterTimeRange[0];
        params.endTime = filterTimeRange[1];
      }
      return getAlerts(params);
    },
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || {};
        const list = Array.isArray(data) ? data : (data?.list || data?.items || []);
        // 后端告警字段为 snake_case，统一映射为前端 AlertItem (camelCase)，否则列表时间/类型/级别等显示为 NaN 或 '-'
        setAlerts(list.map((item: any) => mapAlert(item)));
        setTotal(res?.total || data?.total || list.length);
      },
    },
  );

  // 加载告警详情
  const { run: loadDetail, loading: detailLoading } = useRequest(
    (alertId: string) => getAlertDetail(alertId),
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || {};
        // 详情 Hash 同样为 snake_case，需映射后展示，否则详情抽屉全部为空（Issue 6）
        setCurrentAlert(mapAlert(data));
        setDrawerVisible(true);
      },
    },
  );

  // 处置告警
  const { run: doDispose, loading: disposing } = useRequest(
    (payload: { alertId: string; disposition: string; note?: string; ttl?: number }) =>
      updateAlertDisposition(payload.alertId, {
        disposition: payload.disposition,
        note: payload.note,
        ttl: payload.ttl,
      }),
    {
      manual: true,
      onSuccess: () => {
        message.success(t('behaviorAnalysis.alert.dispositionSuccess'));
        setDisposeModalVisible(false);
        disposeForm.resetFields();
        loadAlerts();
        if (currentAlert && disposingAlert?.alertId === currentAlert.alertId) {
          loadDetail(currentAlert.alertId);
        }
      },
    },
  );

  useEffect(() => {
    loadAlerts();
  }, [page, pageSize, filterStatus, filterRiskType, filterRiskLevel, filterTimeRange]);

  useEffect(() => {
    if (initialAlertId) {
      loadDetail(initialAlertId);
    }
  }, [initialAlertId]);

  const handleViewDetail = (alert: AlertItem) => {
    loadDetail(alert.alertId);
  };

  const handleDispose = (alert: AlertItem) => {
    setDisposingAlert(alert);
    disposeForm.resetFields();
    disposeForm.setFieldsValue({ disposition: 'acknowledge', ttl: 7 * 86400 });
    setDisposeModalVisible(true);
  };

  const submitDispose = async () => {
    try {
      const values = await disposeForm.validateFields();
      if (!disposingAlert) return;
      doDispose({
        alertId: disposingAlert.alertId,
        disposition: values.disposition,
        note: values.note,
        ttl: values.disposition === 'blacklist' ? values.ttl : undefined,
      });
    } catch (e) {
      // 校验失败
    }
  };

  // 处置选项
  const dispositionOptions = [
    { value: 'blacklist', label: t('behaviorAnalysis.alert.blacklist') },
    { value: 'degrade_config', label: t('behaviorAnalysis.alert.degradeConfig') },
    { value: 'acknowledge', label: t('behaviorAnalysis.alert.acknowledge') },
    { value: 'false_positive', label: t('behaviorAnalysis.alert.falsePositive') },
  ];

  // 列定义
  const columns = [
    {
      title: t('behaviorAnalysis.alert.time'),
      dataIndex: 'timestamp',
      key: 'timestamp',
      width: 150,
      sorter: (a: AlertItem, b: AlertItem) => (a.timestamp || 0) - (b.timestamp || 0),
      defaultSortOrder: 'descend' as const,
      render: (v: number) => formatTime(v),
    },
    {
      title: t('behaviorAnalysis.alert.riskType'),
      dataIndex: 'riskType',
      key: 'riskType',
      width: 110,
      render: (v: string) => v ? <Tag>{t(RISK_TYPE_KEYS[v] || 'behaviorAnalysis.alert.riskType')}</Tag> : '-',
    },
    {
      title: t('behaviorAnalysis.alert.riskLevel'),
      dataIndex: 'riskLevel',
      key: 'riskLevel',
      width: 80,
      render: (v: string) => v ? <Tag color={RISK_LEVEL_COLORS[v] || 'default'}>{t(`behaviorAnalysis.alert.level${v.charAt(0).toUpperCase() + v.slice(1)}`)}</Tag> : '-',
    },
    {
      title: t('behaviorAnalysis.alert.riskScore'),
      dataIndex: 'riskScore',
      key: 'riskScore',
      width: 80,
      render: (v: number) => v || 0,
    },
    {
      title: t('behaviorAnalysis.alert.user'),
      key: 'user',
      width: 120,
      ellipsis: true,
      render: (_: any, r: AlertItem) => r.userName || r.userId || '-',
    },
    {
      title: t('behaviorAnalysis.alert.agent'),
      dataIndex: 'agentId',
      key: 'agentId',
      width: 120,
      ellipsis: true,
      render: (v: string) => v || '-',
    },
    {
      title: t('behaviorAnalysis.alert.session'),
      dataIndex: 'sessionId',
      key: 'sessionId',
      width: 140,
      ellipsis: true,
      render: (v: string) => v ? (
        <Tooltip title={v}>
          <Button type="link" size="small" style={{ padding: 0 }} onClick={() => navigate(`/behavior-analysis/session-graph?sessionId=${encodeURIComponent(v)}`)}>
            {v.length > 16 ? v.slice(0, 8) + '...' + v.slice(-4) : v}
          </Button>
        </Tooltip>
      ) : '-',
    },
    {
      title: t('behaviorAnalysis.alert.description'),
      dataIndex: 'title',
      key: 'title',
      ellipsis: true,
      render: (v: string, r: AlertItem) => v || r.description || '-',
    },
    {
      title: t('behaviorAnalysis.alert.status'),
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (v: string) => v ? <Tag>{t(STATUS_KEYS[v] || 'behaviorAnalysis.alert.status')}</Tag> : '-',
    },
    {
      title: t('behaviorAnalysis.alert.action'),
      key: 'action',
      width: 130,
      fixed: 'right' as const,
      render: (_: any, r: AlertItem) => (
        <Space size={4}>
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => handleViewDetail(r)}>
            {t('behaviorAnalysis.alert.view')}
          </Button>
          <Button type="link" size="small" onClick={() => handleDispose(r)}>
            {t('behaviorAnalysis.alert.dispose')}
          </Button>
        </Space>
      ),
    },
  ];

  // 证据链展示
  const renderEvidence = (evidence: any) => {
    if (!evidence) return '-';
    if (typeof evidence === 'string') {
      try { evidence = JSON.parse(evidence); } catch { return evidence; }
    }
    if (typeof evidence !== 'object') return String(evidence);
    return (
      <pre style={{ margin: 0, padding: 12, background: '#fafafa', fontSize: 12, maxHeight: 300, overflow: 'auto', borderRadius: 4 }}>
        {JSON.stringify(evidence, null, 2)}
      </pre>
    );
  };

  return (
    <div style={{ padding: '0 0 24px' }}>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontWeight: 500, fontSize: 15 }}>{t('behaviorAnalysis.alert.title')}</span>
        <Button icon={<ReloadOutlined />} onClick={() => loadAlerts()} loading={loading}>
          {t('behaviorAnalysis.refresh')}
        </Button>
      </div>

      {/* 处置语义提示 */}
      <Alert
        type="info"
        showIcon
        message={t('behaviorAnalysis.alert.disposeTip')}
        style={{ marginBottom: 16 }}
      />

      {/* 筛选栏 */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select
            allowClear
            placeholder={t('behaviorAnalysis.alert.allStatus')}
            value={filterStatus}
            onChange={(v) => { setFilterStatus(v); setPage(1); }}
            style={{ width: 140 }}
            options={[
              { value: 'open', label: t('behaviorAnalysis.alert.statusOpen') },
              { value: 'acknowledged', label: t('behaviorAnalysis.alert.statusAcknowledged') },
              { value: 'resolved', label: t('behaviorAnalysis.alert.statusResolved') },
              { value: 'false_positive', label: t('behaviorAnalysis.alert.statusFalsePositive') },
            ]}
          />
          <Select
            allowClear
            placeholder={t('behaviorAnalysis.alert.allTypes')}
            value={filterRiskType}
            onChange={(v) => { setFilterRiskType(v); setPage(1); }}
            style={{ width: 160 }}
            options={Object.entries(RISK_TYPE_KEYS).map(([v, k]) => ({ value: v, label: t(k) }))}
          />
          <Select
            allowClear
            placeholder={t('behaviorAnalysis.alert.allLevels')}
            value={filterRiskLevel}
            onChange={(v) => { setFilterRiskLevel(v); setPage(1); }}
            style={{ width: 120 }}
            options={[
              { value: 'low', label: t('behaviorAnalysis.alert.levelLow') },
              { value: 'medium', label: t('behaviorAnalysis.alert.levelMedium') },
              { value: 'high', label: t('behaviorAnalysis.alert.levelHigh') },
              { value: 'critical', label: t('behaviorAnalysis.alert.levelCritical') },
            ]}
          />
          <RangePicker
            showTime
            onChange={(_, dateStrings) => {
              if (dateStrings && dateStrings[0] && dateStrings[1]) {
                setFilterTimeRange([new Date(dateStrings[0]).getTime(), new Date(dateStrings[1]).getTime()]);
              } else {
                setFilterTimeRange(undefined);
              }
              setPage(1);
            }}
          />
        </Space>
      </Card>

      {/* 告警列表 */}
      <Card>
        <Table
          dataSource={alerts}
          columns={columns}
          rowKey="alertId"
          size="small"
          loading={loading}
          scroll={{ x: 1200 }}
          pagination={{
            current: page,
            pageSize: pageSize,
            total: total,
            showSizeChanger: true,
            showTotal: (tot) => `${t('behaviorAnalysis.total')}: ${tot}`,
            onChange: (p, ps) => { setPage(p); setPageSize(ps); },
          }}
          locale={{ emptyText: <Empty description={t('behaviorAnalysis.noData')} /> }}
        />
      </Card>

      {/* 告警详情抽屉 */}
      <Drawer
        title={t('behaviorAnalysis.alert.detail')}
        open={drawerVisible}
        onClose={() => setDrawerVisible(false)}
        width={720}
        destroyOnClose
      >
        {currentAlert ? (
          <Spin spinning={detailLoading}>
            <Descriptions column={2} bordered size="small">
              <Descriptions.Item label={t('behaviorAnalysis.alert.time')} span={2}>
                {formatTime(currentAlert.timestamp)}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.alert.riskType')}>
                {currentAlert.riskType ? t(RISK_TYPE_KEYS[currentAlert.riskType] || 'behaviorAnalysis.alert.riskType') : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.alert.riskLevel')}>
                {currentAlert.riskLevel ? (
                  <Tag color={RISK_LEVEL_COLORS[currentAlert.riskLevel] || 'default'}>
                    {t(`behaviorAnalysis.alert.level${currentAlert.riskLevel.charAt(0).toUpperCase() + currentAlert.riskLevel.slice(1)}`)}
                  </Tag>
                ) : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.alert.riskScore')}>
                {currentAlert.riskScore || 0}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.alert.status')}>
                {currentAlert.status ? t(STATUS_KEYS[currentAlert.status] || 'behaviorAnalysis.alert.status') : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.alert.user')}>
                {currentAlert.userName || currentAlert.userId || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.alert.agent')}>
                {currentAlert.agentId || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.alert.session')} span={2}>
                {currentAlert.sessionId ? (
                  <Button type="link" size="small" style={{ padding: 0 }} onClick={() => {
                    setDrawerVisible(false);
                    navigate(`/behavior-analysis/session-graph?sessionId=${encodeURIComponent(currentAlert.sessionId!)}`);
                  }}>
                    {currentAlert.sessionId}
                  </Button>
                ) : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.alert.traceId')} span={2}>
                {currentAlert.traceId || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('behaviorAnalysis.alert.description')} span={2}>
                {currentAlert.title || currentAlert.description || '-'}
              </Descriptions.Item>
            </Descriptions>

            {/* 证据链 */}
            <div style={{ marginTop: 16 }}>
              <div style={{ fontWeight: 500, marginBottom: 8 }}>{t('behaviorAnalysis.alert.evidence')}</div>
              {renderEvidence(currentAlert.evidence)}
            </div>

            {/* 关联审计日志 */}
            {currentAlert.relatedLogs && currentAlert.relatedLogs.length > 0 && (
              <div style={{ marginTop: 16 }}>
                <div style={{ fontWeight: 500, marginBottom: 8 }}>{t('behaviorAnalysis.alert.relatedLogs')}</div>
                <Space wrap>
                  {currentAlert.relatedLogs.map((logId) => (
                    <Tag key={logId} color="blue">{logId}</Tag>
                  ))}
                </Space>
              </div>
            )}

            <div style={{ marginTop: 24 }}>
              <Button
                type="primary"
                icon={<ExclamationCircleOutlined />}
                onClick={() => currentAlert && handleDispose(currentAlert)}
              >
                {t('behaviorAnalysis.alert.dispose')}
              </Button>
            </div>
          </Spin>
        ) : (
          <Empty description={t('behaviorAnalysis.noData')} />
        )}
      </Drawer>

      {/* 处置弹窗 */}
      <Modal
        title={t('behaviorAnalysis.alert.confirmDispose')}
        open={disposeModalVisible}
        onOk={submitDispose}
        onCancel={() => setDisposeModalVisible(false)}
        confirmLoading={disposing}
        okText={t('behaviorAnalysis.alert.dispose')}
        destroyOnClose
      >
        {disposingAlert && (
          <div style={{ marginBottom: 16, padding: 8, background: '#fafafa', borderRadius: 4, fontSize: 13 }}>
            <div><strong>{t('behaviorAnalysis.alert.riskType')}:</strong> {t(RISK_TYPE_KEYS[disposingAlert.riskType || ''] || '-')}</div>
            <div><strong>{t('behaviorAnalysis.alert.user')}:</strong> {disposingAlert.userName || disposingAlert.userId || '-'}</div>
            <div><strong>{t('behaviorAnalysis.alert.agent')}:</strong> {disposingAlert.agentId || '-'}</div>
          </div>
        )}
        <Form form={disposeForm} layout="vertical">
          <Form.Item
            name="disposition"
            label={t('behaviorAnalysis.alert.disposition')}
            rules={[{ required: true }]}
          >
            <Select options={dispositionOptions} />
          </Form.Item>
          <Form.Item
            noStyle
            shouldUpdate={(prev, curr) => prev.disposition !== curr.disposition}
          >
            {({ getFieldValue }) => getFieldValue('disposition') === 'blacklist' ? (
              <Form.Item
                name="ttl"
                label={t('behaviorAnalysis.alert.dispositionTtl')}
                rules={[{ required: true }]}
              >
                <InputNumber min={60} max={365 * 86400} style={{ width: '100%' }} />
              </Form.Item>
            ) : null}
          </Form.Item>
          <Form.Item name="note" label={t('behaviorAnalysis.alert.dispositionNote')}>
            <Input.TextArea rows={3} maxLength={500} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default BehaviorAlertsPage;
