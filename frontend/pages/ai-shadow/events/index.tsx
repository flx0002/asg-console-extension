/* eslint-disable max-len */
import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert, Button, Card, DatePicker, Input, Segmented, Space, Table, Tag, message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ReloadOutlined } from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import moment from 'moment';
import { getAiShadowDetectEvents } from '@/services';
import type { AiShadowDetectEvent } from '@/interfaces/ai-shadow';
import { consumerOfReason, eventReasonLabel, formatEventTime, renderEventDetail } from '@/components/ai-shadow-events';

const REFRESH_INTERVAL = 30000;
const DEFAULT_PAGE_SIZE = 20;
const { RangePicker } = DatePicker;

type FixedRangeKey = 'today' | 'week' | 'month';
type RangeKey = FixedRangeKey | 'custom';
// 'all' 不能省：默认只看「已拦截」会让人以为没被拦的都没发生。
type StatusKey = 'all' | 'blocked' | 'monitored';

const RANGE_DAYS: Record<FixedRangeKey, number> = { today: 1, week: 7, month: 30 };
const MAX_CUSTOM_DAYS = 31;

// 与另外两个 tab 完全同一套窗口算法：起点对齐浏览器本地零点。
const rangeWindow = (key: FixedRangeKey): { startTime: number; endTime: number } => {
  const endTime = Date.now();
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  start.setDate(start.getDate() - (RANGE_DAYS[key] - 1));
  return { startTime: start.getTime(), endTime };
};

const startOfDay = (ms: number): number => new Date(ms).setHours(0, 0, 0, 0);
const endOfDay = (ms: number): number => new Date(ms).setHours(23, 59, 59, 999);

interface EventsViewProps {
  // 由「路由与消费者管控」的汇总卡片跳转进来时预置的筛选。
  // 只是快捷方式：本页不依赖任何点击就能直接打开和浏览。
  focusStatus?: 'blocked' | 'monitored';
  // 跳转方当时的取数窗口：跟着切到同一个区间，否则「点 11 条」进来只看到今天的 3 条。
  focusWindow?: { startTime: number; endTime: number };
  focusSeq?: number;
}

const AiShadowEventsPage: React.FC<EventsViewProps> = ({ focusStatus, focusWindow, focusSeq = 0 }) => {
  const { t } = useTranslation();
  const [range, setRange] = useState<RangeKey>('today');
  const [customRange, setCustomRange] = useState<[number, number] | null>(null);
  const [status, setStatus] = useState<StatusKey>('all');
  // domainInput 是输入框里的草稿，domain 是已提交的查询条件：
  // 边打边查会让每敲一个字母都打一次库，且列表在输入过程中乱跳。
  const [domainInput, setDomainInput] = useState('');
  const [domain, setDomain] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE);

  useEffect(() => {
    if (!focusSeq) return;
    setStatus(focusStatus ?? 'all');
    if (focusWindow) {
      setRange('custom');
      setCustomRange([focusWindow.startTime, focusWindow.endTime]);
    }
    setPage(0);
  }, [focusSeq, focusStatus, focusWindow]);

  const currentWindow = useCallback((): { startTime: number; endTime: number } => {
    if (range !== 'custom') {
      return rangeWindow(range);
    }
    if (customRange) {
      return { startTime: startOfDay(customRange[0]), endTime: endOfDay(customRange[1]) };
    }
    // 刚切到自定义还没选日期：先按近 7 天取数，避免整页空白
    return rangeWindow('week');
  }, [range, customRange]);

  const windowKey = range === 'custom'
    ? `custom:${customRange?.[0] ?? 0}:${customRange?.[1] ?? 0}`
    : range;

  const rangeSummary = `${moment(currentWindow().startTime).format('YYYY-MM-DD HH:mm')} ~ ${moment(currentWindow().endTime).format('YYYY-MM-DD HH:mm')}`;

  // 必须拿到 error：请求失败时 antd 只会显示空表，与「这段时间真的没有事件」
  // 长得一模一样，会直接误导判断。
  const { data, loading, error, refresh } = useRequest(
    () => {
      const { startTime, endTime } = currentWindow();
      return getAiShadowDetectEvents({
        surface: 'route',
        startTime,
        endTime,
        page,
        size,
        ...(status === 'all' ? {} : { status }),
        ...(domain ? { domain } : {}),
      });
    },
    {
      refreshDeps: [windowKey, status, domain, page, size],
      pollingInterval: REFRESH_INTERVAL,
      pollingWhenHidden: false,
      onError: () => {},
    },
  );

  const columns: ColumnsType<AiShadowDetectEvent> = [
    {
      title: t('aiShadow.eventColTime'),
      dataIndex: 'eventTime',
      key: 'eventTime',
      width: 170,
      render: (value: AiShadowDetectEvent['eventTime']) => formatEventTime(value),
    },
    {
      title: t('aiShadow.eventColStatus'),
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (value?: string) => (value === 'blocked'
        ? <Tag color="red">{t('aiShadow.eventStatusBlocked')}</Tag>
        : <Tag color="blue">{t('aiShadow.eventStatusMonitored')}</Tag>),
    },
    {
      title: t('aiShadow.eventColReason'),
      dataIndex: 'reason',
      key: 'reason',
      width: 190,
      render: (value?: string) => <span style={{ fontFamily: 'monospace' }}>{eventReasonLabel(t, value)}</span>,
    },
    {
      title: t('aiShadow.eventsColConsumer'),
      dataIndex: 'reason',
      key: 'consumer',
      width: 140,
      render: (value?: string) => {
        const consumer = consumerOfReason(value);
        return consumer
          ? <Tag>{consumer}</Tag>
          : <span style={{ color: '#999' }}>{t('aiShadow.eventsNoConsumer')}</span>;
      },
    },
    {
      title: t('aiShadow.eventColSrcIp'),
      dataIndex: 'srcIp',
      key: 'srcIp',
      width: 140,
      render: (value?: string) => <span style={{ fontFamily: 'monospace' }}>{value || '-'}</span>,
    },
    {
      title: t('aiShadow.eventColDomain'),
      dataIndex: 'domain',
      key: 'domain',
      render: (value?: string) => <span style={{ fontFamily: 'monospace' }}>{value || '-'}</span>,
    },
  ];

  const items = data?.items || [];
  const total = data?.total ?? 0;

  return (
    <Card
      title={t('aiShadow.eventsTitle')}
      extra={(
        <Button icon={<ReloadOutlined />} onClick={refresh} loading={loading}>
          {t('aiShadow.refresh')}
        </Button>
      )}
    >
      {/* 时间范围与本页其余筛选同属一块，作用于下方全部行 */}
      <div style={{
        display: 'flex',
        alignItems: 'center',
        flexWrap: 'wrap',
        gap: 12,
        marginBottom: 12,
      }}
      >
        <span style={{ fontSize: 13, color: '#595959' }}>{t('aiShadow.timeRangeLabel')}</span>
        <Segmented
          value={range}
          onChange={(value) => {
            setRange(value as RangeKey);
            setPage(0);
          }}
          options={[
            { value: 'today', label: t('aiShadow.trendRangeToday') },
            { value: 'week', label: t('aiShadow.trendRangeWeek') },
            { value: 'month', label: t('aiShadow.trendRangeMonth') },
            { value: 'custom', label: t('aiShadow.trendRangeCustom') },
          ]}
        />
        {range === 'custom' ? (
          <RangePicker
            allowClear={false}
            style={{ width: 260 }}
            value={customRange ? ([moment(customRange[0]), moment(customRange[1])] as any) : null}
            disabledDate={(d: any) => !!d && startOfDay(d.valueOf()) > startOfDay(Date.now())}
            onChange={(v: any) => {
              if (!v || !v[0] || !v[1]) {
                return;
              }
              const start = startOfDay(v[0].valueOf());
              const end = endOfDay(v[1].valueOf());
              // 超过后端窗口上限时后端会静默截断起点，界面与实际口径就对不上了。
              if (end - start > MAX_CUSTOM_DAYS * 86400000) {
                message.warning(t('aiShadow.timeRangeTooLong', { days: MAX_CUSTOM_DAYS }));
                return;
              }
              setCustomRange([start, end]);
              setPage(0);
            }}
          />
        ) : null}
        <span style={{ marginLeft: 'auto', fontSize: 12, color: '#8c8c8c' }}>{rangeSummary}</span>
      </div>

      <Space style={{ marginBottom: 12 }} wrap>
        <Segmented
          value={status}
          onChange={(value) => {
            setStatus(value as StatusKey);
            setPage(0);
          }}
          options={[
            { value: 'all', label: t('aiShadow.eventsStatusAll') },
            { value: 'blocked', label: t('aiShadow.eventStatusBlocked') },
            { value: 'monitored', label: t('aiShadow.eventStatusMonitored') },
          ]}
        />
        <Input.Search
          allowClear
          style={{ width: 260 }}
          placeholder={t('aiShadow.eventsDomainPlaceholder')}
          value={domainInput}
          onChange={(e) => setDomainInput(e.target.value)}
          onSearch={(value) => {
            setDomain(value.trim());
            setPage(0);
          }}
        />
      </Space>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message={t('aiShadow.eventsHint')}
      />

      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 12 }}
          message={t('aiShadow.eventsFailed')}
        />
      )}

      <Table<AiShadowDetectEvent>
        dataSource={items}
        columns={columns}
        rowKey={(record) => String(record.id)}
        size="small"
        loading={loading}
        expandable={{
          expandedRowRender: (record) => renderEventDetail(t, record.detail),
          rowExpandable: (record) => !!record.detail,
        }}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          showSizeChanger: true,
          pageSizeOptions: ['20', '50', '100'],
          onChange: (nextPage: number, nextSize: number) => {
            setPage(nextPage - 1);
            setSize(nextSize);
          },
          showTotal: (value: number) => t('aiShadow.eventTotalCount', { total: value }),
        }}
        locale={{ emptyText: t('aiShadow.eventsEmpty') }}
      />
    </Card>
  );
};

export default AiShadowEventsPage;
