import React from 'react';
import AiMetricsDashboard from '@/components/ai-dashboard-metrics';

/** 主菜单「监控面板」→ 自建中文全量监控（与 AI 监控面板一致，含 Token 指标） */
const AiDashboardOverviewPage: React.FC = () => <AiMetricsDashboard mode="full" />;

export default AiDashboardOverviewPage;
