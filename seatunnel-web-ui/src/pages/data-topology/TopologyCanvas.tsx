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
import { linkId, taskNodeId } from './graph';
import { topologyPositions } from './layout';
import type { FieldLink, LineageAsset, LineageLink, LineageSource } from './service';
import { fieldHandle, tableNodeSize, taskNodeSize, TopologyAssetNode, TopologyTaskNode, type FieldView } from './TopologyNodes';

const nodeTypes = { asset: TopologyAssetNode, task: TopologyTaskNode };
const tableEdgeColor = '#62a6d6';
const fieldEdgeColor = '#63c5a6';

interface Props {
  assets: LineageAsset[];
  links: LineageLink[];
  sources: LineageSource[];
  summaries: Map<string, DataExplorationTable>;
  focusedId?: string;
  selectedId?: string;
  fields: Map<string, FieldView>;
  mappings: FieldLink[];
  groupedTasks: Set<string>;
  onSelect: (id: string) => void;
  onFields: (id: string) => void;
  onPair: (link: LineageLink) => void;
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

function tableEdge(id: string, source: string, target: string, link: LineageLink,
  grouped: boolean, selected: boolean): Edge {
  const color = selected ? '#80c9f4' : tableEdgeColor;
  return {
    id, source, target, type: 'default', sourceHandle: 'out', targetHandle: 'in',
    data: grouped ? { link } : undefined,
    className: grouped ? 'data-topology-pair-edge' : undefined,
    markerEnd: { type: MarkerType.ArrowClosed, color, width: 14, height: 14 },
    style: { stroke: color, strokeWidth: selected ? 2 : 1.5 },
  };
}

export default function TopologyCanvas({
  assets, links, sources, summaries, focusedId, selectedId, fields, mappings, groupedTasks, onSelect, onFields, onPair,
}: Props) {
  const [nodes, setNodes, changeNodes] = useNodesState([]);
  const dragged = useRef(new Map<string, XYPosition>());
  const taskNodes = useMemo(() => links.flatMap(({ task }) => task && !groupedTasks.has(taskNodeId(task))
    ? [{ id: taskNodeId(task), task }] : []), [groupedTasks, links]);
  const fitIds = useMemo(() => [
    ...assets.map(({ id }) => id),
    ...taskNodes.map(({ id }) => id),
  ], [assets, taskNodes]);

  const tablePaths = useMemo(() => links.flatMap((link) => {
    const id = link.task ? taskNodeId(link.task) : undefined;
    const grouped = Boolean(id && groupedTasks.has(id));
    return !id || grouped ? [{ id: `direct:${linkId(link)}`, source: link.sourceId, target: link.targetId, link, grouped }]
      : [
        { id: `in:${id}`, source: link.sourceId, target: id, link, grouped },
        { id: `out:${id}`, source: id, target: link.targetId, link, grouped },
      ];
  }), [groupedTasks, links]);

  const edges = useMemo<Edge[]>(() => [
    ...tablePaths.map(({ id, source, target, link, grouped }) =>
      tableEdge(id, source, target, link, grouped, Boolean(link.task && taskNodeId(link.task) === selectedId))),
    ...mappings.map((mapping) => ({
      id: JSON.stringify([mapping.sourceId, mapping.fromColumn, mapping.targetId, mapping.toColumn]),
      source: mapping.sourceId,
      target: mapping.targetId,
      sourceHandle: fieldHandle(mapping.fromColumn, 'from'),
      targetHandle: fieldHandle(mapping.toColumn, 'to'),
      markerEnd: { type: MarkerType.ArrowClosed, color: fieldEdgeColor },
      style: { stroke: fieldEdgeColor, strokeWidth: 1.4 },
    })),
  ], [mappings, selectedId, tablePaths]);

  const positions = useMemo(() => topologyPositions([
    ...assets.map(({ id, type }) => {
      const view = fields.get(id);
      const height = type === 'table' ? tableNodeSize.height : 58;
      const fieldHeight = view ? Math.max(1, view.rows.length + Number(Boolean(view.loading || view.failed))) * 27 : 0;
      return { id, width: tableNodeSize.width, height: height + fieldHeight };
    }),
    ...taskNodes.map(({ id }) => ({ id, ...taskNodeSize, offsetY: 4 })),
  ], tablePaths), [assets, fields, taskNodes, tablePaths]);

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
    for (const { id, task } of taskNodes) {
      next.push({
        id,
        type: 'task',
        ariaLabel: task.name || `任务 ${task.id}`,
        position: dragged.current.get(id) ?? positions.get(id)!,
        dragHandle: '.data-topology-node-heading',
        selected: id === selectedId,
        data: task,
      });
    }
    setNodes(next);
  }, [assets, fields, focusedId, onFields, positions, selectedId, setNodes, sources, summaries, taskNodes]);

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
      onEdgeClick={(_, edge) => { if (edge.data?.link) onPair(edge.data.link); }}
    >
      <Background color="var(--st-color-divider)" gap={20} />
      <Controls showInteractive={false} />
      <MiniMap pannable zoomable style={{ width: 126, height: 86 }}
        nodeColor="var(--st-color-line-strong)" maskColor="rgb(9 24 43 / 55%)" />
      <FitGraph ids={fitIds} />
    </ReactFlow>
  );
}
