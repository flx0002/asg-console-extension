import React, { useState } from 'react';
import { Card, Table, Tag, Button, message, Space, Popconfirm, Upload, Modal, Drawer } from 'antd';
import { useRequest } from 'ahooks';
import {
  getConfigSnapshots,
  takeConfigSnapshot,
  restoreConfigSnapshot,
  exportConfigBackup,
  importConfigBackup,
  getConfigBackupLogs,
} from '@/services/config-version';
import { useTranslation } from 'react-i18next';

const SOURCE_COLORS: Record<string, string> = {
  save: 'blue',
  manual: 'cyan',
  'pre-restore': 'purple',
  'pre-import': 'geekblue',
};

const ConfigSnapshots: React.FC = () => {
  const { t } = useTranslation();
  const [backupLogVisible, setBackupLogVisible] = useState(false);

  const {
    data: snapshots,
    loading: snapshotsLoading,
    run: loadSnapshots,
  } = useRequest(
    async () => {
      const res: any = await getConfigSnapshots();
      return res?.data || res || [];
    },
    { manual: false },
  );

  const handleTakeSnapshot = async () => {
    const res: any = await takeConfigSnapshot();
    const objects = res?.data?.objects ?? res?.objects;
    message.success(`${t('configVersion.snapshotTaken')}${objects >= 0 ? ` (${objects} ${t('configVersion.objectsUnit')})` : ''}`);
    loadSnapshots();
  };

  const handleRestore = async (id: number) => {
    try {
      const res: any = await restoreConfigSnapshot(id);
      const restored = res?.data?.restoredObjects ?? res?.restoredObjects;
      message.success(`${t('configVersion.restoreSuccess')}${restored != null ? ` (${restored})` : ''}`);
      loadSnapshots();
    } catch (e: any) {
      message.error(`${t('configVersion.restoreFailed')}: ${e?.message || e}`);
    }
  };

  const handleExport = async () => {
    const res: any = await exportConfigBackup();
    const content = res?.data || res;
    if (!content) {
      message.error(t('configVersion.exportFailed'));
      return;
    }
    const blob = new Blob([content], { type: 'application/octet-stream' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `config-backup-${Date.now()}.asgbak`;
    a.click();
    URL.revokeObjectURL(url);
    message.success(t('configVersion.exportSuccess'));
  };

  const handleImport = async (file: File) => {
    if (!file.name.endsWith('.asgbak')) {
      message.error(t('configVersion.invalidFile'));
      return;
    }
    const text = await file.text();
    try {
      const res: any = await importConfigBackup(text);
      const restored = res?.data?.restoredObjects ?? res?.restoredObjects;
      message.success(`${t('configVersion.importSuccess')}${restored != null ? ` (${restored})` : ''}`);
      loadSnapshots();
    } catch (e: any) {
      message.error(`${t('configVersion.importFailed')}: ${e?.message || e}`);
    }
  };

  const snapshotColumns = [
    { title: t('configVersion.snapshotNo'), dataIndex: 'versionId', key: 'versionId', width: 90 },
    { title: t('configVersion.source'), dataIndex: 'source', key: 'source',
      render: (v: string) => <Tag color={SOURCE_COLORS[v] || 'default'}>{v}</Tag> },
    { title: t('configVersion.operator'), dataIndex: 'operator', key: 'operator' },
    { title: t('configVersion.time'), dataIndex: 'createdAt', key: 'createdAt' },
    {
      title: t('configVersion.actions'),
      key: 'actions',
      render: (_: any, row: any) => (
        <Popconfirm
          title={t('configVersion.restoreConfirmTitle')}
          description={t('configVersion.restoreConfirmContent')}
          onConfirm={() => handleRestore(row.id)}
        >
          <Button type="link" danger>
            {t('configVersion.restore')}
          </Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <Card
      title={t('configVersion.title')}
      extra={
        <Space>
          <Button onClick={() => setBackupLogVisible(true)}>{t('configVersion.backupLogs')}</Button>
          <Upload
            accept=".asgbak"
            showUploadList={false}
            beforeUpload={(file: any) => {
              Modal.confirm({
                title: t('configVersion.importConfirmTitle'),
                content: t('configVersion.importConfirmContent'),
                onOk: () => handleImport(file),
              });
              return false;
            }}
          >
            <Button>{t('configVersion.importBackup')}</Button>
          </Upload>
          <Button onClick={handleExport}>{t('configVersion.exportBackup')}</Button>
          <Button type="primary" onClick={handleTakeSnapshot}>
            {t('configVersion.takeSnapshot')}
          </Button>
        </Space>
      }
    >
      <p style={{ marginTop: 0, color: '#888' }}>{t('configVersion.pageHint')}</p>
      <Table
        rowKey="id"
        size="small"
        loading={snapshotsLoading}
        columns={snapshotColumns}
        dataSource={snapshots || []}
        pagination={false}
        locale={{ emptyText: t('configVersion.noSnapshots') }}
      />

      <Drawer
        title={t('configVersion.backupLogs')}
        width={720}
        open={backupLogVisible}
        onClose={() => setBackupLogVisible(false)}
      >
        <BackupLogs />
      </Drawer>
    </Card>
  );
};

const BackupLogs: React.FC = () => {
  const { t } = useTranslation();
  const { data: logs } = useRequest(async () => {
    const res: any = await getConfigBackupLogs();
    return res?.data || res || [];
  });
  return (
    <Table
      rowKey="id"
      size="small"
      columns={[
        { title: t('configVersion.action'), dataIndex: 'action', key: 'action' },
        { title: t('configVersion.fileName'), dataIndex: 'fileName', key: 'fileName' },
        { title: t('configVersion.count'), dataIndex: 'domainCount', key: 'domainCount' },
        { title: t('configVersion.result'), dataIndex: 'result', key: 'result',
          render: (v: string) => <Tag color={v === 'success' ? 'green' : 'red'}>{v}</Tag> },
        { title: t('configVersion.time'), dataIndex: 'createdAt', key: 'createdAt' },
      ]}
      dataSource={logs || []}
      pagination={false}
    />
  );
};

export default ConfigSnapshots;
