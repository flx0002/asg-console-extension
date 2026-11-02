/* eslint-disable max-len */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Empty, Input, Modal, Space, Table, Tag, Tooltip, message } from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import { getAiShadowAuthorizedDomains, getAiShadowEnabled, updateAiShadowAuthorizedDomains } from '@/services';
import type { AiShadowAuthzItem } from '@/interfaces/ai-shadow';

const pad2 = (n: number): string => (n < 10 ? `0${n}` : String(n));

// 输入的域名先归一：去掉协议、路径、端口和通配前缀，否则同一域名会以多种写法进清单，
// 之后清理时谁也说不清哪一条才是「有效的那条」。
const normalizeAuthzInput = (raw: string): string => raw.trim().toLowerCase()
  .replace(/^https?:\/\//, '')
  .replace(/\/.*$/, '')
  .replace(/:\d+$/, '')
  .replace(/^\*\.?/, '');

const isPlausibleDomain = (value: string): boolean => value.length > 0 && value.length <= 255
  && /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/.test(value);

const formatDateTime = (ms?: number): string => {
  if (!ms) return '-';
  const d = new Date(ms);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
};

/**
 * 基础流量 · 策略：哪些域名的访问算是「正常使用的 AI」。
 *
 * <p>这个清单是基础流量通道的判定依据——不在清单内的 AI 访问记为影子AI。
 * 所以本页只有一份清单和一个输入框，没有任何观测内容：看到了什么在「影子AI事件」里。
 */
const AiShadowBasicTrafficPage: React.FC<{ enabled?: boolean }> = ({ enabled }) => {
  const { t } = useTranslation();
  // 直接访问 /ai-shadow/detected 时没有父组件传状态，必须自己读一次总开关：
  // 否则关闭影子AI后，这个地址仍能改授权名单，绕过了「关闭」这件事本身。
  const [globalEnabled, setGlobalEnabled] = useState<boolean>(true);
  useEffect(() => {
    getAiShadowEnabled().then((v) => setGlobalEnabled(v !== false)).catch(() => {});
  }, []);
  const active = enabled === undefined ? globalEnabled : enabled;
  const [input, setInput] = useState('');
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<string[]>([]);

  // 默认窗口（后端 24 小时）只用于「命中」这一列：一条授权如果长期零命中，
  // 说明它要么写错了、要么早就没人在用，是该清理的信号。
  const { data, refresh, loading, error } = useRequest(() => getAiShadowAuthorizedDomains(), { onError: () => {} });

  const items = useMemo<AiShadowAuthzItem[]>(() => {
    const list = data?.items;
    if (list && list.length) return list;
    return (data?.domains || []).map((domain) => ({ domain, matchType: 'suffix', source: 'imported', createdAt: 0 }));
  }, [data]);

  const filtered = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    if (!keyword) return items;
    return items.filter((item) => item.domain.includes(keyword));
  }, [items, search]);

  const addDomain = useCallback(async () => {
    const domain = normalizeAuthzInput(input);
    if (!domain) return;
    if (!isPlausibleDomain(domain)) {
      message.error(t('aiShadow.authzInvalidDomain'));
      return;
    }
    if (items.some((item) => item.domain === domain)) {
      message.warning(t('aiShadow.authzDuplicate'));
      return;
    }
    try {
      await updateAiShadowAuthorizedDomains({ addDomains: [domain], source: 'manual' });
      message.success(t('aiShadow.authzSuccess', { domain }));
      setInput('');
      refresh();
    } catch {
      message.error(t('aiShadow.actionFailed'));
    }
  }, [input, items, refresh, t]);

  // 移除授权会让该域名的新访问重新作为影子事件上报，是影响判定的破坏性操作，
  // 单条与批量都必须先确认；已产生的历史事件不受影响。
  const removeDomain = useCallback((domain: string) => {
    Modal.confirm({
      title: t('aiShadow.deauthzConfirmTitle'),
      content: t('aiShadow.deauthzConfirmSingle', { domain }),
      okText: t('aiShadow.deauthzBtn'),
      okButtonProps: { danger: true },
      cancelText: t('misc.cancel'),
      onOk: async () => {
        try {
          await updateAiShadowAuthorizedDomains({ removeDomains: [domain] });
          message.success(t('aiShadow.deauthzSuccess', { domain }));
          setSelected((prev) => prev.filter((item) => item !== domain));
          refresh();
        } catch {
          message.error(t('aiShadow.actionFailed'));
        }
      },
    });
  }, [refresh, t]);

  const removeSelected = useCallback(() => {
    if (!selected.length) return;
    Modal.confirm({
      title: t('aiShadow.deauthzConfirmTitle'),
      content: t('aiShadow.deauthzConfirmBatch', { count: selected.length }),
      okText: t('aiShadow.deauthzBtn'),
      okButtonProps: { danger: true },
      cancelText: t('misc.cancel'),
      onOk: async () => {
        try {
          await updateAiShadowAuthorizedDomains({ removeDomains: selected });
          message.success(t('aiShadow.authzBatchDeauthzSuccess', { count: selected.length }));
          setSelected([]);
          refresh();
        } catch {
          message.error(t('aiShadow.actionFailed'));
        }
      },
    });
  }, [selected, refresh, t]);

  const columns = [
    {
      title: t('aiShadow.authzColDomain'),
      dataIndex: 'domain',
      key: 'domain',
      render: (value: string) => <span style={{ fontFamily: 'monospace' }}>{value}</span>,
    },
    {
      title: t('aiShadow.authzColMatch'),
      dataIndex: 'matchType',
      key: 'matchType',
      width: 110,
      render: (value: string) => (value === 'exact'
        ? <Tag>{t('aiShadow.authzMatchExact')}</Tag>
        : <Tag color="blue">{t('aiShadow.authzMatchSuffix')}</Tag>),
    },
    {
      title: t('aiShadow.authzColSource'),
      dataIndex: 'source',
      key: 'source',
      width: 120,
      render: (value: string) => {
        const meta: Record<string, { color: string; label: string }> = {
          manual: { color: 'green', label: t('aiShadow.authzSourceManual') },
          detected: { color: 'gold', label: t('aiShadow.authzSourceDetected') },
          imported: { color: 'default', label: t('aiShadow.authzSourceImported') },
        };
        const hit = meta[value] || meta.imported;
        return <Tag color={hit.color}>{hit.label}</Tag>;
      },
    },
    {
      title: (
        <Tooltip title={t('aiShadow.authzHitsHint')}>
          <span>{t('aiShadow.authzColHits24h')}</span>
        </Tooltip>
      ),
      dataIndex: 'hitCount',
      key: 'hitCount',
      width: 130,
      align: 'right' as const,
      // null 与 0 必须分开：0 是「确认没流量」，null 是「没算出来」（聚合失败）。
      render: (value?: number | null) => {
        if (value === null || value === undefined) {
          return <Tooltip title={t('aiShadow.authzHitsUnknown')}><span style={{ color: '#bfbfbf' }}>-</span></Tooltip>;
        }
        if (value === 0) {
          return <Tooltip title={t('aiShadow.authzHitsZero')}><span style={{ color: '#bfbfbf' }}>0</span></Tooltip>;
        }
        return <span>{value.toLocaleString()}</span>;
      },
    },
    {
      title: t('aiShadow.authzColCreatedAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (value: number) => <span style={{ color: value ? undefined : '#bfbfbf' }}>{formatDateTime(value)}</span>,
    },
    {
      title: t('aiShadow.authzColActions'),
      key: 'actions',
      width: 90,
      render: (_: unknown, record: AiShadowAuthzItem) => (
        <Button type="link" size="small" danger disabled={!active} onClick={() => removeDomain(record.domain)}>
          {t('aiShadow.deauthzBtn')}
        </Button>
      ),
    },
  ];

  return (
    <Card size="small" loading={loading && !data}>
      <Space style={{ marginBottom: 12 }} wrap>
        <Input
          style={{ width: 320 }}
          value={input}
          placeholder={t('aiShadow.authzAddPlaceholder')}
          onChange={(e) => setInput(e.target.value)}
          onPressEnter={addDomain}
          disabled={!active}
          allowClear
        />
        <Button type="primary" icon={<PlusOutlined />} disabled={!active} onClick={addDomain}>
          {t('aiShadow.authzAddBtn')}
        </Button>
        <Input.Search
          allowClear
          style={{ width: 200 }}
          placeholder={t('aiShadow.authzSearchPlaceholder')}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <Button danger disabled={!enabled || selected.length === 0} onClick={removeSelected}>
          {t('aiShadow.authzBatchDeauthz', { count: selected.length })}
        </Button>
        <Button icon={<ReloadOutlined />} onClick={refresh}>{t('aiShadow.refresh')}</Button>
        {/* 页面上唯一的一句规则说明：它定义的是「漏配会发生什么」，删了会让人不知道后果。 */}
        <span style={{ marginLeft: 'auto', fontSize: 12, color: '#8c8c8c' }}>{t('aiShadow.basicTrafficRule')}</span>
      </Space>

      {error ? (
        <Alert type="error" showIcon style={{ marginBottom: 12 }} message={t('aiShadow.authzLoadFailed')} />
      ) : null}

      <Table<AiShadowAuthzItem>
        dataSource={filtered}
        columns={columns}
        rowKey="domain"
        size="small"
        pagination={{ pageSize: 10, hideOnSinglePage: true, size: 'small' }}
        locale={{ emptyText: <Empty description={t('aiShadow.authzNoData')} /> }}
        rowSelection={{
          selectedRowKeys: selected,
          onChange: (keys) => setSelected(keys as string[]),
        }}
      />
    </Card>
  );
};

export default AiShadowBasicTrafficPage;
