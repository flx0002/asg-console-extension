import React, { useEffect, useState } from 'react';
import {
  Alert, Card, Descriptions, Input, message, Space, Tag, Typography,
} from 'antd';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import { getKbOnlineSetting, updateKbOnlineSetting } from '@/services/ai-kb';
import { KbOnlineSetting } from '@/interfaces/ai-kb';

const KbOnlineSettingPage: React.FC = () => {
  const { t } = useTranslation();
  const [url, setUrl] = useState('');

  const { data: setting, loading, refresh } =
    useRequest(() => getKbOnlineSetting(), {
      onError: () => {},
      onSuccess: (s: KbOnlineSetting) => setUrl(s?.url ?? ''),
    });

  // 加载完成后把页面持久化值回填输入框（env 默认只读展示，不回填，避免误覆盖）
  useEffect(() => {
    if (setting) setUrl(setting.url ?? '');
  }, [setting]);

  const handleSave = async () => {
    const trimmed = url.trim();
    if (trimmed && !/^https?:\/\/\S+/i.test(trimmed)) {
      message.error(t('kbSetting.invalid'));
      return;
    }
    try {
      await updateKbOnlineSetting(trimmed);
      message.success(t('kbSetting.saved'));
      refresh();
    } catch (e: any) {
      message.error(`${t('kbSetting.actionFailed')}: ${e?.message || e}`);
    }
  };

  const lic: KbOnlineSetting = setting || {};
  const configured = !!lic.configured;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {!configured && (
        <Alert type="warning" showIcon message={t('kbSetting.notConfiguredHint')} />
      )}

      <Card title={t('kbSetting.section')}>
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Typography.Text>{t('kbSetting.serverUrl')}</Typography.Text>
          <Input.Search
            value={url}
            allowClear
            onChange={(e) => setUrl(e.target.value)}
            onSearch={handleSave}
            enterButton={t('kbSetting.save')}
            placeholder={t('kbSetting.placeholder')}
            disabled={loading}
          />
        </Space>
      </Card>

      <Card title={t('kbSetting.sectionStatus')} loading={loading}>
        <Descriptions bordered size="small" column={1}>
          <Descriptions.Item label={t('kbSetting.status')}>
            <Tag color={configured ? 'green' : 'default'}>
              {configured ? t('kbSetting.configured') : t('kbSetting.notConfigured')}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label={t('kbSetting.effective')}>
            <Typography.Text code>{lic.effectiveUrl || '-'}</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label={t('kbSetting.envDefault')}>
            <Typography.Text code>{lic.envUrl || '-'}</Typography.Text>
          </Descriptions.Item>
        </Descriptions>
      </Card>
    </Space>
  );
};

export default KbOnlineSettingPage;
