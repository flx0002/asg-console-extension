import React, { useState, useEffect } from 'react';
import {
  Card, Switch, Select, Input, InputNumber, Button, Space, Divider,
  message, Tag, Row, Col,
} from 'antd';
import {
  CheckCircleOutlined, ExclamationCircleOutlined,
} from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { getGlobalPluginInstance, updateGlobalPluginInstance } from '@/services';
import { useTranslation } from 'react-i18next';
import yaml from 'js-yaml';

const PLUGIN_NAME = 'ai-agent-guard';

interface FormState {
  maxRequestsPerMinute: number;
  maxStepsPerSession: number;
  maxTokensPerSession: number;
  riskScoreThreshold: number;
  maxViolations: number;
  decayTau: number;
  redisFailAction: string;
  validationEnabled: boolean;
  onValidationFailure: string;
  prefixWhitelist: string;
  logLevel: string;
}

const defaultFormState: FormState = {
  maxRequestsPerMinute: 60,
  maxStepsPerSession: 100,
  maxTokensPerSession: 100000,
  riskScoreThreshold: 80,
  maxViolations: 10,
  decayTau: 600,
  redisFailAction: 'degrade',
  validationEnabled: true,
  onValidationFailure: 'degrade',
  prefixWhitelist: '',
  logLevel: 'standard',
};

const ConfigPage: React.FC = () => {
  const { t } = useTranslation();
  const [enabled, setEnabled] = useState(false);
  const [form, setForm] = useState<FormState>({ ...defaultFormState });
  const [initialForm, setInitialForm] = useState<FormState>({ ...defaultFormState });
  const [pluginData, setPluginData] = useState<any>(null);

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
              const sl = cfg.session_limits || {};
              const rs = cfg.risk_scoring || {};
              const ss = cfg.session_id_security || {};
              const val = ss.validation || {};
              const audit = cfg.audit || {};
              const parsed: FormState = {
                maxRequestsPerMinute: sl.max_requests_per_minute ?? defaultFormState.maxRequestsPerMinute,
                maxStepsPerSession: sl.max_steps_per_session ?? defaultFormState.maxStepsPerSession,
                maxTokensPerSession: sl.max_tokens_per_session ?? defaultFormState.maxTokensPerSession,
                riskScoreThreshold: sl.risk_score_threshold ?? defaultFormState.riskScoreThreshold,
                maxViolations: sl.max_violations ?? defaultFormState.maxViolations,
                decayTau: rs.decay_tau ?? defaultFormState.decayTau,
                redisFailAction: cfg.redis_fail_action ?? defaultFormState.redisFailAction,
                validationEnabled: val.enabled ?? defaultFormState.validationEnabled,
                onValidationFailure: val.on_validation_failure ?? defaultFormState.onValidationFailure,
                prefixWhitelist: (ss.prefix_whitelist || []).join(', '),
                logLevel: audit.log_level ?? defaultFormState.logLevel,
              };
              setForm(parsed);
              setInitialForm(parsed);
            }
          } catch {
            // ignore parse error
          }
        }
      },
      onError: () => {
        message.error(t('agentGuard.loadFailed'));
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
      message.success(t('agentGuard.saveSuccess'));
    } catch {
      message.error(t('agentGuard.saveFailed'));
    }
  };

  const handleSave = async () => {
    try {
      const prefixList = form.prefixWhitelist
        .split(',')
        .map(s => s.trim())
        .filter(s => s.length > 0);
      const configObj = {
        session_limits: {
          max_requests_per_minute: form.maxRequestsPerMinute,
          max_steps_per_session: form.maxStepsPerSession,
          max_tokens_per_session: form.maxTokensPerSession,
          risk_score_threshold: form.riskScoreThreshold,
          max_violations: form.maxViolations,
        },
        risk_scoring: {
          decay_tau: form.decayTau,
        },
        session_id_security: {
          validation: {
            enabled: form.validationEnabled,
            on_validation_failure: form.onValidationFailure,
          },
          prefix_whitelist: prefixList,
        },
        audit: {
          log_level: form.logLevel,
        },
        redis_fail_action: form.redisFailAction,
      };
      const rawConfig = yaml.dump(configObj);
      const params = { ...pluginData, enabled, rawConfigurations: rawConfig };
      delete params.configurations;
      await updateGlobalPluginInstance(PLUGIN_NAME, params);
      setInitialForm({ ...form });
      message.success(t('agentGuard.saveSuccess'));
    } catch {
      message.error(t('agentGuard.saveFailed'));
    }
  };

  const handleReset = () => {
    setForm({ ...initialForm });
  };

  const updateField = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm(prev => ({ ...prev, [key]: value }));
  };

  return (
    <div style={{ padding: '0 0 24px' }}>
      <Card style={{ marginBottom: 16 }} loading={loading}>
        <Space size="large" align="center">
          <Switch
            checked={enabled}
            onChange={handleToggle}
            checkedChildren={t('agentGuard.on')}
            unCheckedChildren={t('agentGuard.off')}
          />
          <Tag color={enabled ? 'green' : 'default'} icon={enabled ? <CheckCircleOutlined /> : <ExclamationCircleOutlined />}>
            {enabled ? t('agentGuard.enabled') : t('agentGuard.disabled')}
          </Tag>
          <span style={{ color: '#999' }}>{t('agentGuard.pluginDesc')}</span>
        </Space>
      </Card>

      <Card>
        <div style={{ maxWidth: 700 }}>
          <h4>{t('agentGuard.sessionLimits')}</h4>
          <Row gutter={16}>
            <Col span={12}>
              <div style={{ marginBottom: 16 }}>
                <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('agentGuard.maxRequestsPerMinute')}</div>
                <InputNumber value={form.maxRequestsPerMinute} onChange={v => updateField('maxRequestsPerMinute', v ?? 60)} min={1} max={10000} style={{ width: '100%' }} />
              </div>
            </Col>
            <Col span={12}>
              <div style={{ marginBottom: 16 }}>
                <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('agentGuard.maxStepsPerSession')}</div>
                <InputNumber value={form.maxStepsPerSession} onChange={v => updateField('maxStepsPerSession', v ?? 100)} min={1} max={100000} style={{ width: '100%' }} />
              </div>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}>
              <div style={{ marginBottom: 16 }}>
                <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('agentGuard.maxTokensPerSession')}</div>
                <InputNumber value={form.maxTokensPerSession} onChange={v => updateField('maxTokensPerSession', v ?? 100000)} min={1000} max={100000000} style={{ width: '100%' }} />
              </div>
            </Col>
            <Col span={12}>
              <div style={{ marginBottom: 16 }}>
                <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('agentGuard.maxViolations')}</div>
                <InputNumber value={form.maxViolations} onChange={v => updateField('maxViolations', v ?? 10)} min={1} max={1000} style={{ width: '100%' }} />
              </div>
            </Col>
          </Row>

          <Divider />

          <h4>{t('agentGuard.riskScoring')}</h4>
          <Row gutter={16}>
            <Col span={12}>
              <div style={{ marginBottom: 16 }}>
                <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('agentGuard.riskScoreThreshold')}</div>
                <InputNumber value={form.riskScoreThreshold} onChange={v => updateField('riskScoreThreshold', v ?? 80)} min={1} max={100} style={{ width: '100%' }} />
              </div>
            </Col>
            <Col span={12}>
              <div style={{ marginBottom: 16 }}>
                <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('agentGuard.decayTau')}</div>
                <InputNumber value={form.decayTau} onChange={v => updateField('decayTau', v ?? 600)} min={60} max={86400} style={{ width: '100%' }} addonAfter={t('agentGuard.seconds')} />
              </div>
            </Col>
          </Row>

          <Divider />

          <h4>{t('agentGuard.sessionIdSecurity')}</h4>
          <Row gutter={16}>
            <Col span={12}>
              <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ fontWeight: 500 }}>{t('agentGuard.validationEnabled')}</span>
                <Switch checked={form.validationEnabled} onChange={v => updateField('validationEnabled', v)} checkedChildren={t('agentGuard.on')} unCheckedChildren={t('agentGuard.off')} />
              </div>
            </Col>
            <Col span={12}>
              <div style={{ marginBottom: 16 }}>
                <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('agentGuard.onValidationFailure')}</div>
                <Select value={form.onValidationFailure} onChange={v => updateField('onValidationFailure', v)} style={{ width: '100%' }}
                  options={[
                    { value: 'degrade', label: t('agentGuard.degradeAction') },
                    { value: 'reject', label: t('agentGuard.rejectAction') },
                    { value: 'log_only', label: t('agentGuard.logOnlyAction') },
                  ]}
                />
              </div>
            </Col>
          </Row>
          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('agentGuard.prefixWhitelist')}</div>
            <Input value={form.prefixWhitelist} onChange={e => updateField('prefixWhitelist', e.target.value)} placeholder={t('agentGuard.prefixWhitelistPlaceholder')} />
          </div>

          <Divider />

          <h4>{t('agentGuard.degradeAndAudit')}</h4>
          <Row gutter={16}>
            <Col span={12}>
              <div style={{ marginBottom: 16 }}>
                <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('agentGuard.redisFailAction')}</div>
                <Select value={form.redisFailAction} onChange={v => updateField('redisFailAction', v)} style={{ width: '100%' }}
                  options={[
                    { value: 'degrade', label: t('agentGuard.degradeAction') },
                    { value: 'block', label: t('agentGuard.blockAction') },
                  ]}
                />
              </div>
            </Col>
            <Col span={12}>
              <div style={{ marginBottom: 16 }}>
                <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('agentGuard.logLevel')}</div>
                <Select value={form.logLevel} onChange={v => updateField('logLevel', v)} style={{ width: '100%' }}
                  options={[
                    { value: 'verbose', label: t('agentGuard.logVerbose') },
                    { value: 'standard', label: t('agentGuard.logStandard') },
                    { value: 'minimal', label: t('agentGuard.logMinimal') },
                  ]}
                />
              </div>
            </Col>
          </Row>

          <Divider />
          <Space>
            <Button type="primary" onClick={handleSave}>{t('agentGuard.saveConfig')}</Button>
            <Button onClick={handleReset}>{t('agentGuard.resetConfig')}</Button>
          </Space>
        </div>
      </Card>
    </div>
  );
};

export default ConfigPage;
