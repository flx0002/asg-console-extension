/* eslint-disable max-len */
// AI 分类知识库（KB）前端类型，与后端 controller.dto.KbMeta / KbLicenseStatus 字段对齐。
// 说明：LocalDateTime 字段在本仓后端可能序列化为 [y,m,d,h,m,s,ns] 数组或 ISO 字符串，
// 页面统一用 formatDateTime 兜底（参照 ai-shadow 页 formatEventTime 惯例）。

export type KbDateTime = string | number[] | null | undefined;

/** KB 版本脱敏视图：仅统计/指纹，绝不含域名明文。 */
export interface KbMeta {
  id?: number;
  versionNo?: number;
  label?: string;
  changelog?: string;
  sigAlgorithm?: string;
  kbHash?: string;
  categoryCount?: number;
  domainCount?: number;
  status?: string; // active / inactive
  source?: string; // online / offline / rollback / builtin
  operator?: string;
  createdAt?: KbDateTime;
}

/** 单个授权功能位：以稳定 id 为身份，name 为后端目录按 id 解析的权威显示名称。active 由后端按当前时间计算。 */
export interface KbLicenseFunction {
  id?: string;
  name?: string;
  issuedAt?: KbDateTime;
  expiresAt?: KbDateTime;
  active?: boolean;
}

/** KB 授权状态（脱敏）：不含原始凭证/签名体。 */
export interface KbLicenseStatus {
  status?: string; // valid / expired / invalid / absent
  canUpdate?: boolean;
  licenseId?: string;
  subject?: string;
  features?: string[];
  /** 授权功能位（含各自签发/过期时间），产品级授权页展示用。 */
  functions?: KbLicenseFunction[];
  issuedAt?: KbDateTime;
  expiresAt?: KbDateTime;
  reason?: string;
  source?: string; // offline / online
  activatedAt?: KbDateTime;
  /** 本机设备 ESN（授权绑定用，可复制到厂商授权工具）。 */
  deviceFingerprint?: string;
  // ===== 全量对齐字段（对齐 TEG 授权）=====
  esn?: string; // 授权文件内绑定的 ESN
  checkSn?: boolean; // 是否校验设备 ESN（对齐 TEG checkSn，默认 true）
  licenseType?: string; // formal（正式/永久） / temporary（临时）
  productName?: string;
  productVersion?: string;
  companyName?: string;
  contractNo?: string;
  licenseModel?: string;
  licenseValue?: number;
  iegCustomerId?: string;
  iegAuthorizedCount?: number;
}

/** 离线更新包：与后端 POST /v1/ai-kb/import 请求体、mock 在线服务器 OnlineKbPackage 同构。 */
export interface KbImportPayload {
  bundle: string; // KbBundle 明文 JSON 字符串（被签名/加密的原文）
  signature: string; // 厂商签名（Base64）
  sigAlgorithm?: string; // 签名算法；缺省用后端默认
  label?: string;
  changelog?: string;
}
