/**
 * 内容合规词库 - API 服务层
 * 独立端点前缀 /v1/compliance-kb/，不与 ai-kb（AI 分类知识库）混用
 *
 * 后端需实现（对应 BE-011）：
 *   GET  /v1/compliance-kb/content        → 当前生效词库完整内容（31类 + 关键词 + 正则）
 *   GET  /v1/compliance-kb/versions       → 版本历史列表
 *   GET  /v1/compliance-kb/active         → 当前生效版本元信息
 *   POST /v1/compliance-kb/import         → 离线导入词库包
 *   POST /v1/compliance-kb/online-update  → 在线拉取最新词库
 *   POST /v1/compliance-kb/rollback/:no   → 回滚到指定版本
 *   GET  /v1/compliance-kb/license        → 词库更新授权状态
 *   POST /v1/compliance-kb/sync-gateway   → 同步词库到网关 WasmPlugin CR
 */
import request from './request';
import axios from 'axios';
import {
  ComplianceKbContent, ComplianceKbVersion, ComplianceKbLicense,
} from '@/interfaces/compliance-kb';

function unwrap<T>(res: any): T {
  if (res && typeof res === 'object' && !Array.isArray(res) && 'success' in res && 'data' in res) {
    if (res.success === false) throw new Error(res.message || '操作失败');
    return res.data as T;
  }
  return res as T;
}

/**
 * 静默读取实例：与共享 request 同 baseURL/鉴权/时间戳防缓存，但响应错误拦截器
 * 不弹全局错误 Modal，直接把失败转为 null/[]。
 * 用途：/v1/compliance-kb/* 为 BE-011 待实现端点，后端未就绪时返回 404；本页面
 * 设计为静默降级到内置基线词库（usingBuiltin Alert），不应以弹窗打扰用户。
 */
const silentRequest = axios.create({
  timeout: 8000,
  baseURL: process.env.ICE_CORE_MODE === 'development' ? '/api' : '',
  headers: { 'Content-Type': 'application/json' },
});
silentRequest.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers = { Authorization: token, ...config.headers };
  }
  if (config.method && config.method.toUpperCase() === 'GET' && config.url) {
    config.url = `${config.url}${config.url.indexOf('?') === -1 ? '?' : '&'}ts=${Date.now()}`;
  }
  return config;
});
silentRequest.interceptors.response.use(
  (response) => {
    const { status, data } = response;
    if (Math.floor(status / 100) === 2) {
      if (data && data.data) return Promise.resolve(data.data);
      return Promise.resolve(data);
    }
    return Promise.resolve(response);
  },
  // 静默：任何错误都不弹 Modal，交由调用方降级处理
  (error) => Promise.reject(error),
);

/** 静默 GET：失败一律返回 null（触发页面降级到内置基线） */
const silentGet = <T>(url: string, fallback: T): Promise<T> =>
  silentRequest.get<any, any>(url)
    .then((r) => unwrap<T>(r) ?? fallback)
    .catch(() => fallback);

/** 获取当前生效词库的完整内容（类别 + 关键词 + 正则） */
export const getComplianceKbContent = (): Promise<ComplianceKbContent | null> =>
  silentGet<ComplianceKbContent | null>('/v1/compliance-kb/content', null);

/** 版本历史列表（新→旧） */
export const listComplianceKbVersions = (): Promise<ComplianceKbVersion[]> =>
  silentGet<ComplianceKbVersion[]>('/v1/compliance-kb/versions', []);

/** 当前生效版本元信息 */
export const getActiveComplianceKb = (): Promise<ComplianceKbVersion | null> =>
  silentGet<ComplianceKbVersion | null>('/v1/compliance-kb/active', null);

/** 词库更新授权状态 */
export const getComplianceKbLicense = (): Promise<ComplianceKbLicense | null> =>
  silentGet<ComplianceKbLicense | null>('/v1/compliance-kb/license', null);

/** 离线导入词库包 */
export const importComplianceKbOffline = (payload: { bundle: string }): Promise<ComplianceKbVersion> =>
  request.post<any, any>('/v1/compliance-kb/import', payload).then((r) => unwrap<ComplianceKbVersion>(r));

/** 在线拉取最新词库并应用 */
export const updateComplianceKbOnline = (): Promise<ComplianceKbVersion> =>
  request.post<any, any>('/v1/compliance-kb/online-update', {}).then((r) => unwrap<ComplianceKbVersion>(r));

/** 回滚到指定版本 */
export const rollbackComplianceKb = (versionNo: number): Promise<ComplianceKbVersion> =>
  request.post<any, any>(`/v1/compliance-kb/rollback/${versionNo}`, {}).then((r) => unwrap<ComplianceKbVersion>(r));

/** 同步当前词库到网关 WasmPlugin CR */
export const syncComplianceKbToGateway = (): Promise<boolean> =>
  request.post<any, any>('/v1/compliance-kb/sync-gateway', {}).then((r) => unwrap<boolean>(r) === true);
