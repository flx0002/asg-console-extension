import React, { useState, useEffect } from 'react';
import { Card, Switch, Checkbox, Input, Table, Tag, Button, message, Space, Divider, Modal, Popconfirm, Typography } from 'antd';
import { useRequest } from 'ahooks';
import { getGlobalPluginInstance, updateGlobalPluginInstance, updateRoutePluginInstance } from '@/services';
import { GlobalSwitchHint, RouteScopeCard, useRouteScope } from '../_route-scope';
// 审计事件入口已统一到「AI 内容安全 → 内容安全事件」（三权分立），本页不再内嵌日志表
import { useTranslation } from 'react-i18next';
import yaml from 'js-yaml';

const PLUGIN_NAME = 'ai-pii-guard';

// spec.yaml 契约：rules 为对象数组（name/pattern/replacement + IR-023 扩展 category/severity/action）。
// 页面勾选的是内置规则短名，保存时展开为完整对象；pattern 与插件 DefaultPIIRules/DefaultSecretRules/
// DefaultCodeRules 保持一致，避免两套定义漂移。
interface RuleObject {
  name: string;
  pattern: string;
  replacement: string;
  category: 'pii' | 'secret' | 'code';
  severity: 'low' | 'medium' | 'high' | 'critical';
  action: 'mask' | 'block' | 'alert';
}

const PAGE_RULE_DEFS: Record<string, RuleObject[]> = {
  email: [{ name: 'email', pattern: '\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b', replacement: '[EMAIL_REDACTED]', category: 'pii', severity: 'medium', action: 'mask' }],
  phone: [
    { name: 'phone_cn', pattern: '\\b1[3-9]\\d{9}\\b', replacement: '[PHONE_REDACTED]', category: 'pii', severity: 'medium', action: 'mask' },
    { name: 'phone_intl', pattern: '\\+\\d{1,3}[-.\\s]?\\d{1,14}', replacement: '[PHONE_REDACTED]', category: 'pii', severity: 'low', action: 'mask' },
  ],
  idcard: [{ name: 'id_card_cn', pattern: '\\b\\d{17}[\\dXx]\\b', replacement: '[ID_REDACTED]', category: 'pii', severity: 'high', action: 'mask' }],
  creditcard: [{ name: 'credit_card', pattern: '\\b(?:\\d{4}[-\\s]?){3}\\d{4}\\b', replacement: '[CARD_REDACTED]', category: 'pii', severity: 'high', action: 'mask' }],
  ssn: [{ name: 'ssn_us', pattern: '\\b\\d{3}-\\d{2}-\\d{4}\\b', replacement: '[SSN_REDACTED]', category: 'pii', severity: 'high', action: 'mask' }],
  ipv4: [{ name: 'ipv4', pattern: '\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b', replacement: '[IP_REDACTED]', category: 'pii', severity: 'low', action: 'mask' }],
  apikey: [{ name: 'generic_secret_assignment', pattern: '(?i)\\b(api[_-]?key|apikey|secret[_-]?key|access[_-]?token|auth[_-]?token|passwd|password)\\b["\']?\\s*[:=]\\s*["\']?[A-Za-z0-9+/=_-]{12,}', replacement: '[SECRET_REDACTED]', category: 'secret', severity: 'high', action: 'block' }],
};

// 页面未暴露的 IR-023 保护规则：始终写入 rules，避免前端保存后凭据阻断/代码告警失效。
const ALWAYS_ON_RULES: RuleObject[] = [
  { name: 'aws_access_key', pattern: '\\bAKIA[0-9A-Z]{16}\\b', replacement: '[SECRET_REDACTED]', category: 'secret', severity: 'critical', action: 'block' },
  { name: 'private_key_block', pattern: '-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY( BLOCK)?-----', replacement: '[SECRET_REDACTED]', category: 'secret', severity: 'critical', action: 'block' },
  { name: 'github_token', pattern: '\\bgh[pousr]_[A-Za-z0-9]{30,}\\b', replacement: '[SECRET_REDACTED]', category: 'secret', severity: 'critical', action: 'block' },
  { name: 'openai_style_key', pattern: '\\bsk-[A-Za-z0-9_-]{20,}\\b', replacement: '[SECRET_REDACTED]', category: 'secret', severity: 'high', action: 'block' },
  { name: 'jwt_token', pattern: '\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,}\\b', replacement: '[SECRET_REDACTED]', category: 'secret', severity: 'high', action: 'block' },
  { name: 'code_snippet', pattern: '(?m)(?:\\x60{3})|(?:^\\s*(?:def\\s+\\w+\\(|func\\s+\\w+\\(|class\\s+\\w+|import\\s+[\\w.]+|package\\s+\\w+|#include\\s*[<"]))', replacement: '[CODE_REDACTED]', category: 'code', severity: 'medium', action: 'alert' },
];

// 规则名 → 页面短名，用于从 spec 格式配置回读勾选状态。
const RULE_TO_PAGE: Record<string, string> = {};
Object.entries(PAGE_RULE_DEFS).forEach(([shortName, rules]) => {
  rules.forEach(r => { RULE_TO_PAGE[r.name] = shortName; });
});
const ALL_BUILTIN_NAMES = new Set([...Object.values(PAGE_RULE_DEFS).flat().map(r => r.name), ...ALWAYS_ON_RULES.map(r => r.name)]);

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
  responseProtect: false,
  builtInRules: ['email', 'phone', 'idcard', 'creditcard', 'ssn', 'ipv4', 'apikey'],
  customRules: [],
};

// 兼容旧格式（checkRequest + 字符串数组 rules + customRules）与新格式（protect_request + 对象数组 rules）。
const parseConfig = (cfg: any): FormState => {
  const requestProtect = cfg.protect_request ?? cfg.checkRequest ?? defaultFormState.requestProtect;
  const responseProtect = cfg.protect_response ?? cfg.checkResponse ?? defaultFormState.responseProtect;
  const rawRules: any[] = Array.isArray(cfg.rules) ? cfg.rules : [];
  const builtInRules: string[] = [];
  let customRules: CustomRule[] = [];

  if (rawRules.length > 0 && typeof rawRules[0] === 'string') {
    // 旧格式：rules 是内置规则短名字符串数组
    rawRules.forEach(name => {
      if (RULE_TO_PAGE[name] && !builtInRules.includes(RULE_TO_PAGE[name])) builtInRules.push(RULE_TO_PAGE[name]);
    });
    customRules = Array.isArray(cfg.customRules) ? cfg.customRules : [];
  } else if (rawRules.length > 0) {
    // 新格式：rules 是对象数组
    rawRules.forEach(r => {
      const name = typeof r?.name === 'string' ? r.name : '';
      if (!name) return;
      const shortName = RULE_TO_PAGE[name];
      if (shortName) {
        if (!builtInRules.includes(shortName)) builtInRules.push(shortName);
      } else if (!ALL_BUILTIN_NAMES.has(name)) {
        customRules.push({ name, regex: r.pattern ?? '', replaceValue: r.replacement ?? '' });
      }
    });
  } else {
    // 空配置：插件将使用全部内置规则，页面勾选默认全部
    builtInRules.push(...defaultFormState.builtInRules);
  }

  return { requestProtect, responseProtect, builtInRules, customRules };
};

const AiPiiGuardPage: React.FC = () => {
  const { t } = useTranslation();
  const scope = useRouteScope(PLUGIN_NAME);
  const [enabled, setEnabled] = useState(false);
  const [form, setForm] = useState<FormState>({ ...defaultFormState });
  const [initialForm, setInitialForm] = useState<FormState>({ ...defaultFormState });
  const [pluginData, setPluginData] = useState<any>(null);

  // Modal state for custom rule CRUD
  const [modalVisible, setModalVisible] = useState(false);
  const [editingIndex, setEditingIndex] = useState(-1);
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
              const parsed = parseConfig(cfg);
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

  const buildRawConfig = () => {
    const rules: RuleObject[] = [];
    form.builtInRules.forEach(shortName => {
      (PAGE_RULE_DEFS[shortName] ?? []).forEach(r => rules.push({ ...r }));
    });
    ALWAYS_ON_RULES.forEach(r => rules.push({ ...r }));
    form.customRules.forEach(c => {
      if (!c.name || !c.regex) return;
      rules.push({
        name: c.name,
        pattern: c.regex,
        replacement: c.replaceValue || '[REDACTED]',
        category: 'pii',
        severity: 'medium',
        action: 'mask',
      });
    });
    return yaml.dump({
      protect_request: form.requestProtect,
      protect_response: form.responseProtect,
      log_matches: true,
      rules,
    });
  };

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

      {/* Zone 3.5: 自定义 PII 规则 */}
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
