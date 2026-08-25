/**
 * ASG 前端扩展菜单配置（asg-console-extension/frontend/menu.config.ts）
 * 由 inject.sh 在构建时注入 console 前端 _defaultProps.tsx，console 源码保持零侵入。
 * icon 引用说明：SafetyCertificateOutlined 上游 _defaultProps.tsx 已导入；
 * 其余 4 个（AuditOutlined/EyeOutlined/RadarChartOutlined/SecurityScanOutlined）由 inject.sh 追加导入。
 */
import {
  AuditOutlined,
  EyeOutlined,
  RadarChartOutlined,
  SafetyCertificateOutlined,
  SecurityScanOutlined,
} from '@ant-design/icons';

export const asgMenuRoutes: any[] = [
  {
    name: 'menu.shadowAiManagement',
    icon: <EyeOutlined />,
    children: [
      {
        name: 'menu.shadowAiDetected',
        path: '/shadow-ai/detected',
      },
      {
        name: 'menu.shadowAiRoute',
        path: '/shadow-ai/route',
      },
    ],
  },
  {
    name: 'menu.aiContentSecurity',
    icon: <SecurityScanOutlined />,
    children: [
      {
        name: 'menu.aiSecurityGuard',
        path: '/ai-security-guard',
      },
      {
        name: 'menu.aiPiiGuard',
        path: '/ai-pii-guard',
      },
      {
        name: 'menu.aiPromptGuard',
        path: '/ai-prompt-guard',
      },
      {
        name: 'menu.aiKeywordFilter',
        path: '/ai-keyword-filter',
      },
      {
        name: 'menu.aiWafProtection',
        path: '/ai-waf',
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
    name: 'menu.auditChain',
    icon: <AuditOutlined />,
    children: [
      {
        name: 'menu.auditChainLogs',
        path: '/audit-chain/audit-logs',
      },
      {
        name: 'menu.auditChainTracking',
        path: '/audit-chain/audit-chain',
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
];
