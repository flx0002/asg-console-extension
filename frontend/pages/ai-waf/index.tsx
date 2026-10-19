/* eslint-disable max-len, no-nested-ternary */
import React, { useState, useEffect } from 'react';
import { Card, Switch, Radio, Table, Tag, Button, Input, message, Space, Modal, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useRequest } from 'ahooks';
import { getGlobalPluginInstance, updateGlobalPluginInstance, updateRoutePluginInstance } from '@/services';
import { GlobalSwitchHint, RouteScopeCard, useRouteScope } from '../_route-scope';
// 审计事件入口已统一到「AI 内容安全 → 内容安全事件」（三权分立），本页不再内嵌日志表
import yaml from 'js-yaml';

// 上游 Higress 内置 waf 插件（Coraza + OWASP CRS）真实配置契约：
//   useCRS: bool          —— 是否启用 OWASP 核心规则集
//   secRules: string[]    —— ModSecurity 指令数组（SecRule...），其余键插件一律忽略
// 因此本页只向网关下发 { useCRS, secRules }，预置规则集与自定义规则都编译进 secRules。
const PLUGIN_NAME = 'waf';

// Coraza 需要的引擎基线指令：规则引擎必须开启，请求/响应体访问按需开启，
// 否则 phase:2(请求体)/phase:4(响应体) 的 SecRule 取不到正文、不会命中。
const BASE_ENGINE = 'SecRuleEngine On';

// mode → 命中动作：block=拦截(deny,403)，detect=仅检测放行(pass)
const actionFor = (mode: string): string => (mode === 'block' ? 'deny,status:403' : 'pass');

// 每个预置规则集内真实 SecRule 模板中的 __ACT__ 占位符在保存时按 mode 替换。
interface PresetRuleSet {
  key: string;
  name: string;
  scenario: string;
  phase: 'request' | 'response';
  idPrefix: string;
  enabled: boolean;
  rules: string[]; // 含 __ACT__ 占位的真实规则
}

const PRESET_RULE_SETS: PresetRuleSet[] = [
  {
    key: '1',
    name: '提示词注入检测规则集',
    scenario: 'LLM01 Prompt Injection',
    phase: 'request',
    idPrefix: 'id:9101',
    enabled: true,
    rules: [
      'SecRule REQUEST_BODY "@rx (ignore|disregard|forget)\\s+(all\\s+)?(previous|prior|above)\\s+(instructions|prompts|directives)" "id:910101,phase:2,t:lowercase,__ACT__,msg:\'Prompt injection: instruction-override\'"',
      'SecRule REQUEST_BODY "@rx (reveal|show|print|repeat|output)\\s+(the\\s+)?(system\\s+prompt|initial\\s+prompt|hidden\\s+instructions)" "id:910102,phase:2,t:lowercase,__ACT__,msg:\'Prompt injection: system-prompt-extraction\'"',
      'SecRule REQUEST_BODY "@rx you\\s+are\\s+now\\s+(in\\s+)?(developer|dan|god|jailbreak)\\s*mode" "id:910103,phase:2,t:lowercase,__ACT__,msg:\'Prompt injection: jailbreak-persona\'"',
      'SecRule REQUEST_BODY "@rx <\\s*/?\\s*system\\s*>" "id:910104,phase:2,t:lowercase,__ACT__,msg:\'Prompt injection: forged-system-tag\'"',
      'SecRule REQUEST_BODY "@rx (pretend|act\\s+as\\s+if|imagine)\\s+(you\\s+)?(have\\s+no|don\'?t\\s+have|are\\s+free\\s+from)\\s+(rules|restrictions|guidelines|policies)" "id:910105,phase:2,t:lowercase,__ACT__,msg:\'Prompt injection: restriction-bypass\'"',
    ],
  },
  {
    key: '2',
    name: 'XSS输出检测规则集',
    scenario: 'LLM05 Improper Output Handling',
    phase: 'response',
    idPrefix: 'id:9202',
    enabled: true,
    rules: [
      'SecRule RESPONSE_BODY "@rx <script[^>]*>" "id:920201,phase:4,t:lowercase,__ACT__,msg:\'XSS: script tag in output\'"',
      'SecRule RESPONSE_BODY "@rx javascript\\s*:" "id:920202,phase:4,t:lowercase,__ACT__,msg:\'XSS: javascript-uri in output\'"',
      'SecRule RESPONSE_BODY "@rx on(error|load|click|mouseover)\\s*=" "id:920203,phase:4,t:lowercase,__ACT__,msg:\'XSS: inline-event-handler in output\'"',
    ],
  },
  {
    key: '3',
    name: 'SQL注入检测规则集',
    scenario: 'LLM01 Injection',
    phase: 'request',
    idPrefix: 'id:9303',
    enabled: true,
    rules: [
      'SecRule REQUEST_BODY "@rx union\\s+.*\\s+select" "id:930301,phase:2,t:lowercase,__ACT__,msg:\'SQLi: union-select\'"',
      'SecRule REQUEST_BODY "@rx select\\s+.*\\s+from\\s+information_schema" "id:930302,phase:2,t:lowercase,__ACT__,msg:\'SQLi: information-schema\'"',
      'SecRule REQUEST_BODY "@rx \\b(drop|delete|truncate)\\s+table" "id:930303,phase:2,t:lowercase,__ACT__,msg:\'SQLi: destructive-statement\'"',
    ],
  },
  {
    key: '4',
    name: '系统提示词泄露检测规则集',
    scenario: 'LLM07 System Prompt Leakage',
    phase: 'response',
    idPrefix: 'id:9404',
    enabled: false,
    rules: [
      'SecRule RESPONSE_BODY "@rx (my|the)\\s+(system\\s+)?(prompt|instructions)\\s+(is|are)\\s*:" "id:940401,phase:4,t:lowercase,__ACT__,msg:\'System-prompt-leak: self-disclosure\'"',
      'SecRule RESPONSE_BODY "@rx beginning\\s+of\\s+(system\\s+)?(prompt|instructions)" "id:940402,phase:4,t:lowercase,__ACT__,msg:\'System-prompt-leak: prompt-boundary\'"',
      'SecRule RESPONSE_BODY "@rx <system>.{20,}?</system>" "id:940403,phase:4,t:lowercase,__ACT__,msg:\'System-prompt-leak: system-block-in-output\'"',
      'SecRule RESPONSE_BODY "@rx you\\s+are\\s+(a|an)\\s+(helpful\\s+)?(ai\\s+)?(assistant|model)\\s+that\\s+was\\s+(created|trained)" "id:940404,phase:4,t:lowercase,__ACT__,msg:\'System-prompt-leak: identity-disclosure\'"',
    ],
  },
];

// 自定义规则默认样例（真实 SecRule，phase 覆盖请求与响应）
const DEFAULT_CUSTOM_RULES = `SecRule REQUEST_BODY "@rx (忽略|ignore).*(指令|instruction|prompt)" "id:1001,phase:2,deny,status:403,msg:'Prompt injection detected'"
SecRule RESPONSE_BODY "@rx <script.*?>" "id:2001,phase:4,deny,status:403,msg:'XSS in LLM output detected'"`;

interface FormState {
  requestDetect: boolean;
  responseDetect: boolean;
  enableOwaspCrs: boolean;
  ruleEngineMode: string; // detect | block
  customRules: string;
}

const defaultFormState: FormState = {
  requestDetect: true,
  responseDetect: true,
  enableOwaspCrs: true,
  ruleEngineMode: 'block',
  customRules: DEFAULT_CUSTOM_RULES,
};

const AiWafPage: React.FC = () => {
  const { t } = useTranslation();
  const scope = useRouteScope(PLUGIN_NAME);
  const [enabled, setEnabled] = useState(false);
  const [form, setForm] = useState<FormState>({ ...defaultFormState });
  const [initialForm, setInitialForm] = useState<FormState>({ ...defaultFormState });
  const [pluginData, setPluginData] = useState<any>(null);
  const [presetRules, setPresetRules] = useState(PRESET_RULE_SETS);

  const [detailModalVisible, setDetailModalVisible] = useState(false);
  const [detailRule, setDetailRule] = useState<PresetRuleSet | null>(null);

  // 把 secRules 数组中的原始行拆分：预置(按 idPrefix)、基线指令、自定义
  const splitSecRules = (arr: string[]) => {
    const isBase = (s: string) => /^(SecRuleEngine|SecRequestBodyAccess|SecResponseBodyAccess)\b/.test(s.trim());
    const custom = arr.filter((s) => !isBase(s) && !PRESET_RULE_SETS.some((p) => s.includes(p.idPrefix)));
    return { custom: custom.join('\n') };
  };

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
            if (cfg && Array.isArray(cfg.secRules)) {
              const sec: string[] = cfg.secRules;
              const has = (kw: string) => sec.some((s) => s.includes(kw));
              const parsed: FormState = {
                requestDetect: has('SecRequestBodyAccess On'),
                responseDetect: has('SecResponseBodyAccess On'),
                enableOwaspCrs: cfg.useCRS ?? defaultFormState.enableOwaspCrs,
                // 命中动作反推模式：出现 deny 视为拦截，否则视为仅检测
                ruleEngineMode: has(',deny') ? 'block' : 'detect',
                customRules: splitSecRules(sec).custom || defaultFormState.customRules,
              };
              setForm(parsed);
              setInitialForm(parsed);
              // 预置启用态：其 id 前缀是否出现在 secRules
              setPresetRules((prev) => prev.map((p) => ({ ...p, enabled: sec.some((s) => s.includes(p.idPrefix)) })));
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

  // 编译为上游真实契约 { useCRS, secRules }
  const compileSecRules = (f: FormState, presets: PresetRuleSet[]): string[] => {
    const act = actionFor(f.ruleEngineMode);
    const out: string[] = [BASE_ENGINE];
    if (f.requestDetect) out.push('SecRequestBodyAccess On');
    if (f.responseDetect) out.push('SecResponseBodyAccess On');
    presets
      .filter((p) => p.enabled)
      .forEach((p) => p.rules.forEach((r) => out.push(r.replace(/__ACT__/g, act))));
    f.customRules
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l.length > 0)
      .forEach((l) => out.push(l));
    return out;
  };

  const buildRawConfig = () => yaml.dump({
    useCRS: form.enableOwaspCrs,
    secRules: compileSecRules(form, presetRules),
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

  const handleViewDetail = (record: PresetRuleSet) => {
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
      dataIndex: 'rules',
      key: 'count',
      render: (rules: string[]) => (
        <Tag style={{ borderRadius: 10, fontSize: 12 }}>{rules.length}条</Tag>
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
      render: (checked: boolean, record: PresetRuleSet) => (
        <Switch size="small" checked={checked} onChange={(v) => handlePresetRuleToggle(record.key, v)} />
      ),
    },
    {
      title: t('aiContentSec.waf.operation'),
      key: 'action',
      render: (_: any, record: PresetRuleSet) => (
        <Button type="link" size="small" onClick={() => handleViewDetail(record)}>
          {t('aiContentSec.waf.viewDetail')}
        </Button>
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

          <div style={{ marginTop: 24, display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            <Button type="primary" onClick={handleSave}>{t('aiContentSec.saveConfig')}</Button>
            <Button onClick={handleReset}>{t('aiContentSec.resetConfig')}</Button>
          </div>
        </div>
      </Card>

      {/* Zone 3.1: AI Preset Rule Sets（真实 SecRule，开关并入 secRules 下发网关） */}
      <Card title={t('aiContentSec.waf.presetRules')} style={{ marginBottom: 16 }}>
        <Table
          dataSource={presetRules}
          columns={presetColumns}
          rowKey="key"
          pagination={false}
          size="small"
        />
      </Card>

      {/* Zone 3.2: Custom WAF Rules */}
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
        <div style={{ marginTop: 12, display: 'flex', justifyContent: 'flex-end' }}>
          <Space>
            <Button type="primary" onClick={handleSave}>{t('aiContentSec.waf.saveRule')}</Button>
            <Button onClick={handleSyntaxCheck}>{t('aiContentSec.waf.syntaxCheck')}</Button>
            <Button onClick={handleFormat}>{t('aiContentSec.waf.format')}</Button>
          </Space>
        </div>
      </Card>

      {/* Detail Modal：展示该预置规则集的真实 SecRule 内容（按当前命中模式呈现动作） */}
      <Modal
        title={`${t('aiContentSec.waf.ruleDetail')}${detailRule ? ` · ${detailRule.name}` : ''}`}
        open={detailModalVisible}
        width={720}
        onCancel={() => setDetailModalVisible(false)}
        footer={<Button type="primary" onClick={() => setDetailModalVisible(false)}>{t('common.close', '关闭')}</Button>}
      >
        {detailRule && (
          <div>
            <p><strong>{t('aiContentSec.waf.scenario')}:</strong> {detailRule.scenario}</p>
            <p><strong>{t('aiContentSec.waf.ruleCount')}:</strong> {detailRule.rules.length}</p>
            <p style={{ marginTop: 12 }}><strong>{t('aiContentSec.waf.ruleContent')}:</strong></p>
            <pre
              style={{
                fontFamily: 'monospace',
                fontSize: 12,
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-all',
                background: '#1e1e2e',
                color: '#cdd6f4',
                padding: 12,
                borderRadius: 4,
                maxHeight: 360,
                overflow: 'auto',
              }}
            >
              {detailRule.rules.map((r) => r.replace(/__ACT__/g, actionFor(form.ruleEngineMode))).join('\n')}
            </pre>
          </div>
        )}
      </Modal>
    </div>
  );
};

export default AiWafPage;
