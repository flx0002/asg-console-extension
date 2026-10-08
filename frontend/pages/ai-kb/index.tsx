import React from 'react';
import {
  Alert, Button, Card, Descriptions, message, Modal, Popconfirm, Space, Table, Tag, Typography, Upload,
} from 'antd';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import {
  listKbVersions, getActiveKb, importKbOffline, updateKbOnline, rollbackKb,
  syncKbToGateway, getKbLicense, getKbOnlineSetting,
} from '@/services/ai-kb';
import { KbMeta, KbDateTime } from '@/interfaces/ai-kb';

const pad2 = (n: number) => String(n).padStart(2, '0');

// 后端 LocalDateTime 可能序列化为 [y,m,d,h,m,s,ns] 数组或 ISO 字符串，统一转可读字符串
const formatDateTime = (v?: KbDateTime): string => {
  if (!v) return '-';
  if (Array.isArray(v) && v.length >= 6) {
    const [y, m, d, h = 0, mi = 0, s = 0] = v;
    return `${y}-${pad2(m)}-${pad2(d)} ${pad2(h)}:${pad2(mi)}:${pad2(s)}`;
  }
  return String(v).replace('T', ' ').slice(0, 19);
};

const STATUS_COLORS: Record<string, string> = {
  online: 'blue', offline: 'cyan', rollback: 'purple', builtin: 'default',
};

const AiKbPage: React.FC = () => {
  const { t } = useTranslation();

  const { data: active, loading: activeLoading, refresh: refreshActive } =
    useRequest(() => getActiveKb(), { onError: () => {} });
  const { data: license, refresh: refreshLicense } =
    useRequest(() => getKbLicense(), { onError: () => {} });
  const { data: onlineSetting } =
    useRequest(() => getKbOnlineSetting(), { onError: () => {} });
  const { data: versions, loading: versionsLoading, refresh: refreshVersions } =
    useRequest(() => listKbVersions(), { onError: () => {} });

  const reloadAll = () => { refreshActive(); refreshLicense(); refreshVersions(); };
  // 需求门控：仅「有效授权」时允许更新；无授权只能用最后一次有效库或回滚历史版本
  const canUpdate = !!license?.canUpdate;
  // 在线更新额外需已配置服务器地址（页面持久化值或 env 默认）；未配置时按钮禁用并提示
  const onlineConfigured = !!onlineSetting?.configured;

  const handleOnlineUpdate = async () => {
    try {
      const meta = await updateKbOnline();
      if (meta?.unchanged) message.info(t('aiKb.alreadyLatest'));
      else message.success(t('aiKb.updateSuccess'));
      reloadAll();
    } catch (e: any) {
      message.error(`${t('aiKb.updateFailed')}: ${e?.message || e}`);
    }
  };

  // 仅在自动同步失败/未同步时供手动重试；成功后刷新以更新状态徽标。
  const handleSyncGateway = async () => {
    try {
      const ok = await syncKbToGateway();
      if (ok) {
        message.success(t('aiKb.syncSuccess'));
        reloadAll();
      } else {
        message.warning(t('aiKb.syncSkipped'));
      }
    } catch (e: any) {
      message.error(`${t('aiKb.actionFailed')}: ${e?.message || e}`);
    }
  };

  const handleRollback = async (versionNo: number) => {
    try {
      await rollbackKb(versionNo);
      message.success(t('aiKb.rollbackSuccess'));
      reloadAll();
    } catch (e: any) {
      message.error(`${t('aiKb.actionFailed')}: ${e?.message || e}`);
    }
  };

  // 离线导入更新包：文件为厂商 signtool 产出的二进制 .wnt（外层签名头 + 内层我方 AES-GCM），
  // 直读字节转 Base64 上送（链路只传密文，不含 label/changelog）。
  const handleImportBundle = async (file: File) => {
    try {
      const bytes = new Uint8Array(await file.arrayBuffer());
      if (!bytes.length) {
        message.error(t('aiKb.invalidPackage'));
        return;
      }
      let binary = '';
      const chunk = 0x8000;
      for (let i = 0; i < bytes.length; i += chunk) {
        binary += String.fromCharCode.apply(null, Array.from(bytes.subarray(i, i + chunk)));
      }
      const b64 = btoa(binary);
      const meta = await importKbOffline({ bundle: b64 });
      if (meta?.unchanged) message.info(t('aiKb.alreadyLatest'));
      else message.success(t('aiKb.updateSuccess'));
      reloadAll();
    } catch (e: any) {
      message.error(`${t('aiKb.updateFailed')}: ${e?.message || e}`);
    }
  };

  const versionColumns = [
    { title: t('aiKb.colVersion'), dataIndex: 'versionNo', key: 'versionNo', width: 140 },
    { title: t('aiKb.colLabel'), dataIndex: 'label', key: 'label' },
    { title: t('aiKb.colCategories'), dataIndex: 'categoryCount', key: 'categoryCount', width: 90 },
    { title: t('aiKb.colDomains'), dataIndex: 'domainCount', key: 'domainCount', width: 90 },
    {
      title: t('aiKb.colStatus'), dataIndex: 'status', key: 'status', width: 90,
      render: (v: string) => (
        <Tag color={v === 'active' ? 'green' : 'default'}>
          {v === 'active' ? t('aiKb.statusActive') : t('aiKb.statusInactive')}
        </Tag>
      ),
    },
    {
      title: t('aiKb.colSource'), dataIndex: 'source', key: 'source', width: 100,
      render: (v: string) => <Tag color={STATUS_COLORS[v] || 'default'}>{v || '-'}</Tag>,
    },
    { title: t('aiKb.colOperator'), dataIndex: 'operator', key: 'operator', width: 110 },
    {
      title: t('aiKb.colCreatedAt'), dataIndex: 'createdAt', key: 'createdAt', width: 170,
      render: (v: KbDateTime) => formatDateTime(v),
    },
    {
      title: t('aiKb.colActions'), key: 'actions', width: 100,
      render: (_: any, row: KbMeta) => (
        row.status === 'active' ? <span style={{ color: '#bbb' }}>-</span> : (
          <Popconfirm
            title={t('aiKb.rollbackConfirmTitle')}
            description={t('aiKb.rollbackConfirmContent')}
            onConfirm={() => handleRollback(Number(row.versionNo))}
          >
            <Button type="link" danger size="small">{t('aiKb.rollback')}</Button>
          </Popconfirm>
        )
      ),
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {!canUpdate && (
        <Alert type="warning" showIcon message={t('aiKb.unauthorizedHint')} />
      )}
      {canUpdate && !onlineConfigured && (
        <Alert type="info" showIcon message={t('aiKb.onlineNotConfigured')} />
      )}

      <Card
        title={t('aiKb.sectionActive')}
        loading={activeLoading}
        extra={
          <Space>
            <Button onClick={reloadAll}>{t('aiKb.refresh')}</Button>
            <Upload
              accept=".wnt"
              showUploadList={false}
              disabled={!canUpdate}
              beforeUpload={(file: any) => {
                Modal.confirm({
                  title: t('aiKb.importBundleConfirmTitle'),
                  onOk: () => handleImportBundle(file),
                });
                return false;
              }}
            >
              <Button disabled={!canUpdate}>{t('aiKb.offlineImport')}</Button>
            </Upload>
            <Button type="primary" disabled={!canUpdate || !onlineConfigured} onClick={handleOnlineUpdate}>
              {t('aiKb.onlineUpdate')}
            </Button>
          </Space>
        }
      >
        {active ? (
          <Descriptions bordered size="small" column={2}>
            <Descriptions.Item label={t('aiKb.colVersion')}>{active.versionNo ?? '-'}</Descriptions.Item>
            <Descriptions.Item label={t('aiKb.colLabel')}>{active.label || '-'}</Descriptions.Item>
            <Descriptions.Item label={t('aiKb.colCategories')}>{active.categoryCount ?? '-'}</Descriptions.Item>
            <Descriptions.Item label={t('aiKb.colDomains')}>{active.domainCount ?? '-'}</Descriptions.Item>
            <Descriptions.Item label={t('aiKb.colSource')}>
              <Tag color={STATUS_COLORS[active.source || ''] || 'default'}>{active.source || '-'}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('aiKb.colSigAlg')}>{active.sigAlgorithm || '-'}</Descriptions.Item>
            <Descriptions.Item label={t('aiKb.colOperator')}>{active.operator || '-'}</Descriptions.Item>
            <Descriptions.Item label={t('aiKb.colCreatedAt')}>{formatDateTime(active.createdAt)}</Descriptions.Item>
            <Descriptions.Item label={t('aiKb.gatewaySyncLabel')} span={2}>
              {active.gatewaySynced ? (
                <Tag color="green">{t('aiKb.gatewaySynced')}</Tag>
              ) : (
                <Space>
                  <Tag color="orange">{t('aiKb.gatewaySyncPending')}</Tag>
                  <Button type="link" size="small" onClick={handleSyncGateway}>{t('aiKb.gatewayRetry')}</Button>
                </Space>
              )}
            </Descriptions.Item>
            <Descriptions.Item label={t('aiKb.colHash')} span={2}>
              <Typography.Text code copyable={{ text: active.kbHash }}>{active.kbHash || '-'}</Typography.Text>
            </Descriptions.Item>
          </Descriptions>
        ) : (
          <Alert type="info" showIcon message={t('aiKb.noActive')} />
        )}
      </Card>

      <Card title={t('aiKb.sectionVersions')}>
        <Table
          rowKey={(r: KbMeta) => String(r.id ?? r.versionNo)}
          size="small"
          loading={versionsLoading}
          columns={versionColumns}
          dataSource={versions || []}
          pagination={{ pageSize: 10, hideOnSinglePage: true }}
          locale={{ emptyText: t('aiKb.noVersions') }}
        />
      </Card>
    </Space>
  );
};

export default AiKbPage;
