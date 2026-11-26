/* eslint-disable max-len */
import React, { useState, useEffect } from 'react';
import { Card, Switch, Input, InputNumber, Tag, Button, message, Space, Divider, Typography, Alert } from 'antd';
import { useRequest } from 'ahooks';
import { getGlobalPluginInstance, updateGlobalPluginInstance, updateRoutePluginInstance } from '@/services';
import { GlobalSwitchHint, RouteScopeCard, useRouteScope } from '../_route-scope';
import { useTranslation } from 'react-i18next';
import yaml from 'js-yaml';

const PLUGIN_NAME = 'ai-security-guard';

// 字段与 spec.yaml (v2.0.3) configSchema 一致；required: serviceName/servicePort/serviceHost/accessKey/secretKey。
interface FormState {
  serviceName: string;
  servicePort: string;
  serviceHost: string;
  accessKey: string;
  secretKey: string;
  checkRequest: boolean;
  checkResponse: boolean;
  requestCheckService: string;
  responseCheckService: string;
  requestContentJsonPath: string;
  responseContentJsonPath: string;
  responseStreamContentJsonPath: string;
  denyCode: number;
  denyMessage: string;
  protocol: string;
}

const defaultFormState: FormState = {
  serviceName: 'safecheck.dns',
  servicePort: '443',
  serviceHost: 'green-cip.cn-hangzhou.aliyuncs.com',
  accessKey: '',
  secretKey: '',
  checkRequest: true,
  checkResponse: false,
  requestCheckService: 'llm_query_moderation',
  responseCheckService: 'llm_response_moderation',
  requestContentJsonPath: 'messages.@reverse.0.content',
  responseContentJsonPath: 'choices.0.message.content',
  responseStreamContentJsonPath: 'choices.0.delta.content',
  denyCode: 200,
  denyMessage: '',
  protocol: 'openai',
};

const AiSecurityGuardPage: React.FC = () => {
  const { t } = useTranslation();
  const scope = useRouteScope(PLUGIN_NAME);
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
              const parsed: FormState = {
                serviceName: cfg.serviceName ?? defaultFormState.serviceName,
                servicePort: String(cfg.servicePort ?? defaultFormState.servicePort),
                serviceHost: cfg.serviceHost ?? defaultFormState.serviceHost,
                accessKey: cfg.accessKey ?? '',
                secretKey: cfg.secretKey ?? '',
                checkRequest: cfg.checkRequest ?? defaultFormState.checkRequest,
                checkResponse: cfg.checkResponse ?? defaultFormState.checkResponse,
                requestCheckService: cfg.requestCheckService ?? defaultFormState.requestCheckService,
                responseCheckService: cfg.responseCheckService ?? defaultFormState.responseCheckService,
                requestContentJsonPath: cfg.requestContentJsonPath ?? defaultFormState.requestContentJsonPath,
                responseContentJsonPath: cfg.responseContentJsonPath ?? defaultFormState.responseContentJsonPath,
                responseStreamContentJsonPath: cfg.responseStreamContentJsonPath ?? defaultFormState.responseStreamContentJsonPath,
                denyCode: cfg.denyCode ?? defaultFormState.denyCode,
                denyMessage: cfg.denyMessage ?? '',
                protocol: cfg.protocol ?? defaultFormState.protocol,
              };
              setForm(parsed);
              setInitialForm(parsed);
            }
          } catch {
            // 存量 YAML 解析失败：明确提示，避免用户误把默认值当作存量配置后保存覆盖
            message.error(t('aiContentSec.loadFailed'));
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
      setPluginData(params);
      setEnabled(checked);
      message.success(t('aiContentSec.saveSuccess'));
    } catch {
      message.error(t('aiContentSec.saveFailed'));
    }
  };

  const buildRawConfig = () => yaml.dump({
    serviceName: form.serviceName,
    servicePort: form.servicePort,
    serviceHost: form.serviceHost,
    accessKey: form.accessKey,
    secretKey: form.secretKey,
    checkRequest: form.checkRequest,
    checkResponse: form.checkResponse,
    requestCheckService: form.requestCheckService,
    responseCheckService: form.responseCheckService,
    requestContentJsonPath: form.requestContentJsonPath,
    responseContentJsonPath: form.responseContentJsonPath,
    responseStreamContentJsonPath: form.responseStreamContentJsonPath,
    denyCode: form.denyCode,
    denyMessage: form.denyMessage,
    protocol: form.protocol,
  });

  // 凭据必填校验：handleSave 与「保存作用范围」共用，避免后者绕过校验写入空凭据
  const validateConfig = () => {
    if (!form.serviceHost || !form.accessKey || !form.secretKey) {
      message.warning(t('aiContentSec.securityGuard.requiredHint'));
      return false;
    }
    return true;
  };

  const handleSave = async () => {
    if (!validateConfig()) return;
    try {
      const rawConfig = buildRawConfig();
      const params = { ...pluginData, enabled, rawConfigurations: rawConfig };
      delete params.configurations;
      await updateGlobalPluginInstance(PLUGIN_NAME, params);
      setPluginData(params);
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

  const textField = (labelKey: string, key: keyof FormState, placeholder?: string, password = false) => (
    <div style={{ marginBottom: 16 }}>
      <div style={{ marginBottom: 6, fontWeight: 500 }}>{t(labelKey)}</div>
      <Input
        type={password ? 'password' : 'text'}
        value={form[key] as string}
        onChange={e => updateField(key, e.target.value as any)}
        placeholder={placeholder}
        autoComplete="off"
      />
    </div>
  );

  return (
    <div style={{ padding: '0 0 24px' }}>
      <Card title={t('aiContentSec.pluginStatus')} style={{ marginBottom: 16 }} loading={loading}>
        <Space size="large" align="center">
          <Switch checked={enabled} onChange={handleToggle} checkedChildren={t('aiContentSec.on')} unCheckedChildren={t('aiContentSec.off')} />
          <Tag color={enabled ? 'green' : 'default'}>
            {enabled ? t('aiContentSec.enabled') : t('aiContentSec.disabled')}
          </Tag>
          <Typography.Text type="secondary">
            {t('aiContentSec.routeScope.summary', { count: scope.bound.length })}
          </Typography.Text>
        </Space>
        <GlobalSwitchHint />
      </Card>

      <RouteScopeCard scope={scope} getRawConfig={buildRawConfig} validate={validateConfig} />

      <Alert type="info" showIcon style={{ marginBottom: 16 }} message={t('aiContentSec.securityGuard.serviceHint')} />

      <Card title={t('aiContentSec.config')} style={{ marginBottom: 16 }}>
        <div style={{ maxWidth: 600 }}>
          <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 500 }}>{t('aiContentSec.requestDetect')}</span>
            <Switch checked={form.checkRequest} onChange={v => updateField('checkRequest', v)} checkedChildren={t('aiContentSec.on')} unCheckedChildren={t('aiContentSec.off')} />
          </div>
          <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 500 }}>{t('aiContentSec.responseDetect')}</span>
            <Switch checked={form.checkResponse} onChange={v => updateField('checkResponse', v)} checkedChildren={t('aiContentSec.on')} unCheckedChildren={t('aiContentSec.off')} />
          </div>

          <Divider />

          {textField('aiContentSec.securityGuard.serviceName', 'serviceName')}
          {textField('aiContentSec.securityGuard.servicePort', 'servicePort')}
          {textField('aiContentSec.securityGuard.serviceHost', 'serviceHost', 'green-cip.cn-hangzhou.aliyuncs.com')}
          {textField('aiContentSec.securityGuard.accessKey', 'accessKey', undefined, true)}
          {textField('aiContentSec.securityGuard.secretKey', 'secretKey', undefined, true)}

          <Divider />

          {textField('aiContentSec.securityGuard.requestCheckService', 'requestCheckService')}
          {textField('aiContentSec.securityGuard.responseCheckService', 'responseCheckService')}
          {textField('aiContentSec.securityGuard.requestContentJsonPath', 'requestContentJsonPath')}
          {textField('aiContentSec.securityGuard.responseContentJsonPath', 'responseContentJsonPath')}
          {textField('aiContentSec.securityGuard.responseStreamContentJsonPath', 'responseStreamContentJsonPath')}

          <Divider />

          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 6, fontWeight: 500 }}>{t('aiContentSec.securityGuard.denyCode')}</div>
            <InputNumber value={form.denyCode} onChange={v => updateField('denyCode', v ?? defaultFormState.denyCode)} min={100} max={599} style={{ width: '100%' }} />
          </div>
          {textField('aiContentSec.rejectMessage', 'denyMessage')}
          {textField('aiContentSec.securityGuard.protocol', 'protocol')}

          <Divider />
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Space>
              <Button type="primary" onClick={handleSave}>{t('aiContentSec.saveConfig')}</Button>
              <Button onClick={handleReset}>{t('aiContentSec.resetConfig')}</Button>
            </Space>
          </div>
        </div>
      </Card>
    </div>
  );
};

export default AiSecurityGuardPage;
