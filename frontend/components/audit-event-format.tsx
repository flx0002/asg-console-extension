/**
 * 审计日志 / 安全事件展示层的唯一入口。
 *
 * 后端 AuditChainServiceImpl 已在输出层归一出 disposition / blocked / timestamp_ms /
 * display_time；这里只做展示与历史数据兜底，避免多页各自挑字符串导致语义再次分叉。
 */
import React from 'react';
import { Descriptions, Tag } from 'antd';

export type Disposition = 'blocked' | 'alerted' | 'masked' | 'passed';

const LOCAL_TIME_RE = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/;

/** 时间唯一入口：排序/筛选/activeToday 用数值 tsMs，展示用 time。 */
export const formatEventTime = (r: any): { tsMs: number; time: string } => {
  const n = Number(r?.timestamp_ms);
  let tsMs = Number.isFinite(n) && n > 0 ? n : 0;
  if (!tsMs) {
    // 兼容历史数据：UTC ISO 串、带偏移 ISO 串、本地时间串混存。
    const raw = r?.display_time ?? r?.timestamp ?? r?.time ?? r?.lastActiveTime;
    if (typeof raw === 'string' && raw && raw !== '-') {
      if (LOCAL_TIME_RE.test(raw)) {
        // 后端已按 Asia/Shanghai 格式化；直接展示，补 +08:00 求绝对时间，
        // 避免浏览器按自身时区二次解析导致日期/时区再次漂移。
        const parsed = new Date(`${raw.replace(' ', 'T')}+08:00`).getTime();
        return { tsMs: Number.isFinite(parsed) && parsed > 0 ? parsed : 0, time: raw };
      }
      const parsed = new Date(raw).getTime();
      tsMs = Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
    }
  }
  return {
    tsMs,
    time: tsMs
      ? new Date(tsMs).toLocaleString('zh-CN', { hour12: false, timeZone: 'Asia/Shanghai' })
      : '-',
  };
};

/** 处置归一：后端已给 disposition 时直接采用；旧数据按同一套优先级兜底。 */
export const normalizeDisposition = (r: any): Disposition => {
  const d = String(r?.disposition ?? '').toLowerCase();
  if (d === 'blocked' || d === 'alerted' || d === 'masked' || d === 'passed') {
    return d;
  }
  if (r?.blocked === true || r?.blocked === 'true') return 'blocked';
  const types: string[] = Array.isArray(r?.record_types) ? r.record_types.map(String) : [];
  if (types.includes('blocked')) return 'blocked';
  const status = String(r?.status ?? '').toLowerCase();
  const action = String(r?.action ?? '').toLowerCase();
  if (['blocked', 'block'].includes(status) || ['blocked', 'block'].includes(action)) return 'blocked';
  if (status === 'masked') return 'masked';
  if (['alerted', 'monitored', 'detected'].includes(status)
    || ['alert', 'alerted', 'enhance'].includes(action)
    || types.includes('security_event')) return 'alerted';
  return 'passed';
};

const DISPOSITION_TEXT: Record<Disposition, string> = {
  blocked: '拦截',
  alerted: '仅告警 / 放行',
  masked: '脱敏',
  passed: '放行',
};

const DISPOSITION_COLOR: Record<Disposition, string> = {
  blocked: 'red',
  alerted: 'orange',
  masked: 'orange',
  passed: 'green',
};

const LONG_TEXT: React.CSSProperties = {
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
  fontFamily: 'monospace',
  fontSize: 12,
};

/**
 * 详情面板：补齐「报了提示注入却看不到输入和判断原因」。
 * event = 事件级对象（hit_content / reason / rule_name ...）；
 * record = 记录级对象（request_body_content / events ...）。
 */
export const renderEventDetail = (record: any, event?: any): React.ReactNode => {
  const e = event || (Array.isArray(record?.events) ? record.events[0] : undefined) || {};
  const d = normalizeDisposition(record);
  const items: Array<[string, React.ReactNode]> = [
    ['命中内容（输入原文）', e.hit_content || record?.request_body_content || '-'],
    ['判断原因', e.reason || '-'],
    ['命中规则', e.rule_name || e.rule || e.rule_id || '-'],
    ['规则组', e.rule_group || '-'],
    ['命中方式', e.match_method || '-'],
    ['命中片段', e.matched_text || '-'],
    ['检测方向', e.direction || '-'],
    ['检测模式', e.detect_mode || '-'],
    ['处置结果', <Tag color={DISPOSITION_COLOR[d]}>{DISPOSITION_TEXT[d]}</Tag>],
    ['事件描述', e.detail || '-'],
    ['记录级请求正文', record?.request_body_content || '-'],
  ];
  return (
    <div>
      <Descriptions bordered size="small" column={1} style={{ maxWidth: 720 }}>
        {items.map(([label, value]) => (
          <Descriptions.Item key={label} label={label}>
            {typeof value === 'string' && value.length > 40
              ? <span style={LONG_TEXT}>{value}</span>
              : <span>{value || '-'}</span>}
          </Descriptions.Item>
        ))}
      </Descriptions>
      <pre style={{ margin: '12px 0 0', padding: 12, background: '#fafafa', fontSize: 12, maxHeight: 300, overflow: 'auto' }}>
        {JSON.stringify({ event: e, record }, null, 2)}
      </pre>
    </div>
  );
};
