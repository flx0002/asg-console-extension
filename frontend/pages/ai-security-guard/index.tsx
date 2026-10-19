/* eslint-disable max-len, no-nested-ternary */
import React, { useState, useEffect } from 'react';
import { Card, Switch, Select, Input, InputNumber, Radio, Tag, Button, message, Space, Divider, Typography } from 'antd';
import { useRequest } from 'ahooks';
import { getGlobalPluginInstance, updateGlobalPluginInstance, updateRoutePluginInstance } from '@/services';
import { GlobalSwitchHint, RouteScopeCard, useRouteScope } from '../_route-scope';
// 审计事件入口已统一到「AI 内容安全 → 内容安全事件」（三权分立：配置与审计分离）
import { useTranslation } from 'react-i18next';
import yaml from 'js-yaml';

const PLUGIN_NAME = 'ai-security-guard';

interface FormState {
  detectMode: string;
  requestDetect: boolean;
  responseDetect: boolean;
  riskThreshold: string;
  riskAction: string;
  rejectMessage: string;
  timeout: number;
}

const defaultFormState: FormState = {
  detectMode: 'tmp',
  requestDetect: true,
  responseDetect: true,
  riskThreshold: 'high',
  riskAction: 'block',
  rejectMessage: '',
  timeout: 3000,
};

const AiSecurityGuardPage: React.FC = () => {
  const { t } = useTranslation();
  const scope = useRouteScope(PLUGIN_NAME);
  const [enabled, setEnabled] = useState(false);
  const [form, setForm] = useState<FormState>({ ...defaultFormState });
  const [initialForm, setInitialForm] = useState<FormState>({ ...defaultFormState });
  const [pluginData, setPluginData] = useState<any>(null);

  // 检测日志已迁移至「AI 内容安全 → 内容安全事件」统一入口（三权分立：配置员与审计员权限分离）

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
                requestDetect: cfg.checkRequest ?? defaultFormState.requestDetect,
                responseDetect: cfg.checkResponse ?? defaultFormState.responseDetect,
                riskThreshold: cfg.riskThreshold ?? defaultFormState.riskThreshold,
                riskAction: cfg.riskAction ?? defaultFormState.riskAction,
                rejectMessage: cfg.rejectMessage ?? defaultFormState.rejectMessage,
                timeout: cfg.timeout ?? defaultFormState.timeout,
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
    detectMode: form.detectMode,
    checkRequest: form.requestDetect,
    checkResponse: form.responseDetect,
    riskThreshold: form.riskThreshold,
    riskAction: form.riskAction,
    rejectMessage: form.rejectMessage,
    timeout: form.timeout,
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
          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.securityGuard.detectMode')}</div>
            <Radio.Group
              value={form.detectMode}
              onChange={e => updateField('detectMode', e.target.value)}
            >
              <Radio value="tmp">{t('aiContentSec.securityGuard.textModeration')}</Radio>
              <Radio value="mmg">{t('aiContentSec.securityGuard.multiModalGuard')}</Radio>
            </Radio.Group>
          </div>

          <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 500 }}>{t('aiContentSec.requestDetect')}</span>
            <Switch
              checked={form.requestDetect}
              onChange={v => updateField('requestDetect', v)}
              checkedChildren={t('aiContentSec.on')}
              unCheckedChildren={t('aiContentSec.off')}
            />
          </div>

          <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 500 }}>{t('aiContentSec.responseDetect')}</span>
            <Switch
              checked={form.responseDetect}
              onChange={v => updateField('responseDetect', v)}
              checkedChildren={t('aiContentSec.on')}
              unCheckedChildren={t('aiContentSec.off')}
            />
          </div>

          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.securityGuard.riskThreshold')}</div>
            <Select
              value={form.riskThreshold}
              onChange={v => updateField('riskThreshold', v)}
              style={{ width: '100%' }}
              options={[
                { value: 'high', label: t('aiContentSec.securityGuard.riskHigh') },
                { value: 'medium', label: t('aiContentSec.securityGuard.riskMedium') },
                { value: 'low', label: t('aiContentSec.securityGuard.riskLow') },
              ]}
            />
          </div>

          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.securityGuard.action')}</div>
            <Select
              value={form.riskAction}
              onChange={v => updateField('riskAction', v)}
              style={{ width: '100%' }}
              options={[
                { value: 'block', label: t('aiContentSec.securityGuard.actionBlock') },
                { value: 'mask', label: t('aiContentSec.securityGuard.actionMask') },
              ]}
            />
          </div>

          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.rejectMessage')}</div>
            <Input
              value={form.rejectMessage}
              onChange={e => updateField('rejectMessage', e.target.value)}
              placeholder={t('aiContentSec.rejectMessage') as string}
            />
          </div>

          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.timeout')}</div>
            <InputNumber
              value={form.timeout}
              onChange={v => updateField('timeout', v ?? defaultFormState.timeout)}
              min={100}
              max={60000}
              style={{ width: '100%' }}
              addonAfter={t('aiContentSec.ms') as string}
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

      {/* Zone 4: 检测日志已迁移至「AI 内容安全 → 内容安全事件」（三权分立：配置与审计分离） */}
    </div>
  );
};

export default AiSecurityGuardPage;
