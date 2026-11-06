/* eslint-disable max-len */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Checkbox, Modal, Select, Space, Table, Tag, Tooltip, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import {
  getAiShadowCaptureConfig,
  getAiShadowCaptureNics,
  updateAiShadowCaptureConfig,
} from '@/services';
import type { AiShadowCaptureNic } from '@/interfaces/ai-shadow';

/**
 * 旁路采集：配置旁路采集器（asg-bypass-guard）在哪些镜像口抓包、阻断包从哪个回注口发出。
 *
 * <p>网口清单是采集器宿主机的实况（链路/速率），控制台只存「选了哪些口 + 每口的回注口」。
 * 保存后由采集器在下一个策略轮询周期(≤30s)热重载生效——不是即时切换，界面文案要如实说。
 *
 * <p>回注口只在「拦截」模式下有意义（监控只记录不发包），故监控模式下列置灰禁用。
 */
const AiShadowCaptureView: React.FC<{ mode: 'monitoring' | 'enforcement' }> = ({ mode }) => {
  const { t } = useTranslation();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [controlMap, setControlMap] = useState<Record<string, string>>({});
  const [initialized, setInitialized] = useState(false);
  const [saving, setSaving] = useState(false);

  const {
    data: config,
    error: configError,
    loading: configLoading,
  } = useRequest(() => getAiShadowCaptureConfig(), { onError: () => {} });

  const {
    data: nicsData,
    loading: nicsLoading,
    refresh: redetect,
  } = useRequest(() => getAiShadowCaptureNics(), { onError: () => {} });

  // 网口名自然排序（enp3s0…enp9s0, enp10s0）：字典序会把 enp10s0 排到 enp3s0 前面。
  const nics = useMemo<AiShadowCaptureNic[]>(() => {
    const list = [...(nicsData?.nics || [])];
    list.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }));
    return list;
  }, [nicsData]);
  const agentOnline = nicsData?.agentOnline === true;
  // 配置未成功初始化前禁止保存：否则读取失败时空勾选会被当「显式关闭采集」下发。
  const disabled = !agentOnline || saving || !initialized;

  // 默认采集口：第一个可用镜像口（有速率），没有就退回第一个口——与出厂默认（enp3s0）一致。
  const defaultSelection = useCallback((list: AiShadowCaptureNic[]): string[] => {
    const first = list.find((n) => n.speed) || list[0];
    return first ? [first.name] : [];
  }, []);

  // 保存的配置只在首屏灌入一次：用户勾选编辑期间不能被后台刷新覆盖。
  useEffect(() => {
    if (initialized || !config || !nicsData?.agentOnline) return;
    const names = nicsData.nics.map((n) => n.name);
    const saved = config.interfaces;
    const initSelected = saved === null
      ? defaultSelection(nicsData.nics)
      : saved.filter((name) => names.includes(name));
    const controls: Record<string, string> = {};
    names.forEach((name) => {
      // 显式 ""（不注入）保留，缺省回落「同口（默认）」
      controls[name] = config.controlInterfaces?.[name] ?? name;
    });
    setSelected(new Set(initSelected));
    setControlMap(controls);
    setInitialized(true);
  }, [config, nicsData, initialized, defaultSelection]);

  const toggle = useCallback((name: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(name)) {
        next.delete(name);
      } else {
        next.add(name);
      }
      return next;
    });
  }, []);

  const setControl = useCallback((name: string, value: string) => {
    setControlMap((prev) => ({ ...prev, [name]: value }));
  }, []);

  const restoreDefault = useCallback(() => {
    if (!nicsData?.agentOnline) return;
    setSelected(new Set(defaultSelection(nicsData.nics)));
    const controls: Record<string, string> = {};
    nicsData.nics.forEach((n) => { controls[n.name] = n.name; });
    setControlMap(controls);
    message.success(t('aiShadow.captureRestoreToast'));
  }, [nicsData, defaultSelection, t]);

  const doSave = useCallback(async () => {
    setSaving(true);
    try {
      // 只提交勾选的口（键 ⊆ interfaces，后端会校验），顺序按网口清单保证稳定。
      const interfaces = nics.filter((n) => selected.has(n.name)).map((n) => n.name);
      const controlInterfaces: Record<string, string> = {};
      interfaces.forEach((name) => {
        controlInterfaces[name] = controlMap[name] ?? name;
      });
      await updateAiShadowCaptureConfig({ interfaces, controlInterfaces });
      message.success(t('aiShadow.captureSaveSuccess'));
    } catch {
      message.error(t('aiShadow.actionFailed'));
    } finally {
      setSaving(false);
    }
  }, [nics, selected, controlMap, t]);

  const openSavePreview = useCallback(() => {
    const caps = nics.filter((n) => selected.has(n.name));
    const ups = caps.filter((n) => n.oper === 'up').map((n) => n.name);
    const downs = caps.filter((n) => n.oper !== 'up').map((n) => n.name);
    const kv: React.CSSProperties = { display: 'flex', gap: 10, padding: '7px 0', borderBottom: '1px dashed #f0f0f0', fontSize: 13 };
    const label: React.CSSProperties = { color: '#8c8c8c', width: 84, flex: 'none' };
    Modal.confirm({
      title: t('aiShadow.capturePreviewTitle'),
      icon: null,
      width: 500,
      okText: t('aiShadow.captureSaveConfirm'),
      cancelText: t('misc.cancel'),
      onOk: doSave,
      content: (
        <div style={{ color: 'rgba(0,0,0,0.65)' }}>
          <div style={kv}>
            <span style={label}>{t('aiShadow.capturePreviewIfaces')}</span>
            <span>
              {caps.length
                ? caps.map((n) => n.name).join('、')
                : <b style={{ color: '#d46b08' }}>{t('aiShadow.capturePreviewNone')}</b>}
            </span>
          </div>
          <div style={kv}>
            <span style={label}>{t('aiShadow.capturePreviewNow')}</span>
            <span>{ups.join('、') || '—'}</span>
          </div>
          {downs.length ? (
            <div style={kv}>
              <span style={label}>{t('aiShadow.capturePreviewWaitUp')}</span>
              <span style={{ color: '#d46b08' }}>{downs.join('、')}</span>
            </div>
          ) : null}
          {mode === 'enforcement' ? (
            <div style={{ ...kv, borderBottom: 'none' }}>
              <span style={label}>{t('aiShadow.capturePreviewControl')}</span>
              <span>
                {caps.filter((n) => (controlMap[n.name] ?? n.name) !== '')
                  .map((n) => `${n.name}→${controlMap[n.name] || t('aiShadow.capturePreviewNoInject')}`)
                  .join('，') || '—'}
              </span>
            </div>
          ) : null}
        </div>
      ),
    });
  }, [nics, selected, controlMap, mode, saving, doSave, t]);

  const columns = useMemo<ColumnsType<AiShadowCaptureNic>>(() => [
    {
      title: '',
      key: 'sel',
      width: 36,
      render: (_, record) => (
        <Checkbox
          checked={selected.has(record.name)}
          disabled={!agentOnline}
          onChange={() => toggle(record.name)}
        />
      ),
    },
    {
      title: t('aiShadow.captureColNic'),
      dataIndex: 'name',
      key: 'name',
      render: (name: string) => (
        <span style={{ fontFamily: 'ui-monospace, Consolas, monospace', fontWeight: 600 }}>{name}</span>
      ),
    },
    {
      title: t('aiShadow.captureColLink'),
      dataIndex: 'oper',
      key: 'oper',
      render: (oper: string) => (
        oper === 'up'
          ? <Tag color="green">UP</Tag>
          : <Tag>DOWN</Tag>
      ),
    },
    {
      title: t('aiShadow.captureColSpeed'),
      dataIndex: 'speed',
      key: 'speed',
      render: (speed: string) => <span style={{ color: '#8c8c8c' }}>{speed || '—'}</span>,
    },
    {
      title: t('aiShadow.captureColRole'),
      key: 'role',
      render: (_, record) => (
        <Tag color="blue">{record.speed ? t('aiShadow.captureRoleMirror') : t('aiShadow.captureRolePending')}</Tag>
      ),
    },
    {
      title: t('aiShadow.captureColStatus'),
      key: 'status',
      render: (_, record) => {
        if (!selected.has(record.name)) {
          return <span style={{ color: '#8c8c8c', fontSize: 12 }}>{t('aiShadow.captureStatusOff')}</span>;
        }
        if (record.oper === 'up') {
          return <span style={{ color: '#52c41a', fontSize: 12, fontWeight: 500 }}>{t('aiShadow.captureStatusOn')}</span>;
        }
        return <span style={{ color: '#d46b08', fontSize: 12, fontWeight: 500 }}>{t('aiShadow.captureStatusWaitLink')}</span>;
      },
    },
    {
      title: t('aiShadow.captureColControl'),
      key: 'control',
      render: (_, record) => {
        const enabled = selected.has(record.name) && mode === 'enforcement' && agentOnline;
        return (
          <span style={{ opacity: mode === 'monitoring' ? 0.4 : 1, display: 'inline-block' }}>
            <Select
              size="small"
              style={{ width: 148 }}
              value={controlMap[record.name] ?? record.name}
              disabled={!enabled}
              onChange={(value) => setControl(record.name, value)}
              options={[
                { value: record.name, label: t('aiShadow.captureControlSame') },
                ...nics
                  .filter((o) => o.name !== record.name)
                  .map((o) => ({ value: o.name, label: o.name })),
                { value: '', label: t('aiShadow.captureControlNone') },
              ]}
            />
          </span>
        );
      },
    },
  ], [selected, controlMap, mode, agentOnline, nics, toggle, setControl, t]);

  const capturingCount = nics.filter((n) => selected.has(n.name)).length;

  return (
    <div>
      {!agentOnline && nicsData ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={t('aiShadow.captureAgentOffline')}
          description={t('aiShadow.captureAgentOfflineHint')}
        />
      ) : null}
      {configError ? (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 12 }}
          message={t('aiShadow.captureLoadFailed')}
        />
      ) : null}

      <Card size="small" style={{ marginBottom: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 }}>
          <span
            style={{
              width: 8,
              height: 8,
              borderRadius: '50%',
              background: capturingCount > 0 ? '#52c41a' : '#bfbfbf',
            }}
          />
          <span style={{ fontWeight: 600 }}>
            {capturingCount > 0 ? t('aiShadow.captureSvcCapturing') : t('aiShadow.captureSvcIdle')}
          </span>
          <span style={{ color: '#8c8c8c' }}>
            {capturingCount > 0
              ? `· ${t('aiShadow.captureSvcPortCount', { count: capturingCount })}`
              : `· ${t('aiShadow.captureSvcNone')}`}
          </span>
          <Button
            type="link"
            size="small"
            style={{ marginLeft: 'auto' }}
            loading={nicsLoading}
            onClick={() => {
              redetect();
              message.success(t('aiShadow.captureRedetectToast'));
            }}
          >
            {t('aiShadow.captureRedetect')}
          </Button>
        </div>
      </Card>

      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <span style={{ fontSize: 13, fontWeight: 600 }}>
          {t('aiShadow.captureNicTitle')}
          <Tooltip title={t('aiShadow.captureNicHelp')}>
            <span
              style={{
                display: 'inline-flex',
                width: 15,
                height: 15,
                borderRadius: '50%',
                background: '#e6f4ff',
                color: '#1677ff',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 10,
                marginLeft: 6,
                cursor: 'help',
                fontWeight: 700,
                verticalAlign: 1,
              }}
            >
              ?
            </span>
          </Tooltip>
        </span>
      </div>

      <Table<AiShadowCaptureNic>
        rowKey="name"
        size="middle"
        columns={columns}
        dataSource={nics}
        loading={configLoading || nicsLoading}
        pagination={false}
        rowClassName={(record) => (selected.has(record.name) ? 'ant-table-row-selected' : '')}
        locale={{ emptyText: agentOnline ? undefined : t('aiShadow.captureAgentOffline') }}
      />

      <Space style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 16 }}>
        <Button disabled={disabled} onClick={restoreDefault}>
          {t('aiShadow.captureRestoreDefault')}
        </Button>
        <Button type="primary" disabled={disabled} onClick={openSavePreview}>
          {t('aiShadow.captureSave')}
        </Button>
      </Space>
    </div>
  );
};

export default AiShadowCaptureView;
