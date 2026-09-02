import { useCallback, useEffect, useMemo, useState } from 'react';
import ReactFlow, { Background, MiniMap } from 'reactflow';
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
}

/**
 * 文件引接固定为 来源 → 去向 的两节点链路，节点不可增删；
 * 点击节点在画布右侧弹出配置面板。
 */
const FileSyncCanvas: React.FC<FileSyncCanvasProps> = ({
  nodes,
  edges,
  onNodesChange,
  onNodeDataChange,
  datasourceOptions,
}) => {
  const [selectedNode, setSelectedNode] = useState<any>(null);

  useEffect(() => {
    if (!selectedNode) return;
    const latest = nodes.find((node) => node.id === selectedNode.id);
    if (latest && latest !== selectedNode) {
      setSelectedNode(latest);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodes]);

  const nodeTypes = useMemo(() => ({ custom: CustomNode }), []);
  const edgeTypes = useMemo(() => ({ custom: CustomEdge }), []);

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
    <div className="relative h-full w-full">
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
        minZoom={0.25}
        maxZoom={1}
        fitView
        fitViewOptions={{ padding: 0.3, minZoom: 0.25, maxZoom: 0.75 }}
        className="reactflow-wrapper pointer-mode"
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

      {selectedNode?.data?.nodeType === 'source' && (
        <FileSyncSourcePanel
          selectedNode={selectedNode}
          onClose={() => setSelectedNode(null)}
          onNodeDataChange={onNodeDataChange}
          datasourceOptions={datasourceOptions}
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

export default FileSyncCanvas;
