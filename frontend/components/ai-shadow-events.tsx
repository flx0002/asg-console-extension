/* eslint-disable max-len */
import React from 'react';
import { Descriptions } from 'antd';

// 影子 AI 几个 tab 共用同一张事件表的不同面（surface=shadow / route），
// 时间、原因、详情的呈现必须一字不差，否则同一条事件在两页上会像两条。
type Translator = (key: string, options?: Record<string, unknown>) => string;

export const pad2 = (n: number): string => (n < 10 ? `0${n}` : String(n));

// 后端 LocalDateTime 会序列化成 [y,m,d,h,mi,s,ns] 数组，也可能是字符串。
export const formatEventTime = (value: string | number[] | undefined): string => {
  if (!value) return '-';
  if (Array.isArray(value) && value.length >= 6) {
    const [y, m, d, h = 0, mi = 0, s = 0] = value;
    return `${y}-${pad2(m)}-${pad2(d)} ${pad2(h)}:${pad2(mi)}:${pad2(s)}`;
  }
  return String(value);
};

// detail 列里既有 JSON（key-auth 上报），也有网关的纯文本（feature=... matched=...），
// 所以解析失败必须返回 null 而不是抛错。
export const parseDetail = (detail?: string): Record<string, unknown> | null => {
  if (!detail) return null;
  try {
    const parsed = JSON.parse(detail);
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch {
    return null;
  }
};

const detailLabelMap = (t: Translator): Record<string, string> => ({
  protocol: t('aiShadow.eventProtocol'),
  method: t('aiShadow.eventMethod'),
  uri: t('aiShadow.eventUri'),
  srcPort: t('aiShadow.eventSrcPort'),
  dstPort: t('aiShadow.eventDstPort'),
  ja3: 'JA3',
  ja4: 'JA4',
});

export const renderEventDetail = (t: Translator, detail?: string): React.ReactNode => {
  const parsed = parseDetail(detail);
  if (!parsed) return <span style={{ color: '#999' }}>-</span>;
  const entries = Object.entries(parsed).filter(([, v]) => v !== '' && v !== null && v !== undefined);
  if (!entries.length) return <span style={{ color: '#999' }}>-</span>;
  const labels = detailLabelMap(t);
  return (
    <Descriptions size="small" column={1} bordered style={{ maxWidth: 640 }}>
      {entries.map(([k, v]) => (
        <Descriptions.Item key={k} label={labels[k] || k}>
          <span style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{String(v)}</span>
        </Descriptions.Item>
      ))}
    </Descriptions>
  );
};

// 判定原因：reason 是后端枚举（准入类 no_key / unknown_key / not_in_allow[:consumer] / off_channel，
// 旁路类 ai_domain_match / policy_violation / policy_blocked），未知值原样返回，避免新原因变空白。
export const eventReasonLabel = (t: Translator, reason?: string): string => {
  if (!reason) return '-';
  if (reason === 'no_key') return t('aiShadow.reasonNoKey');
  if (reason === 'unknown_key') return t('aiShadow.reasonUnknownKey');
  if (reason === 'off_channel') return t('aiShadow.reasonOffChannel');
  if (reason === 'ai_domain_match') return t('aiShadow.reasonAiDomainMatch');
  if (reason === 'policy_violation') return t('aiShadow.reasonPolicyViolation');
  if (reason === 'policy_blocked') return t('aiShadow.reasonPolicyBlocked');
  if (reason.startsWith('not_in_allow')) {
    const consumer = reason.split(':')[1];
    return consumer
      ? t('aiShadow.reasonNotInAllowNamed', { consumer })
      : t('aiShadow.reasonNotInAllow');
  }
  return reason;
};

// not_in_allow:<consumer> 里带着调用方名字——它是「谁」的唯一线索，
// 单列出来比埋在原因字符串里可读。
export const consumerOfReason = (reason?: string): string => {
  const value = reason || '';
  return value.startsWith('not_in_allow:') ? value.slice('not_in_allow:'.length) : '';
};
