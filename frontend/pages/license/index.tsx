import React from 'react';
import {
  Alert, Button, Card, Descriptions, message, Modal, Space, Table, Tag, Upload,
} from 'antd';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import {
  getKbLicense, importKbLicense, activateKbLicenseOnline,
} from '@/services/ai-kb';
import { KbLicenseStatus, KbLicenseFunction, KbDateTime } from '@/interfaces/ai-kb';

const pad2 = (n: number) => String(n).padStart(2, '0');

// 后端 LocalDateTime 可能序列化为 [y,m,d,h,m,s,ns] 数组或 ISO 字符串，统一转可读字符串。
// Jackson 会省略尾部为 0 的分/秒/纳秒（如 00:00:00 → [y,m,d]），故数组长度可为 3~7，缺省分量按 0 补。
const formatDateTime = (v?: KbDateTime): string => {
  if (!v) return '';
  if (Array.isArray(v) && v.length >= 3) {
    const [y, m, d, h = 0, mi = 0, s = 0] = v;
    return `${y}-${pad2(m)}-${pad2(d)} ${pad2(h)}:${pad2(mi)}:${pad2(s)}`;
  }
  return String(v).replace('T', ' ').slice(0, 19);
};

const LICENSE_COLORS: Record<string, string> = {
  valid: 'green', expired: 'orange', invalid: 'red', absent: 'default',
};

const LicensePage: React.FC = () => {
  const { t } = useTranslation();

  const { data: license, loading, refresh: refreshLicense } =
    useRequest(() => getKbLicense(), { onError: () => {} });

  const statusLabel = (s?: string) => {
    if (s === 'valid') return t('license.statusValid');
    if (s === 'expired') return t('license.statusExpired');
    if (s === 'invalid') return t('license.statusInvalid');
    return t('license.statusAbsent');
  };

  // 导入授权文件（厂商授权工具生成的 .dat），读为 Base64 后上传
  const fileToBase64 = async (file: File): Promise<string> => {
    const bytes = new Uint8Array(await file.arrayBuffer());
    let bin = '';
    for (let i = 0; i < bytes.length; i += 1) bin += String.fromCharCode(bytes[i]);
    return btoa(bin);
  };

  const handleImportLicense = async (file: File) => {
    try {
      await importKbLicense(await fileToBase64(file));
      message.success(t('license.importSuccess'));
      refreshLicense();
    } catch (e: any) {
      message.error(`${t('license.actionFailed')}: ${e?.message || e}`);
    }
  };

  const handleUpdateLicense = async () => {
    try {
      await activateKbLicenseOnline();
      message.success(t('license.updateSuccess'));
      refreshLicense();
    } catch (e: any) {
      message.error(`${t('license.actionFailed')}: ${e?.message || e}`);
    }
  };

  // 复制本机设备 ESN（供运维粘贴到厂商授权工具绑定设备后签发 .dat）
  const copyFp = async () => {
    const fp = license?.deviceFingerprint || '';
    if (!fp) return;
    try {
      await navigator.clipboard.writeText(fp);
      message.success(t('license.copied'));
    } catch (e) {
      message.error(t('license.actionFailed'));
    }
  };

  // 功能位是否有效：优先用后端计算的 active（按服务器当前时间判定，避免前端时区/格式误判）；
  // 若后端未下发（旧数据兼容），退化为本地比较。
  const fnValid = (row: KbLicenseFunction): boolean => {
    if (typeof row.active === 'boolean') return row.active;
    if (!row.expiresAt) return true;
    const end = formatDateTime(row.expiresAt);
    const now = new Date();
    const nowStr = `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())} `
      + `${pad2(now.getHours())}:${pad2(now.getMinutes())}:${pad2(now.getSeconds())}`;
    return end !== '' && end >= nowStr;
  };

  const fnColumns = [
    {
      title: t('license.featureName'), dataIndex: 'name', key: 'name',
      render: (v: string) => t(`license.feature.${v}`, { defaultValue: v || '-' }),
    },
    {
      title: t('license.featureIssuedAt'), dataIndex: 'issuedAt', key: 'issuedAt', width: 200,
      render: (v: KbDateTime) => formatDateTime(v) || '-',
    },
    {
      title: t('license.featureExpiresAt'), dataIndex: 'expiresAt', key: 'expiresAt', width: 200,
      render: (v: KbDateTime) => (v ? formatDateTime(v) : t('license.forever')),
    },
    {
      title: t('license.featureStatus'), key: 'status', width: 100,
      render: (_: any, row: KbLicenseFunction) => (
        <Tag color={fnValid(row) ? 'green' : 'red'}>{fnValid(row) ? t('license.yes') : t('license.no')}</Tag>
      ),
    },
  ];

  const lic: KbLicenseStatus = license || {};

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {lic.status !== 'valid' && lic.reason && (
        <Alert type="warning" showIcon message={lic.reason} />
      )}

      <Card
        title={t('license.sectionInfo')}
        loading={loading}
        extra={
          <Space>
            <Upload
              accept=".dat"
              showUploadList={false}
              beforeUpload={(file: any) => {
                Modal.confirm({
                  title: t('license.importConfirmTitle'),
                  content: t('license.importConfirmContent'),
                  onOk: () => handleImportLicense(file),
                });
                return false;
              }}
            >
              <Button>{t('license.import')}</Button>
            </Upload>
            <Button type="primary" onClick={handleUpdateLicense}>{t('license.update')}</Button>
          </Space>
        }
      >
        <Descriptions bordered size="small" column={2}>
          <Descriptions.Item label={t('license.status')}>
            <Tag color={LICENSE_COLORS[lic.status || 'absent'] || 'default'}>
              {statusLabel(lic.status)}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label={t('license.type')}>
            {lic.licenseType === 'formal' ? t('license.typeFormal')
              : lic.licenseType === 'temporary' ? t('license.typeTemporary')
                : (lic.licenseType || '-')}
          </Descriptions.Item>
          <Descriptions.Item label={t('license.company')} span={2}>{lic.companyName || '-'}</Descriptions.Item>
          <Descriptions.Item label={t('license.deviceFp')} span={2}>
            <Space>
              {lic.deviceFingerprint || '-'}
              {lic.deviceFingerprint && (
                <Button size="small" onClick={copyFp}>{t('license.copy')}</Button>
              )}
            </Space>
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title={t('license.sectionFeatures')}>
        <Table
          rowKey={(r: KbLicenseFunction) => String(r.name)}
          size="small"
          loading={loading}
          columns={fnColumns}
          dataSource={lic.functions || []}
          pagination={false}
          locale={{ emptyText: t('license.noFeatures') }}
        />
      </Card>
    </Space>
  );
};

export default LicensePage;
