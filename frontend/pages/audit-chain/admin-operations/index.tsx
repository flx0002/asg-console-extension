/* eslint-disable max-len, no-nested-ternary */
import React, { useCallback, useEffect, useState } from 'react';
import {
  Button, Card, DatePicker, Drawer, Input, Space, Table, Tag, message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import moment from 'moment';
import { getAdminOperations, exportAdminOperations } from '@/services';
import {
  describeAdminAction, describeAdminResult, renderAdminOperationDetail,
} from '@/components/audit-event-format';

const { RangePicker } = DatePicker;

interface AdminOperation {
  id: number;
  time: string;
  timestampMs: number;
  sessionId: string;
  userId: string;
  action: string;
  clientIp: string;
  httpStatus: string;
  source: string;
  rawJson: string;
}

const RAW_STYLE: React.CSSProperties = {
  color: '#bfbfbf', fontFamily: 'monospace', fontSize: 11, marginTop: 2,
};
const SUB_STYLE: React.CSSProperties = { color: '#86909c', fontSize: 12 };

const AdminOperationAuditPage: React.FC = () => {
  const { t } = useTranslation();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [total, setTotal] = useState(0);
  const [rows, setRows] = useState<AdminOperation[]>([]);
  const [loading, setLoading] = useState(false);
  const [action, setAction] = useState('');
  const [keyword, setKeyword] = useState('');
  const [range, setRange] = useState<[number, number] | null>(null);
  // Filters are committed on Search/Export; typing must not fire a request per keystroke.
  const [applied, setApplied] = useState<{
    action: string; keyword: string; range: [number, number] | null;
  }>({ action: '', keyword: '', range: null });
  const [detail, setDetail] = useState<AdminOperation | null>(null);

  const query = useCallback(() => {
    setLoading(true);
    getAdminOperations({
      page,
      pageSize,
      startMs: applied.range ? applied.range[0] : undefined,
      endMs: applied.range ? applied.range[1] : undefined,
      action: applied.action,
      keyword: applied.keyword,
    })
      .then((res: any) => {
        const data = res?.data ?? res ?? {};
        setRows(Array.isArray(data.list) ? data.list : []);
        setTotal(Number(data.total || 0));
      })
      .catch(() => message.error(t('adminOperationAudit.loadFailed')))
      .finally(() => setLoading(false));
  }, [page, pageSize, applied, t]);

  useEffect(() => {
    query();
  }, [query]);

  const doExport = () => {
    exportAdminOperations({
      startMs: applied.range ? applied.range[0] : undefined,
      endMs: applied.range ? applied.range[1] : undefined,
      action: applied.action,
      keyword: applied.keyword,
    })
      .then(async (blob: any) => {
        const text = await blob.text();
        const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = 'admin-operations.json';
        link.click();
        URL.revokeObjectURL(url);
        // The export endpoint caps the page at 10000 rows but returns the real total.
        let total = 0;
        let exported = 0;
        try {
          const payload = JSON.parse(text);
          total = Number(payload.total) || 0;
          exported = Array.isArray(payload.list) ? payload.list.length : 0;
        } catch (e) {
          // A changed payload shape must not block the download itself, but leave a trace.
          console.warn('[admin-audit] export payload not parseable, skipping truncation hint', e);
        }
        if (total > exported) {
          message.warning(t('adminOperationAudit.exportTruncated', { total, exported }));
        } else {
          message.success(t('adminOperationAudit.exportDone'));
        }
        // The export request itself is audited; the async sink needs a moment to flush.
        window.setTimeout(query, 1500);
      })
      .catch(() => message.error(t('adminOperationAudit.exportFailed')));
  };

  const columns: ColumnsType<AdminOperation> = [
    {
      title: t('adminOperationAudit.time'),
      dataIndex: 'time',
      width: 170,
      render: (_: string, row) => moment(Number(row.timestampMs)).format('YYYY-MM-DD HH:mm:ss'),
    },
    {
      title: t('adminOperationAudit.action'),
      dataIndex: 'action',
      render: (value: string) => {
        const desc = describeAdminAction(value);
        return (
          <div>
            {desc.matched ? (
              <div>
                <span style={SUB_STYLE}>{desc.menu} › {desc.page}</span>
                <div><b>{desc.verb}「{desc.item}」</b></div>
              </div>
            ) : (
              <div>
                <b>{t('adminOperationAudit.actionFallback')}</b>
                <div style={SUB_STYLE}>{t('adminOperationAudit.actionFallbackHint')}</div>
              </div>
            )}
            <div style={RAW_STYLE}>{value}</div>
          </div>
        );
      },
    },
    {
      title: t('adminOperationAudit.operator'),
      dataIndex: 'userId',
      width: 110,
      render: (value: string) => value || '-',
    },
    {
      title: t('adminOperationAudit.clientIp'),
      dataIndex: 'clientIp',
      width: 140,
      render: (value: string) => value || '-',
    },
    {
      title: t('adminOperationAudit.status'),
      dataIndex: 'httpStatus',
      width: 160,
      render: (_: string, row) => {
        const res = describeAdminResult(row.httpStatus);
        return (
          <div>
            <Tag color={res.ok ? 'green' : 'red'}>{res.label}</Tag>
            <div style={SUB_STYLE}>
              {res.noted ? res.reason : `${res.code}${res.reason ? ` · ${res.reason}` : ''}`}
            </div>
          </div>
        );
      },
    },
    {
      title: t('adminOperationAudit.detail'),
      width: 80,
      render: (_, row) => (
        <Button type="link" size="small" onClick={() => setDetail(row)}>
          {t('adminOperationAudit.viewDetail')}
        </Button>
      ),
    },
  ];

  return (
    <Card title={t('adminOperationAudit.title')}>
      <Space wrap style={{ marginBottom: 16 }}>
        <RangePicker
          showTime
          onChange={(values) => {
            setRange(values && values[0] && values[1]
              ? [values[0].valueOf(), values[1].valueOf()]
              : null);
          }}
        />
        <Input
          allowClear
          style={{ width: 260 }}
          placeholder={t('adminOperationAudit.actionPlaceholder')}
          value={action}
          onChange={(e) => setAction(e.target.value)}
        />
        <Input
          allowClear
          style={{ width: 220 }}
          placeholder={t('adminOperationAudit.keywordPlaceholder')}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
        />
        <Button type="primary" onClick={() => { setApplied({ action, keyword, range }); setPage(1); }}>
          {t('adminOperationAudit.search')}
        </Button>
        <Button icon={<ReloadOutlined />} onClick={query}>
          {t('adminOperationAudit.refresh')}
        </Button>
        <Button icon={<DownloadOutlined />} onClick={doExport}>
          {t('adminOperationAudit.export')}
        </Button>
      </Space>
      <Table
        rowKey="id"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={{
          current: page,
          pageSize,
          total,
          showSizeChanger: true,
          onChange: (nextPage, nextSize) => { setPage(nextPage); setPageSize(nextSize); },
        }}
      />
      <Drawer
        title={t('adminOperationAudit.detail')}
        width={760}
        open={!!detail}
        onClose={() => setDetail(null)}
      >
        {detail ? renderAdminOperationDetail(detail) : null}
      </Drawer>
    </Card>
  );
};

export default AdminOperationAuditPage;
