import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import ReactFlow, { Background, MiniMap, ReactFlowProvider, useReactFlow } from 'reactflow';
import 'reactflow/dist/style.css';
import CustomNode from '../../workflow/nodes';
import CustomEdge from '../../workflow/edge';
import FileSyncSourcePanel from './FileSyncSourcePanel';
import FileSyncSinkPanel from './FileSyncSinkPanel';

interface FileSyncCanvasProps {
  nodes: any[];
  edges: any[];
  onNodesChange: (nodes: any) => void;
  onNodeDataChange: (nodeId: string, data: any) => void;
  datasourceOptions: Array<{ label: string; value: string; dbType: string }>;
  jobDefinitionId?: string | number;
  engineVersion?: string;
}

/** 内部画布：使用 ReactFlowProvider 让 fitView 在容器渲染后稳定地触发。 */
const FileSyncCanvasInner: React.FC<FileSyncCanvasProps> = ({
  nodes,
  edges,
  onNodesChange,
  onNodeDataChange,
  datasourceOptions,
  jobDefinitionId,
  engineVersion,
}) => {
  const [selectedNode, setSelectedNode] = useState<any>(null);
  const canvasRef = useRef<HTMLDivElement>(null);
  const [canvasReady, setCanvasReady] = useState(false);
  const { fitView } = useReactFlow();

  useLayoutEffect(() => {
    const element = canvasRef.current;
    if (!element) return;

    const updateSize = () => {
      const rect = element.getBoundingClientRect();
      setCanvasReady(rect.width > 0 && rect.height > 0);
    };

    updateSize();
    if (typeof ResizeObserver === 'undefined') {
      const frame = window.requestAnimationFrame(updateSize);
      return () => window.cancelAnimationFrame(frame);
    }

    const observer = new ResizeObserver(updateSize);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!selectedNode) return;
    const latest = nodes.find((node) => node.id === selectedNode.id);
    if (latest && latest !== selectedNode) {
      setSelectedNode(latest);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodes]);

  // 节点初次加载后等容器布局稳定再 fitView，确保节点落在画布可视区内。
  useEffect(() => {
    if (!canvasReady) return;
    const timer = setTimeout(() => fitView({ padding: 0.4, duration: 0 }), 100);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canvasReady, nodes.length]);

  const nodeTypes = useMemo(() => ({ custom: CustomNode }), []);
  const edgeTypes = useMemo(() => ({ custom: CustomEdge }), []);
  const sinkNode = nodes.find((node) => node?.data?.nodeType === 'sink');
  const sinkConfig = sinkNode?.data?.config || {};

  const handleNodeClick = useCallback((_: any, node: any) => {
    setSelectedNode(node);
  }, []);

  const handlePaneClick = useCallback(() => {
    setSelectedNode(null);
  }, []);

  const wrappedEdges = useMemo(
    () =>
      edges.map((edge) => ({
        ...edge,
        type: edge.type || 'custom',
        data: { ...(edge.data || {}) },
      })),
    [edges],
  );

  return (
    <div ref={canvasRef} className="file-workflow-canvas relative h-full min-h-[420px] w-full">
      {canvasReady ? (
        <ReactFlow
          nodes={nodes}
          edges={wrappedEdges}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          onNodesChange={onNodesChange}
          onNodeClick={handleNodeClick}
          onPaneClick={handlePaneClick}
          nodesDraggable
          nodesConnectable={false}
          deleteKeyCode={null}
          minZoom={0.1}
          maxZoom={1.5}
          className="react-flow-wrapper pointer-mode"
        >
          <Background gap={[14, 14]} size={2} color="#8585ad26" />
          <MiniMap
            className="workflow-minimap"
            position="bottom-left"
            style={{ width: 102, height: 72 }}
            maskColor="rgba(0, 25, 34, 0.72)"
            pannable
          />
        </ReactFlow>
      ) : (
        <div className="flex h-full min-h-[420px] items-center justify-center text-sm text-slate-400">
          正在加载画布…
        </div>
      )}

      {selectedNode?.data?.nodeType === 'source' && (
        <FileSyncSourcePanel
          selectedNode={selectedNode}
          onClose={() => setSelectedNode(null)}
          onNodeDataChange={onNodeDataChange}
          datasourceOptions={datasourceOptions}
          jobDefinitionId={jobDefinitionId}
          engineVersion={engineVersion}
          targetDataSourceId={sinkConfig.dataSourceId}
          targetDbType={sinkConfig.dbType}
        />
      )}

      {selectedNode?.data?.nodeType === 'sink' && (
        <FileSyncSinkPanel
          selectedNode={selectedNode}
          onClose={() => setSelectedNode(null)}
          onNodeDataChange={onNodeDataChange}
          datasourceOptions={datasourceOptions}
        />
      )}
    </div>
  );
};

const FileSyncCanvas: React.FC<FileSyncCanvasProps> = (props) => (
  <ReactFlowProvider>
    <FileSyncCanvasInner {...props} />
  </ReactFlowProvider>
);

export default FileSyncCanvas;
