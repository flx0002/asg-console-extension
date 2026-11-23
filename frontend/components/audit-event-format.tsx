/**
 * 审计日志 / 安全事件展示层的唯一入口。
 *
 * 后端 AuditChainServiceImpl 已在输出层归一出 disposition / blocked / timestamp_ms /
 * display_time；这里只做展示与历史数据兜底，避免多页各自挑字符串导致语义再次分叉。
 */
import React from 'react';
import { Descriptions, Tag } from 'antd';
import moment from 'moment';

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
 * 展示层脱敏：命中内容可能夹带未脱敏的手机号 / 邮箱 / 身份证 / API Key。
 * PII 类事件在入库前已由插件脱敏；这里兜底其他类型（如提示注入）原文里夹带的敏感串。
 */
export const redactContent = (value: any): string => {
  const s = value == null ? '' : String(value);
  if (!s) return '';
  return s
    .replace(/\b\d{17}[\dXx]\b/g, (m) => `${m.slice(0, 6)}********${m.slice(-4)}`)
    .replace(/\b1[3-9]\d{9}\b/g, (m) => `${m.slice(0, 3)}****${m.slice(-4)}`)
    .replace(/\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b/g, (m) => {
      const at = m.indexOf('@');
      return `${m.slice(0, 1)}***${m.slice(at)}`;
    })
    .replace(/\bsk-[A-Za-z0-9_-]{16,}\b/g, 'sk-***');
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
    ['命中内容（输入原文）', redactContent(e.hit_content || record?.request_body_content) || '-'],
    ['判断原因', e.reason || '-'],
    ['命中规则', e.rule_name || e.rule || e.rule_id || '-'],
    ['规则组', e.rule_group || '-'],
    ['命中方式', e.match_method || '-'],
    ['命中片段', e.matched_text || '-'],
    ['检测方向', e.direction || '-'],
    ['检测模式', e.detect_mode || '-'],
    ['严重度', e.severity
      ? <Tag color={String(e.severity).toLowerCase() === 'high' ? 'red' : String(e.severity).toLowerCase() === 'low' ? 'blue' : 'default'}>{e.severity}</Tag>
      : '-'],
    ['置信度', e.score != null ? String(e.score) : '-'],
    ['处置结果', <Tag color={DISPOSITION_COLOR[d]}>{DISPOSITION_TEXT[d]}</Tag>],
    ['事件描述', e.detail || '-'],
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
    </div>
  );
};

/* ===================== 系统操作审计（原「管理操作审计」）展示 ===================== */
/*
 * 安全收敛：详情只展示业务/执行信息，技术字段最小化。
 * 不展示 raw_json、query_string、error（异常类名）、session_id、record_types 等——
 * 它们对使用者无意义，却是主要泄露面（query 可能含 token/关键字，异常类名暴露技术栈）。
 * 完整记录仍在数据库供追责，需要时走后端。
 */

export interface AdminActionDesc {
  menu: string;
  page: string;
  verb: string;
  item: string;
  raw: string;
  matched: boolean;
}

type AdminActionRule = {
  re: RegExp;
  menu: string;
  page: string;
  verb: string;
  item: string;
};

// 越具体越靠前；带路径参数的用正则吞掉 {name}/{id}/{sessionId}/{no}
const ADMIN_ACTION_RULES: AdminActionRule[] = [
  // —— 影子AI管理 ——
  { re: /^PUT \/v1\/ai-shadow\/action\/batch$/, menu: '影子AI管理', page: '路由与消费者管控', verb: '批量更新', item: '已授权消费者名单' },
  { re: /^PUT \/v1\/ai-shadow\/action$/, menu: '影子AI管理', page: '路由与消费者管控', verb: '调整', item: '消费者授权 / 阻断' },
  { re: /^(PUT|POST|DELETE) \/v1\/ai-shadow\/consumers.*$/, menu: '影子AI管理', page: '路由与消费者管控', verb: '调整', item: '消费者名单' },
  { re: /^PUT \/v1\/ai-shadow\/authorized-domains$/, menu: '影子AI管理', page: '授权域名管理', verb: '修改', item: '授权域名名单' },
  { re: /^PUT \/v1\/ai-shadow\/dns-policy$/, menu: '影子AI管理', page: '影子AI配置', verb: '修改', item: 'DNS 检测策略' },
  { re: /^PUT \/v1\/ai-shadow\/detect-mode$/, menu: '影子AI管理', page: '影子AI配置', verb: '修改', item: '检测模式' },
  { re: /^PUT \/v1\/ai-shadow\/mode$/, menu: '影子AI管理', page: '影子AI配置', verb: '修改', item: '路由运行模式（监控 / 管控）' },
  { re: /^PUT \/v1\/ai-shadow\/enabled$/, menu: '影子AI管理', page: '影子AI配置', verb: '切换', item: '影子AI 总开关' },
  { re: /^PUT \/v1\/ai-shadow\/capture\/config$/, menu: '影子AI管理', page: '旁路采集', verb: '修改', item: '采集 / 回注网口' },
  { re: /^POST \/v1\/ai-kb\/license\/import$/, menu: '影子AI管理', page: 'AI分类知识库', verb: '导入', item: '授权文件' },
  { re: /^POST \/v1\/ai-kb\/online-update$/, menu: '影子AI管理', page: 'AI分类知识库', verb: '在线更新', item: '分类知识库' },
  { re: /^POST \/v1\/ai-kb\/sync-gateway$/, menu: '影子AI管理', page: 'AI分类知识库', verb: '同步到网关', item: '分类知识库' },

  // —— AI内容安全 ——
  { re: /^POST \/v1\/compliance-kb\/import$/, menu: 'AI内容安全', page: '内容合规词库', verb: '离线导入', item: '合规词库' },
  { re: /^POST \/v1\/compliance-kb\/online-update$/, menu: 'AI内容安全', page: '内容合规词库', verb: '在线更新', item: '合规词库' },
  { re: /^POST \/v1\/compliance-kb\/rollback\/.+$/, menu: 'AI内容安全', page: '内容合规词库', verb: '回滚', item: '合规词库版本' },
  { re: /^POST \/v1\/compliance-kb\/sync-gateway$/, menu: 'AI内容安全', page: '内容合规词库', verb: '同步到网关', item: '合规词库' },
  { re: /^PUT \/v1\/wasm-plugins\/[^/]+\/route-scopes$/, menu: 'AI内容安全', page: '防护插件', verb: '保存', item: '作用路由' },
  { re: /^DELETE \/v1\/wasm-plugins\/[^/]+\/route-scopes$/, menu: 'AI内容安全', page: '防护插件', verb: '移除', item: '作用路由' },
  { re: /^(PUT|POST) \/v1\/global\/plugin-instances\/[^/]+$/, menu: 'AI内容安全', page: '防护插件', verb: '保存', item: '防护配置' },
  { re: /^DELETE \/v1\/global\/plugin-instances\/[^/]+$/, menu: 'AI内容安全', page: '防护插件', verb: '删除', item: '防护插件实例' },

  // —— AI智能体安全 / 行为分析 ——
  { re: /^POST \/v1\/ai-principals\/[^/]+\/actions$/, menu: 'AI智能体安全', page: '防护配置', verb: '处置', item: '智能体主体' },
  { re: /^PUT \/v1\/ai-principals\/[^/]+$/, menu: 'AI智能体安全', page: '防护配置', verb: '修改', item: '主体信息' },
  { re: /^POST \/v1\/ai-principals\/import$/, menu: '行为分析', page: '用户/智能体画像', verb: '导入', item: '主体清单' },
  { re: /^GET \/v1\/ai-principals\/export$/, menu: '行为分析', page: '用户/智能体画像', verb: '导出', item: '主体清单' },
  { re: /^POST \/v1\/ai-isolations$/, menu: 'AI智能体安全', page: '防护配置', verb: '新建', item: '隔离策略' },
  { re: /^DELETE \/v1\/ai-isolations\/.+$/, menu: 'AI智能体安全', page: '防护配置', verb: '解除', item: '隔离策略' },

  // —— 路由管理 ——
  { re: /^DELETE \/v1\/ai\/routes\/.+$/, menu: '路由管理', page: 'AI路由', verb: '删除', item: 'AI 路由' },
  { re: /^PUT \/v1\/ai\/routes$/, menu: '路由管理', page: 'AI路由', verb: '修改', item: 'AI 路由' },

  // —— 日志与审计 ——
  { re: /^GET \/v1\/audit-chain\/admin-operations\/export$/, menu: '日志与审计', page: '系统操作审计', verb: '导出', item: '系统操作审计记录' },
  { re: /^GET \/v1\/audit-chain\/export\/.+$/, menu: '日志与审计', page: '审计日志', verb: '导出', item: '会话审计日志' },
  { re: /^DELETE \/v1\/audit-chain\/logs\/session\/.+$/, menu: '日志与审计', page: '审计日志', verb: '清理', item: '会话审计日志' },
  { re: /^PUT \/v1\/audit-chain\/config$/, menu: '日志与审计', page: '审计日志', verb: '修改', item: '审计策略' },
  { re: /^POST \/v1\/audit-chain\/cleanup$/, menu: '日志与审计', page: '审计日志', verb: '清理', item: '过期审计记录' },

  // —— 系统配置（配置备份与恢复）——
  { re: /^(PUT|POST|DELETE) \/v1\/config-version\/.*$/, menu: '系统配置', page: '配置备份与恢复', verb: '执行', item: '配置备份 / 恢复' },

  // —— 细粒度业务动作（业务自报，无 HTTP 方法）——
  { re: /^set_mode$/, menu: '影子AI管理', page: '影子AI配置', verb: '修改', item: '路由运行模式（监控 / 管控）' },
  { re: /^set_detect_mode$/, menu: '影子AI管理', page: '影子AI配置', verb: '修改', item: '检测模式' },
  { re: /^enable_shadow_ai$/, menu: '影子AI管理', page: '影子AI配置', verb: '开启', item: '影子AI 总开关' },
  { re: /^disable_shadow_ai$/, menu: '影子AI管理', page: '影子AI配置', verb: '关闭', item: '影子AI 总开关' },
  { re: /^authorize$/, menu: '影子AI管理', page: '路由与消费者管控', verb: '授权', item: '消费者' },
  { re: /^block$/, menu: '影子AI管理', page: '路由与消费者管控', verb: '阻断', item: '消费者' },
  { re: /^auto_manage$/, menu: '影子AI管理', page: '路由与消费者管控', verb: '自动调整', item: '消费者管控' },
  { re: /^replace_consumers$/, menu: '影子AI管理', page: '路由与消费者管控', verb: '批量更新', item: '已授权消费者名单' },
  { re: /^authorize_domain$/, menu: '影子AI管理', page: '授权域名管理', verb: '授权', item: '域名' },
  { re: /^deauthorize_domain$/, menu: '影子AI管理', page: '授权域名管理', verb: '取消授权', item: '域名' },
  { re: /^set_capture_config$/, menu: '影子AI管理', page: '旁路采集', verb: '修改', item: '采集 / 回注网口' },
];

/** 把 action（兜底的 "METHOD /path"，或业务自报的细粒度动作名）翻译成业务可读描述。未匹配不猜、不硬翻。 */
export const describeAdminAction = (action?: string): AdminActionDesc => {
  const raw = String(action || '').trim();
  for (let i = 0; i < ADMIN_ACTION_RULES.length; i += 1) {
    const rule = ADMIN_ACTION_RULES[i];
    if (rule.re.test(raw)) {
      return { menu: rule.menu, page: rule.page, verb: rule.verb, item: rule.item, raw, matched: true };
    }
  }
  return { menu: '', page: '', verb: '', item: '', raw: raw || '-', matched: false };
};

export interface AdminResultDesc {
  ok: boolean;
  label: string;
  code: string;
  reason: string;
  noted: boolean;
}

/** 结果归一：成功 / 失败 + 结果码 + 原因，替代裸 200/403/500。 */
export const describeAdminResult = (httpStatus?: string | number): AdminResultDesc => {
  const raw = httpStatus === null || httpStatus === undefined ? '' : String(httpStatus).trim();
  if (!raw || raw === '-' || raw === '0') {
    // 业务自报记录没有 HTTP 结果码；业务代码在成功路径写审计，按「成功」显示并标注来源。
    return { ok: true, label: '成功', code: '—', reason: '业务上报，未记录结果码', noted: true };
  }
  const n = Number(raw);
  if (Number.isFinite(n)) {
    if (n >= 200 && n < 400) return { ok: true, label: '成功', code: raw, reason: '', noted: false };
    const reason = n === 401 ? '未认证'
      : n === 403 ? '无权限'
      : n === 404 ? '资源不存在'
      : n === 409 ? '资源冲突'
      : n === 429 ? '请求过于频繁'
      : n >= 500 ? '服务异常'
      : '请求失败';
    return { ok: false, label: '失败', code: raw, reason, noted: false };
  }
  return { ok: false, label: '失败', code: raw, reason: '状态未知', noted: false };
};

const MONO: React.CSSProperties = { fontFamily: 'monospace', wordBreak: 'break-all' };

/** 系统操作审计详情：对齐原型的「摘要条 + 操作信息 / 执行信息分组 + 折叠技术信息」。安全收敛：不外泄 raw_json / query / 异常类名 / session_id。 */
export const renderAdminOperationDetail = (r: any): React.ReactNode => {
  let raw: any = {};
  try {
    raw = r && r.rawJson ? JSON.parse(r.rawJson) : {};
  } catch (e) {
    raw = {};
  }
  const desc = describeAdminAction(r && r.action);
  const res = describeAdminResult(r && r.httpStatus);
  const actionStr = String((r && r.action) || '');
  const method = raw.http_method || (actionStr.indexOf(' ') > 0 ? actionStr.split(' ')[0] : '-');
  const path = raw.http_path || (actionStr.indexOf(' ') > 0 ? actionStr.slice(actionStr.indexOf(' ') + 1) : '-');
  const eventId = raw.event_id || '-';
  const operator = (r && r.userId) || '-';
  const clientIp = (r && r.clientIp) || '-';
  const sourceLabel = r && r.source === 'ai_shadow_console'
    ? '影子AI 配置（业务上报）'
    : (r && r.source === 'admin_http' ? '控制台请求' : ((r && r.source) || '-'));
  // 时间：与列表列同源（DTO 字段名是 timestampMs），按 Asia/Shanghai 展示，兼容历史字符串
  const ts = Number(r && r.timestampMs);
  const opTime = Number.isFinite(ts) && ts > 0
    ? moment(ts).format('YYYY-MM-DD HH:mm:ss')
    : (String((r && r.time) || '-').slice(0, 19) || '-');
  // 操作对象 = 业务自报的目标；变更内容 = 业务自报的说明（密钥类由后端只记「已修改」）
  const targetParts: string[] = [];
  if (raw.route_name) targetParts.push(`路由 ${raw.route_name}`);
  if (raw.consumer_name) targetParts.push(`消费者 ${raw.consumer_name}`);
  if (raw.mode) targetParts.push(`模式 ${raw.mode}`);
  // Fallback records carry no business target; recover it from the audited path.
  const pluginMatch = String(path || '').match(/\/v1\/(?:global\/)?plugin-instances\/([^/?]+)/);
  if (pluginMatch) {
    const pluginLabels: Record<string, string> = {
      'ai-prompt-guard': '提示注入防护',
      'ai-pii-guard': '敏感信息防护',
      'ai-waf': 'AI WAF 防护',
      'ai-security-guard': 'AI 安全防护',
      'ai-keyword-filter': '关键词过滤',
      'ai-agent-guard': '智能体防护',
    };
    const name = pluginMatch[1];
    targetParts.push(`插件 ${pluginLabels[name] || name}`);
  }
  const target = targetParts.join('；') || '-';
  const change = (raw.detail && typeof raw.detail === 'string' && raw.detail.length < 200)
    ? raw.detail : '-';

  const sectionTitle: React.CSSProperties = {
    fontSize: 13, fontWeight: 600, margin: '0 0 8px',
    display: 'flex', alignItems: 'center', gap: 8,
  };
  const bar: React.CSSProperties = {
    width: 3, height: 13, background: '#1677ff', borderRadius: 2, display: 'inline-block',
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 16, padding: '10px 12px', background: '#fafafa', borderRadius: 4 }}>
        <div>
          <div style={{ fontWeight: 600, fontSize: 14 }}>
            {desc.matched
              ? `${desc.menu} › ${desc.page} · ${desc.verb}「${desc.item}」`
              : <span style={MONO}>{desc.raw}</span>}
          </div>
          <div style={{ color: '#86909c', fontSize: 12, marginTop: 4 }}>
            {opTime} · 操作者 {operator} · 来源 IP {clientIp}
          </div>
        </div>
        <Tag color={res.ok ? 'green' : 'red'} style={{ marginTop: 2 }}>
          {res.label}{!res.noted ? ` · ${res.code}` : ''}
        </Tag>
      </div>

      <h4 style={sectionTitle}><span style={bar} />操作信息</h4>
      <Descriptions bordered size="small" column={1} style={{ maxWidth: 720, marginBottom: 16 }}>
        {desc.matched ? (
          <>
            <Descriptions.Item label="所属菜单">{desc.menu}</Descriptions.Item>
            <Descriptions.Item label="所在页面">{desc.page}</Descriptions.Item>
            <Descriptions.Item label="配置项"><b>{desc.item}</b></Descriptions.Item>
            <Descriptions.Item label="操作类型">{desc.verb}</Descriptions.Item>
          </>
        ) : (
          <Descriptions.Item label="原始操作"><span style={MONO}>{desc.raw}</span></Descriptions.Item>
        )}
        <Descriptions.Item label="操作对象"><span style={MONO}>{target}</span></Descriptions.Item>
        <Descriptions.Item label="变更内容"><span style={LONG_TEXT}>{change}</span></Descriptions.Item>
      </Descriptions>

      <h4 style={sectionTitle}><span style={bar} />执行信息</h4>
      <Descriptions bordered size="small" column={1} style={{ maxWidth: 720 }}>
        <Descriptions.Item label="操作者">{operator}</Descriptions.Item>
        <Descriptions.Item label="客户端 IP"><span style={MONO}>{clientIp}</span></Descriptions.Item>
        <Descriptions.Item label="操作时间"><span style={MONO}>{opTime}</span></Descriptions.Item>
        <Descriptions.Item label="结果"><Tag color={res.ok ? 'green' : 'red'}>{res.label}</Tag></Descriptions.Item>
        <Descriptions.Item label="结果码">
          {res.noted
            ? <span>{res.code}（{res.reason}）</span>
            : <span style={MONO}>{res.code}{res.reason ? ` · ${res.reason}` : ''}</span>}
        </Descriptions.Item>
        <Descriptions.Item label="记录来源">{sourceLabel}</Descriptions.Item>
      </Descriptions>

      <details style={{ marginTop: 12 }}>
        <summary style={{ cursor: 'pointer', color: '#1677ff', fontSize: 12 }}>
          技术信息（排障用）
        </summary>
        <Descriptions bordered size="small" column={1} style={{ maxWidth: 720, marginTop: 8 }}>
          <Descriptions.Item label="请求方法"><span style={MONO}>{method}</span></Descriptions.Item>
          <Descriptions.Item label="请求路径"><span style={MONO}>{path}</span></Descriptions.Item>
          <Descriptions.Item label="事件 ID"><span style={MONO}>{eventId}</span></Descriptions.Item>
          <Descriptions.Item label="内部记录标识"><span style={MONO}>{(r && r.source) || '-'}</span></Descriptions.Item>
        </Descriptions>
      </details>
    </div>
  );
};
