/**
 * 内容合规词库管理页（AI内容安全 → 内容合规词库）
 * 路由：/ai-content-compliance-kb
 *
 * 核心功能：
 *   1. 展示词库内容本身（31 类关键词/正则，按 6 业务语义归组 Collapse）
 *   2. 版本管理（在线更新 / 离线导入 / 历史回滚）—— 独立 /v1/compliance-kb/* 端点
 *   3. 授权门控（license.functions 中 featureCode=content_compliance_kb）
 *
 * 数据来源优先级：
 *   后端 /v1/compliance-kb/content → 若不可用则降级读前端内置 appA_rules.json
 */
import React, { useState, useMemo } from 'react';
import {
  Alert, Button, Card, Collapse, Descriptions, Input, Modal,
  Popconfirm, Row, Col, Space, Statistic, Table, Tag,
  Typography, Upload, message,
} from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'ice';
import {
  getComplianceKbContent, listComplianceKbVersions, getActiveComplianceKb,
  importComplianceKbOffline, updateComplianceKbOnline, rollbackComplianceKb,
  syncComplianceKbToGateway, getComplianceKbLicense,
} from '@/services/compliance-kb';
// 在线更新服务器基址与 ai-kb 共用同一份系统设置（/v1/ai-kb/online-setting）
import { getKbOnlineSetting } from '@/services/ai-kb';
import {
  ComplianceKbContent, ComplianceKbVersion, ComplianceKbCategory,
} from '@/interfaces/compliance-kb';
// 内置基线（后端未就绪时降级展示）
import builtinData from '../ai-prompt-guard/appA_rules.json';

const pad2 = (n: number) => String(n).padStart(2, '0');
const formatDateTime = (v?: number[] | string): string => {
  if (!v) return '-';
  if (Array.isArray(v) && v.length >= 6) {
    const [y, m, d, h = 0, mi = 0, s = 0] = v;
    return `${y}-${pad2(m)}-${pad2(d)} ${pad2(h)}:${pad2(mi)}:${pad2(s)}`;
  }
  return String(v).replace('T', ' ').slice(0, 19);
};

/** 6 业务语义归组（与 prompt-guard 页一致） */
const BUSINESS_GROUPS = [
  { id: 'political-security', label: '政治安全', codes: ['A.1-a', 'A.1-b', 'A.1-c', 'A.1-e'] },
  { id: 'public-security', label: '公共安全', codes: ['A.1-d', 'A.1-f'] },
  { id: 'social-ethics', label: '公序良俗', codes: ['A.1-g', 'A.1-h', 'A.2-a', 'A.2-b', 'A.2-c', 'A.2-d', 'A.2-e', 'A.2-f', 'A.2-g', 'A.2-h', 'A.2-i'] },
  { id: 'business-order', label: '商业秩序', codes: ['A.3-a', 'A.3-b', 'A.3-c', 'A.3-d', 'A.3-e'] },
  { id: 'rights-protection', label: '他人权益', codes: ['A.4-a', 'A.4-b', 'A.4-c', 'A.4-d', 'A.4-e', 'A.4-f', 'A.4-g'] },
  { id: 'service-advisory', label: '特定服务 · 免责提示', codes: ['A.5-a', 'A.5-b'] },
];

/** 从 sections 展平所有 categories */
function flattenCategories(sections: any[]): ComplianceKbCategory[] {
  return sections.flatMap((s: any) => (s.categories || []).map((c: any) => ({
    code: c.code, name: c.name, mode: c.mode || 'block',
    keywords: c.keywords || [], patterns: c.patterns || [],
    keywordCount: c.keywordCount || (c.keywords || []).length,
    advice: c.advice || '', sample: c.sample,
  })));
}

const ComplianceKbPage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [searchText, setSearchText] = useState('');

  // --- 数据加载 ---
  const { data: remoteContent, loading: contentLoading } =
    useRequest(() => getComplianceKbContent(), { onError: () => {} });
  const { data: versions, loading: versionsLoading, refresh: refreshVersions } =
    useRequest(() => listComplianceKbVersions(), { onError: () => {} });
  const { data: activeVersion, refresh: refreshActive } =
    useRequest(() => getActiveComplianceKb(), { onError: () => {} });
  const { data: license, refresh: refreshLicense } =
    useRequest(() => getComplianceKbLicense(), { onError: () => {} });
  const { data: onlineSetting } =
    useRequest(() => getKbOnlineSetting(), { onError: () => {} });

  // 后端不可用时降级到内置基线
  const usingBuiltin = !remoteContent;
  const content: ComplianceKbContent | null = useMemo(() => {
    if (remoteContent) return remoteContent;
    // 内置 appA_rules.json
    const raw = builtinData as any;
    if (!raw?.sections) return null;
    const cats = flattenCategories(raw.sections);
    return {
      versionNo: 0, kbHash: 'builtin', standard: raw.standard || 'GB/T 45654-2025',
      appendix: raw.appendix || 'A', categoryTotal: raw.categoryTotal || cats.length,
      keywordTotal: raw.keywordTotal || cats.reduce((a, c) => a + c.keywordCount, 0),
      generatedBy: raw.generatedBy, sections: raw.sections,
    } as ComplianceKbContent;
  }, [remoteContent]);

  const allCategories = useMemo(
    () => (content ? flattenCategories(content.sections as any[]) : []),
    [content],
  );

  const canUpdate = license?.canUpdate === true;
  // 在线更新需先配置服务器地址（与 ai-kb 同一份设置）；未配置时禁用并提示
  const onlineConfigured = !!onlineSetting?.configured;
  const reloadAll = () => { refreshVersions(); refreshActive(); refreshLicense(); };

  // --- 操作函数 ---
  const handleOnlineUpdate = async () => {
    try {
      const meta = await updateComplianceKbOnline();
      if (meta?.unchanged) message.info(t('complianceKb.alreadyLatest'));
      else message.success(t('complianceKb.updateSuccess'));
      reloadAll();
    } catch (e: any) {
      message.error(`${t('complianceKb.updateFailed')}: ${e?.message || e}`);
    }
  };

  const handleRollback = async (versionNo: number) => {
    try {
      await rollbackComplianceKb(versionNo);
      message.success(t('complianceKb.rollbackSuccess'));
      reloadAll();
    } catch (e: any) {
      message.error(`${t('complianceKb.actionFailed')}: ${e?.message || e}`);
    }
  };

  // 离线导入更新包：与 ai-kb 同一厂商 signtool 产出的二进制 .wnt（外层签名 + 内层 AES-GCM），
  // 直读字节转 Base64 上送；空文件拦截、命中相同内容回退“已是最新”（与在线更新一致的去重反馈）。
  const handleImport = async (file: File) => {
    try {
      const bytes = new Uint8Array(await file.arrayBuffer());
      if (!bytes.length) {
        message.error(t('complianceKb.invalidPackage'));
        return;
      }
      let binary = '';
      const chunk = 0x8000;
      for (let i = 0; i < bytes.length; i += chunk) {
        binary += String.fromCharCode.apply(null, Array.from(bytes.subarray(i, i + chunk)));
      }
      const meta = await importComplianceKbOffline({ bundle: btoa(binary) });
      if (meta?.unchanged) message.info(t('complianceKb.alreadyLatest'));
      else message.success(t('complianceKb.updateSuccess'));
      reloadAll();
    } catch (e: any) {
      message.error(`${t('complianceKb.updateFailed')}: ${e?.message || e}`);
    }
  };

  const handleSyncGateway = async () => {
    try {
      const ok = await syncComplianceKbToGateway();
      if (ok) { message.success(t('complianceKb.syncSuccess')); reloadAll(); }
      else { message.warning(t('complianceKb.syncSkipped')); }
    } catch (e: any) {
      message.error(`${t('complianceKb.actionFailed')}: ${e?.message || e}`);
    }
  };

  // --- 搜索过滤 ---
  const filteredCategories = useMemo(() => {
    if (!searchText.trim()) return allCategories;
    const kw = searchText.trim().toLowerCase();
    return allCategories.filter((c) =>
      c.name.toLowerCase().includes(kw) || c.code.toLowerCase().includes(kw) ||
      c.keywords.some((k) => k.toLowerCase().includes(kw)),
    );
  }, [allCategories, searchText]);

  // --- 按 6 业务组归类 ---
  const groupedData = useMemo(() => {
    return BUSINESS_GROUPS.map((g) => ({
      ...g,
      items: filteredCategories.filter((c) => g.codes.includes(c.code)),
    })).filter((g) => g.items.length > 0);
  }, [filteredCategories]);

  // --- 词库内容 Collapse（antd v4：使用 <Collapse.Panel> 子元素，而非 v5 的 items 属性 API） ---
  const collapsePanels = groupedData.map((g) => (
    <Collapse.Panel
      key={g.id}
      header={(
        <Space>
          <span style={{ fontWeight: 500 }}>{g.label}</span>
          <Tag>{g.items.length} {t('complianceKb.unitCategories')}</Tag>
          <Typography.Text type="secondary">
            {g.items.reduce((a, c) => a + c.keywordCount, 0)} {t('complianceKb.unitKeywords')}
          </Typography.Text>
        </Space>
      )}
    >
      <Table
        rowKey="code"
        size="small"
        pagination={false}
        dataSource={g.items}
        columns={[
          { title: t('complianceKb.colCode'), dataIndex: 'code', width: 80 },
          { title: t('complianceKb.colName'), dataIndex: 'name', ellipsis: true },
          {
            title: t('complianceKb.colMode'), dataIndex: 'mode', width: 100,
            render: (v: string) => v === 'advisory'
              ? <Tag color="blue">{t('complianceKb.modeAdvisory')}</Tag>
              : <Tag color="red">{t('complianceKb.modeBlock')}</Tag>,
          },
          {
            title: t('complianceKb.colKeywords'), dataIndex: 'keywordCount', width: 80,
            render: (v: number, row: ComplianceKbCategory) =>
              `${v}${row.patterns?.length ? ` +${row.patterns.length} ${t('complianceKb.patternsUnit')}` : ''}`,
          },
        ]}
      />
    </Collapse.Panel>
  ));

  // --- 版本表列 ---
  const versionColumns = [
    { title: t('complianceKb.colVersion'), dataIndex: 'versionNo', key: 'versionNo', width: 100 },
    { title: t('complianceKb.colLabel'), dataIndex: 'label', key: 'label' },
    { title: t('complianceKb.colCategories'), dataIndex: 'categoryCount', key: 'categoryCount', width: 90 },
    { title: t('complianceKb.colKeywords'), dataIndex: 'keywordCount', key: 'keywordCount', width: 90 },
    {
      title: t('complianceKb.colStatus'), dataIndex: 'status', key: 'status', width: 90,
      render: (v: string) => <Tag color={v === 'active' ? 'green' : 'default'}>{v === 'active' ? t('complianceKb.statusActive') : t('complianceKb.statusInactive')}</Tag>,
    },
    {
      title: t('complianceKb.colSource'), dataIndex: 'source', key: 'source', width: 90,
      render: (v: string) => {
        const colors: Record<string, string> = { online: 'blue', offline: 'cyan', rollback: 'purple', builtin: 'default' };
        return <Tag color={colors[v] || 'default'}>{v || '-'}</Tag>;
      },
    },
    { title: t('complianceKb.colOperator'), dataIndex: 'operator', key: 'operator', width: 100 },
    {
      title: t('complianceKb.colCreatedAt'), dataIndex: 'createdAt', key: 'createdAt', width: 170,
      render: (v: any) => formatDateTime(v),
    },
    {
      title: t('complianceKb.colActions'), key: 'actions', width: 80,
      render: (_: any, row: ComplianceKbVersion) => (
        row.status === 'active' ? <span style={{ color: '#bbb' }}>-</span> : (
          <Popconfirm title={t('complianceKb.rollbackConfirmTitle')} description={t('complianceKb.rollbackConfirmContent')} onConfirm={() => handleRollback(row.versionNo)}>
            <Button type="link" danger size="small">{t('complianceKb.rollback')}</Button>
          </Popconfirm>
        )
      ),
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {/* 授权提示（仅在拿到后端授权信息且未授权时展示） */}
      {!canUpdate && !usingBuiltin && (
        <Alert type="warning" showIcon message={t('complianceKb.unauthorizedHint')} />
      )}
      {canUpdate && !onlineConfigured && (
        <Alert type="info" showIcon message={t('complianceKb.onlineNotConfigured')} />
      )}

      {/* Zone 1: 词库概览 KPI */}
      <Row gutter={16}>
        <Col span={6}><Card size="small"><Statistic title={t('complianceKb.kpiCategories')} value={content?.categoryTotal ?? '-'} suffix={t('complianceKb.unitCategories')} /></Card></Col>
        <Col span={6}><Card size="small"><Statistic title={t('complianceKb.kpiKeywords')} value={content?.keywordTotal ?? '-'} /></Card></Col>
        <Col span={6}><Card size="small"><Statistic title={t('complianceKb.kpiVersion')} value={activeVersion?.versionNo ?? (usingBuiltin ? t('complianceKb.statusBuiltin') : '-')} /></Card></Col>
        <Col span={6}><Card size="small"><Statistic title={t('complianceKb.kpiGroups')} value={groupedData.length} suffix="/ 6" /></Card></Col>
      </Row>

      {/* Zone 2: 词库内容浏览 */}
      <Card
        title={t('complianceKb.sectionContent')}
        loading={contentLoading}
        extra={
          <Input
            prefix={<SearchOutlined />}
            placeholder={t('complianceKb.searchPlaceholder')}
            allowClear
            style={{ width: 240 }}
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
          />
        }
      >
        {/* 默认收起：不传 defaultActiveKey */}
        <Collapse>
          {collapsePanels}
        </Collapse>
      </Card>

      {/* Zone 3: 版本管理 */}
      <Card
        title={t('complianceKb.sectionManage')}
        extra={(
          <Space>
            <Upload
              accept=".wnt"
              showUploadList={false}
              disabled={!canUpdate}
              beforeUpload={(file: any) => {
                Modal.confirm({
                  title: t('complianceKb.importBundleConfirmTitle'),
                  content: t('complianceKb.importBundleConfirmContent'),
                  onOk: () => handleImport(file),
                });
                return false;
              }}
            >
              <Button disabled={!canUpdate}>{t('complianceKb.offlineImport')}</Button>
            </Upload>
            <Button type="primary" disabled={!canUpdate || !onlineConfigured} onClick={handleOnlineUpdate}>
              {t('complianceKb.onlineUpdate')}
            </Button>
            <Button onClick={() => navigate('/kb-online-setting')}>
              {t('complianceKb.onlineSetting')}
            </Button>
          </Space>
        )}
      >
        {/* 当前生效版本 */}
        {activeVersion && (
          <Descriptions bordered size="small" column={3} style={{ marginBottom: 16 }}>
            <Descriptions.Item label={t('complianceKb.colVersion')}>{activeVersion.versionNo}</Descriptions.Item>
            <Descriptions.Item label={t('complianceKb.colSource')}><Tag>{activeVersion.source}</Tag></Descriptions.Item>
            <Descriptions.Item label={t('complianceKb.colCreatedAt')}>{formatDateTime(activeVersion.createdAt)}</Descriptions.Item>
            <Descriptions.Item label={t('complianceKb.colCategories')}>{activeVersion.categoryCount}</Descriptions.Item>
            <Descriptions.Item label={t('complianceKb.colKeywords')}>{activeVersion.keywordCount}</Descriptions.Item>
            <Descriptions.Item label={t('complianceKb.gatewaySyncLabel')}>
              {activeVersion.gatewaySynced ? (
                <Tag color="green">{t('complianceKb.gatewaySynced')}</Tag>
              ) : (
                <Space>
                  <Tag color="orange">{t('complianceKb.gatewaySyncPending')}</Tag>
                  <Button type="link" size="small" onClick={handleSyncGateway}>{t('complianceKb.gatewayRetry')}</Button>
                </Space>
              )}
            </Descriptions.Item>
            <Descriptions.Item label={t('complianceKb.colHash')} span={3}>
              <Typography.Text code copyable>{activeVersion.kbHash || '-'}</Typography.Text>
            </Descriptions.Item>
          </Descriptions>
        )}
        {/* 历史版本表 */}
        <Table
          rowKey={(r: ComplianceKbVersion) => String(r.id ?? r.versionNo)}
          size="small"
          loading={versionsLoading}
          columns={versionColumns}
          dataSource={versions || []}
          pagination={{ pageSize: 10, hideOnSinglePage: true }}
          locale={{ emptyText: t('complianceKb.noVersions') }}
        />
      </Card>
    </Space>
  );
};

export default ComplianceKbPage;
