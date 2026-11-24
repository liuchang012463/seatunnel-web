import type { DataExplorationTable } from '@/pages/data-source/types';
import { useCallback, useEffect, useMemo, useRef } from 'react';
import ReactFlow, {
  Background,
  Controls,
  getNodesBounds,
  MarkerType,
  MiniMap,
  useNodesInitialized,
  useNodesState,
  useReactFlow,
  useStore,
  type Edge,
  type Node,
  type OnNodesChange,
  type XYPosition,
} from 'reactflow';
import { linkId } from './graph';
import { topologyPositions } from './layout';
import type { FieldLink, LineageAsset, LineageLink, LineageSource } from './service';
import { fieldHandle, tableNodeSize, taskNodeSize, TopologyAssetNode, TopologyTaskNode, type FieldView } from './TopologyNodes';

const nodeTypes = { asset: TopologyAssetNode, task: TopologyTaskNode };
const tableEdgeColor = '#62a6d6';
const fieldEdgeColor = '#63c5a6';

export const taskNodeId = (link: LineageLink) => `task:${linkId(link)}`;

interface Props {
  assets: LineageAsset[];
  links: LineageLink[];
  sources: LineageSource[];
  summaries: Map<string, DataExplorationTable>;
  focusedId?: string;
  selectedId?: string;
  fields: Map<string, FieldView>;
  mappings: FieldLink[];
  onSelect: (id: string) => void;
  onFields: (id: string) => void;
}

function FitGraph({ ids }: { ids: string[] }) {
  const initialized = useNodesInitialized();
  const width = useStore((state) => state.width);
  const { getNodes, setViewport } = useReactFlow();
  const fitted = useRef<{ ids: string[]; width: number }>();
  useEffect(() => {
    if (!initialized || width <= 48 || (fitted.current?.ids === ids && fitted.current.width === width)) return;
    const frame = requestAnimationFrame(() => {
      const visible = new Set(ids);
      const bounds = getNodesBounds(getNodes().filter(({ id }) => visible.has(id)));
      if (!bounds.width) return;
      const zoom = Math.min(1, Math.max(0.05, (width - 48) / bounds.width));
      // Anchor the first row so disconnected tables start in view under the linked graph.
      setViewport({ zoom, x: (width - bounds.width * zoom) / 2 - bounds.x * zoom, y: 24 - bounds.y * zoom });
      fitted.current = { ids, width };
    });
    return () => cancelAnimationFrame(frame);
  }, [getNodes, ids, initialized, setViewport, width]);
  return null;
}

function tableEdge(id: string, source: string, target: string): Edge {
  return {
    id, source, target, type: 'smoothstep', sourceHandle: 'out', targetHandle: 'in',
    markerEnd: { type: MarkerType.ArrowClosed, color: tableEdgeColor },
    style: { stroke: tableEdgeColor, strokeWidth: 1.75 },
  };
}

export default function TopologyCanvas({
  assets, links, sources, summaries, focusedId, selectedId, fields, mappings, onSelect, onFields,
}: Props) {
  const [nodes, setNodes, changeNodes] = useNodesState([]);
  const dragged = useRef(new Map<string, XYPosition>());
  const fitIds = useMemo(() => [
    ...assets.map(({ id }) => id),
    ...links.filter(({ task }) => task).map(taskNodeId),
  ], [assets, links]);

  const tableEdges = useMemo(() => links.flatMap((link) => {
    if (!link.task) return [tableEdge(`direct:${linkId(link)}`, link.sourceId, link.targetId)];
    const taskId = taskNodeId(link);
    return [
      tableEdge(`in:${taskId}`, link.sourceId, taskId),
      tableEdge(`out:${taskId}`, taskId, link.targetId),
    ];
  }), [links]);

  const edges = useMemo<Edge[]>(() => [
    ...tableEdges,
    ...mappings.map((mapping) => ({
      id: JSON.stringify([mapping.sourceId, mapping.fromColumn, mapping.targetId, mapping.toColumn]),
      source: mapping.sourceId,
      target: mapping.targetId,
      sourceHandle: fieldHandle(mapping.fromColumn, 'from'),
      targetHandle: fieldHandle(mapping.toColumn, 'to'),
      markerEnd: { type: MarkerType.ArrowClosed, color: fieldEdgeColor },
      style: { stroke: fieldEdgeColor, strokeWidth: 1.4 },
    })),
  ], [mappings, tableEdges]);

  const positions = useMemo(() => topologyPositions([
    ...assets.map(({ id, type }) => {
      const view = fields.get(id);
      const height = type === 'table' ? tableNodeSize.height : 58;
      const fieldHeight = view ? Math.max(1, view.rows.length + Number(Boolean(view.loading || view.failed))) * 27 : 0;
      return { id, width: tableNodeSize.width, height: height + fieldHeight };
    }),
    ...links.filter(({ task }) => task).map((link) => ({ id: taskNodeId(link), ...taskNodeSize, offsetY: 4 })),
  ], tableEdges), [assets, fields, links, tableEdges]);

  useEffect(() => {
    const sourceTypes = new Map(sources.map(({ id, dbType }) => [id, dbType]));
    const next: Node[] = assets.map((asset) => ({
      id: asset.id,
      type: 'asset',
      ariaLabel: asset.name,
      position: dragged.current.get(asset.id) ?? positions.get(asset.id)!,
      dragHandle: '.data-topology-node-heading',
      selected: asset.id === selectedId,
      data: {
        asset, fields: fields.get(asset.id), dbType: sourceTypes.get(asset.dataSourceId), focused: asset.id === focusedId,
        summary: summaries.get(asset.id), onFields,
      },
    }));
    for (const link of links) {
      if (!link.task) continue;
      const id = taskNodeId(link);
      next.push({
        id,
        type: 'task',
        ariaLabel: link.task.name || `任务 ${link.task.id}`,
        position: dragged.current.get(id) ?? positions.get(id)!,
        dragHandle: '.data-topology-node-heading',
        selected: id === selectedId,
        data: link.task,
      });
    }
    setNodes(next);
  }, [assets, fields, focusedId, links, onFields, positions, selectedId, setNodes, sources, summaries]);

  const onNodesChange = useCallback<OnNodesChange>((changes) => {
    for (const change of changes) {
      if (change.type === 'position' && change.position) dragged.current.set(change.id, change.position);
    }
    changeNodes(changes);
  }, [changeNodes]);

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      nodesConnectable={false}
      edgesFocusable={false}
      deleteKeyCode={null}
      multiSelectionKeyCode={null}
      selectionKeyCode={null}
      selectNodesOnDrag={false}
      zoomOnDoubleClick={false}
      minZoom={0.05}
      maxZoom={2}
      onNodesChange={onNodesChange}
      onNodeClick={(_, node) => onSelect(node.id)}
    >
      <Background color="var(--st-color-divider)" gap={20} />
      <Controls showInteractive={false} />
      <MiniMap pannable zoomable style={{ width: 126, height: 86 }}
        nodeColor="var(--st-color-line-strong)" maskColor="rgb(9 24 43 / 55%)" />
      <FitGraph ids={fitIds} />
    </ReactFlow>
  );
}
