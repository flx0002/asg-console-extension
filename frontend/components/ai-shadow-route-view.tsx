/* eslint-disable max-len */
import React, { useCallback, useMemo, useState } from 'react';
import { Alert, Button, Card, Checkbox, Drawer, Empty, Input, Segmented, Space, Table, Tag, Tooltip, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ReloadOutlined } from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import { getAiShadowConsumers, getAiShadowStatus, replaceAiShadowConsumers } from '@/services';
import type { AiShadowStatus } from '@/interfaces/ai-shadow';

interface AiShadowRouteAdmissionProps {
  /** 影子AI总开关。关闭时授权按钮不可点，避免在功能停用期间改动准入名单。 */
  enabled?: boolean;
}

/**
 * 路由准入 · 策略：谁能调用哪条 AI 路由。
 *
 * <p>这里只回答「谁被允许」——允许名单为空即默认拒绝。发现出来的准入违规在
 * 「影子AI事件」里看，处置方式是全局的（在配置页顶部），所以本页不做任何观测与开关。
 */
const AiShadowRouteAdmissionPage: React.FC<AiShadowRouteAdmissionProps> = ({ enabled = true }) => {
  const { t } = useTranslation();
  const [keyword, setKeyword] = useState('');
  const [filter, setFilter] = useState<'all' | 'deny'>('all');
  const [drawerRow, setDrawerRow] = useState<AiShadowStatus | null>(null);
  const [checked, setChecked] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);

  // 后端会顺带对账（缺 key-auth 实例的路由自动纳管、名单为空落默认拒绝），
  // 所以这里拿到的每一条都是「已纳管」状态。
  const { data: statusList, loading, error, refresh } = useRequest(() => getAiShadowStatus(false), { onError: () => {} });
  const { data: allConsumers, refresh: refreshConsumers } = useRequest(() => getAiShadowConsumers(), { onError: () => {} });

  const rows = useMemo<AiShadowStatus[]>(() => statusList || [], [statusList]);

  const visibleRows = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    return rows.filter((row) => {
      if (kw && !`${row.routeName} ${row.resourceName || ''}`.toLowerCase().includes(kw)) {
        return false;
      }
      if (filter === 'deny') return !!row.defaultDeny;
      return true;
    });
  }, [rows, keyword, filter]);

  const denyRouteCount = rows.filter((row) => !!row.defaultDeny).length;

  const openDrawer = useCallback((row: AiShadowStatus) => {
    setDrawerRow(row);
    setChecked(row.authorizedConsumers || []);
  }, []);

  const saveDrawer = useCallback(async () => {
    if (!drawerRow) return;
    setSaving(true);
    try {
      await replaceAiShadowConsumers({ routeName: drawerRow.routeName, consumerNames: checked });
      message.success(t('aiShadow.drawerSaved'));
      setDrawerRow(null);
      refresh();
      refreshConsumers();
    } catch {
      message.error(t('aiShadow.actionFailed'));
    } finally {
      setSaving(false);
    }
  }, [drawerRow, checked, refresh, refreshConsumers, t]);

  const drawerOptions = useMemo(() => {
    const names = new Set<string>(allConsumers || []);
    (drawerRow?.authorizedConsumers || []).forEach((name) => names.add(name));
    return Array.from(names).sort();
  }, [allConsumers, drawerRow]);

  const columns: ColumnsType<AiShadowStatus> = [
    {
      title: t('aiShadow.colRoute'),
      dataIndex: 'routeName',
      width: '44%',
      render: (_value, row) => (
        <div>
          <span style={{ fontWeight: 500 }}>{row.routeName}</span>
          <div style={{ color: '#8f959e', fontSize: 12 }}>{row.resourceName}</div>
        </div>
      ),
    },
    {
      // 列头挂说明：整列都是「空」时，光看单元格不知道空意味着什么。
      title: (
        <Tooltip title={t('aiShadow.colAllowHint')}>
          <span style={{ borderBottom: '1px dashed #bfbfbf', cursor: 'help' }}>
            {t('aiShadow.colAllow')}
          </span>
        </Tooltip>
      ),
      dataIndex: 'authorizedConsumers',
      render: (_value, row) => {
        const names = row.authorizedConsumers || [];
        if (names.length === 0) {
          // 「默认拒绝」是结果不是内容：把结果写在内容列里，会让人以为这条名单叫默认拒绝。
          // 内容就是空的，结果放在悬浮说明里。
          return (
            <Tooltip title={t('aiShadow.statusDefaultDenyHint')}>
              <Tag style={{ cursor: 'help' }}>{t('aiShadow.allowEmpty')}</Tag>
            </Tooltip>
          );
        }
        return (
          <Space size={[4, 4]} wrap>
            {names.map((name) => <Tag key={name}>{name}</Tag>)}
          </Space>
        );
      },
    },
    {
      title: t('aiShadow.colActions'),
      align: 'right',
      width: 110,
      render: (_value, row) => (
        <Button size="small" disabled={!enabled} onClick={() => openDrawer(row)}>
          {t('aiShadow.authorize')}
        </Button>
      ),
    },
  ];

  return (
    <Card size="small" loading={loading && !statusList}>
      <Space style={{ marginBottom: 12 }} wrap>
        <Input
          allowClear
          style={{ width: 240 }}
          placeholder={t('aiShadow.searchPlaceholder')}
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
        />
        <Segmented
          value={filter}
          onChange={(value) => setFilter(value as typeof filter)}
          options={[
            { label: `${t('aiShadow.filterAll')} (${rows.length})`, value: 'all' },
            { label: `${t('aiShadow.filterDefaultDeny')} (${denyRouteCount})`, value: 'deny' },
          ]}
        />
        <Button icon={<ReloadOutlined />} onClick={() => { refresh(); refreshConsumers(); }}>
          {t('aiShadow.refresh')}
        </Button>
        {/* 与基础流量页对称的那一行规则：它定义的是「漏配会发生什么」。 */}
        <span style={{ marginLeft: 'auto', fontSize: 12, color: '#8c8c8c' }}>{t('aiShadow.routeAdmissionRule')}</span>
      </Space>

      {error ? (
        <Alert type="error" showIcon style={{ marginBottom: 12 }} message={t('aiShadow.routeLoadFailed')} />
      ) : null}

      <Table<AiShadowStatus>
        rowKey="routeName"
        size="middle"
        loading={loading}
        columns={columns}
        dataSource={visibleRows}
        pagination={{ defaultPageSize: 20, hideOnSinglePage: true }}
        locale={{ emptyText: <Empty description={t('aiShadow.noData')} /> }}
      />

      <Drawer
        width={440}
        open={!!drawerRow}
        onClose={() => setDrawerRow(null)}
        title={t('aiShadow.drawerTitle')}
        footer={(
          <div style={{ textAlign: 'right' }}>
            <Space>
              <Button onClick={() => setDrawerRow(null)}>{t('misc.cancel')}</Button>
              <Button type="primary" loading={saving} onClick={saveDrawer}>
                {t('aiShadow.drawerSave')}
              </Button>
            </Space>
          </div>
        )}
      >
        {drawerRow && (
          <div>
            <div style={{ marginBottom: 12, color: '#595959' }}>
              {t('aiShadow.routeName')}: <Tag>{drawerRow.routeName}</Tag>
            </div>
            <Alert
              type={checked.length === 0 ? 'warning' : 'info'}
              showIcon
              style={{ marginBottom: 12 }}
              message={checked.length === 0 ? t('aiShadow.statusDefaultDeny') : t('aiShadow.drawerHint')}
              description={checked.length === 0 ? t('aiShadow.drawerEmptyWarn') : undefined}
            />
            <Checkbox.Group
              value={checked}
              onChange={(values) => setChecked(values as string[])}
              style={{ display: 'block' }}
            >
              <Space direction="vertical" size={10} style={{ width: '100%' }}>
                {drawerOptions.map((name) => (
                  <Checkbox key={name} value={name} style={{ width: '100%' }}>
                    <span style={{ marginRight: 8 }}>{name}</span>
                    {name === 'none' ? <Tag>{t('aiShadow.usageNoIdentity')}</Tag> : null}
                  </Checkbox>
                ))}
              </Space>
            </Checkbox.Group>
            {drawerOptions.length === 0 && <Empty description={t('aiShadow.noData')} />}
          </div>
        )}
      </Drawer>
    </Card>
  );
};

export default AiShadowRouteAdmissionPage;
