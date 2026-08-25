import React, { useState, useEffect } from 'react';
import { Card, Switch, Select, Input, InputNumber, Radio, Table, Tag, Button, message, Space, Divider } from 'antd';
import { useRequest } from 'ahooks';
import { getGlobalPluginInstance, updateGlobalPluginInstance } from '@/services';
import { getAuditLogs, getAuditSessions } from '@/services/audit-chain-service';
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
          getAuditLogs({ sessionId: s.sessionId, recordType: 'security_event', pageSize: 20 }).catch(() => null),
        ),
      );
      const allLogs: any[] = [];
      results.forEach((res: any) => {
        if (!res) return;
        const records = res?.list || res?.data?.list || [];
        allLogs.push(...records);
      });
      allLogs.sort((a, b) => (b.timestamp ?? 0) - (a.timestamp ?? 0));
      return allLogs.slice(0, 20);
    },
    {
      manual: true,
      onSuccess: (allLogs) => {
        const mapped = allLogs.map((r: any) => {
          const events = r.events || [];
          const eventTypes = events.map((e: any) => e.type).join(', ');
          const eventDetail = events.map((e: any) => e.detail).join('; ');
          const risk = r.risk_score ?? 0;
          const riskLevel = risk >= 60 ? 'high' : risk >= 30 ? 'medium' : 'low';
          const action = r.action || 'passed';
          const result = action === 'blocked' ? 'blocked' : action === 'masked' ? 'masked' : 'passed';
          const ts = r.timestamp ?? 0;
          return {
            time: ts ? new Date(ts).toLocaleString('zh-CN') : '-',
            dimension: eventTypes || 'security_event',
            riskLevel,
            result,
            summary: eventDetail || `${r.model || ''} - ${r.tool_name || ''}`,
          };
        });
        setDetectionLogs(mapped);
      },
    }
  );

  useEffect(() => {
    loadDetectionLogs();
  }, []);

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

  const handleSave = async () => {
    try {
      const configObj = {
        detectMode: form.detectMode,
        checkRequest: form.requestDetect,
        checkResponse: form.responseDetect,
        riskThreshold: form.riskThreshold,
        riskAction: form.riskAction,
        rejectMessage: form.rejectMessage,
        timeout: form.timeout,
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

  const logColumns = [
    {
      title: t('aiContentSec.time'),
      dataIndex: 'time',
      key: 'time',
    },
    {
      title: t('aiContentSec.securityGuard.logDimension'),
      dataIndex: 'dimension',
      key: 'dimension',
    },
    {
      title: t('aiContentSec.securityGuard.logRiskLevel'),
      dataIndex: 'riskLevel',
      key: 'riskLevel',
      render: (level: string) => {
        const colorMap: Record<string, string> = { high: 'red', medium: 'orange', low: 'green' };
        const labelMap: Record<string, string> = {
          high: t('aiContentSec.securityGuard.highRisk'),
          medium: t('aiContentSec.securityGuard.mediumRisk'),
          low: t('aiContentSec.securityGuard.lowRisk'),
        };
        return <Tag color={colorMap[level] || 'default'}>{labelMap[level] || level}</Tag>;
      },
    },
    {
      title: t('aiContentSec.securityGuard.logResult'),
      dataIndex: 'result',
      key: 'result',
      render: (result: string) => {
        const colorMap: Record<string, string> = { blocked: 'red', masked: 'orange', passed: 'green' };
        const labelMap: Record<string, string> = {
          blocked: t('aiContentSec.securityGuard.blocked'),
          masked: t('aiContentSec.securityGuard.masked'),
          passed: t('aiContentSec.securityGuard.passed'),
        };
        return <Tag color={colorMap[result] || 'default'}>{labelMap[result] || result}</Tag>;
      },
    },
    {
      title: t('aiContentSec.securityGuard.logSummary'),
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
    </div>
  );
};

export default AiSecurityGuardPage;
