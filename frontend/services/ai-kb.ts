/* eslint-disable max-len */
import request from './request';
import { KbMeta, KbLicenseStatus, KbImportPayload } from '@/interfaces/ai-kb';

// request.tsx 响应拦截器：2xx 且 data.data 为「真值」时返回 data.data；当 payload 为 null/false
// （如「无生效库」的 active、返回 false 的 sync-gateway）时返回整个 Response 包装体
// {success,message,data}。此处统一归一化为业务 payload，避免调用方误拿包装体。
function unwrap<T>(res: any): T {
  if (res && typeof res === 'object' && !Array.isArray(res) && 'success' in res && 'data' in res) {
    return res.data as T;
  }
  return res as T;
}

// ===== 版本查看（脱敏，不含域名明文）=====

// 版本历史（新→旧）
export const listKbVersions = (): Promise<KbMeta[]> =>
  request.get<any, any>('/v1/ai-kb/versions').then((r) => unwrap<KbMeta[]>(r) || []);

// 当前生效版本；无则 null
export const getActiveKb = (): Promise<KbMeta | null> =>
  request.get<any, any>('/v1/ai-kb/active').then((r) => unwrap<KbMeta | null>(r) ?? null);

// ===== 更新（受授权门控 + 厂商验签；无授权/验签失败后端拒绝）=====

// 离线上传更新包
export const importKbOffline = (payload: KbImportPayload): Promise<KbMeta> =>
  request.post<any, any>('/v1/ai-kb/import', payload).then((r) => unwrap<KbMeta>(r));

// 在线拉取最新库并应用
export const updateKbOnline = (): Promise<KbMeta> =>
  request.post<any, any>('/v1/ai-kb/online-update', {}).then((r) => unwrap<KbMeta>(r));

// 回滚到指定版本（既有已验签版本间切换，不受授权门控）
export const rollbackKb = (versionNo: number): Promise<KbMeta> =>
  request.post<any, any>(`/v1/ai-kb/rollback/${versionNo}`, {}).then((r) => unwrap<KbMeta>(r));

// 把当前 active KB 的 categories 重新同步到 ai-shadow-detect 网关插件
export const syncKbToGateway = (): Promise<boolean> =>
  request.post<any, any>('/v1/ai-kb/sync-gateway', {}).then((r) => unwrap<boolean>(r) === true);

// ===== 授权 =====

// 授权状态；无则 null
export const getKbLicense = (): Promise<KbLicenseStatus | null> =>
  request.get<any, any>('/v1/ai-kb/license').then((r) => unwrap<KbLicenseStatus | null>(r) ?? null);

// 导入离线授权文件（内容即 rawLicense）
export const importKbLicense = (rawLicense: string): Promise<KbLicenseStatus> =>
  request.post<any, any>('/v1/ai-kb/license/import', { rawLicense }).then((r) => unwrap<KbLicenseStatus>(r));

// 在线激活（上报设备指纹、拉取签名授权）
export const activateKbLicenseOnline = (): Promise<KbLicenseStatus> =>
  request.post<any, any>('/v1/ai-kb/license/activate', {}).then((r) => unwrap<KbLicenseStatus>(r));
