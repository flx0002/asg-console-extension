/* eslint-disable max-len */
import React, { useCallback, useEffect, useState } from 'react';
import { Button, Card, Modal, Segmented, Switch, Tabs, Tooltip, message } from 'antd';
import { ArrowRightOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getAiShadowDetectMode, getAiShadowEnabled, setAiShadowDetectMode, setAiShadowEnabled } from '@/services';
import DetectedView from '@/components/ai-shadow-detected-view';
import RouteView from '@/components/ai-shadow-route-view';
import CaptureView from '@/components/ai-shadow-capture-view';

type Disposition = 'monitoring' | 'enforcement';

/**
 * 影子AI配置：本页只管「怎么判定影子AI」和「发现之后怎么办」。
 * 发现出来的东西一律在「影子AI事件」里看，两者的分界线就是这张页面本身的边界。
 *
 * <p>两个设置都放在页签之上：它们管的是整个影子AI，不是其中某一条通道。
 * 通道只是网关发现它的位置不同——没走纳管路由的是基础流量侧发现，
 * 走了纳管路由但过不了准入的是路由准入侧发现。
 *
 * <p>路由是 /ai-shadow/config 而不是 /ai-shadow：菜单高亮按 path 前缀匹配，
 * 配置页若占着 /ai-shadow，访问 /ai-shadow/events 时两项会同时命中，看起来像两个都被选中。
 * 让两个菜单项变成平级的子路径，才能真正只亮一个；/ai-shadow 由 pages/ai-shadow/index.tsx 重定向过来。
 */
const AiShadowConfigPage: React.FC = () => {
  const { t } = useTranslation();
  const [enabled, setEnabledState] = useState<boolean>(true);
  const [mode, setModeState] = useState<Disposition>('monitoring');
  const [switchingEnabled, setSwitchingEnabled] = useState<boolean>(false);
  const [switchingMode, setSwitchingMode] = useState<boolean>(false);

  useEffect(() => {
    getAiShadowEnabled().then((v) => setEnabledState(v !== false)).catch(() => {});
    getAiShadowDetectMode()
      .then((v) => {
        if (v === 'monitoring' || v === 'enforcement') setModeState(v);
      })
      .catch(() => {});
  }, []);

  const applyEnabled = useCallback(async (next: boolean) => {
    setSwitchingEnabled(true);
    try {
      await setAiShadowEnabled(next);
      setEnabledState(next);
      message.success(next ? t('aiShadow.enableSuccess') : t('aiShadow.disableSuccess'));
    } catch {
      message.error(t('aiShadow.actionFailed'));
      // 后端可能是「部分生效」：全局插件写成功了，某几条路由的准入没同步上。
      // 界面若停在旧值，会让人以为什么都没发生，实际已经是新状态。
      getAiShadowEnabled().then((v) => setEnabledState(v !== false)).catch(() => {});
    } finally {
      setSwitchingEnabled(false);
    }
  }, [t]);

  const applyMode = useCallback(async (next: Disposition) => {
    setSwitchingMode(true);
    try {
      await setAiShadowDetectMode(next);
      setModeState(next);
      message.success(t('aiShadow.modeSwitchSuccess'));
    } catch {
      message.error(t('aiShadow.actionFailed'));
      // 同上：处置方式也是先写全局再逐路由同步，失败时以服务端实际值为准。
      getAiShadowDetectMode()
        .then((v) => {
          if (v === 'monitoring' || v === 'enforcement') setModeState(v);
        })
        .catch(() => {});
    } finally {
      setSwitchingMode(false);
    }
  }, [t]);

  // 这个开关一次动两条通道：全局插件 + 每条纳管路由上的准入校验。
  // 两个方向都会改变线上行为，所以两个方向都要先说清楚再执行——
  // 只在关闭时提示，会让人以为「打开只是恢复观测」。
  const handleEnabledSwitch = useCallback(() => {
    if (!enabled) {
      Modal.confirm({
        title: t('aiShadow.enableConfirmTitle'),
        content: mode === 'enforcement'
          ? t('aiShadow.enableConfirmContentEnforce')
          : t('aiShadow.enableConfirmContentMonitor'),
        okText: t('aiShadow.enableConfirmOk'),
        cancelText: t('misc.cancel'),
        onOk: () => applyEnabled(true),
      });
      return;
    }
    Modal.confirm({
      title: t('aiShadow.disableConfirmTitle'),
      content: t('aiShadow.disableConfirmContent'),
      okText: t('aiShadow.disableConfirmOk'),
      okButtonProps: { danger: true },
      cancelText: t('misc.cancel'),
      onOk: () => applyEnabled(false),
    });
  }, [enabled, mode, applyEnabled, t]);

  const handleModeChange = useCallback((next: Disposition) => {
    if (next === mode) return;
    // 切到拦截会让未授权的访问当场被拒，属于改变线上行为，先确认。
    if (next === 'enforcement') {
      Modal.confirm({
        title: t('aiShadow.dispositionConfirmTitle'),
        content: t('aiShadow.dispositionConfirmContent'),
        okText: t('aiShadow.dispositionConfirmOk'),
        cancelText: t('misc.cancel'),
        onOk: () => applyMode(next),
      });
      return;
    }
    applyMode(next);
  }, [mode, applyMode, t]);

  return (
    <div style={{ padding: '0 0 24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 14 }}>
        <span style={{ fontSize: 16, fontWeight: 600 }}>{t('aiShadow.configPageTitle')}</span>
        <Button
          type="link"
          style={{ marginLeft: 'auto' }}
          onClick={() => { window.location.href = '/ai-shadow/events'; }}
        >
          {t('aiShadow.viewEvents')} <ArrowRightOutlined />
        </Button>
      </div>

      <Card size="small" style={{ marginBottom: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
          <span style={{ fontSize: 13, fontWeight: 600 }}>{t('aiShadow.detectSwitch')}</span>
          <Tooltip title={t('aiShadow.enabledTooltip')}>
            <Switch
              checked={enabled}
              loading={switchingEnabled}
              checkedChildren={t('aiShadow.enabledOn')}
              unCheckedChildren={t('aiShadow.enabledOff')}
              onChange={handleEnabledSwitch}
            />
          </Tooltip>
          <span style={{ marginLeft: 'auto', fontSize: 12, color: '#8c8c8c' }}>
            {enabled ? t('aiShadow.detectSwitchOnHint') : t('aiShadow.detectDisabledDesc')}
          </span>
        </div>

        <div style={{ height: 1, background: '#f0f0f0', margin: '14px 0' }} />

        <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
          <span style={{ fontSize: 13, fontWeight: 600 }}>{t('aiShadow.disposition')}</span>
          <Segmented
            value={mode}
            disabled={!enabled}
            loading={switchingMode}
            onChange={(value) => handleModeChange(value as Disposition)}
            options={[
              { value: 'monitoring', label: t('aiShadow.dispositionMonitor') },
              { value: 'enforcement', label: t('aiShadow.dispositionEnforce') },
            ]}
          />
          <span style={{ marginLeft: 'auto', fontSize: 12, color: '#8c8c8c' }}>
            {mode === 'enforcement' ? t('aiShadow.dispositionEnforceHint') : t('aiShadow.dispositionMonitorHint')}
          </span>
        </div>
        {mode === 'monitoring' ? (
          <div style={{ marginTop: 10, fontSize: 12, color: '#d46b08' }}>
            {t('aiShadow.dispositionMonitorWhy')}
          </div>
        ) : null}
      </Card>

      <Tabs
        defaultActiveKey="basic"
        items={[
          {
            key: 'basic',
            label: t('aiShadow.tabBasicTraffic'),
            children: <DetectedView enabled={enabled} />,
          },
          {
            key: 'route',
            label: t('aiShadow.tabRouteAdmission'),
            children: <RouteView enabled={enabled} />,
          },
          {
            key: 'capture',
            label: t('aiShadow.tabCapture'),
            children: <CaptureView mode={mode} />,
          },
        ]}
      />
    </div>
  );
};

export default AiShadowConfigPage;
