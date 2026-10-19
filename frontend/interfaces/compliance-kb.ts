export interface ComplianceKbCategory {
  code: string;
  name: string;
  mode: 'block' | 'advisory';
  keywords: string[];
  patterns: string[];
  keywordCount: number;
  advice?: string;
  sample?: { positive: string; negative: string };
}

export interface ComplianceKbSection {
  section: string;
  title: string;
  clause: string;
  categories: ComplianceKbCategory[];
}

export interface ComplianceKbContent {
  versionNo: number;
  kbHash: string;
  standard: string;
  appendix: string;
  categoryTotal: number;
  keywordTotal: number;
  generatedBy?: string;
  sections: ComplianceKbSection[];
}

export interface ComplianceKbVersion {
  id?: number;
  versionNo: number;
  label?: string;
  categoryCount: number;
  keywordCount: number;
  kbHash?: string;
  status?: 'active' | 'inactive';
  source?: 'builtin' | 'online' | 'offline' | 'rollback';
  operator?: string;
  createdAt?: number[] | string;
  /** 当前 active 是否已成功下发到网关（kbHash == gatewaySyncedHash）。 */
  gatewaySynced?: boolean;
  /** 导入/在线更新命中相同内容（kbHash 去重），未产生新版本。 */
  unchanged?: boolean;
}

export interface ComplianceKbLicense {
  canUpdate: boolean;
  featureName?: string;
  expiresAt?: number[] | string;
  reason?: string;
}
