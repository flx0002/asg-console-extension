import React, { useState, useEffect } from 'react';
import { Card, Switch, Checkbox, Input, Table, Tag, Button, message, Space, Divider, Modal, Popconfirm, Typography } from 'antd';
import { useRequest } from 'ahooks';
import { getGlobalPluginInstance, updateGlobalPluginInstance, updateRoutePluginInstance } from '@/services';
import { GlobalSwitchHint, RouteScopeCard, useRouteScope } from '../_route-scope';
// 审计事件入口已统一到「AI 内容安全 → 内容安全事件」（三权分立），本页不再内嵌日志表
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
  const scope = useRouteScope(PLUGIN_NAME);
  const [enabled, setEnabled] = useState(false);
  const [form, setForm] = useState<FormState>({ ...defaultFormState });
  const [initialForm, setInitialForm] = useState<FormState>({ ...defaultFormState });
  const [pluginData, setPluginData] = useState<any>(null);

  // 检测日志已迁移至「AI 内容安全 → 内容安全事件」统一入口（三权分立：配置员与审计员权限分离）

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

  const buildRawConfig = () => yaml.dump({
    checkRequest: form.requestProtect,
    checkResponse: form.responseProtect,
    rules: form.builtInRules,
    customRules: form.customRules,
  });

  const handleSave = async () => {
    try {
      const rawConfig = buildRawConfig();
      const params = { ...pluginData, enabled, rawConfigurations: rawConfig };
      delete params.configurations;
      await updateGlobalPluginInstance(PLUGIN_NAME, params);
      if (scope.selected.length) {
        const failed: string[] = [];
        await Promise.all(scope.selected.map(async (n) => {
          try {
            await updateRoutePluginInstance({ name: n, pluginName: PLUGIN_NAME }, { enabled: true, rawConfigurations: rawConfig });
          } catch { failed.push(n); }
        }));
        if (failed.length) message.warning(t('aiContentSec.routeScope.syncFail', { names: failed.join(', ') }));
        await scope.reload();
      }
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
    const newRules = [...form.customRules];
    newRules.splice(index, 1);
    updateField('customRules', newRules);
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
          <Popconfirm
            title={t('aiContentSec.piiGuard.deleteConfirm')}
            onConfirm={() => handleDeleteRule(index)}
            okText={t('aiContentSec.piiGuard.delete')}
            cancelText={t('aiContentSec.piiGuard.edit')}
          >
            <a style={{ color: '#ff4d4f' }}>{t('aiContentSec.piiGuard.delete')}</a>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: '0 0 24px' }}>
      {/* Zone 1: Plugin Status */}
      <Card
        title={t('aiContentSec.pluginStatus')}
        style={{ marginBottom: 16 }}
        loading={loading}
      >
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
          <Typography.Text type="secondary">
            {t('aiContentSec.routeScope.summary', { count: scope.bound.length })}
          </Typography.Text>
        </Space>
        <GlobalSwitchHint />
      </Card>

      {/* Zone 2: Route Scope */}
      <RouteScopeCard scope={scope} getRawConfig={buildRawConfig} />

      {/* Zone 3: Configuration */}
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
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Space>
              <Button type="primary" onClick={handleSave}>{t('aiContentSec.saveConfig')}</Button>
              <Button onClick={handleReset}>{t('aiContentSec.resetConfig')}</Button>
            </Space>
          </div>
        </div>
      </Card>

      {/* Zone 3.5: 自定义 PII 规则 · Card 分区块，extra Button primary small */}
      <Card
        title={t('aiContentSec.piiGuard.customRules')}
        style={{ marginBottom: 16 }}
        extra={(
          <Button type="primary" size="small" onClick={handleAddRule}>
            {t('aiContentSec.piiGuard.addRule')}
          </Button>
        )}
      >
        <Table
          dataSource={form.customRules}
          columns={customRuleColumns}
          rowKey="name"
          pagination={false}
          size="small"
        />
      </Card>

      {/* Zone 4: 检测日志已迁移至「AI 内容安全 → 内容安全事件」（三权分立：配置与审计分离） */}

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
