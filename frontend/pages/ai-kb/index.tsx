import React from 'react';
import {
  Alert, Button, Card, Descriptions, message, Modal, Popconfirm, Space, Table, Tag, Typography, Upload,
} from 'antd';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import {
  listKbVersions, getActiveKb, importKbOffline, updateKbOnline, rollbackKb,
  syncKbToGateway, getKbLicense,
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
  const { data: versions, loading: versionsLoading, refresh: refreshVersions } =
    useRequest(() => listKbVersions(), { onError: () => {} });

  const reloadAll = () => { refreshActive(); refreshLicense(); refreshVersions(); };
  // 需求门控：仅「有效授权」时允许更新；无授权只能用最后一次有效库或回滚历史版本
  const canUpdate = !!license?.canUpdate;

  const handleOnlineUpdate = async () => {
    try {
      await updateKbOnline();
      message.success(t('aiKb.updateSuccess'));
      reloadAll();
    } catch (e: any) {
      message.error(`${t('aiKb.updateFailed')}: ${e?.message || e}`);
    }
  };

  const handleSyncGateway = async () => {
    try {
      const ok = await syncKbToGateway();
      if (ok) message.success(t('aiKb.syncSuccess'));
      else message.warning(t('aiKb.syncSkipped'));
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

  // 离线导入更新包：文件为 JSON {bundle,signature,sigAlgorithm,label,changelog}
  const handleImportBundle = async (file: File) => {
    try {
      const pkg = JSON.parse(await file.text());
      const bundle = typeof pkg.bundle === 'string' ? pkg.bundle : JSON.stringify(pkg.bundle);
      if (!bundle || !pkg.signature) {
        message.error(t('aiKb.invalidPackage'));
        return;
      }
      await importKbOffline({
        bundle, signature: pkg.signature, sigAlgorithm: pkg.sigAlgorithm,
        label: pkg.label, changelog: pkg.changelog,
      });
      message.success(t('aiKb.updateSuccess'));
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

      <Card
        title={t('aiKb.sectionActive')}
        loading={activeLoading}
        extra={
          <Space>
            <Button onClick={reloadAll}>{t('aiKb.refresh')}</Button>
            <Button onClick={handleSyncGateway}>{t('aiKb.syncGateway')}</Button>
            <Upload
              accept=".json"
              showUploadList={false}
              disabled={!canUpdate}
              beforeUpload={(file: any) => {
                Modal.confirm({
                  title: t('aiKb.importBundleConfirmTitle'),
                  content: t('aiKb.importBundleConfirmContent'),
                  onOk: () => handleImportBundle(file),
                });
                return false;
              }}
            >
              <Button disabled={!canUpdate}>{t('aiKb.offlineImport')}</Button>
            </Upload>
            <Button type="primary" disabled={!canUpdate} onClick={handleOnlineUpdate}>
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
