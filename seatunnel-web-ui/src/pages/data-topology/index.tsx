import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import { ApartmentOutlined, ArrowRightOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Select, Spin } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import ReactFlow, { Background, Controls, MarkerType, Position, useNodesInitialized, useReactFlow } from 'reactflow';
import 'reactflow/dist/style.css';
import TableLineagePanel from './TableLineagePanel';
import { topologyPositions } from './layout';
import { fetchTopology, type DataSourceNode, type LineageLink, type Topology } from './service';
import './index.less';

type Selection = { kind: 'source' | 'link'; id: string };

function linkId({ sourceId, targetId }: LineageLink) {
  return `${sourceId}:${targetId}`;
}

function KeepFocusInView({ focus, topology }: { focus?: Set<string>; topology: Topology }) {
  const initialized = useNodesInitialized();
  const { fitView } = useReactFlow();
  useEffect(() => {
    if (!initialized) return;
    const frame = requestAnimationFrame(() => fitView({
      nodes: focus && [...focus].map((id) => ({ id })),
      padding: 0.18,
      maxZoom: 1,
    }));
    return () => cancelAnimationFrame(frame);
  }, [fitView, initialized, focus, topology]);
  return null;
}

function NeighborList({ title, neighbors, onFocus }: {
  title: string;
  neighbors: DataSourceNode[];
  onFocus: (id: string) => void;
}) {
  return (
    <section>
      <h3>{title}</h3>
      {neighbors.length === 0 ? <p className="data-topology-muted">暂无数据源</p> : (
        <ul className="data-topology-neighbors">
          {neighbors.map((node) => <li key={node.id}><Button type="link" onClick={() => onFocus(node.id)}>{node.name}</Button></li>)}
        </ul>
      )}
    </section>
  );
}

export default function DataTopologyPage() {
  const [topology, setTopology] = useState<Topology>();
  const [selection, setSelection] = useState<Selection>();
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let current = true;
    setLoading(true);
    setFailed(false);
    fetchTopology().then((graph) => {
      if (!current) return;
      setTopology(graph);
      setSelection((selected) => {
        if (!selected) return undefined;
        const exists = selected.kind === 'source'
          ? graph.nodes.some(({ id }) => id === selected.id)
          : graph.edges.some((edge) => linkId(edge) === selected.id);
        return exists ? selected : undefined;
      });
    }).catch(() => {
      if (current) setFailed(true);
    }).finally(() => {
      if (current) setLoading(false);
    });
    return () => { current = false; };
  }, [attempt]);

  const positions = useMemo(() => topology && topologyPositions(topology), [topology]);
  const byId = useMemo(() => new Map(topology?.nodes.map((node) => [node.id, node])), [topology]);
  const selectedSource = selection?.kind === 'source' ? byId.get(selection.id) : undefined;
  const selectedLink = selection?.kind === 'link'
    ? topology?.edges.find((edge) => linkId(edge) === selection.id)
    : undefined;

  const focus = useMemo(() => {
    if (!selection || !topology) return undefined;
    const edges = topology.edges.filter((edge) => selection.kind === 'source'
      ? edge.sourceId === selection.id || edge.targetId === selection.id
      : linkId(edge) === selection.id);
    const ids = new Set(edges.flatMap(({ sourceId, targetId }) => [sourceId, targetId]));
    if (selection.kind === 'source') ids.add(selection.id);
    return { nodes: ids, links: new Set(edges.map(linkId)) };
  }, [selection, topology]);

  const graphNodes = topology?.nodes.map((node) => ({
    id: node.id,
    position: positions?.get(node.id) ?? { x: 0, y: 0 },
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    className: focus && !focus.nodes.has(node.id)
      ? 'data-topology-node--muted'
      : focus?.nodes.has(node.id) ? 'data-topology-node--active' : undefined,
    ariaLabel: node.name,
    data: { label: (
      <div className="data-topology-node-label">
        <DatabaseIcons dbType={node.dbType} />
        <span title={node.name}>{node.name}</span>
      </div>
    ) },
  })) ?? [];
  const graphEdges = topology?.edges.map((edge) => {
    const id = linkId(edge);
    const active = focus?.links.has(id);
    const color = active ? '#2e9aff' : '#6a9cbd';
    return {
      id,
      source: edge.sourceId,
      target: edge.targetId,
      type: 'smoothstep',
      ariaLabel: `${byId.get(edge.sourceId)?.name}流向${byId.get(edge.targetId)?.name}`,
      markerEnd: { type: MarkerType.ArrowClosed, color, width: 18, height: 18 },
      style: { stroke: color, strokeWidth: active ? 2.6 : 2, opacity: focus && !active ? 0.4 : 1 },
    };
  }) ?? [];

  function neighborNodes(direction: 'upstream' | 'downstream'): DataSourceNode[] {
    if (!selectedSource || !topology) return [];
    const upstream = direction === 'upstream';
    return topology.edges.flatMap((edge) => {
      if ((upstream ? edge.targetId : edge.sourceId) !== selectedSource.id) return [];
      const neighbor = byId.get(upstream ? edge.sourceId : edge.targetId);
      return neighbor ? [neighbor] : [];
    });
  }

  const refresh = () => setAttempt((value) => value + 1);
  const focusSource = (id: string) => setSelection({ kind: 'source', id });
  const clearFocus = () => setSelection(undefined);

  return (
    <div className="data-topology-page">
      <div className="data-topology-toolbar">
        <Select<string>
          aria-label="搜索数据源"
          placeholder="搜索数据源"
          className="data-topology-search"
          showSearch
          allowClear
          optionFilterProp="label"
          value={selectedSource?.id}
          options={topology?.nodes.map(({ id, name }) => ({ value: id, label: name }))}
          onChange={(id) => id ? focusSource(id) : clearFocus()}
        />
        <Button icon={<ApartmentOutlined />} disabled={!selection} onClick={clearFocus}>全局视图</Button>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={refresh}>刷新拓扑</Button>
        {topology?.readAt && (
          <span className="data-topology-read-time data-topology-muted" role="status">
            最近成功读取 <time dateTime={topology.readAt}>{new Date(topology.readAt).toLocaleString('zh-CN', { hour12: false })}</time>
          </span>
        )}
      </div>

      {failed && topology && (
        <Alert
          showIcon
          type="warning"
          message="读取血缘失败，当前显示上次成功读取的结果"
          action={<Button onClick={refresh}>重试</Button>}
        />
      )}

      <div className="data-topology-workspace">
        <section className="data-topology-graph" aria-label="数据引接拓扑">
          <div className="data-topology-graph-header">
            <h2>数据源流向</h2>
            {topology && <span className="data-topology-muted" role="status">
              {topology.nodes.length} 个数据源 · {topology.edges.length > 0 ? `${topology.edges.length} 条链路` : '暂无跨数据源血缘'}
            </span>}
          </div>
          <div className="data-topology-canvas">
            {topology && graphNodes.length > 0 ? (
              <ReactFlow
                nodes={graphNodes}
                edges={graphEdges}
                nodesDraggable={false}
                nodesConnectable={false}
                deleteKeyCode={null}
                fitView
                fitViewOptions={{ padding: 0.18, maxZoom: 1 }}
                minZoom={0.1}
                maxZoom={2}
                onNodeClick={(_, node) => focusSource(node.id)}
                onEdgeClick={(_, edge) => setSelection({ kind: 'link', id: edge.id })}
              >
                <Background color="var(--st-color-divider)" gap={20} />
                <Controls showInteractive={false} />
                <KeepFocusInView focus={focus?.nodes} topology={topology} />
              </ReactFlow>
            ) : (
              <div className="data-topology-empty">
                {loading ? <Spin aria-label="正在读取拓扑" size="large" /> : (
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={failed ? '读取血缘失败' : '暂无已绑定 OM 的数据源'}>
                    {failed && <Button onClick={refresh}>重试</Button>}
                  </Empty>
                )}
              </div>
            )}
          </div>
        </section>

        {(selectedSource || selectedLink) && (
          <aside className="data-topology-details" aria-label="血缘详情">
            <div className="data-topology-detail-header">
              <h2>{selectedSource ? '数据源详情' : '链路详情'}</h2>
              <Button type="text" icon={<CloseOutlined />} aria-label="关闭血缘详情" onClick={clearFocus} />
            </div>
            <div className="data-topology-detail-body">
              {selectedSource && (
                <>
                  <div className="data-topology-source-title">
                    <DatabaseIcons dbType={selectedSource.dbType} />
                    <strong>{selectedSource.name}</strong>
                  </div>
                  <NeighborList title="上游数据源" neighbors={neighborNodes('upstream')} onFocus={focusSource} />
                  <NeighborList title="下游数据源" neighbors={neighborNodes('downstream')} onFocus={focusSource} />
                  {selectedSource.tableCatalogAvailable ? (
                    <TableLineagePanel key={selectedSource.id} dataSourceId={selectedSource.id} readAt={topology?.readAt} />
                  ) : <p className="data-topology-muted">当前没有可选的库表目录</p>}
                </>
              )}
              {selectedLink && (
                <>
                  <div className="data-topology-link-title">
                    <strong>{byId.get(selectedLink.sourceId)?.name}</strong>
                    <ArrowRightOutlined />
                    <strong>{byId.get(selectedLink.targetId)?.name}</strong>
                  </div>
                  <p className="data-topology-muted">关联资产关系数 {selectedLink.assetCount ?? '—'}</p>
                </>
              )}
            </div>
          </aside>
        )}
      </div>
    </div>
  );
}
