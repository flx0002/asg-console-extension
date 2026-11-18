/**
 * ASG 前端扩展菜单配置（asg-console-extension/frontend/menu.config.ts）
 * 由 inject.sh 在构建时注入 console 前端 _defaultProps.tsx，console 源码保持零侵入。
 * icon 引用说明：SafetyCertificateOutlined 上游 _defaultProps.tsx 已导入；
 * 其余 6 个（AlertOutlined/AuditOutlined/BookOutlined/EyeOutlined/FileSearchOutlined/
 * RadarChartOutlined/SecurityScanOutlined）由 inject.sh 通过 needed 集合追加进 import 块，
 * menu.config.ts 的 import 语句本身不参与构建产物。
 */
import {
  AlertOutlined,
  AuditOutlined,
  BookOutlined,
  EyeOutlined,
  FileSearchOutlined,
  RadarChartOutlined,
  SafetyCertificateOutlined,
  SecurityScanOutlined,
} from '@ant-design/icons';

export const asgMenuRoutes: any[] = [
  {
    name: 'menu.aiShadowManagement',
    icon: <EyeOutlined />,
    children: [
      {
        // 路径必须与 /ai-shadow/events 同路径深度（不能写成 /ai-shadow）：
        // 侧栏高亮按 path 前缀匹配，父路径会与子路径同时命中，两个菜单项一起亮。
        name: 'menu.aiShadowConfig',
        path: '/ai-shadow/config',
      },
      {
        name: 'menu.aiKb',
        path: '/ai-kb',
      },
    ],
  },
  {
    name: 'menu.aiContentSecurity',
    icon: <SecurityScanOutlined />,
    children: [
      {
        name: 'menu.aiPromptGuard',
        path: '/ai-prompt-guard',
      },
      {
        name: 'menu.aiPiiGuard',
        path: '/ai-pii-guard',
      },
      {
        name: 'menu.aiWafProtection',
        path: '/ai-waf',
      },
      {
        name: 'menu.aiSecurityGuard',
        path: '/ai-security-guard',
      },
      {
        name: 'menu.aiKeywordFilter',
        path: '/ai-keyword-filter',
      },
      {
        name: 'menu.aiComplianceKb',
        path: '/ai-content-compliance-kb',
        icon: <BookOutlined />,
      },
    ],
  },
  {
    name: 'menu.aiAgentGuard',
    icon: <SafetyCertificateOutlined />,
    children: [
      {
        name: 'menu.aiAgentGuardConfig',
        path: '/ai-agent-guard/config',
      },
    ],
  },
  {
    name: 'menu.logsAudit',
    icon: <FileSearchOutlined />,
    children: [
      {
        // 影子AI事件属可审计事件流，归「日志与审计」首位；页面与路径不变。
        name: 'menu.aiShadowEvents',
        path: '/ai-shadow/events',
      },
      {
        name: 'menu.logsAuditChainLogs',
        path: '/audit-chain/audit-logs',
      },
      {
        name: 'menu.logsAuditChainTracking',
        path: '/audit-chain/audit-chain',
      },
      {
        name: 'menu.logsSecurityEvents',
        path: '/security-events',
        icon: <AlertOutlined />,
      },
      {
        name: 'menu.logsAdminOperations',
        path: '/audit-chain/admin-operations',
      },
    ],
  },
  {
    name: 'menu.behaviorAnalysis',
    icon: <RadarChartOutlined />,
    children: [
      {
        name: 'menu.behaviorDashboard',
        path: '/behavior-analysis/dashboard',
      },
      {
        name: 'menu.behaviorAlerts',
        path: '/behavior-analysis/alerts',
      },
      {
        name: 'menu.behaviorProfiles',
        path: '/behavior-analysis/profiles',
      },
      {
        name: 'menu.behaviorSessionGraph',
        path: '/behavior-analysis/session-graph',
      },
    ],
  },
  // 配置备份与恢复不在此处作为顶级菜单：由 inject.sh 注入为「系统配置」(menu.systemSettings) 的子菜单
];
