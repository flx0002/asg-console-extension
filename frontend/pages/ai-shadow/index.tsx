import React, { useState, useEffect, useCallback } from 'react';
import { Card, Switch, Tooltip, Space, Tabs, Row, Col, message } from 'antd';
import { SafetyCertificateOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getAiShadowEnabled, setAiShadowEnabled } from '@/services';
import DetectedView from './detected';
import RouteView from './route';

// 影子AI 统一入口：顶部为功能总开关（独占一行，与检测/管控模式分离），
// 其下两个 tab 分别承载「流量检测与审计」和「路由与消费者管控」。
const AiShadowPage: React.FC = () => {
  const { t } = useTranslation();
  const [enabled, setEnabledState] = useState<boolean>(true);
  const [switching, setSwitching] = useState<boolean>(false);

  useEffect(() => {
    getAiShadowEnabled().then((v) => setEnabledState(v !== false)).catch(() => {});
  }, []);

  const handleEnabledSwitch = useCallback(async () => {
    // 防止连续快速点击触发并发 PUT（同一 CR 版本冲突会返回 500），请求在途时忽略新的点击。
    if (switching) {
      return;
    }
    const next = !enabled;
    setSwitching(true);
    try {
      await setAiShadowEnabled(next);
      setEnabledState(next);
      message.success(next ? t('aiShadow.enableSuccess') : t('aiShadow.disableSuccess'));
    } catch {
      message.error(t('aiShadow.actionFailed'));
    } finally {
      setSwitching(false);
    }
  }, [enabled, switching, t]);

  return (
    <div style={{ padding: '0 0 24px' }}>
      <Card style={{ marginBottom: 16 }}>
        <Row align="middle" justify="space-between">
          <Col>
            <Space size="middle">
              <SafetyCertificateOutlined style={{ fontSize: 16 }} />
              <span style={{ fontSize: 15, fontWeight: 600 }}>{t('aiShadow.featureSwitchLabel')}</span>
              <Tooltip title={t('aiShadow.enabledTooltip')}>
                <Switch
                  checked={enabled}
                  loading={switching}
                  checkedChildren={t('aiShadow.enabledOn')}
                  unCheckedChildren={t('aiShadow.enabledOff')}
                  onChange={handleEnabledSwitch}
                />
              </Tooltip>
            </Space>
          </Col>
          <Col>
            <span style={{ color: enabled ? '#52c41a' : '#cf1322' }}>
              {enabled ? t('aiShadow.enabledOnDesc') : t('aiShadow.detectDisabledDesc')}
            </span>
          </Col>
        </Row>
      </Card>

      <Tabs
        defaultActiveKey="detected"
        items={[
          {
            key: 'detected',
            label: t('aiShadow.tabDetectedLabel'),
            children: <DetectedView enabled={enabled} />,
          },
          {
            key: 'route',
            label: t('aiShadow.tabRouteLabel'),
            children: <RouteView enabled={enabled} />,
          },
        ]}
      />
    </div>
  );
};

export default AiShadowPage;
