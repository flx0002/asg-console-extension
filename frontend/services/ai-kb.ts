/* eslint-disable max-len */
import request from './request';
import { KbMeta, KbLicenseStatus, KbImportPayload, KbOnlineSetting } from '@/interfaces/ai-kb';

// request.tsx 响应拦截器：2xx 且 data.data 为「真值」时返回 data.data；当 payload 为 null/false
// （如「无生效库」的 active、返回 false 的 sync-gateway）时返回整个 Response 包装体
// {success,message,data}。此处统一归一化为业务 payload，避免调用方误拿包装体。
// 业务级失败（后端以 200 + success=false 返回，如更新包非法/服务器不可达）按 message 抛出，
// 交由调用方 catch 展示真实原因，而非被通用 HTTP 错误弹窗掩盖。
function unwrap<T>(res: any): T {
  if (res && typeof res === 'object' && !Array.isArray(res) && 'success' in res && 'data' in res) {
    if (res.success === false) {
      throw new Error(res.message || '操作失败');
    }
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

// 导入离线授权文件（内容即 rawLicense）；后端返回校验后的授权状态，调用方按 status 判定成败
export const importKbLicense = (rawLicense: string): Promise<KbLicenseStatus> =>
  request.post<any, any>('/v1/ai-kb/license/import', { rawLicense }).then((r) => unwrap<KbLicenseStatus>(r));

// ===== 在线更新设置（系统配置页）=====

// 读取在线更新服务器设置（页面值 + env 默认 + 生效地址 + 是否已配置）
export const getKbOnlineSetting = (): Promise<KbOnlineSetting> =>
  request.get<any, any>('/v1/ai-kb/online-setting').then((r) => unwrap<KbOnlineSetting>(r) || {});

// 保存在线更新服务器基址（传空=清除页面值，回退 env）
export const updateKbOnlineSetting = (url: string): Promise<KbOnlineSetting> =>
  request.post<any, any>('/v1/ai-kb/online-setting', { url }).then((r) => unwrap<KbOnlineSetting>(r) || {});
