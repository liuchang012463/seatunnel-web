import { Controls, Background, MiniMap, ReactFlow, ReactFlowProvider, type Edge, type Node } from 'reactflow';
import { useMemo } from 'react';
import CustomEdge from '@/pages/batch-link-up/workflow/edge';
import CustomNode from '@/pages/batch-link-up/workflow/nodes';
import type { ReactNode } from 'react';
import 'reactflow/dist/style.css';

export type OfflineImportConfigNode = 'source' | 'sink';

interface OfflineImportConfigCanvasProps {
  presentation?: 'split' | 'graph' | 'inspector';
  activeNode: OfflineImportConfigNode;
  onActiveNodeChange: (node: OfflineImportConfigNode) => void;
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

/** Fixed Source→Sink graph reuses the file-ingest canvas node visuals without a node palette. */
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
          className="offline-import-config-canvas__flow"
        >
          <Controls showInteractive={false} />
          <Background gap={14} size={2} color="#8585ad26" />
          <MiniMap
            className="workflow-minimap"
            position="bottom-left"
            style={{ width: 102, height: 72 }}
            maskColor="rgba(148, 163, 184, 0.14)"
          />
        </ReactFlow>
      </ReactFlowProvider>
    </div>
  );
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
  if (presentation === 'inspector') return inspector;

  return <div className="offline-import-config-canvas">{graph}{inspector}</div>;
};

export default OfflineImportConfigCanvas;
