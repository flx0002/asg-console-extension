/* eslint-disable max-len, no-nested-ternary */
import React, { useState, useEffect } from 'react';
import { Card, Switch, Input, InputNumber, Table, Tag, Button, message, Space, Modal, Select, Popconfirm, Typography } from 'antd';
import { useRequest } from 'ahooks';
import { getGlobalPluginInstance, updateGlobalPluginInstance, updateRoutePluginInstance } from '@/services';
import { GlobalSwitchHint, RouteScopeCard, useRouteScope } from '../_route-scope';
// 审计事件入口已统一到「AI 内容安全 → 内容安全事件」（三权分立：配置与审计分离）
import { useTranslation } from 'react-i18next';
import yaml from 'js-yaml';

const PLUGIN_NAME = 'request-block';

interface FormState {
  requestBodyFilter: boolean;
  caseSensitive: boolean;
  blockStatusCode: number;
  blockMessage: string;
}

interface PresetRule {
  key: string;
  name: string;
  keywords: string[];
  scenario: string;
  enabled: boolean;
}

interface CustomRule {
  key: string;
  keyword: string;
  matchMode: string;
  enabled: boolean;
}

const defaultFormState: FormState = {
  requestBodyFilter: true,
  caseSensitive: false,
  blockStatusCode: 403,
  blockMessage: '请求包含违规内容，已被拦截',
};

const initialPresetRules: PresetRule[] = [
  {
    key: '1',
    name: '提示词注入防护',
    keywords: ['忽略上面的指令', 'ignore previous instructions', 'ignore above'],
    scenario: 'LLM01',
    enabled: true,
  },
  {
    key: '2',
    name: '系统提示词泄露防护',
    keywords: ['system prompt', '系统提示词', '初始化指令', 'system instruction'],
    scenario: 'LLM07',
    enabled: true,
  },
  {
    key: '3',
    name: '敏感操作拦截',
    keywords: ['DELETE FROM', 'DROP TABLE', 'rm -rf'],
    scenario: 'LLM01',
    enabled: true,
  },
];

const initialCustomRules: CustomRule[] = [
  { key: '1', keyword: '越狱', matchMode: 'contains', enabled: true },
  { key: '2', keyword: 'jailbreak', matchMode: 'contains', enabled: true },
  { key: '3', keyword: 'DAN\\s+mode', matchMode: 'regex', enabled: false },
];

const AiKeywordFilterPage: React.FC = () => {
  const { t } = useTranslation();
  const scope = useRouteScope(PLUGIN_NAME);
  const [enabled, setEnabled] = useState(false);
  const [form, setForm] = useState<FormState>({ ...defaultFormState });
  const [initialForm, setInitialForm] = useState<FormState>({ ...defaultFormState });
  const [pluginData, setPluginData] = useState<any>(null);

  // 检测日志已迁移至「AI 内容安全 → 内容安全事件」统一入口（三权分立：配置员与审计员权限分离）

  // Rule state
  const [presetRules, setPresetRules] = useState<PresetRule[]>(initialPresetRules);
  const [customRules, setCustomRules] = useState<CustomRule[]>(initialCustomRules);

  // Modal state
  const [modalVisible, setModalVisible] = useState(false);
  const [modalType, setModalType] = useState<'preset' | 'custom'>('custom');
  const [editingIndex, setEditingIndex] = useState(-1);

  // Custom rule form
  const [editKeyword, setEditKeyword] = useState('');
  const [editMatchMode, setEditMatchMode] = useState('contains');

  // Preset rule form
  const [editRuleName, setEditRuleName] = useState('');
  const [editKeywords, setEditKeywords] = useState('');
  const [editScenario, setEditScenario] = useState('');

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
              // 对齐官方 request-block schema：block_bodies / blocked_code / blocked_message / case_sensitive
              const bodies: string[] = Array.isArray(cfg.block_bodies) ? cfg.block_bodies : [];
              const hasBodies = bodies.length > 0;
              const parsed: FormState = {
                requestBodyFilter: cfg.block_bodies !== undefined ? hasBodies : defaultFormState.requestBodyFilter,
                caseSensitive: cfg.case_sensitive ?? defaultFormState.caseSensitive,
                blockStatusCode: cfg.blocked_code ?? defaultFormState.blockStatusCode,
                blockMessage: cfg.blocked_message ?? defaultFormState.blockMessage,
              };
              setForm(parsed);
              setInitialForm(parsed);
              if (hasBodies) {
                const set = new Set(bodies);
                setPresetRules(prev => prev.map(r => ({ ...r, enabled: r.keywords.some(k => set.has(k)) })));
                setCustomRules(prev => prev.map(r => ({ ...r, enabled: set.has(r.keyword) })));
              }
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
    // request-block 为输入侧、URL/头/正文“包含匹配”：将所有启用的预置/自定义关键词序列化进 block_bodies
    const bodies: string[] = [];
    if (form.requestBodyFilter) {
      presetRules.forEach(r => { if (r.enabled) r.keywords.forEach(k => bodies.push(k)); });
      customRules.forEach(r => { if (r.enabled && r.keyword) bodies.push(r.keyword); });
    }
    const uniq = Array.from(new Set(bodies.filter(Boolean)));
    return yaml.dump({
      block_bodies: uniq,
      blocked_code: form.blockStatusCode,
      blocked_message: form.blockMessage,
      case_sensitive: form.caseSensitive,
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

  // --- Custom Rules CRUD ---
  const openAddCustomModal = () => {
    setModalType('custom');
    setEditingIndex(-1);
    setEditKeyword('');
    setEditMatchMode('contains');
    setModalVisible(true);
  };

  const openEditCustomModal = (index: number) => {
    const rule = customRules[index];
    setModalType('custom');
    setEditingIndex(index);
    setEditKeyword(rule.keyword);
    setEditMatchMode(rule.matchMode);
    setModalVisible(true);
  };

  const handleCustomSave = () => {
    if (!editKeyword.trim()) {
      message.warning(t('aiContentSec.keywordFilter.keywordPlaceholder'));
      return;
    }
    if (editingIndex === -1) {
      // Add
      const newRule: CustomRule = {
        key: Date.now().toString(),
        keyword: editKeyword.trim(),
        matchMode: editMatchMode,
        enabled: true,
      };
      setCustomRules(prev => [...prev, newRule]);
    } else {
      // Edit
      setCustomRules(prev =>
        prev.map((r, i) =>
          (i === editingIndex ? { ...r, keyword: editKeyword.trim(), matchMode: editMatchMode } : r)));
    }
    setModalVisible(false);
  };

  const handleCustomDelete = (index: number) => {
    setCustomRules(prev => prev.filter((_, i) => i !== index));
  };

  // --- Preset Rules CRUD ---
  const openAddPresetModal = () => {
    setModalType('preset');
    setEditingIndex(-1);
    setEditRuleName('');
    setEditKeywords('');
    setEditScenario('');
    setModalVisible(true);
  };

  const openEditPresetModal = (index: number) => {
    const rule = presetRules[index];
    setModalType('preset');
    setEditingIndex(index);
    setEditRuleName(rule.name);
    setEditKeywords(rule.keywords.join(', '));
    setEditScenario(rule.scenario);
    setModalVisible(true);
  };

  const handlePresetSave = () => {
    if (!editRuleName.trim()) {
      message.warning(t('aiContentSec.keywordFilter.ruleNamePlaceholder'));
      return;
    }
    const keywords = editKeywords.split(',').map(k => k.trim()).filter(Boolean);
    if (keywords.length === 0) {
      message.warning(t('aiContentSec.keywordFilter.keywordsPlaceholder'));
      return;
    }
    if (editingIndex === -1) {
      // Add
      const newRule: PresetRule = {
        key: Date.now().toString(),
        name: editRuleName.trim(),
        keywords,
        scenario: editScenario.trim(),
        enabled: true,
      };
      setPresetRules(prev => [...prev, newRule]);
    } else {
      // Edit
      setPresetRules(prev =>
        prev.map((r, i) =>
          (i === editingIndex
            ? { ...r, name: editRuleName.trim(), keywords, scenario: editScenario.trim() }
            : r)));
    }
    setModalVisible(false);
  };

  const handlePresetDelete = (index: number) => {
    setPresetRules(prev => prev.filter((_, i) => i !== index));
  };

  const handleModalOk = () => {
    if (modalType === 'custom') {
      handleCustomSave();
    } else {
      handlePresetSave();
    }
  };

  const matchModeLabel = (mode: string) => {
    if (mode === 'contains') return t('aiContentSec.keywordFilter.matchContains');
    if (mode === 'regex') return t('aiContentSec.keywordFilter.matchRegex');
    return mode;
  };

  const presetColumns = [
    {
      title: t('aiContentSec.keywordFilter.ruleName'),
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: t('aiContentSec.keywordFilter.keywordList'),
      dataIndex: 'keywords',
      key: 'keywords',
      render: (keywords: string[]) => (
        <Space wrap>
          {keywords.map(kw => <Tag key={kw}>{kw}</Tag>)}
        </Space>
      ),
    },
    {
      title: t('aiContentSec.keywordFilter.scenario'),
      dataIndex: 'scenario',
      key: 'scenario',
      render: (scenario: string) => <Tag color="blue">{scenario}</Tag>,
    },
    {
      title: t('aiContentSec.keywordFilter.ruleStatus'),
      dataIndex: 'enabled',
      key: 'enabled',
      render: (val: boolean, _: PresetRule, index: number) => (
        <Switch
          checked={val}
          size="small"
          onChange={checked => {
            setPresetRules(prev => prev.map((r, i) => (i === index ? { ...r, enabled: checked } : r)));
          }}
        />
      ),
    },
    {
      title: t('aiContentSec.keywordFilter.operation'),
      key: 'operation',
      render: (_: any, __: PresetRule, index: number) => (
        <Space>
          <a onClick={() => openEditPresetModal(index)}>{t('aiContentSec.keywordFilter.edit')}</a>
          <Popconfirm
            title={t('aiContentSec.keywordFilter.deleteConfirm')}
            onConfirm={() => handlePresetDelete(index)}
            okText={t('aiContentSec.keywordFilter.delete')}
            cancelText={t('aiContentSec.keywordFilter.edit')}
          >
            <a style={{ color: '#ff4d4f' }}>{t('aiContentSec.keywordFilter.delete')}</a>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const customColumns = [
    {
      title: t('aiContentSec.keywordFilter.keyword'),
      dataIndex: 'keyword',
      key: 'keyword',
    },
    {
      title: t('aiContentSec.keywordFilter.matchMode'),
      dataIndex: 'matchMode',
      key: 'matchMode',
      render: (mode: string) => <Tag color={mode === 'regex' ? 'purple' : 'blue'}>{matchModeLabel(mode)}</Tag>,
    },
    {
      title: t('aiContentSec.keywordFilter.ruleStatus'),
      dataIndex: 'enabled',
      key: 'enabled',
      render: (val: boolean, _: CustomRule, index: number) => (
        <Switch
          checked={val}
          size="small"
          onChange={checked => {
            setCustomRules(prev => prev.map((r, i) => (i === index ? { ...r, enabled: checked } : r)));
          }}
        />
      ),
    },
    {
      title: t('aiContentSec.keywordFilter.operation'),
      key: 'operation',
      render: (_: any, __: CustomRule, index: number) => (
        <Space>
          <a onClick={() => openEditCustomModal(index)}>{t('aiContentSec.keywordFilter.edit')}</a>
          <Popconfirm
            title={t('aiContentSec.keywordFilter.deleteConfirm')}
            onConfirm={() => handleCustomDelete(index)}
            okText={t('aiContentSec.keywordFilter.delete')}
            cancelText={t('aiContentSec.keywordFilter.edit')}
          >
            <a style={{ color: '#ff4d4f' }}>{t('aiContentSec.keywordFilter.delete')}</a>
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
        <div style={{ maxWidth: 600 }}>
          <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 500 }}>{t('aiContentSec.keywordFilter.requestBodyFilter')}</span>
            <Switch
              checked={form.requestBodyFilter}
              onChange={v => updateField('requestBodyFilter', v)}
              checkedChildren={t('aiContentSec.on')}
              unCheckedChildren={t('aiContentSec.off')}
            />
          </div>

          <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 500 }}>{t('aiContentSec.keywordFilter.caseSensitive')}</span>
            <Switch
              checked={form.caseSensitive}
              onChange={v => updateField('caseSensitive', v)}
              checkedChildren={t('aiContentSec.on')}
              unCheckedChildren={t('aiContentSec.off')}
            />
          </div>

          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.keywordFilter.blockStatusCode')}</div>
            <InputNumber
              value={form.blockStatusCode}
              onChange={v => updateField('blockStatusCode', v ?? defaultFormState.blockStatusCode)}
              min={100}
              max={599}
              style={{ width: '100%' }}
            />
          </div>

          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.keywordFilter.blockMessage')}</div>
            <Input
              value={form.blockMessage}
              onChange={e => updateField('blockMessage', e.target.value)}
              placeholder={defaultFormState.blockMessage}
            />
          </div>

          <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12, marginBottom: 8 }}>
            {t('aiContentSec.keywordFilter.scopeHint')}
          </Typography.Text>

          <div style={{ marginTop: 24, display: 'flex', justifyContent: 'flex-end' }}>
            <Space>
              <Button type="primary" onClick={handleSave}>{t('aiContentSec.saveConfig')}</Button>
              <Button onClick={handleReset}>{t('aiContentSec.resetConfig')}</Button>
            </Space>
          </div>
        </div>
      </Card>

      {/* Zone 3.1: AI Preset Rules */}
      <Card
        title={t('aiContentSec.keywordFilter.presetRules')}
        style={{ marginBottom: 16 }}
        extra={<Button type="primary" size="small" onClick={openAddPresetModal}>{t('aiContentSec.keywordFilter.addRule')}</Button>}
      >
        <Table
          dataSource={presetRules}
          columns={presetColumns}
          rowKey="key"
          pagination={false}
          size="small"
        />
      </Card>

      {/* Zone 3.2: Custom Keyword Rules */}
      <Card
        title={t('aiContentSec.keywordFilter.customRules')}
        style={{ marginBottom: 16 }}
        extra={<Button type="primary" size="small" onClick={openAddCustomModal}>{t('aiContentSec.keywordFilter.addKeyword')}</Button>}
      >
        <Table
          dataSource={customRules}
          columns={customColumns}
          rowKey="key"
          pagination={false}
          size="small"
        />
      </Card>

      {/* Zone 4: 拦截日志已迁移至「AI 内容安全 → 内容安全事件」（三权分立：配置与审计分离） */}

      {/* Modal for Add/Edit */}
      <Modal
        open={modalVisible}
        title={modalType === 'custom'
          ? (editingIndex === -1 ? t('aiContentSec.keywordFilter.addKeyword') : t('aiContentSec.keywordFilter.edit'))
          : (editingIndex === -1 ? t('aiContentSec.keywordFilter.addRule') : t('aiContentSec.keywordFilter.edit'))
        }
        onOk={handleModalOk}
        onCancel={() => setModalVisible(false)}
        okText={t('aiContentSec.saveConfig')}
        cancelText={t('aiContentSec.resetConfig')}
      >
        {modalType === 'custom' ? (
          <>
            <div style={{ marginBottom: 16 }}>
              <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.keywordFilter.keyword')}</div>
              <Input
                value={editKeyword}
                onChange={e => setEditKeyword(e.target.value)}
                placeholder={t('aiContentSec.keywordFilter.keywordPlaceholder')}
              />
            </div>
            <div style={{ marginBottom: 16 }}>
              <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.keywordFilter.matchMode')}</div>
              <Select
                value={editMatchMode}
                onChange={v => setEditMatchMode(v)}
                style={{ width: '100%' }}
                placeholder={t('aiContentSec.keywordFilter.matchModePlaceholder')}
                options={[
                  { value: 'contains', label: t('aiContentSec.keywordFilter.matchContains') },
                  { value: 'regex', label: t('aiContentSec.keywordFilter.matchRegex') },
                ]}
              />
            </div>
          </>
        ) : (
          <>
            <div style={{ marginBottom: 16 }}>
              <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.keywordFilter.ruleName')}</div>
              <Input
                value={editRuleName}
                onChange={e => setEditRuleName(e.target.value)}
                placeholder={t('aiContentSec.keywordFilter.ruleNamePlaceholder')}
              />
            </div>
            <div style={{ marginBottom: 16 }}>
              <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.keywordFilter.keywordsInput')}</div>
              <Input
                value={editKeywords}
                onChange={e => setEditKeywords(e.target.value)}
                placeholder={t('aiContentSec.keywordFilter.keywordsPlaceholder')}
              />
            </div>
            <div style={{ marginBottom: 16 }}>
              <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.keywordFilter.scenario')}</div>
              <Input
                value={editScenario}
                onChange={e => setEditScenario(e.target.value)}
                placeholder={t('aiContentSec.keywordFilter.scenarioPlaceholder')}
              />
            </div>
          </>
        )}
      </Modal>
    </div>
  );
};

export default AiKeywordFilterPage;
