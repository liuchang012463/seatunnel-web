import { Controls, Background, MiniMap, ReactFlow, ReactFlowProvider, type Edge, type Node } from 'reactflow';
import { useMemo } from 'react';
import CustomEdge from '@/pages/batch-link-up/workflow/edge';
import CustomNode from '@/pages/batch-link-up/workflow/nodes';
import PanelShell from '@/pages/batch-link-up/workflow/panel/components/PanelShell';
import type { ReactNode } from 'react';
import 'reactflow/dist/style.css';

export type OfflineImportConfigNode = 'source' | 'sink';
type OfflineImportConfigPresentation = 'split' | 'graph' | 'inspector' | 'interactive';

interface OfflineImportConfigCanvasProps {
  presentation?: OfflineImportConfigPresentation;
  activeNode: OfflineImportConfigNode | null;
  onActiveNodeChange: (node: OfflineImportConfigNode | null) => void;
  sourceTitle: string;
  sourceSummary: string;
  sinkTitle: string;
  sinkSummary: string;
  sinkDbType: string;
  sourceConfig?: ReactNode;
  sinkConfig?: ReactNode;
}

const nodeTypes = { custom: CustomNode };
const edgeTypes = { custom: CustomEdge };

/** 导入任务保留固定 Source/Sink 节点，点选后复用批量任务的浮动配置面板。 */
const OfflineImportConfigCanvas: React.FC<OfflineImportConfigCanvasProps> = ({
  presentation = 'split',
  activeNode,
  onActiveNodeChange,
  sourceTitle,
  sourceSummary,
  sinkTitle,
  sinkSummary,
  sinkDbType,
  sourceConfig,
  sinkConfig,
}) => {
  const nodes = useMemo<Node[]>(() => [
    {
      id: 'source',
      type: 'custom',
      position: { x: 120, y: 210 },
      selected: activeNode === 'source',
      data: {
        nodeType: 'source',
        sourceMode: 'WEB_UPLOAD',
        title: sourceTitle,
        description: sourceSummary,
        dbType: 'MINIO',
        config: { sourceMode: 'WEB_UPLOAD' },
      },
    },
    {
      id: 'sink',
      type: 'custom',
      position: { x: 500, y: 210 },
      selected: activeNode === 'sink',
      data: {
        nodeType: 'sink',
        title: sinkTitle,
        description: sinkSummary,
        dbType: sinkDbType,
        config: { targetMode: 'database' },
      },
    },
  ], [activeNode, sinkDbType, sinkSummary, sinkTitle, sourceSummary, sourceTitle]);
  const edges = useMemo<Edge[]>(() => [{
    id: 'source-sink',
    source: 'source',
    target: 'sink',
    type: 'custom',
    data: {},
  }], []);

  const graph = (
    <div className="offline-import-config-canvas__graph" aria-label="导入任务画布">
      <ReactFlowProvider>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          onNodeClick={(_, node) => {
            if (node.id === 'source' || node.id === 'sink') onActiveNodeChange(node.id);
          }}
          nodesDraggable={false}
          nodesConnectable={false}
          nodesFocusable={false}
          edgesFocusable={false}
          elementsSelectable={false}
          deleteKeyCode={null}
          fitView
          fitViewOptions={{ padding: 0.3, minZoom: 0.35, maxZoom: 0.85 }}
          minZoom={0.25}
          maxZoom={1}
          proOptions={{ hideAttribution: true }}
          className="offline-import-config-canvas__flow"
        >
          <Controls position="top-left" showInteractive={false} />
          <Background gap={14} size={2} color="var(--st-color-divider)" />
          <MiniMap
            className="workflow-minimap"
            position="bottom-left"
            style={{ width: 102, height: 72 }}
            maskColor="rgba(0, 25, 34, 0.72)"
          />
        </ReactFlow>
      </ReactFlowProvider>
    </div>
  );
  const nodePanel = activeNode ? (
    <PanelShell
      eyebrow={activeNode === 'source' ? 'Source Config' : 'Sink Config'}
      title={activeNode === 'source' ? '来源配置' : '目标配置'}
      badge={activeNode === 'source' ? '输入节点' : '输出节点'}
      desc={activeNode === 'source' ? '选择导入文件并配置解析参数。' : '选择目标数据源和 Schema。'}
      heroTitle={activeNode === 'source' ? sourceTitle : sinkTitle}
      heroDesc={activeNode === 'source' ? sourceSummary : sinkSummary}
      heroTag={activeNode === 'source' ? 'SOURCE' : 'SINK'}
      dbType={activeNode === 'source' ? 'MINIO' : sinkDbType}
      onClose={() => onActiveNodeChange(null)}
      footer={<button type="button" className="workflow-panel__btn workflow-panel__btn--ghost" onClick={() => onActiveNodeChange(null)}>关闭</button>}
    >
      {activeNode === 'source' ? sourceConfig : sinkConfig}
    </PanelShell>
  ) : null;
  const inspector = (
    <aside className="offline-import-config-canvas__inspector" aria-label={activeNode === 'source' ? '来源配置' : '目标配置'}>
      <div className="offline-import-config-canvas__inspector-title">
        <span>{activeNode === 'source' ? '来源配置' : '目标配置'}</span>
      </div>
      <div className="offline-import-config-canvas__inspector-body">
        {activeNode === 'source' ? sourceConfig : sinkConfig}
      </div>
    </aside>
  );

  if (presentation === 'graph') return graph;
  if (presentation === 'interactive') {
    return <div className="offline-import-config-canvas offline-import-config-canvas--interactive">{graph}{nodePanel}</div>;
  }
  if (presentation === 'inspector') return inspector;

  return <div className="offline-import-config-canvas">{graph}{inspector}</div>;
};

export default OfflineImportConfigCanvas;
