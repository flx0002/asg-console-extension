import React, { useState, useEffect } from 'react';
import { Card, Switch, Radio, Input, Table, Tag, Button, message, Space, Divider, Modal } from 'antd';
import { useRequest } from 'ahooks';
import { getGlobalPluginInstance, updateGlobalPluginInstance } from '@/services';
import { getAuditLogs, getAuditSessions } from '@/services/audit-chain-service';
import { useTranslation } from 'react-i18next';
import yaml from 'js-yaml';

const PLUGIN_NAME = 'ai-prompt-guard';
const EVENT_TYPE_FILTER = 'prompt_injection';

interface RuleItem {
  key: string;
  name: string;
  regex: string;
  matchType: string;
}

interface FormState {
  detectMode: string;
  denyRules: RuleItem[];
  allowRules: RuleItem[];
  rejectMessage: string;
}

const defaultDenyRules: RuleItem[] = [
  { key: '1', name: 'SQL注入检测', regex: '(?i)(union\\s+select|drop\\s+table|insert\\s+into)', matchType: 'regex' },
  { key: '2', name: '系统提示词泄露', regex: '(?i)(ignore\\s+previous|forget\\s+instructions|system\\s*prompt)', matchType: 'regex' },
  { key: '3', name: '越狱攻击', regex: '(?i)(jailbreak|dan\\s+mode|developer\\s+mode)', matchType: 'regex' },
];

const defaultAllowRules: RuleItem[] = [
  { key: '1', name: '允许的SQL教学', regex: '(?i)(how\\s+to\\s+learn\\s+sql|sql\\s+tutorial)', matchType: 'regex' },
];

const defaultFormState: FormState = {
  detectMode: 'deny',
  denyRules: defaultDenyRules,
  allowRules: defaultAllowRules,
  rejectMessage: '检测到恶意提示词，请求已被拦截',
};

const AiPromptGuardPage: React.FC = () => {
  const { t } = useTranslation();
  const [enabled, setEnabled] = useState(false);
  const [form, setForm] = useState<FormState>({ ...defaultFormState });
  const [initialForm, setInitialForm] = useState<FormState>({ ...defaultFormState });
  const [pluginData, setPluginData] = useState<any>(null);
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
          if (events.some((e: any) => e.type === EVENT_TYPE_FILTER)) {
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
          const promptEvent = events.find((e: any) => e.type === EVENT_TYPE_FILTER) || {};
          const ts = r.timestamp ?? 0;
          const action = r.action || 'passed';
          return {
            time: ts ? new Date(ts).toLocaleString('zh-CN') : '-',
            attackType: promptEvent.type || EVENT_TYPE_FILTER,
            matchedRule: promptEvent.detail || '-',
            result: action === 'blocked' ? 'blocked' : 'passed',
            summary: `${r.model || ''} - ${r.tool_name || ''}`,
          };
        });
        setDetectionLogs(mapped);
      },
    }
  );

  useEffect(() => { loadDetectionLogs(); }, []);

  const [modalVisible, setModalVisible] = useState(false);
  const [editingType, setEditingType] = useState<'deny' | 'allow'>('deny');
  const [editingIndex, setEditingIndex] = useState(-1);
  const [ruleName, setRuleName] = useState('');
  const [ruleRegex, setRuleRegex] = useState('');

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
                detectMode: cfg.detectMode ?? defaultFormState.detectMode,
                denyRules: cfg.denyRules ?? defaultFormState.denyRules,
                allowRules: cfg.allowRules ?? defaultFormState.allowRules,
                rejectMessage: cfg.rejectMessage ?? defaultFormState.rejectMessage,
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
        detectMode: form.detectMode,
        denyRules: form.denyRules.map(({ name, regex, matchType }) => ({ name, regex, matchType })),
        allowRules: form.allowRules.map(({ name, regex, matchType }) => ({ name, regex, matchType })),
        rejectMessage: form.rejectMessage,
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

  const handleAddRule = (type: 'deny' | 'allow') => {
    setEditingType(type);
    setEditingIndex(-1);
    setRuleName('');
    setRuleRegex('');
    setModalVisible(true);
  };

  const handleEditRule = (type: 'deny' | 'allow', record: RuleItem, index: number) => {
    setEditingType(type);
    setEditingIndex(index);
    setRuleName(record.name);
    setRuleRegex(record.regex);
    setModalVisible(true);
  };

  const handleDeleteRule = (type: 'deny' | 'allow', index: number) => {
    Modal.confirm({
      title: t('aiContentSec.promptGuard.deleteConfirm'),
      onOk: () => {
        const field = type === 'deny' ? 'denyRules' : 'allowRules';
        const newRules = [...form[field]];
        newRules.splice(index, 1);
        updateField(field, newRules);
      },
    });
  };

  const handleModalOk = () => {
    if (!ruleName || !ruleRegex) {
      message.warning(t('aiContentSec.promptGuard.ruleName') + ' / ' + t('aiContentSec.promptGuard.regex'));
      return;
    }
    const newRule: RuleItem = { key: Date.now().toString(), name: ruleName, regex: ruleRegex, matchType: 'regex' };
    const field = editingType === 'deny' ? 'denyRules' : 'allowRules';
    const newRules = [...form[field]];
    if (editingIndex === -1) {
      newRules.push(newRule);
    } else {
      newRules[editingIndex] = newRule;
    }
    updateField(field, newRules);
    setModalVisible(false);
  };

  const getRuleColumns = (type: 'deny' | 'allow') => [
    {
      title: t('aiContentSec.promptGuard.ruleName'),
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: t('aiContentSec.promptGuard.regex'),
      dataIndex: 'regex',
      key: 'regex',
      render: (text: string) => <code style={{ fontSize: 12 }}>{text}</code>,
    },
    {
      title: t('aiContentSec.promptGuard.matchType'),
      dataIndex: 'matchType',
      key: 'matchType',
      render: () => <Tag>{t('aiContentSec.promptGuard.regexMatch')}</Tag>,
    },
    {
      title: t('aiContentSec.promptGuard.operation'),
      key: 'operation',
      render: (_: any, record: RuleItem, index: number) => (
        <Space size="small">
          <Button type="link" size="small" onClick={() => handleEditRule(type, record, index)}>{t('aiContentSec.promptGuard.edit')}</Button>
          <Button type="link" size="small" danger onClick={() => handleDeleteRule(type, index)}>{t('aiContentSec.promptGuard.delete')}</Button>
        </Space>
      ),
    },
  ];

  const logColumns = [
    {
      title: t('aiContentSec.time'),
      dataIndex: 'time',
      key: 'time',
    },
    {
      title: t('aiContentSec.promptGuard.logAttackType'),
      dataIndex: 'attackType',
      key: 'attackType',
    },
    {
      title: t('aiContentSec.promptGuard.logMatchedRule'),
      dataIndex: 'matchedRule',
      key: 'matchedRule',
    },
    {
      title: t('aiContentSec.promptGuard.logResult'),
      dataIndex: 'result',
      key: 'result',
      render: (result: string) => {
        const colorMap: Record<string, string> = { blocked: 'red' };
        const labelMap: Record<string, string> = { blocked: t('aiContentSec.promptGuard.blocked') };
        return <Tag color={colorMap[result] || 'default'}>{labelMap[result] || result}</Tag>;
      },
    },
    {
      title: t('aiContentSec.promptGuard.logSummary'),
      dataIndex: 'summary',
      key: 'summary',
      ellipsis: true,
    },
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
        <div style={{ maxWidth: 800 }}>
          {/* Detection Mode */}
          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.promptGuard.detectMode')}</div>
            <Radio.Group
              value={form.detectMode}
              onChange={e => updateField('detectMode', e.target.value)}
            >
              <Radio value="deny">{t('aiContentSec.promptGuard.denyMode')}</Radio>
              <Radio value="allow">{t('aiContentSec.promptGuard.allowMode')}</Radio>
            </Radio.Group>
            <div style={{ marginTop: 4, color: '#999', fontSize: 12 }}>
              {t('aiContentSec.promptGuard.denyModeHint')}
            </div>
          </div>

          <Divider />

          {/* Deny Rules */}
          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontWeight: 500 }}>{t('aiContentSec.promptGuard.denyRules')}</span>
              <Button type="primary" size="small" onClick={() => handleAddRule('deny')}>{t('aiContentSec.promptGuard.addRule')}</Button>
            </div>
            <Table
              dataSource={form.denyRules}
              columns={getRuleColumns('deny')}
              rowKey="key"
              pagination={false}
              size="small"
            />
          </div>

          <Divider />

          {/* Allow Rules */}
          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontWeight: 500 }}>{t('aiContentSec.promptGuard.allowRules')}</span>
              <Button type="primary" size="small" onClick={() => handleAddRule('allow')}>{t('aiContentSec.promptGuard.addRule')}</Button>
            </div>
            <Table
              dataSource={form.allowRules}
              columns={getRuleColumns('allow')}
              rowKey="key"
              pagination={false}
              size="small"
            />
          </div>

          <Divider />

          {/* Reject Message */}
          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.rejectMessage')}</div>
            <Input
              value={form.rejectMessage}
              onChange={e => updateField('rejectMessage', e.target.value)}
              placeholder={t('aiContentSec.rejectMessage') as string}
            />
          </div>

          <Divider />
          <Space>
            <Button type="primary" onClick={handleSave}>{t('aiContentSec.saveConfig')}</Button>
            <Button onClick={handleReset}>{t('aiContentSec.resetConfig')}</Button>
          </Space>
        </div>
      </Card>

      {/* Section 3: Recent Detection Logs */}
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

      {/* Rule Edit Modal */}
      <Modal
        title={editingIndex === -1 ? t('aiContentSec.promptGuard.addRule') : t('aiContentSec.promptGuard.edit')}
        open={modalVisible}
        onOk={handleModalOk}
        onCancel={() => setModalVisible(false)}
        okText={t('aiContentSec.saveConfig')}
        cancelText={t('aiContentSec.resetConfig')}
      >
        <div style={{ marginBottom: 16 }}>
          <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.promptGuard.ruleName')}</div>
          <Input
            value={ruleName}
            onChange={e => setRuleName(e.target.value)}
            placeholder={t('aiContentSec.promptGuard.ruleName') as string}
          />
        </div>
        <div>
          <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.promptGuard.regex')}</div>
          <Input
            value={ruleRegex}
            onChange={e => setRuleRegex(e.target.value)}
            placeholder={t('aiContentSec.promptGuard.regex') as string}
          />
        </div>
      </Modal>
    </div>
  );
};

export default AiPromptGuardPage;
