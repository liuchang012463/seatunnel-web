import { ApartmentOutlined, PartitionOutlined, ReloadOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { Alert, Button, Empty, InputNumber, Spin } from 'antd';
import { useMemo, useState } from 'react';
import 'reactflow/dist/style.css';
import { fieldRows, lineageNeighborhood, linkId, schemaLimits, tableLimits, type GraphLimits } from './graph';
import {
  fetchFieldLineage, fetchFieldMetadata, fetchTopology, type FieldLink, type LineageAsset, type LineageTask,
} from './service';
import TopologyBrowser from './TopologyBrowser';
import TopologyCanvas, { taskNodeId } from './TopologyCanvas';
import TopologyInspector from './TopologyInspector';
import type { FieldView } from './TopologyNodes';
import useLineageRead from './useLineageRead';
import useTopologyCatalog, { type CatalogScope } from './useTopologyCatalog';
import './index.less';

const noMappings: FieldLink[] = [];
const graphOptions = [
  { key: 'upstream', label: '上游深度', min: 0 },
  { key: 'downstream', label: '下游深度', min: 0 },
  { key: 'nodesPerLayer', label: '每层节点数', min: 1 },
] as const;

function restoreView() {
  const query = new URLSearchParams(history.location.search);
  function get(key: string) {
    return query.get(key) || undefined;
  }
  const focusedId = get('tableId');
  const defaults = focusedId ? tableLimits : schemaLimits;
  const limits = { ...defaults };
  for (const { key, min } of graphOptions) {
    const value = Number(get(key));
    if (Number.isSafeInteger(value) && value >= min) limits[key] = value;
  }
  return {
    scope: { sourceId: get('dataSourceId'), databaseFqn: get('database'), schemaFqn: get('schema') },
    focusedId, selectedId: get('node') ?? focusedId, fieldId: get('fields'), limits,
  };
}

function GraphSettings({ limits, onChange, onLayout }: {
  limits: GraphLimits;
  onChange: (limits: GraphLimits) => void;
  onLayout: () => void;
}) {
  return (
    <div className="data-topology-settings">
      {graphOptions.map(({ key, label, min }) => (
        <label key={key}>
          {label}
          <InputNumber size="small" aria-label={label} min={min} precision={0} value={limits[key]}
            onChange={(value) => {
              if (value !== null && Number.isSafeInteger(value) && value >= min) {
                onChange({ ...limits, [key]: value });
              }
            }} />
        </label>
      ))}
      <Button size="small" icon={<PartitionOutlined />} onClick={onLayout}>自动布局</Button>
    </div>
  );
}

export default function DataTopologyPage() {
  const [initial] = useState(restoreView);
  const [scope, setScope] = useState<CatalogScope>(initial.scope);
  const [focusedId, setFocusedId] = useState(initial.focusedId);
  const [selectedId, setSelectedId] = useState(initial.selectedId);
  const [fieldId, setFieldId] = useState(initial.fieldId);
  const [overviewSettings, setOverviewSettings] = useState(initial.focusedId ? schemaLimits : initial.limits);
  const [tableSettings, setTableSettings] = useState(initial.focusedId ? initial.limits : tableLimits);
  const [layout, setLayout] = useState(0);
  const [revision, setRevision] = useState(0);

  const topology = useLineageRead(fetchTopology, revision);
  const catalog = useTopologyCatalog(scope, topology.data, revision);
  const focus = catalog.roots.find(({ id }) => id === focusedId);
  const limits = focus ? tableSettings : overviewSettings;
  const graph = useMemo(() => lineageNeighborhood(topology.data, focus ? [focus] : catalog.roots, limits),
    [catalog.roots, focus, limits, topology.data]);
  const assets = useMemo(() => new Map(graph.assets.map((asset) => [asset.id, asset])), [graph.assets]);
  const activeFieldAsset = fieldId ? assets.get(fieldId) : undefined;
  const fieldScope = useMemo(() => {
    if (!activeFieldAsset) return undefined;
    const pairs = new Set<string>();
    const ids = new Set([activeFieldAsset.id]);
    for (const link of graph.links) {
      if (link.sourceId !== activeFieldAsset.id && link.targetId !== activeFieldAsset.id) continue;
      pairs.add(linkId(link));
      ids.add(link.sourceId);
      ids.add(link.targetId);
    }
    return { pairs, assets: [...ids].map((id) => assets.get(id)!) };
  }, [activeFieldAsset, assets, graph.links]);

  const loadMappings = useMemo(() => activeFieldAsset && fieldScope?.pairs.size
    ? () => fetchFieldLineage(activeFieldAsset) : undefined, [activeFieldAsset, fieldScope?.pairs.size]);
  const loadColumns = useMemo(() => fieldScope
    ? (signal: AbortSignal) => fetchFieldMetadata(fieldScope.assets, signal) : undefined, [fieldScope]);
  const mappingResult = useLineageRead(loadMappings, revision);
  const columnResult = useLineageRead(loadColumns, revision);
  const mappings = useMemo(() => fieldScope
    ? mappingResult.data?.filter((mapping) => fieldScope.pairs.has(linkId(mapping))) ?? noMappings
    : noMappings, [fieldScope, mappingResult.data]);
  const fields = useMemo(() => {
    const result = new Map<string, FieldView>();
    for (const asset of fieldScope?.assets ?? []) {
      const detail = columnResult.data?.details.get(asset.id);
      result.set(asset.id, {
        rows: fieldRows(asset, detail?.columns ?? [], mappings),
        loading: asset.type === 'table' && columnResult.loading && !detail,
        failed: columnResult.data?.unavailable.has(asset.id),
      });
    }
    return result;
  }, [columnResult.data, columnResult.loading, fieldScope, mappings]);

  function focusTable(id?: string) {
    setFocusedId(id);
    setSelectedId(id);
    setFieldId(undefined);
  }

  function changeScope(next: CatalogScope) {
    setScope(next);
    focusTable();
  }

  function openTask(task: LineageTask) {
    // Keep the current graph on the history entry so Back returns to this view.
    const query = new URLSearchParams({
      dataSourceId: catalog.source?.id ?? '',
      database: catalog.database?.fullyQualifiedName ?? '',
      schema: catalog.schema?.fullyQualifiedName ?? '',
      ...Object.fromEntries(graphOptions.map(({ key }) => [key, String(limits[key])])),
    });
    if (focus) query.set('tableId', focus.id);
    if (selectedId) query.set('node', selectedId);
    if (activeFieldAsset) query.set('fields', activeFieldAsset.id);
    history.replace({ pathname: history.location.pathname, search: `?${query}` });
    const list = task.kind === 'STREAMING' ? 'stream-link-up' : 'batch-link-up';
    history.push(`/sync/${list}?id=${encodeURIComponent(task.id)}`);
  }

  const busy = topology.loading || catalog.loading;
  const scopeFqn = catalog.schema?.fullyQualifiedName;
  const title = focus?.name ?? 'Schema 血缘总览';
  const selectedAsset: LineageAsset | undefined = selectedId ? assets.get(selectedId) : undefined;
  const selectedLink = graph.links.find((link) => link.task && taskNodeId(link) === selectedId);
  const fieldLabel = mappingResult.loading ? '正在读取字段映射'
    : mappingResult.failed ? '字段映射读取失败'
      : mappings.length > 0 ? `${mappings.length} 条字段映射` : '暂无字段映射';

  return (
    <div className="data-topology-page">
      <header className="data-topology-header">
        <div className="data-topology-header-icon"><ApartmentOutlined /></div>
        <div className="data-topology-header-copy">
          <h1>数据引接血缘</h1>
          {scopeFqn && <small title={scopeFqn}>{scopeFqn}</small>}
        </div>
        {topology.data?.readAt && <time dateTime={topology.data.readAt} className="data-topology-read-time">
          {new Date(topology.data.readAt).toLocaleString('zh-CN', { hour12: false })}
        </time>}
        <Button icon={<ReloadOutlined />} loading={busy} onClick={() => setRevision((current) => current + 1)}>刷新血缘</Button>
      </header>
      {topology.failed && <Alert showIcon type="warning"
        message={topology.data ? '读取血缘失败，当前显示上次成功读取的结果' : '读取表间血缘失败，请刷新重试。'} />}
      {catalog.failed && <Alert showIcon type="warning" message="读取元数据目录失败，请刷新重试。" />}
      <div className="data-topology-workspace">
        <TopologyBrowser catalog={catalog} focusedId={focus?.id} onScope={changeScope}
          onFocus={focusTable} onOverview={() => focusTable()} />
        <section className="data-topology-graph" aria-label={title}>
          <header className="data-topology-graph-header">
            <div className="data-topology-graph-title">
              <h2 title={title}>{title}</h2>
              <span role="status">{graph.assets.length} 个资产 · {graph.links.length} 条血缘
                {graph.truncated && ' · 已达到每层节点上限'}
              </span>
            </div>
            <GraphSettings limits={limits} onChange={focus ? setTableSettings : setOverviewSettings}
              onLayout={() => setLayout((current) => current + 1)} />
          </header>
          {activeFieldAsset && <div className="data-topology-field-toolbar">
            <span title={activeFieldAsset.fullyQualifiedName}>
              <strong>{activeFieldAsset.name}</strong> · {fieldLabel}
            </span>
            <Button type="text" size="small" onClick={() => setFieldId(undefined)}>收起字段</Button>
          </div>}
          <div className="data-topology-canvas">
            {graph.assets.length ? <TopologyCanvas
              key={JSON.stringify([scopeFqn, focus?.id, layout])}
              assets={graph.assets} links={graph.links} sources={topology.data?.sources ?? catalog.sources}
              focusedId={focus?.id} selectedId={selectedId} fields={fields} mappings={mappings} onSelect={setSelectedId}
            /> : <div className="data-topology-empty">
              {busy ? <Spin aria-label="正在读取血缘" />
                : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={
                  scopeFqn ? '该 Schema 暂无数据表' : '暂无可用的 Schema'
                } />}
            </div>}
          </div>
        </section>
        <TopologyInspector asset={selectedAsset} link={selectedLink} assets={assets} fieldId={activeFieldAsset?.id}
          onFields={(id) => setFieldId((current) => current === id ? undefined : id)} onOpenTask={openTask} />
      </div>
    </div>
  );
}
