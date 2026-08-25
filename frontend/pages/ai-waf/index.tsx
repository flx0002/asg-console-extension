import React, { useState, useEffect } from 'react';
import { Card, Switch, Radio, Table, Tag, Button, Input, message, Space, Modal } from 'antd';
import { useTranslation } from 'react-i18next';
import { useRequest } from 'ahooks';
import { getGlobalPluginInstance, updateGlobalPluginInstance } from '@/services';
import { getAuditLogs, getAuditSessions } from '@/services/audit-chain-service';
import yaml from 'js-yaml';

const PLUGIN_NAME = 'waf';
const EVENT_TYPE_FILTERS = ['waf_violation', 'waf', 'code_injection'];

const DEFAULT_CUSTOM_RULES = `SecRule REQUEST_BODY "@rx (忽略|ignore).*(指令|instruction|prompt)" "id:1001,phase:2,deny,status:403,msg:'Prompt injection detected'"
SecRule RESPONSE_BODY "@rx <script.*?>" "id:2001,phase:4,deny,status:403,msg:'XSS in LLM output detected'"`;

const PRESET_RULES = [
  { key: '1', name: '提示词注入检测规则集', count: 5, scenario: 'LLM01 Prompt Injection', enabled: true },
  { key: '2', name: 'XSS输出检测规则集', count: 3, scenario: 'LLM05 Improper Output Handling', enabled: true },
  { key: '3', name: 'SQL注入输出检测规则集', count: 3, scenario: 'LLM05 Improper Output Handling', enabled: true },
  { key: '4', name: '系统提示词泄露检测规则集', count: 4, scenario: 'LLM07 System Prompt Leakage', enabled: false },
];

interface FormState {
  requestDetect: boolean;
  responseDetect: boolean;
  enableOwaspCrs: boolean;
  ruleEngineMode: string;
  customRules: string;
}

const defaultFormState: FormState = {
  requestDetect: true,
  responseDetect: true,
  enableOwaspCrs: true,
  ruleEngineMode: 'detect',
  customRules: DEFAULT_CUSTOM_RULES,
};

const AiWafPage: React.FC = () => {
  const { t } = useTranslation();
  const [enabled, setEnabled] = useState(false);
  const [form, setForm] = useState<FormState>({ ...defaultFormState });
  const [initialForm, setInitialForm] = useState<FormState>({ ...defaultFormState });
  const [pluginData, setPluginData] = useState<any>(null);
  const [presetRules, setPresetRules] = useState(PRESET_RULES);
  const [detectionLogs, setDetectionLogs] = useState<any[]>([]);

  const { run: loadDetectionLogs } = useRequest(
    async () => {
      const sessionsRes: any = await getAuditSessions();
      const sessions = sessionsRes?.list || sessionsRes?.data?.list || sessionsRes || [];
      if (!Array.isArray(sessions) || sessions.length === 0) return [];
      const validSessions = sessions.filter(
        (s: any) => s?.sessionId && /^[a-zA-Z0-9_-]{1,128}$/.test(s.sessionId),
      );
      const results = await Promise.all(
        validSessions.map((s: any) =>
          getAuditLogs({ sessionId: s.sessionId, recordType: 'security_event', pageSize: 50 }).catch(() => null),
        ),
      );
      const allLogs: any[] = [];
      results.forEach((res: any) => {
        if (!res) return;
        const records = res?.list || res?.data?.list || [];
        records.forEach((r: any) => {
          const events = r.events || [];
          if (events.some((e: any) => EVENT_TYPE_FILTERS.includes(e.type))) {
            allLogs.push(r);
          }
        });
      });
      allLogs.sort((a, b) => (b.timestamp ?? 0) - (a.timestamp ?? 0));
      return allLogs.slice(0, 20);
    },
    {
      manual: true,
      onSuccess: (allLogs) => {
        const mapped = allLogs.map((r: any) => {
          const events = r.events || [];
          const wafEvent = events.find((e: any) => EVENT_TYPE_FILTERS.includes(e.type)) || {};
          const ts = r.timestamp ?? 0;
          const risk = r.risk_score ?? 0;
          const riskLevel = risk >= 60 ? 'high' : risk >= 30 ? 'medium' : 'low';
          const action = r.action || 'passed';
          return {
            time: ts ? new Date(ts).toLocaleString('zh-CN') : '-',
            ruleId: wafEvent.type || '-',
            phase: r.step_type === 'user_input' ? 'request' : 'response',
            match: wafEvent.detail || '-',
            riskLevel,
            result: action === 'blocked' ? 'blocked' : action === 'masked' ? 'masked' : 'passed',
          };
        });
        setDetectionLogs(mapped);
      },
    }
  );

  useEffect(() => { loadDetectionLogs(); }, []);

  const [detailModalVisible, setDetailModalVisible] = useState(false);
  const [detailRule, setDetailRule] = useState<typeof PRESET_RULES[0] | null>(null);

  const { run: loadConfig, loading } = useRequest(
    () => getGlobalPluginInstance(PLUGIN_NAME),
    {
      manual: true,
      onSuccess: (res) => {
        setPluginData(res);
        setEnabled(res?.enabled ?? false);
        if (res?.rawConfigurations) {
          try {
            const cfg = yaml.load(res.rawConfigurations) as any;
            if (cfg) {
              const parsed: FormState = {
                requestDetect: cfg.checkRequest ?? defaultFormState.requestDetect,
                responseDetect: cfg.checkResponse ?? defaultFormState.responseDetect,
                enableOwaspCrs: cfg.enableOwaspCrs ?? defaultFormState.enableOwaspCrs,
                ruleEngineMode: cfg.mode ?? defaultFormState.ruleEngineMode,
                customRules: cfg.customRules ?? defaultFormState.customRules,
              };
              setForm(parsed);
              setInitialForm(parsed);
            }
          } catch {
            // ignore parse error, use defaults
          }
        }
      },
      onError: () => {
        message.error(t('aiContentSec.loadFailed'));
      },
    },
  );

  useEffect(() => {
    loadConfig();
  }, []);

  const handleToggle = async (checked: boolean) => {
    try {
      const params = { ...pluginData, enabled: checked };
      delete params.configurations;
      await updateGlobalPluginInstance(PLUGIN_NAME, params);
      setEnabled(checked);
      message.success(t('aiContentSec.saveSuccess'));
    } catch {
      message.error(t('aiContentSec.saveFailed'));
    }
  };

  const handleSave = async () => {
    try {
      const configObj = {
        checkRequest: form.requestDetect,
        checkResponse: form.responseDetect,
        enableOwaspCrs: form.enableOwaspCrs,
        mode: form.ruleEngineMode,
        customRules: form.customRules,
      };
      const rawConfig = yaml.dump(configObj);
      const params = { ...pluginData, enabled, rawConfigurations: rawConfig };
      delete params.configurations;
      await updateGlobalPluginInstance(PLUGIN_NAME, params);
      setInitialForm({ ...form });
      message.success(t('aiContentSec.saveSuccess'));
    } catch {
      message.error(t('aiContentSec.saveFailed'));
    }
  };

  const handleReset = () => {
    setForm({ ...initialForm });
  };

  const updateField = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm(prev => ({ ...prev, [key]: value }));
  };

  const handlePresetRuleToggle = (key: string, checked: boolean) => {
    setPresetRules((prev) => prev.map((r) => (r.key === key ? { ...r, enabled: checked } : r)));
  };

  const handleSyntaxCheck = () => {
    if (form.customRules.includes('SecRule')) {
      message.success(t('aiContentSec.waf.syntaxCheckSuccess'));
    } else {
      message.error(t('aiContentSec.waf.syntaxCheckFailed'));
    }
  };

  const handleFormat = () => {
    const formatted = form.customRules
      .split('\n')
      .map(line => line.trim())
      .filter(line => line.length > 0)
      .join('\n');
    updateField('customRules', formatted);
    message.success(t('aiContentSec.waf.formatSuccess'));
  };

  const handleViewDetail = (record: typeof PRESET_RULES[0]) => {
    setDetailRule(record);
    setDetailModalVisible(true);
  };

  const presetColumns = [
    {
      title: t('aiContentSec.waf.ruleSetName'),
      dataIndex: 'name',
      key: 'name',
      render: (text: string) => <span style={{ fontWeight: 500 }}>{text}</span>,
    },
    {
      title: t('aiContentSec.waf.ruleCount'),
      dataIndex: 'count',
      key: 'count',
      render: (count: number) => (
        <Tag style={{ borderRadius: 10, fontSize: 12 }}>{count}条</Tag>
      ),
    },
    {
      title: t('aiContentSec.waf.scenario'),
      dataIndex: 'scenario',
      key: 'scenario',
      render: (scenario: string) => <Tag color="blue">{scenario}</Tag>,
    },
    {
      title: t('aiContentSec.waf.status'),
      dataIndex: 'enabled',
      key: 'enabled',
      render: (checked: boolean, record: typeof PRESET_RULES[0]) => (
        <Switch size="small" checked={checked} onChange={(v) => handlePresetRuleToggle(record.key, v)} />
      ),
    },
    {
      title: t('aiContentSec.waf.operation'),
      key: 'action',
      render: (_: any, record: typeof PRESET_RULES[0]) => (
        <Button type="link" size="small" onClick={() => handleViewDetail(record)}>
          {t('aiContentSec.waf.viewDetail')}
        </Button>
      ),
    },
  ];

  const logColumns = [
    { title: t('aiContentSec.time'), dataIndex: 'time', key: 'time' },
    { title: t('aiContentSec.waf.logRuleId'), dataIndex: 'ruleId', key: 'ruleId' },
    { title: t('aiContentSec.waf.logPhase'), dataIndex: 'phase', key: 'phase' },
    { title: t('aiContentSec.waf.logMatch'), dataIndex: 'match', key: 'match' },
    { title: t('aiContentSec.waf.logRiskLevel'), dataIndex: 'riskLevel', key: 'riskLevel' },
    { title: t('aiContentSec.waf.logResult'), dataIndex: 'result', key: 'result' },
  ];

  return (
    <div style={{ padding: '0 0 24px' }}>
      {/* Section 1: Plugin Status */}
      <Card title={t('aiContentSec.pluginStatus')} style={{ marginBottom: 16 }} loading={loading}>
        <Space size="large" align="center">
          <Switch
            checked={enabled}
            onChange={handleToggle}
            checkedChildren={t('aiContentSec.on')}
            unCheckedChildren={t('aiContentSec.off')}
          />
          <Tag color={enabled ? 'green' : 'default'}>
            {enabled ? t('aiContentSec.enabled') : t('aiContentSec.disabled')}
          </Tag>
        </Space>
      </Card>

      {/* Section 2: Configuration */}
      <Card title={t('aiContentSec.config')} style={{ marginBottom: 16 }}>
        <div style={{ maxWidth: 700 }}>
          <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 500 }}>{t('aiContentSec.waf.requestDetect')}</span>
            <Switch
              checked={form.requestDetect}
              onChange={v => updateField('requestDetect', v)}
              checkedChildren={t('aiContentSec.on')}
              unCheckedChildren={t('aiContentSec.off')}
            />
          </div>

          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 4, display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontWeight: 500 }}>{t('aiContentSec.waf.responseDetect')}</span>
              <Switch
                checked={form.responseDetect}
                onChange={v => updateField('responseDetect', v)}
                checkedChildren={t('aiContentSec.on')}
                unCheckedChildren={t('aiContentSec.off')}
              />
            </div>
            <span style={{ color: '#faad14', fontSize: 12, marginLeft: 8 }}>
              {t('aiContentSec.waf.responseDetectHint')}
            </span>
          </div>

          <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 500 }}>{t('aiContentSec.waf.enableOwaspCrs')}</span>
            <Switch
              checked={form.enableOwaspCrs}
              onChange={v => updateField('enableOwaspCrs', v)}
              checkedChildren={t('aiContentSec.on')}
              unCheckedChildren={t('aiContentSec.off')}
            />
          </div>

          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 4, fontWeight: 500 }}>{t('aiContentSec.waf.ruleEngineMode')}</div>
            <Radio.Group
              value={form.ruleEngineMode}
              onChange={e => updateField('ruleEngineMode', e.target.value)}
            >
              <Radio value="detect">{t('aiContentSec.waf.detectMode')}</Radio>
              <Radio value="block">{t('aiContentSec.waf.blockMode')}</Radio>
            </Radio.Group>
            {form.ruleEngineMode === 'detect' && (
              <div style={{ color: '#999', fontSize: 12, marginTop: 4 }}>
                {t('aiContentSec.waf.detectModeHint')}
              </div>
            )}
          </div>

          <div style={{ marginTop: 24, display: 'flex', gap: 8 }}>
            <Button type="primary" onClick={handleSave}>{t('aiContentSec.saveConfig')}</Button>
            <Button onClick={handleReset}>{t('aiContentSec.resetConfig')}</Button>
          </div>
        </div>
      </Card>

      {/* Section 3: AI Preset Rule Sets */}
      <Card title={t('aiContentSec.waf.presetRules')} style={{ marginBottom: 16 }}>
        <Table
          dataSource={presetRules}
          columns={presetColumns}
          rowKey="key"
          pagination={false}
          size="small"
        />
      </Card>

      {/* Section 4: Custom WAF Rules */}
      <Card title={t('aiContentSec.waf.customRules')} style={{ marginBottom: 16 }}>
        <p style={{ color: '#666', marginBottom: 12 }}>
          {t('aiContentSec.waf.customRulesHint')}
        </p>
        <Input.TextArea
          value={form.customRules}
          onChange={e => updateField('customRules', e.target.value)}
          rows={6}
          style={{
            fontFamily: 'monospace',
            backgroundColor: '#1e1e2e',
            color: '#cdd6f4',
            border: '1px solid #45475a',
          }}
        />
        <div style={{ marginTop: 12 }}>
          <Space>
            <Button type="primary" onClick={handleSave}>{t('aiContentSec.waf.saveRule')}</Button>
            <Button onClick={handleSyntaxCheck}>{t('aiContentSec.waf.syntaxCheck')}</Button>
            <Button onClick={handleFormat}>{t('aiContentSec.waf.format')}</Button>
          </Space>
        </div>
      </Card>

      {/* Section 5: WAF Detection Logs */}
      <Card title={t('aiContentSec.recentLogs')}>
        <Table
          dataSource={detectionLogs}
          columns={logColumns}
          rowKey="time"
          pagination={false}
          size="small"
          locale={{ emptyText: t('aiContentSec.noLogData') }}
        />
      </Card>

      {/* Detail Modal */}
      <Modal
        title={t('aiContentSec.waf.ruleDetail')}
        open={detailModalVisible}
        onCancel={() => setDetailModalVisible(false)}
        footer={<Button onClick={() => setDetailModalVisible(false)}>{t('aiContentSec.waf.viewDetail')}</Button>}
      >
        {detailRule && (
          <div>
            <p><strong>{t('aiContentSec.waf.ruleSetName')}:</strong> {detailRule.name}</p>
            <p><strong>{t('aiContentSec.waf.scenario')}:</strong> {detailRule.scenario}</p>
            <p><strong>{t('aiContentSec.waf.ruleContent')}:</strong></p>
            <p style={{ color: '#999', fontSize: 13 }}>{t('aiContentSec.waf.ruleContentPlaceholder')}</p>
          </div>
        )}
      </Modal>
    </div>
  );
};

export default AiWafPage;
