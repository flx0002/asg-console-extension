/* eslint-disable max-len, no-nested-ternary */
/**
 * ai-prompt-guard 配置页 · 提示攻击防护
 *
 * 本轮返工（对齐 4 项新批评）：
 *  1. 去掉顶部「配置项之间的关系」Alert——仅本页有、默认展开且内容有误，风格与其他页不一致
 *  2. 「检测模式」原为黑/白名单选择（deny/allow）——改为 WAF 式「命中处置」：默认以检测规则运行，
 *     可配置命中规则后 拦截 / 仅检测告警。后端契约字段为 `detectMode`（deny=拦截 / monitor=仅告警），
 *     本页下发真实字段名与取值（BE-012：根因是前端之前写了后端不读的 `mode`，已对齐）。
 *     本页默认以检测规则方式运行；allowRules 不在本页编辑，读入后端存量后只读透传保留。
 *  3. 内置内容合规词库不再是「独立 Wasm 内置处置通道」——它与本页检测规则同构，命中处置
 *     同样遵循「命中处置」模式；词库只是默认预置在库里的检测词条。
 *  4. 本页不展示词库详情（折叠卡片/KPI 已移除），仅在检测规则区给一行简要说明 + 跳转
 *     「内容合规词库」页；keywordRules/advisoryRules 读入后原样透传保存，避免本页保存清空存量词库。
 *
 * 数据流：本页编辑 denyRules/detectMode/rejectMessage；allowRules/keywordRules/advisoryRules 均只读
 * 透传（不渲染编辑入口），由后端存量、词库页与运维装载脚本维护，本页保存不会静默清空。
 */
import React, { useState, useEffect } from 'react';
import {
  Card, Switch, Input, Radio, Table, Tag, Button, message, Space, Modal,
  Typography, Popconfirm, Tooltip,
} from 'antd';
import { useRequest } from 'ahooks';
import { getGlobalPluginInstance, updateGlobalPluginInstance, updateRoutePluginInstance } from '@/services';
import { GlobalSwitchHint, RouteScopeCard, useRouteScope } from '../_route-scope';
import { useTranslation } from 'react-i18next';
import yaml from 'js-yaml';
import appAData from './appA_rules.json';

const PLUGIN_NAME = 'ai-prompt-guard';

interface RuleItem { name: string; regex: string; matchType: string; }
interface KeywordRuleGroup { name: string; patterns: string[]; enabled: boolean; }
interface AdvisoryRule { name: string; disclaimer: string; enabled: boolean; }

interface ComplianceCategory {
  code: string; name: string; mode: 'intercept' | 'advisory';
  patterns?: string[]; disclaimer?: string;
}

const COMPLIANCE_SECTIONS: { categories: ComplianceCategory[] }[] = (appAData as any).sections || [];
const ALL_COMPLIANCE_CATEGORIES: ComplianceCategory[] = COMPLIANCE_SECTIONS.flatMap((s) => s.categories);

const defaultDenyRules: RuleItem[] = [
  { name: '提示词注入-忽略指令', regex: '(?i)(ignore\\s+(previous|above)\\s+instructions?|忽略(上面|之前)的(指令|要求))', matchType: 'regex' },
  { name: '系统提示词窃取', regex: '(?i)(reveal\\s+system\\s+prompt|输出(系统)?提示词)', matchType: 'regex' },
  { name: '角色扮演越狱', regex: '(?i)(you\\s+are\\s+now\\s+(DAN|developer mode)|假装(你)?是(?!.*安全))', matchType: 'regex' },
];
const defaultAllowRules: RuleItem[] = [];  // 本页不编辑，仅在读入后端存量缺失时占位

// 内置基线词库（后端不可用时透传用；正常应从 CR 读取存量）
const buildDefaultKeywordRules = (): KeywordRuleGroup[] =>
  ALL_COMPLIANCE_CATEGORIES
    .filter((c) => c.mode !== 'advisory')
    .map((c) => ({ name: `${c.code} ${c.name}`, patterns: c.patterns || [], enabled: true }));

const buildDefaultAdvisoryRules = (): AdvisoryRule[] =>
  ALL_COMPLIANCE_CATEGORIES
    .filter((c) => c.mode === 'advisory')
    .map((c) => ({ name: `${c.code} ${c.name}`, disclaimer: c.disclaimer || '', enabled: true }));

// 归一化后端 detectMode：兼容存量旧写法（后端不读的 `mode` 与 block/detect 别名）。
// 与数据面 ai-prompt-guard parseConfig 的归一口径保持一致：deny=拦截 / monitor=仅告警。
const toDetectMode = (v: any): 'deny' | 'monitor' => {
  const s = String(v ?? 'deny').toLowerCase();
  return (s === 'monitor' || s === 'detect' || s === 'observe' || s === 'pass' || s === 'dry_run' || s === 'dryrun') ? 'monitor' : 'deny';
};

interface FormState {
  detectMode: 'deny' | 'monitor';
  checkRequest: boolean;
  checkResponse: boolean;
  denyRules: RuleItem[];
  allowRules: RuleItem[];
  keywordRules: KeywordRuleGroup[];   // 只读透传，本页不编辑
  advisoryRules: AdvisoryRule[];      // 只读透传，本页不编辑
  rejectMessage: string;
}

const defaultFormState: FormState = {
  detectMode: 'deny',
  checkRequest: true,
  checkResponse: false,
  denyRules: defaultDenyRules,
  allowRules: defaultAllowRules,
  keywordRules: buildDefaultKeywordRules(),
  advisoryRules: buildDefaultAdvisoryRules(),
  rejectMessage: '请求包含违规内容，已被拦截',
};

const AiPromptGuardPage: React.FC = () => {
  const { t } = useTranslation();
  const scope = useRouteScope(PLUGIN_NAME);
  const [enabled, setEnabled] = useState(false);
  const [form, setForm] = useState<FormState>({ ...defaultFormState });
  const [initialForm, setInitialForm] = useState<FormState>({ ...defaultFormState });
  const [pluginData, setPluginData] = useState<any>(null);

  const [ruleModal, setRuleModal] = useState<{ open: boolean; kind: 'deny' | 'allow'; idx: number; item: RuleItem }>({
    open: false, kind: 'deny', idx: -1, item: { name: '', regex: '', matchType: 'regex' },
  });

  const { run: loadConfig, loading } = useRequest(
    () => getGlobalPluginInstance(PLUGIN_NAME),
    {
      manual: true,
      onSuccess: (res: any) => {
        setPluginData(res);
        setEnabled(res?.enabled ?? false);
        if (res?.rawConfigurations) {
          try {
            const cfg = yaml.load(res.rawConfigurations) as any;
            if (cfg) {
              const parsed: FormState = {
                detectMode: toDetectMode(cfg.detectMode ?? cfg.mode),
                checkRequest: cfg.checkRequest ?? defaultFormState.checkRequest,
                checkResponse: cfg.checkResponse ?? defaultFormState.checkResponse,
                denyRules: cfg.denyRules ?? defaultFormState.denyRules,
                allowRules: cfg.allowRules ?? defaultFormState.allowRules,
                // 词库透传：优先取存量，缺失时回落内置基线，保证保存不会清空词库
                keywordRules: cfg.keywordRules ?? defaultFormState.keywordRules,
                advisoryRules: cfg.advisoryRules ?? defaultFormState.advisoryRules,
                rejectMessage: cfg.rejectMessage ?? defaultFormState.rejectMessage,
              };
              setForm(parsed);
              setInitialForm(parsed);
            }
          } catch {
            /* keep defaults */
          }
        }
      },
      onError: () => message.error(t('aiContentSec.loadFailed')),
    },
  );

  useEffect(() => { loadConfig(); }, []);

  const handleToggle = async (checked: boolean) => {
    try {
      const params = { ...pluginData, enabled: checked };
      delete params.configurations;
      await updateGlobalPluginInstance(PLUGIN_NAME, params);
      setEnabled(checked);
      message.success(t('aiContentSec.saveSuccess'));
    } catch { message.error(t('aiContentSec.saveFailed')); }
  };

  const buildRawConfig = () => yaml.dump({
    detectMode: form.detectMode,
    checkRequest: form.checkRequest,
    checkResponse: form.checkResponse,
    denyRules: form.denyRules.map(({ name, regex, matchType }) => ({ name, regex, matchType })),
    allowRules: form.allowRules.map(({ name, regex, matchType }) => ({ name, regex, matchType })),
    keywordRules: form.keywordRules,
    advisoryRules: form.advisoryRules,
    rejectMessage: form.rejectMessage,
  });

  const handleSave = async () => {
    try {
      const rawConfig = buildRawConfig();
      const params = { ...pluginData, enabled, rawConfigurations: rawConfig };
      delete params.configurations;
      await updateGlobalPluginInstance(PLUGIN_NAME, params);
      if (scope.selected.length) {
        const failed: string[] = [];
        await Promise.all(scope.selected.map(async (n: string) => {
          try {
            await updateRoutePluginInstance({ name: n, pluginName: PLUGIN_NAME }, { enabled: true, rawConfigurations: rawConfig });
          } catch { failed.push(n); }
        }));
        if (failed.length) message.warning(t('aiContentSec.routeScope.syncFail', { names: failed.join(', ') }));
        await scope.reload();
      }
      setInitialForm({ ...form });
      message.success(t('aiContentSec.saveSuccess'));
    } catch { message.error(t('aiContentSec.saveFailed')); }
  };

  const handleReset = () => setForm({ ...initialForm });
  const updateField = <K extends keyof FormState>(key: K, value: FormState[K]) => setForm((prev) => ({ ...prev, [key]: value }));

  // deny/allow 规则 CRUD
  const openRuleModal = (kind: 'deny' | 'allow', idx: number) => {
    const list = kind === 'deny' ? form.denyRules : form.allowRules;
    setRuleModal({ open: true, kind, idx, item: idx === -1 ? { name: '', regex: '', matchType: 'regex' } : { ...list[idx] } });
  };
  const commitRuleModal = () => {
    const { kind, idx, item } = ruleModal;
    if (!item.name || !item.regex) { message.warning(t('aiContentSec.requiredFields', '名称与表达式必填')); return; }
    const key = kind === 'deny' ? 'denyRules' : 'allowRules';
    const list = [...form[key]];
    if (idx === -1) list.push(item); else list[idx] = item;
    updateField(key, list);
    setRuleModal({ ...ruleModal, open: false });
  };
  const deleteRule = (kind: 'deny' | 'allow', idx: number) => {
    const key = kind === 'deny' ? 'denyRules' : 'allowRules';
    updateField(key, form[key].filter((_, i) => i !== idx));
  };

  const renderRuleColumns = (kind: 'deny' | 'allow') => [
    { title: t('aiContentSec.promptGuard.ruleName', '规则名称'), dataIndex: 'name', key: 'name' },
    { title: t('aiContentSec.promptGuard.rulePattern', '匹配表达式'), dataIndex: 'regex', key: 'regex', ellipsis: true },
    {
      title: t('aiContentSec.promptGuard.matchType', '匹配方式'),
      dataIndex: 'matchType',
      key: 'matchType',
      width: 120,
      render: (v: string) => <Tag>{v}</Tag>,
    },
    {
      title: t('aiContentSec.operation', '操作'),
      key: 'op',
      width: 160,
      render: (_: any, __: RuleItem, idx: number) => (
        <Space>
          <a onClick={() => openRuleModal(kind, idx)}>{t('aiContentSec.edit', '编辑')}</a>
          <Popconfirm title={t('aiContentSec.deleteConfirm', '确认删除？')} onConfirm={() => deleteRule(kind, idx)}>
            <a style={{ color: '#ff4d4f' }}>{t('aiContentSec.delete', '删除')}</a>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: '0 0 24px' }}>
      {/* Zone 1: Plugin Status */}
      <Card
        title={t('aiContentSec.pluginStatus', '插件状态')}
        style={{ marginBottom: 16 }}
        loading={loading}
      >
        <Space size="large" align="center">
          <Switch
            checked={enabled}
            onChange={handleToggle}
            checkedChildren={t('aiContentSec.on', '开')}
            unCheckedChildren={t('aiContentSec.off', '关')}
          />
          <Tag color={enabled ? 'green' : 'default'}>
            {enabled ? t('aiContentSec.enabled', '已启用') : t('aiContentSec.disabled', '已停用')}
          </Tag>
          <Typography.Text type="secondary">
            {t('aiContentSec.routeScope.summary', { count: scope.bound.length, defaultValue: `已绑定 ${scope.bound.length} 条路由` })}
          </Typography.Text>
        </Space>
        <GlobalSwitchHint />
      </Card>

      {/* Zone 2: Route Scope */}
      <RouteScopeCard scope={scope} getRawConfig={buildRawConfig} />

      {/* Zone 3: 配置项（无 Alert 说明；检测方向 + 命中处置） */}
      <Card title={t('aiContentSec.promptGuard.policy', '配置项')} style={{ marginBottom: 16 }}>
        <div style={{ maxWidth: 720 }}>
          {/* §3.1 请求方向检测 */}
          <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 500 }}>{t('aiContentSec.requestDetect', '请求方向检测')}</span>
            <Switch
              checked={form.checkRequest}
              onChange={(v) => updateField('checkRequest', v)}
              checkedChildren={t('aiContentSec.on', '开')}
              unCheckedChildren={t('aiContentSec.off', '关')}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('aiContentSec.requestDetectHint', '对进入网关的用户 prompt 做匹配')}
            </Typography.Text>
          </div>

          {/* §3.2 响应方向检测 */}
          <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 500 }}>{t('aiContentSec.responseDetect', '响应方向检测')}</span>
            <Switch
              checked={form.checkResponse}
              onChange={(v) => updateField('checkResponse', v)}
              checkedChildren={t('aiContentSec.on', '开')}
              unCheckedChildren={t('aiContentSec.off', '关')}
            />
            <Tooltip title={t('aiContentSec.responseDetectHint', '对上游模型返回内容进行护栏复检')}>
              <Typography.Text type="warning" style={{ fontSize: 12 }}>建议开启</Typography.Text>
            </Tooltip>
          </div>

          {/* §3.3 命中处置（默认黑名单运行，配置命中后拦截 / 仅检测） */}
          <div style={{ marginBottom: 8 }}>
            <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.promptGuard.hitAction', '命中处置')}</div>
            <Radio.Group value={form.detectMode} onChange={(e) => updateField('detectMode', e.target.value)}>
              <Radio value="deny">{t('aiContentSec.promptGuard.blockMode', '拦截模式（命中即拦截）')}</Radio>
              <Radio value="monitor">{t('aiContentSec.promptGuard.detectMode', '检测模式（仅告警，不拦截）')}</Radio>
            </Radio.Group>
            {form.detectMode === 'monitor' && (
              <div style={{ color: '#999', fontSize: 12, marginTop: 4 }}>
                {t('aiContentSec.promptGuard.detectModeHint', '检测模式：命中规则（含内置词库）仅记入安全事件审计，不拦截流量')}
              </div>
            )}
          </div>
        </div>
      </Card>

      {/* Zone 4: 检测规则（默认运行方式；内置词库简要说明 + 用户自定义检测规则） */}
      <Card
        title={t('aiContentSec.promptGuard.blacklist', '检测规则')}
        style={{ marginBottom: 16 }}
        extra={(
          <Button type="primary" size="small" onClick={() => openRuleModal('deny', -1)}>
            {t('aiContentSec.add', '新增规则')}
          </Button>
        )}
      >
        <Typography.Paragraph type="secondary" style={{ marginBottom: 12, fontSize: 13 }}>
          {t('aiContentSec.promptGuard.blacklistHint', '命中规则（含内置内容合规词库）即按上方「命中处置」拦截或仅告警。')}
          <Button
            type="link"
            size="small"
            style={{ padding: '0 4px' }}
            onClick={() => window.location.assign('/ai-content-compliance-kb')}
          >
            {t('aiContentSec.promptGuard.lexicon.goto', '前往内容合规词库')}
          </Button>
        </Typography.Paragraph>

        <Table
          rowKey={(r, i) => `${r.name}-${i}`}
          dataSource={form.denyRules}
          columns={renderRuleColumns('deny') as any}
          size="small"
          pagination={false}
        />
      </Card>

      {/* Zone 5: 拒绝响应文案 */}
      <Card title={t('aiContentSec.rejectMessage', '拒绝响应文案')} style={{ marginBottom: 16 }}>
        <div style={{ maxWidth: 720 }}>
          <Input
            value={form.rejectMessage}
            onChange={(e) => updateField('rejectMessage', e.target.value)}
            placeholder={defaultFormState.rejectMessage}
          />
        </div>
      </Card>

      {/* Zone 7: 保存动作 */}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
        <Button type="primary" onClick={handleSave}>{t('aiContentSec.saveConfig', '保存配置')}</Button>
        <Button onClick={handleReset}>{t('aiContentSec.resetConfig', '撤销改动')}</Button>
      </div>

      {/* Rule edit modal */}
      <Modal
        open={ruleModal.open}
        title={ruleModal.kind === 'deny'
          ? t('aiContentSec.promptGuard.blacklist', '检测规则')
          : t('aiContentSec.promptGuard.allowRules', '白名单规则')}
        onOk={commitRuleModal}
        onCancel={() => setRuleModal({ ...ruleModal, open: false })}
        okText={t('aiContentSec.saveConfig', '保存')}
        cancelText={t('aiContentSec.cancel', '取消')}
      >
        <div style={{ marginBottom: 12 }}>
          <div style={{ marginBottom: 6 }}>{t('aiContentSec.promptGuard.ruleName', '规则名称')}</div>
          <Input value={ruleModal.item.name} onChange={(e) => setRuleModal({ ...ruleModal, item: { ...ruleModal.item, name: e.target.value } })} />
        </div>
        <div style={{ marginBottom: 12 }}>
          <div style={{ marginBottom: 6 }}>{t('aiContentSec.promptGuard.rulePattern', '匹配表达式')}</div>
          <Input.TextArea rows={3} value={ruleModal.item.regex} onChange={(e) => setRuleModal({ ...ruleModal, item: { ...ruleModal.item, regex: e.target.value } })} />
        </div>
        <div>
          <div style={{ marginBottom: 6 }}>{t('aiContentSec.promptGuard.matchType', '匹配方式')}</div>
          <Radio.Group value={ruleModal.item.matchType} onChange={(e) => setRuleModal({ ...ruleModal, item: { ...ruleModal.item, matchType: e.target.value } })}>
            <Radio value="regex">regex</Radio>
            <Radio value="contains">contains</Radio>
            <Radio value="equal">equal</Radio>
          </Radio.Group>
        </div>
      </Modal>
    </div>
  );
};

export default AiPromptGuardPage;
