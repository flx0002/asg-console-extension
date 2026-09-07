import request from './request';

// ===== 配置快照（IR-028）=====

// 整机配置快照列表（最多 5 个，最新在前）
export const getConfigSnapshots = (): Promise<any> => {
  return request.get('/v1/config-version/snapshots');
};

// 立即保存整机配置快照
export const takeConfigSnapshot = (): Promise<any> => {
  return request.post('/v1/config-version/snapshots');
};

// 恢复到指定整机快照（恢复前自动拍 pre-restore 快照）
export const restoreConfigSnapshot = (id: number): Promise<any> => {
  return request.post(`/v1/config-version/snapshots/${id}/restore`);
};

// ===== 整机备份文件（IR-028）=====

// 导出整机配置加密备份（返回 Base64 加密串）
export const exportConfigBackup = (): Promise<any> => {
  return request.get('/v1/config-version/backup/export');
};

// 导入整机配置备份（覆盖式恢复）
export const importConfigBackup = (content: string): Promise<any> => {
  return request.post('/v1/config-version/backup/import', { content });
};

// 快照/备份操作日志
export const getConfigBackupLogs = (): Promise<any> => {
  return request.get('/v1/config-version/backup/logs');
};
