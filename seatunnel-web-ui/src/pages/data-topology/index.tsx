import { readTaskDefinition } from '@/pages/common/workflow/TaskDefinitionView';
import { ApartmentOutlined, ReloadOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { Alert, Button, Empty, message, Select, Spin } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import ReactFlow, { Background, Controls, type Edge, MarkerType, type Node, useNodesInitialized, useReactFlow } from 'reactflow';
import 'reactflow/dist/style.css';
import { type AssetNodeData, fieldHandle, type FieldPort, TopologyAssetNode, TopologyTaskNode } from './TopologyNodes';
import { ancestry, buildHierarchy, type Group, levels, linksInFilter, openPaths, projectGraph, taskId } from './graph';
import { topologyPositions } from './layout';
import { fetchFieldLineage, fetchTopology, type FieldLink, type LineageTask, type Topology } from './service';
import './index.less';

const nodeTypes = { asset: TopologyAssetNode, task: TopologyTaskNode };
const filterLabels = { source: '数据源', database: '库', schema: 'Schema', table: '表' };
const noFields: FieldLink[] = [];
const noPorts: FieldPort[] = [];

function FitGraph({ positions }: { positions: ReturnType<typeof topologyPositions> }) {
  const initialized = useNodesInitialized();
  const { fitView } = useReactFlow();
  useEffect(() => {
    if (!initialized) return;
    const frame = requestAnimationFrame(() => fitView({ padding: 0.2, maxZoom: 1 }));
    return () => cancelAnimationFrame(frame);
  }, [fitView, initialized, positions]);
  return null;
}

function fieldPorts(fields: FieldLink[]) {
  const ports = new Map<string, Map<string, FieldPort>>();
  function connect(id: string, fqn: string, direction: 'incoming' | 'outgoing') {
    const asset = ports.get(id) ?? new Map<string, FieldPort>();
    const field = asset.get(fqn) ?? { fqn };
    field[direction] = true;
    asset.set(fqn, field);
    ports.set(id, asset);
  }
  for (const field of fields) {
    connect(field.sourceId, field.fromColumn, 'outgoing');
    connect(field.targetId, field.toColumn, 'incoming');
  }
  return new Map([...ports].map(([id, fields]) => [id, [...fields.values()]]));
}

export default function DataTopologyPage() {
  const [topology, setTopology] = useState<Topology>();
  const [filterId, setFilterId] = useState<string>();
  const [opened, setOpened] = useState(new Set<string>());
  const [fieldId, setFieldId] = useState<string>();
  const [fieldResult, setFieldResult] = useState<{ id: string; fields?: FieldLink[] }>();
  const [removedTasks, setRemovedTasks] = useState(new Set<string>());
  const [checkingTask, setCheckingTask] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [notice, noticeContext] = message.useMessage();

  useEffect(() => {
    let current = true;
    setLoading(true);
    setFailed(false);
    fetchTopology().then((data) => {
      if (current) setTopology(data);
    }).catch(() => {
      if (current) setFailed(true);
    }).finally(() => {
      if (current) setLoading(false);
    });
    return () => { current = false; };
  }, [attempt]);

  const hierarchy = useMemo(() => buildHierarchy(topology), [topology]);
  const activeFilter = filterId && hierarchy.groups.has(filterId) ? filterId : undefined;
  const filterPath = ancestry(hierarchy, activeFilter);
  const links = useMemo(() => linksInFilter(topology?.edges ?? [], hierarchy, activeFilter),
    [topology, hierarchy, activeFilter]);
  const focusedAsset = fieldId ? hierarchy.paths.get(fieldId)?.at(-1)?.asset : undefined;
  const fieldState = !focusedAsset ? undefined
    : fieldResult?.id !== focusedAsset.id ? 'loading'
      : fieldResult.fields ? 'open' : 'error';

  useEffect(() => {
    if (!focusedAsset) return;
    let current = true;
    setFieldResult(undefined);
    fetchFieldLineage(focusedAsset).then((fields) => {
      if (current) setFieldResult({ id: focusedAsset.id, fields });
    }).catch(() => {
      if (current) setFieldResult({ id: focusedAsset.id });
    });
    return () => { current = false; };
  }, [focusedAsset]);

  const visibleFields = fieldState === 'open' ? fieldResult?.fields ?? noFields : noFields;
  const projection = useMemo(() => projectGraph(links, hierarchy, opened, visibleFields),
    [links, hierarchy, opened, visibleFields]);
  const ports = useMemo(() => fieldPorts(projection.fields), [projection.fields]);
  const positions = useMemo(() => topologyPositions([
    ...projection.groups.map(({ group }) => ({
      id: group.id,
      height: 88 + (ports.get(group.asset?.id ?? '')?.length ?? 0) * 29 + (focusedAsset && group.asset?.id === focusedAsset.id ? 32 : 0),
    })),
    ...projection.tasks.map(([id]) => ({ id, height: 72 })),
  ], projection.links), [projection, ports, focusedAsset]);

  function applyFilter(id?: string) {
    setFilterId(id);
    setFieldId(undefined);
    const depth = Math.max(0, ancestry(hierarchy, id).length - 1);
    setOpened(openPaths(linksInFilter(topology?.edges ?? [], hierarchy, id), hierarchy, depth));
  }

  function collapse(id: string) {
    setOpened((current) => {
      const next = new Set(current);
      next.delete(id);
      return next;
    });
    if (focusedAsset && hierarchy.paths.get(focusedAsset.id)?.some((group) => group.id === id)) {
      setFieldId(undefined);
    }
  }

  function showFields(group: Group) {
    const asset = group.asset;
    if (!asset) return;
    if (fieldId === asset.id) {
      setFieldId(undefined);
      return;
    }
    const related = links.filter((edge) => edge.sourceId === asset.id || edge.targetId === asset.id);
    setOpened((current) => new Set([...current, ...openPaths(related, hierarchy, levels.length - 1)]));
    setFieldResult(undefined);
    setFieldId(asset.id);
  }

  async function openDefinition(task: LineageTask) {
    if (checkingTask) return;
    const key = taskId(task);
    setCheckingTask(key);
    try {
      const current = await readTaskDefinition(task.kind, task.id);
      if (!current) {
        setRemovedTasks((previous) => new Set([...previous, key]));
        notice.warning('任务已删除');
        return;
      }
      const path = task.kind === 'STREAMING' ? 'stream-link-up' : 'batch-link-up';
      history.push(`/sync/${path}/${encodeURIComponent(task.id)}/detail?view=definition`);
    } catch {
      notice.error('无法查看任务定义');
    } finally {
      setCheckingTask(undefined);
    }
  }

  const nodes: Node[] = projection.groups.map(({ group, assets, internalCount }) => {
    const data: AssetNodeData = {
      group,
      parents: ancestry(hierarchy, group.parentId),
      assetCount: assets.size,
      internalCount,
      fields: ports.get(group.asset?.id ?? '') ?? noPorts,
      fieldState: group.asset?.id === focusedAsset?.id ? fieldState : undefined,
      onExpand: ({ id }) => setOpened((current) => new Set([...current, id])),
      onCollapse: collapse,
      onFields: showFields,
    };
    return { id: group.id, type: 'asset', position: positions.get(group.id)!, data, ariaLabel: group.name };
  });
  for (const [id, link] of projection.tasks) {
    const key = link.task ? taskId(link.task) : undefined;
    const task = key && link.task && removedTasks.has(key) ? { ...link.task, deleted: true } : link.task;
    nodes.push({ id, type: 'task', position: positions.get(id)!,
      data: { task, checking: key !== undefined && checkingTask === key, onOpen: openDefinition } });
  }
  const edges: Edge[] = projection.links.map((edge) => ({
    ...edge,
    type: 'smoothstep',
    sourceHandle: 'out',
    targetHandle: 'in',
    label: edge.count ? `${edge.count} 条` : undefined,
    labelStyle: { fontSize: 14, fill: 'var(--st-color-text-primary)' },
    labelBgStyle: { fill: 'var(--st-color-bg-elevated)' },
    markerEnd: { type: MarkerType.ArrowClosed, color: '#62a6d6' },
    style: { stroke: '#62a6d6', strokeWidth: 2 },
  }));
  projection.fields.forEach((field, index) => {
    edges.push({
      id: `field:${index}`,
      className: 'data-topology-field-edge',
      source: `asset:${field.sourceId}`,
      target: `asset:${field.targetId}`,
      sourceHandle: fieldHandle(field.fromColumn, 'from'),
      targetHandle: fieldHandle(field.toColumn, 'to'),
      ariaLabel: `${field.fromColumn} → ${field.toColumn}`,
      markerEnd: { type: MarkerType.ArrowClosed, color: '#63c5a6' },
      style: { stroke: '#63c5a6', strokeWidth: 1.4 },
    });
  });

  const refresh = () => setAttempt((value) => value + 1);
  return (
    <div className="data-topology-page">
      {noticeContext}
      <div className="data-topology-toolbar">
        {levels.map((level, index) => {
          const parent = filterPath.find((group) => group.kind === levels[index - 1]);
          const options = [...hierarchy.groups.values()]
            .filter((group) => group.kind === level && (!parent || group.parentId === parent.id))
            .map((group) => ({ value: group.id,
              label: parent ? group.name : ancestry(hierarchy, group.id).map(({ name }) => name).join(' / ') }))
            .sort((a, b) => a.label.localeCompare(b.label, 'zh-CN'));
          return <Select<string>
            key={level}
            aria-label={`筛选${filterLabels[level]}`}
            placeholder={`筛选${filterLabels[level]}`}
            className="data-topology-filter"
            showSearch
            allowClear
            optionFilterProp="label"
            value={filterPath.find((group) => group.kind === level)?.id}
            options={options}
            onChange={(value) => applyFilter(value || parent?.id)}
          />;
        })}
        <Button icon={<ApartmentOutlined />} aria-label="全局视图" disabled={!activeFilter && opened.size === 0 && !fieldId}
          onClick={() => applyFilter()}>全局视图</Button>
        <Button icon={<ReloadOutlined />} aria-label="刷新拓扑" loading={loading} onClick={refresh}>刷新拓扑</Button>
      </div>
      {failed && topology && <Alert showIcon type="warning" message="读取血缘失败，当前显示上次成功读取的结果"
        action={<Button onClick={refresh}>重试</Button>} />}
      <section className="data-topology-graph" aria-label="数据引接拓扑">
        <div className="data-topology-graph-header">
          <h2>数据引接血缘</h2>
          <div className="data-topology-graph-meta" role="status">
            {topology && <span>{new Set(links.flatMap(({ sourceId, targetId }) => [sourceId, targetId])).size} 个资产 · {links.length} 条血缘</span>}
            {topology?.readAt && <span>最近成功读取 <time dateTime={topology.readAt}>
              {new Date(topology.readAt).toLocaleString('zh-CN', { hour12: false })}
            </time></span>}
          </div>
        </div>
        <div className="data-topology-canvas">
          {nodes.length ? <ReactFlow
            nodes={nodes} edges={edges} nodeTypes={nodeTypes} nodesDraggable={false} nodesConnectable={false}
            elementsSelectable={false} deleteKeyCode={null} fitView fitViewOptions={{ padding: 0.2, maxZoom: 1 }}
            minZoom={0.1} maxZoom={2}
          >
            <Background color="var(--st-color-divider)" gap={20} />
            <Controls showInteractive={false} />
            <FitGraph positions={positions} />
          </ReactFlow> : <div className="data-topology-empty">
            {loading ? <Spin aria-label="正在读取拓扑" size="large" />
              : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={failed ? '读取血缘失败' : '暂无可展示的血缘'}>
                {failed && !topology && <Button onClick={refresh}>重试</Button>}
              </Empty>}
          </div>}
        </div>
      </section>
    </div>
  );
}
