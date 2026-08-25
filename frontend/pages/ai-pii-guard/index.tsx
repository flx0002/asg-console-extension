import React, { useState, useEffect } from 'react';
import { Card, Switch, Checkbox, Input, Table, Tag, Button, message, Space, Divider, Modal } from 'antd';
import { useRequest } from 'ahooks';
import { getGlobalPluginInstance, updateGlobalPluginInstance } from '@/services';
import { getAuditLogs, getAuditSessions } from '@/services/audit-chain-service';
import { useTranslation } from 'react-i18next';
import yaml from 'js-yaml';

const PLUGIN_NAME = 'ai-pii-guard';
const EVENT_TYPE_FILTER = 'pii_leak';

const BUILT_IN_RULE_OPTIONS = [
  { label: 'aiContentSec.piiGuard.email', value: 'email' },
  { label: 'aiContentSec.piiGuard.phone', value: 'phone' },
  { label: 'aiContentSec.piiGuard.idcard', value: 'idcard' },
  { label: 'aiContentSec.piiGuard.creditcard', value: 'creditcard' },
  { label: 'aiContentSec.piiGuard.ssn', value: 'ssn' },
  { label: 'aiContentSec.piiGuard.ipv4', value: 'ipv4' },
  { label: 'aiContentSec.piiGuard.apikey', value: 'apikey' },
] as const;

interface CustomRule {
  name: string;
  regex: string;
  replaceValue: string;
}

interface FormState {
  requestProtect: boolean;
  responseProtect: boolean;
  builtInRules: string[];
  customRules: CustomRule[];
}

const defaultFormState: FormState = {
  requestProtect: true,
  responseProtect: true,
  builtInRules: ['email', 'phone', 'idcard', 'creditcard', 'ssn', 'ipv4', 'apikey'],
  customRules: [
    { name: '自定义手机号', regex: '\\d{3}-\\d{4}-\\d{4}', replaceValue: '***-****-****' },
    { name: '内部工号', regex: 'EMP\\d{6}', replaceValue: 'EMP******' },
  ],
};

const AiPiiGuardPage: React.FC = () => {
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
          const piiEvent = events.find((e: any) => e.type === EVENT_TYPE_FILTER) || {};
          const ts = r.timestamp ?? 0;
          return {
            time: ts ? new Date(ts).toLocaleString('zh-CN') : '-',
            type: piiEvent.type || EVENT_TYPE_FILTER,
            original: piiEvent.detail || '-',
            masked: r.action === 'masked' ? t('aiContentSec.securityGuard.masked') : '-',
            source: r.step_type === 'user_input' ? 'request' : 'response',
          };
        });
        setDetectionLogs(mapped);
      },
    }
  );

  useEffect(() => { loadDetectionLogs(); }, []);

  // Modal state for custom rule CRUD
  const [modalVisible, setModalVisible] = useState(false);
  const [editingIndex, setEditingIndex] = useState(-1); // -1 means adding new
  const [ruleName, setRuleName] = useState('');
  const [ruleRegex, setRuleRegex] = useState('');
  const [ruleReplaceValue, setRuleReplaceValue] = useState('');

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
                requestProtect: cfg.checkRequest ?? defaultFormState.requestProtect,
                responseProtect: cfg.checkResponse ?? defaultFormState.responseProtect,
                builtInRules: cfg.rules ?? defaultFormState.builtInRules,
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
        checkRequest: form.requestProtect,
        checkResponse: form.responseProtect,
        rules: form.builtInRules,
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

  const handleAddRule = () => {
    setEditingIndex(-1);
    setRuleName('');
    setRuleRegex('');
    setRuleReplaceValue('');
    setModalVisible(true);
  };

  const handleEditRule = (record: CustomRule, index: number) => {
    setEditingIndex(index);
    setRuleName(record.name);
    setRuleRegex(record.regex);
    setRuleReplaceValue(record.replaceValue);
    setModalVisible(true);
  };

  const handleDeleteRule = (index: number) => {
    Modal.confirm({
      title: t('aiContentSec.piiGuard.deleteConfirm'),
      onOk: () => {
        const newRules = [...form.customRules];
        newRules.splice(index, 1);
        updateField('customRules', newRules);
      },
    });
  };

  const handleModalOk = () => {
    if (!ruleName || !ruleRegex) {
      message.warning('请填写规则名称和正则表达式');
      return;
    }
    const newRule: CustomRule = { name: ruleName, regex: ruleRegex, replaceValue: ruleReplaceValue };
    const newRules = [...form.customRules];
    if (editingIndex === -1) {
      newRules.push(newRule);
    } else {
      newRules[editingIndex] = newRule;
    }
    updateField('customRules', newRules);
    setModalVisible(false);
  };

  const customRuleColumns = [
    {
      title: t('aiContentSec.piiGuard.ruleName'),
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: t('aiContentSec.piiGuard.regex'),
      dataIndex: 'regex',
      key: 'regex',
      render: (text: string) => <code>{text}</code>,
    },
    {
      title: t('aiContentSec.piiGuard.replaceValue'),
      dataIndex: 'replaceValue',
      key: 'replaceValue',
    },
    {
      title: t('aiContentSec.piiGuard.operation'),
      key: 'operation',
      render: (_: any, record: CustomRule, index: number) => (
        <Space size="small">
          <a onClick={() => handleEditRule(record, index)}>{t('aiContentSec.piiGuard.edit')}</a>
          <a onClick={() => handleDeleteRule(index)} style={{ color: 'red' }}>{t('aiContentSec.piiGuard.delete')}</a>
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
      title: t('aiContentSec.piiGuard.logType'),
      dataIndex: 'type',
      key: 'type',
    },
    {
      title: t('aiContentSec.piiGuard.logOriginal'),
      dataIndex: 'original',
      key: 'original',
    },
    {
      title: t('aiContentSec.piiGuard.logMasked'),
      dataIndex: 'masked',
      key: 'masked',
    },
    {
      title: t('aiContentSec.piiGuard.logSource'),
      dataIndex: 'source',
      key: 'source',
      render: (source: string) => {
        const isRequest = source === 'request';
        return (
          <Tag color={isRequest ? 'blue' : 'green'}>
            {isRequest ? t('aiContentSec.piiGuard.request') : t('aiContentSec.piiGuard.response')}
          </Tag>
        );
      },
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
        <div style={{ maxWidth: 700 }}>
          <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 500 }}>{t('aiContentSec.piiGuard.requestProtect')}</span>
            <Switch
              checked={form.requestProtect}
              onChange={v => updateField('requestProtect', v)}
              checkedChildren={t('aiContentSec.on')}
              unCheckedChildren={t('aiContentSec.off')}
            />
          </div>

          <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 500 }}>{t('aiContentSec.piiGuard.responseProtect')}</span>
            <Switch
              checked={form.responseProtect}
              onChange={v => updateField('responseProtect', v)}
              checkedChildren={t('aiContentSec.on')}
              unCheckedChildren={t('aiContentSec.off')}
            />
          </div>

          <Divider />

          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 8, fontWeight: 500 }}>{t('aiContentSec.piiGuard.builtInRules')}</div>
            <Checkbox.Group
              value={form.builtInRules}
              onChange={v => updateField('builtInRules', v as string[])}
              options={BUILT_IN_RULE_OPTIONS.map(opt => ({
                label: t(opt.label),
                value: opt.value,
              }))}
            />
          </div>

          <Divider />

          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 8, fontWeight: 500 }}>{t('aiContentSec.piiGuard.customRules')}</div>
            <Table
              dataSource={form.customRules}
              columns={customRuleColumns}
              rowKey="name"
              pagination={false}
              size="small"
              style={{ marginBottom: 12 }}
            />
            <Button type="dashed" onClick={handleAddRule}>
              {t('aiContentSec.piiGuard.addRule')}
            </Button>
          </div>

          <Divider />
          <Space>
            <Button type="primary" onClick={handleSave}>{t('aiContentSec.saveConfig')}</Button>
            <Button onClick={handleReset}>{t('aiContentSec.resetConfig')}</Button>
          </Space>
        </div>
      </Card>

      {/* Section 3: Recent PII Detection Logs */}
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

      {/* Modal for adding/editing custom rules */}
      <Modal
        title={editingIndex === -1 ? t('aiContentSec.piiGuard.addRule') : t('aiContentSec.piiGuard.edit')}
        open={modalVisible}
        onOk={handleModalOk}
        onCancel={() => setModalVisible(false)}
      >
        <div style={{ marginBottom: 16 }}>
          <div style={{ marginBottom: 4 }}>{t('aiContentSec.piiGuard.ruleName')}</div>
          <Input value={ruleName} onChange={e => setRuleName(e.target.value)} />
        </div>
        <div style={{ marginBottom: 16 }}>
          <div style={{ marginBottom: 4 }}>{t('aiContentSec.piiGuard.regex')}</div>
          <Input value={ruleRegex} onChange={e => setRuleRegex(e.target.value)} />
        </div>
        <div style={{ marginBottom: 16 }}>
          <div style={{ marginBottom: 4 }}>{t('aiContentSec.piiGuard.replaceValue')}</div>
          <Input value={ruleReplaceValue} onChange={e => setRuleReplaceValue(e.target.value)} />
        </div>
      </Modal>
    </div>
  );
};

export default AiPiiGuardPage;
