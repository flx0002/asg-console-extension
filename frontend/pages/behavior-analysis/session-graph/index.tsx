import React, { useEffect, useRef, useState } from 'react';
import {
  Card, Input, Button, Empty, Spin, Alert, Row, Col, Statistic, Descriptions, Tag, Space, Tooltip,
} from 'antd';
import {
  SearchOutlined, ReloadOutlined, ApartmentOutlined,
} from '@ant-design/icons';
import G6, { Graph } from '@antv/g6';
import { useRequest } from 'ahooks';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'ice';
import { getSessionGraph } from '@/services';

interface GraphNode {
  id: string;
  step_index?: string;
  tool_name?: string;
  step_type?: string;
  risk_score?: number;
  high_risk?: boolean;
  model?: string;
  timestamp?: number;
}

interface GraphEdge {
  source: string;
  target: string;
  trace_id?: string;
}

interface SessionGraphData {
  nodes: GraphNode[];
  edges: GraphEdge[];
  node_count?: number;
  folded?: boolean;
  max_nodes?: number;
  session_id?: string;
}

const MAX_NODES_THRESHOLD = 50;

// 节点颜色：正常(蓝) / 高风险(红) / 阻断(红实心)
const getNodeColor = (node: GraphNode): string => {
  if (node.risk_score !== undefined && node.risk_score >= 80) return '#ff4d4f';
  if (node.high_risk) return '#ff7875';
  if (node.step_type === 'blocked') return '#cf1322';
  return '#1677ff';
};

// 折叠连续同名工具调用（方案 6.5 大图保护：>50 节点时折叠）
const foldBigGraph = (nodes: GraphNode[], edges: GraphEdge[]) => {
  if (nodes.length <= MAX_NODES_THRESHOLD) {
    return { nodes, edges, folded: false, groupMap: new Map<string, GraphNode[]>() };
  }

  // 按工具名分组连续节点
  const groups: Map<string, GraphNode[]> = new Map();
  const foldedNodes: GraphNode[] = [];
  const foldedEdges: GraphEdge[] = [];
  const nodeIdToGroupId: Map<string, string> = new Map();

  let currentGroup: GraphNode[] = [];
  let currentTool = '';

  for (const node of nodes) {
    const tool = node.tool_name || node.step_type || 'unknown';
    if (tool !== currentTool || currentGroup.length >= 10) {
      // 切换分组
      if (currentGroup.length > 0) {
        const groupId = `group-${foldedNodes.length}`;
        currentGroup.forEach((n) => nodeIdToGroupId.set(n.id, groupId));
        groups.set(groupId, currentGroup);
        foldedNodes.push({
          id: groupId,
          step_index: `${currentGroup.length}`,
          tool_name: `${currentTool} (${currentGroup.length})`,
          step_type: 'group',
          risk_score: Math.max(...currentGroup.map((n) => n.risk_score || 0)),
          high_risk: currentGroup.some((n) => n.high_risk),
        });
      }
      currentGroup = [];
      currentTool = tool;
    }
    currentGroup.push(node);
  }
  // 收尾
  if (currentGroup.length > 0) {
    const groupId = `group-${foldedNodes.length}`;
    currentGroup.forEach((n) => nodeIdToGroupId.set(n.id, groupId));
    groups.set(groupId, currentGroup);
    foldedNodes.push({
      id: groupId,
      step_index: `${currentGroup.length}`,
      tool_name: `${currentTool} (${currentGroup.length})`,
      step_type: 'group',
      risk_score: Math.max(...currentGroup.map((n) => n.risk_score || 0)),
      high_risk: currentGroup.some((n) => n.high_risk),
    });
  }

  // 重建边（合并同组内的边）
  const edgeSet: Map<string, GraphEdge> = new Map();
  for (const edge of edges) {
    const srcGroup = nodeIdToGroupId.get(edge.source) || edge.source;
    const tgtGroup = nodeIdToGroupId.get(edge.target) || edge.target;
    if (srcGroup === tgtGroup) continue; // 组内边跳过
    const key = `${srcGroup}->${tgtGroup}`;
    if (!edgeSet.has(key)) {
      edgeSet.set(key, { source: srcGroup, target: tgtGroup, trace_id: edge.trace_id });
    }
  }
  edgeSet.forEach((e) => foldedEdges.push(e));

  return { nodes: foldedNodes, edges: foldedEdges, folded: true, groupMap: groups };
};

const BehaviorSessionGraphPage: React.FC = () => {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const initialSessionId = searchParams.get('sessionId') || '';

  const [sessionIdInput, setSessionIdInput] = useState<string>(initialSessionId);
  const [graphData, setGraphData] = useState<SessionGraphData | null>(null);
  const [selectedNode, setSelectedNode] = useState<GraphNode | null>(null);
  const [folded, setFolded] = useState<boolean>(false);

  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Graph | null>(null);
  const groupMapRef = useRef<Map<string, GraphNode[]>>(new Map());
  const fullNodesRef = useRef<GraphNode[]>([]);
  const resizeHandlerRef = useRef<(() => void) | null>(null);

  // 加载会话图谱
  const { run: loadGraph, loading } = useRequest(
    (sid: string) => getSessionGraph(sid),
    {
      manual: true,
      onSuccess: (res) => {
        const data = res?.data || res || {};
        const nodes: GraphNode[] = Array.isArray(data.nodes) ? data.nodes : [];
        const edges: GraphEdge[] = Array.isArray(data.edges) ? data.edges : [];
        fullNodesRef.current = nodes;

        // 大图折叠
        const foldedResult = foldBigGraph(nodes, edges);
        groupMapRef.current = foldedResult.groupMap;
        setFolded(foldedResult.folded);

        setGraphData({
          nodes: foldedResult.nodes,
          edges: foldedResult.edges,
          node_count: nodes.length,
          folded: foldedResult.folded,
          max_nodes: MAX_NODES_THRESHOLD,
          session_id: data.session_id || '',
        });
      },
    },
  );

  // 初始化 G6 图实例
  // 依赖 graphData：图谱容器 <div ref={containerRef}> 仅在 graphData 为真时渲染，
  // 故初始化需在容器挂载后（graphData 变为真时）执行；graphRef 守卫避免重复创建。
  useEffect(() => {
    if (!containerRef.current) return;
    if (graphRef.current) return;

    const width = containerRef.current.offsetWidth;
    const height = Math.max(500, window.innerHeight - 300);

    const minimap = new G6.Minimap({
      size: [180, 120],
      className: 'behavior-session-graph-minimap',
      type: 'keyShape',
    });

    const graph = new G6.Graph({
      container: containerRef.current,
      width,
      height,
      fitView: true,
      fitViewPadding: 20,
      plugins: [minimap],
      modes: {
        default: [
          'drag-canvas',
          'zoom-canvas',
          'drag-node',
          {
            type: 'tooltip',
            formatText(model: any) {
              const tool = model.tool_name || '-';
              const risk = model.risk_score ?? 0;
              const step = model.step_index || '-';
              return `<div style="padding:6px;font-size:12px;">
                <div>步骤: ${step}</div>
                <div>工具: ${tool}</div>
                <div>风险分: ${risk}</div>
              </div>`;
            },
          } as any,
        ],
      },
      layout: {
        // 方案 6.5：默认 Dagre 分层布局（禁止力导向作为默认）
        type: 'dagre',
        rankdir: 'TB',
        nodesep: 30,
        ranksep: 50,
        preventOverlap: true,
      } as any,
      defaultNode: {
        size: [120, 40],
        type: 'rect',
        style: {
          radius: 6,
          fill: '#1677ff',
          stroke: '#0958d9',
          lineWidth: 1,
        },
        labelCfg: {
          style: {
            fill: '#fff',
            fontSize: 11,
          },
        },
      },
      defaultEdge: {
        type: 'cubic',
        style: {
          stroke: '#bfbfbf',
          lineWidth: 1,
          endArrow: {
            path: G6.Arrow.triangle(8, 10, 0),
            fill: '#bfbfbf',
          } as any,
        },
      },
      nodeStateStyles: {
        highRisk: {
          fill: '#ff4d4f',
          stroke: '#cf1322',
          lineWidth: 2,
          shadowColor: '#ff4d4f',
          shadowBlur: 12,
        },
        selected: {
          stroke: '#fa8c16',
          lineWidth: 3,
          shadowColor: '#fa8c16',
          shadowBlur: 8,
        },
      },
    });

    graph.on('node:click', (evt: any) => {
      const node = evt.item;
      const model = node.getModel() as any;
      // 清除其他选中状态
      graph.getNodes().forEach((n) => graph.setItemState(n, 'selected', false));
      graph.setItemState(node, 'selected', true);

      // 折叠组节点：展开
      if (model.step_type === 'group') {
        // 方案 6.5：点击折叠组节点按需展开（前端展开同组节点）
        const groupNodes = groupMapRef.current.get(model.id) || [];
        setSelectedNode({
          id: model.id,
          step_index: `${groupNodes.length} 个节点`,
          tool_name: model.tool_name,
          step_type: 'group',
          risk_score: model.risk_score,
        });
      } else {
        // 普通节点：显示详情
        setSelectedNode({
          id: model.id,
          step_index: model.step_index,
          tool_name: model.tool_name,
          step_type: model.step_type,
          risk_score: model.risk_score,
          high_risk: model.high_risk,
          model: model.model,
          timestamp: model.timestamp,
        });
      }
    });

    graphRef.current = graph;

    // 响应窗口大小
    const handleResize = () => {
      if (graphRef.current && containerRef.current) {
        graphRef.current.changeSize(containerRef.current.offsetWidth, height);
      }
    };
    window.addEventListener('resize', handleResize);
    resizeHandlerRef.current = handleResize;
    // 不在此返回 cleanup，避免 graphData 变化时销毁图谱；卸载清理由下方独立 effect 处理
  }, [graphData]);

  // 卸载时销毁图谱与 resize 监听
  useEffect(() => {
    return () => {
      if (resizeHandlerRef.current) {
        window.removeEventListener('resize', resizeHandlerRef.current);
        resizeHandlerRef.current = null;
      }
      if (graphRef.current) {
        graphRef.current.destroy();
        graphRef.current = null;
      }
    };
  }, []);

  // 数据变化时重绘
  useEffect(() => {
    if (!graphRef.current || !graphData) return;

    // 构造 G6 数据结构
    const nodes = graphData.nodes.map((n) => {
      const color = getNodeColor(n);
      const isGroup = n.step_type === 'group';
      return {
        id: n.id,
        step_index: n.step_index,
        tool_name: n.tool_name,
        step_type: n.step_type,
        risk_score: n.risk_score,
        high_risk: n.high_risk,
        model: n.model,
        timestamp: n.timestamp,
        label: isGroup ? `📦 ${n.tool_name}` : `${n.step_index || ''}: ${n.tool_name || '-'}`,
        style: {
          fill: color,
          stroke: color,
        },
      };
    });

    const edges = graphData.edges.map((e, idx) => ({
      id: `edge-${idx}`,
      source: e.source,
      target: e.target,
    }));

    graphRef.current.data({ nodes, edges });
    graphRef.current.render();
    graphRef.current.fitView(20);

    // 设置高风险节点状态（脉冲）
    graphData.nodes.forEach((n) => {
      if (n.high_risk || (n.risk_score ?? 0) >= 80) {
        const item = graphRef.current?.findById(n.id);
        if (item) graphRef.current?.setItemState(item, 'highRisk', true);
      }
    });
  }, [graphData]);

  // 初始加载
  useEffect(() => {
    if (initialSessionId) {
      setSessionIdInput(initialSessionId);
      loadGraph(initialSessionId);
    }
  }, [initialSessionId]);

  const handleLoad = () => {
    if (!sessionIdInput.trim()) return;
    setSelectedNode(null);
    loadGraph(sessionIdInput.trim());
  };

  // 会话统计
  const stats = (() => {
    if (!graphData) return { totalSteps: 0, maxRisk: 0, blockedCount: 0, highRiskCount: 0 };
    const allNodes = fullNodesRef.current;
    const totalSteps = allNodes.length;
    const maxRisk = allNodes.reduce((max, n) => Math.max(max, n.risk_score || 0), 0);
    const blockedCount = allNodes.filter((n) => n.step_type === 'blocked').length;
    const highRiskCount = allNodes.filter((n) => n.high_risk || (n.risk_score ?? 0) >= 80).length;
    return { totalSteps, maxRisk, blockedCount, highRiskCount };
  })();

  return (
    <div style={{ padding: '0 0 24px' }}>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontWeight: 500, fontSize: 15 }}>{t('behaviorAnalysis.sessionGraph.title')}</span>
      </div>

      {/* 查询栏 */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space>
          <Input
            placeholder={t('behaviorAnalysis.sessionGraph.sessionIdPlaceholder')}
            value={sessionIdInput}
            onChange={(e) => setSessionIdInput(e.target.value)}
            onPressEnter={handleLoad}
            style={{ width: 400 }}
            prefix={<SearchOutlined />}
          />
          <Button type="primary" icon={<ApartmentOutlined />} onClick={handleLoad} loading={loading}>
            {t('behaviorAnalysis.sessionGraph.load')}
          </Button>
          {graphData && (
            <Button icon={<ReloadOutlined />} onClick={() => sessionIdInput && loadGraph(sessionIdInput)} loading={loading}>
              {t('behaviorAnalysis.refresh')}
            </Button>
          )}
        </Space>
      </Card>

      {/* 大图提示 */}
      {folded && (
        <Alert
          type="warning"
          showIcon
          message={t('behaviorAnalysis.sessionGraph.bigGraphTip')}
          style={{ marginBottom: 16 }}
        />
      )}

      {!graphData ? (
        <Card>
          <Empty description={t('behaviorAnalysis.sessionGraph.noGraph')} style={{ padding: 80 }} />
        </Card>
      ) : (
        <Row gutter={16}>
          {/* 图谱区 */}
          <Col span={18}>
            <Card size="small" title={`${t('behaviorAnalysis.sessionGraph.title')} - ${graphData.session_id || ''}`}>
              <Spin spinning={loading}>
                <div
                  ref={containerRef}
                  style={{ width: '100%', minHeight: 500, background: '#fafafa', borderRadius: 4, position: 'relative' }}
                />
              </Spin>
            </Card>
          </Col>

          {/* 右侧统计面板 */}
          <Col span={6}>
            <Card size="small" title={t('behaviorAnalysis.profile.statistics')} style={{ marginBottom: 16 }}>
              <Row gutter={[8, 16]}>
                <Col span={12}>
                  <Statistic title={t('behaviorAnalysis.sessionGraph.totalSteps')} value={stats.totalSteps} />
                </Col>
                <Col span={12}>
                  <Statistic
                    title={t('behaviorAnalysis.sessionGraph.maxRiskScore')}
                    value={stats.maxRisk}
                    valueStyle={{ color: stats.maxRisk >= 80 ? '#cf1322' : '#3f8600' }}
                  />
                </Col>
                <Col span={12}>
                  <Statistic
                    title={t('behaviorAnalysis.sessionGraph.blockedCount')}
                    value={stats.blockedCount}
                    valueStyle={{ color: stats.blockedCount > 0 ? '#cf1322' : '#3f8600' }}
                  />
                </Col>
                <Col span={12}>
                  <Statistic
                    title={t('behaviorAnalysis.sessionGraph.highRisk')}
                    value={stats.highRiskCount}
                    valueStyle={{ color: stats.highRiskCount > 0 ? '#cf1322' : '#3f8600' }}
                  />
                </Col>
              </Row>
            </Card>

            {/* 节点详情 */}
            <Card size="small" title={t('behaviorAnalysis.sessionGraph.stepInfo')}>
              {selectedNode ? (
                <Descriptions column={1} size="small">
                  <Descriptions.Item label={t('behaviorAnalysis.sessionGraph.stepIndex')}>
                    {selectedNode.step_index || '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('behaviorAnalysis.sessionGraph.toolName')}>
                    {selectedNode.tool_name || '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('behaviorAnalysis.sessionGraph.stepType')}>
                    <Tag color={selectedNode.step_type === 'group' ? 'purple' : 'blue'}>
                      {selectedNode.step_type || '-'}
                    </Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label={t('behaviorAnalysis.sessionGraph.riskScore')}>
                    <Tag color={(selectedNode.risk_score ?? 0) >= 80 ? 'red' : (selectedNode.risk_score ?? 0) >= 50 ? 'orange' : 'green'}>
                      {selectedNode.risk_score ?? 0}
                    </Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label={t('behaviorAnalysis.sessionGraph.status')}>
                    {selectedNode.high_risk ? (
                      <Tag color="red">{t('behaviorAnalysis.sessionGraph.highRisk')}</Tag>
                    ) : selectedNode.step_type === 'blocked' ? (
                      <Tag color="red">{t('behaviorAnalysis.sessionGraph.blocked')}</Tag>
                    ) : (
                      <Tag color="green">{t('behaviorAnalysis.sessionGraph.normal')}</Tag>
                    )}
                  </Descriptions.Item>
                  {selectedNode.step_type === 'group' && (
                    <Descriptions.Item label={t('behaviorAnalysis.sessionGraph.groupNode')}>
                      <Tooltip title={t('behaviorAnalysis.sessionGraph.expandGroup')}>
                        <Tag color="purple">{t('behaviorAnalysis.sessionGraph.groupNode')}</Tag>
                      </Tooltip>
                    </Descriptions.Item>
                  )}
                </Descriptions>
              ) : (
                <Empty description={t('behaviorAnalysis.noData')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
              )}
            </Card>
          </Col>
        </Row>
      )}
    </div>
  );
};

export default BehaviorSessionGraphPage;
