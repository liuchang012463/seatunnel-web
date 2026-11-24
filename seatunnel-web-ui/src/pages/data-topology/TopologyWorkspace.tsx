import type { DataExplorationTable } from '@/pages/data-source/types';
import { ApartmentOutlined, ArrowLeftOutlined, PartitionOutlined, SettingOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { Alert, Button, Empty, InputNumber, Popover, Spin } from 'antd';
import { useCallback, useMemo, useState } from 'react';
import 'reactflow/dist/style.css';
import {
  fieldRows, groupLineageTasks, lineageNeighborhood, linkId, schemaLimits, tableLimits, taskNodeId, type GraphLimits,
} from './graph';
import {
  createTableMetadataReader, fetchFieldLineage, fetchTableDetails, type CatalogSource, type FieldLink, type LineageAsset,
  type LineageLink, type LineageTask, type Topology,
} from './service';
import TopologyBrowser from './TopologyBrowser';
import TopologyCanvas from './TopologyCanvas';
import TopologyInspector from './TopologyInspector';
import type { FieldView } from './TopologyNodes';
import useLineageRead from './useLineageRead';
import useTopologyCatalog, { type CatalogScope } from './useTopologyCatalog';

const noMappings: FieldLink[] = [];
const graphOptions = [
  { key: 'upstream', label: '上游深度', min: 0 },
  { key: 'downstream', label: '下游深度', min: 0 },
  { key: 'nodesPerLayer', label: '每层节点数', min: 1 },
] as const;

function restoreView(search: string) {
  const query = new URLSearchParams(search);
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
    focusedId, selectedId: get('node') ?? focusedId, fieldId: get('fields'), pairId: get('pair'), limits,
  };
}

function GraphSettings({ limits, onChange, onLayout }: {
  limits: GraphLimits;
  onChange: (limits: GraphLimits) => void;
  onLayout: () => void;
}) {
  function setting({ key, label, min }: typeof graphOptions[number]) {
    return (
      <label key={key}>
        {label}
        <InputNumber size="small" aria-label={label} min={min} precision={0} value={limits[key]}
          onChange={(value) => {
            if (value !== null && Number.isSafeInteger(value) && value >= min) {
              onChange({ ...limits, [key]: value });
            }
          }} />
      </label>
    );
  }
  return (
    <div className="data-topology-settings">
      {graphOptions.slice(0, 2).map(setting)}
      <Button size="small" icon={<PartitionOutlined />} onClick={onLayout}>自动布局</Button>
      <Popover title="画布设置" content={<div className="data-topology-limit-popover">{setting(graphOptions[2])}</div>}
        trigger="click" placement="bottomRight">
        <Button size="small" icon={<SettingOutlined />} aria-label="画布设置" title="画布设置" />
      </Popover>
    </div>
  );
}

export default function TopologyWorkspace({ sources, source, topology, topologyLoading, revision, onSourceChange }: {
  sources: CatalogSource[];
  source: CatalogSource;
  topology?: Topology;
  topologyLoading: boolean;
  revision: number;
  onSourceChange: (id: string) => void;
}) {
  const [initial] = useState(() => restoreView(history.location.search));
  const [scope, setScope] = useState<CatalogScope>(initial.scope);
  const [focusedId, setFocusedId] = useState(initial.focusedId);
  const [selectedId, setSelectedId] = useState(initial.selectedId);
  const [fieldId, setFieldId] = useState(initial.fieldId);
  const [pairId, setPairId] = useState(initial.pairId);
  const [overviewSettings, setOverviewSettings] = useState(initial.focusedId ? schemaLimits : initial.limits);
  const [tableSettings, setTableSettings] = useState(initial.focusedId ? initial.limits : tableLimits);
  const [layout, setLayout] = useState(0);
  const readTable = useMemo(() => createTableMetadataReader(), [revision]);
  const catalog = useTopologyCatalog(scope, source, topology, revision);
  const focus = catalog.roots.find(({ id }) => id === focusedId);
  const limits = focus ? tableSettings : overviewSettings;
  const graph = useMemo(() => lineageNeighborhood(topology, focus ? [focus] : catalog.roots, limits),
    [catalog.roots, focus, limits, topology]);
  const catalogAssets = useMemo(() => new Map(topology?.assets.map((asset) => [asset.id, asset])), [topology]);
  const tasks = useMemo(() => groupLineageTasks(topology?.edges ?? []), [topology]);
  // Older history entries identified tasks by a table pair.
  const legacyTask = selectedId?.startsWith('task:') && !tasks.has(selectedId)
    ? topology?.edges.find((link) => `task:${linkId(link)}` === selectedId)?.task : undefined;
  const selection = legacyTask ? taskNodeId(legacyTask) : selectedId;
  const groupedTasks = useMemo(() => new Set([...tasks].filter(([, group]) => group.links.length > 1)
    .map(([id]) => id)), [tasks]);
  const visibleGroups = useMemo(() => [...groupLineageTasks(graph.links).keys()]
    .filter((id) => groupedTasks.has(id)).map((id) => tasks.get(id)!), [graph.links, groupedTasks, tasks]);
  const activePair = pairId ? topology?.edges.find((link) => linkId(link) === pairId
    && catalogAssets.has(link.sourceId) && catalogAssets.has(link.targetId)) : undefined;
  const canvas = useMemo(() => activePair ? {
    assets: [...new Set([activePair.sourceId, activePair.targetId])].map((id) => catalogAssets.get(id)!),
    links: [activePair],
  } : graph, [activePair, catalogAssets, graph]);
  const assets = useMemo(() => new Map(canvas.assets.map((asset) => [asset.id, asset])), [canvas.assets]);
  const activeFieldAsset = fieldId ? assets.get(fieldId) : undefined;
  const fieldScope = useMemo(() => {
    if (!activeFieldAsset) return undefined;
    const pairs = new Set<string>();
    const ids = new Set([activeFieldAsset.id]);
    for (const link of canvas.links) {
      if (link.sourceId !== activeFieldAsset.id && link.targetId !== activeFieldAsset.id) continue;
      pairs.add(linkId(link));
      ids.add(link.sourceId);
      ids.add(link.targetId);
    }
    return { pairs, assets: [...ids].map((id) => assets.get(id)!) };
  }, [activeFieldAsset, assets, canvas.links]);

  const loadMappings = useMemo(() => activeFieldAsset && fieldScope?.pairs.size
    ? () => fetchFieldLineage(activeFieldAsset) : undefined, [activeFieldAsset, fieldScope?.pairs.size]);
  const loadColumns = useMemo(() => fieldScope
    ? (signal: AbortSignal) => fetchTableDetails(fieldScope.assets, signal, readTable) : undefined, [fieldScope, readTable]);
  const mappingResult = useLineageRead(loadMappings, revision);
  const columnResult = useLineageRead(loadColumns, revision);
  const listedTables = useMemo(() => new Map(catalog.tables.data?.map((table) => [table.id, table])), [catalog.tables.data]);
  const readNeighbors = useMemo(() => {
    const neighbors = canvas.assets.filter(({ type, id }) => type === 'table' && !listedTables.has(id));
    return neighbors.length ? (signal: AbortSignal) => fetchTableDetails(neighbors, signal, readTable) : undefined;
  }, [canvas.assets, listedTables, readTable]);
  const neighborDetails = useLineageRead(readNeighbors, revision).data;
  const summaries = useMemo(() => {
    const result = new Map<string, DataExplorationTable>(listedTables);
    for (const tables of [neighborDetails, columnResult.data]) {
      for (const [id, table] of tables?.details ?? []) {
        result.set(id, { ...table, columnCount: table.columns.length });
      }
    }
    return result;
  }, [columnResult.data, listedTables, neighborDetails]);
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
    setPairId(undefined);
  }

  function changeScope(next: CatalogScope) {
    if (next.sourceId && next.sourceId !== source.id) {
      onSourceChange(next.sourceId);
      return;
    }
    setScope(next);
    focusTable();
  }

  const toggleFields = useCallback((id: string) => {
    setSelectedId(id);
    setFieldId(fields.has(id) ? undefined : id);
  }, [fields]);

  const openPair = useCallback((link: LineageLink) => {
    setPairId(linkId(link));
    setFieldId(link.sourceId);
    setSelectedId(link.task ? taskNodeId(link.task) : link.sourceId);
  }, []);

  function closePair() {
    setPairId(undefined);
    setFieldId(undefined);
  }

  function openTask(task: LineageTask) {
    // Keep the current graph on the history entry so Back returns to this view.
    const query = new URLSearchParams(history.location.search);
    const values = {
      dataSourceId: source.id,
      database: catalog.database?.fullyQualifiedName,
      schema: catalog.schema?.fullyQualifiedName,
      tableId: focus?.id,
      node: selection,
      fields: activeFieldAsset?.id,
      pair: activePair && linkId(activePair),
      ...Object.fromEntries(graphOptions.map(({ key }) => [key, String(limits[key])])),
    };
    for (const [key, value] of Object.entries(values)) {
      if (value) query.set(key, value);
      else query.delete(key);
    }
    history.replace({ pathname: history.location.pathname, search: `?${query}` });
    const list = task.kind === 'STREAMING' ? 'stream-link-up' : 'batch-link-up';
    history.push(`/sync/${list}?id=${encodeURIComponent(task.id)}`);
  }

  const busy = topologyLoading || catalog.loading;
  const scopeFqn = catalog.schema?.fullyQualifiedName;
  const title = activePair ? '表对血缘' : focus?.name ?? (source.tableCatalog ? 'Schema 血缘总览' : '数据源血缘');
  const selectedAsset: LineageAsset | undefined = selection ? assets.get(selection) : undefined;
  const selectedGroup = selection ? tasks.get(selection) : undefined;
  const resetLayout = () => setLayout((current) => current + 1);
  const fieldLabel = mappingResult.loading ? '正在读取字段映射'
    : mappingResult.failed ? '字段映射读取失败'
      : mappings.length > 0 ? `${mappings.length} 条字段映射` : '暂无字段映射';

  return (
    <>
      {catalog.failed && <Alert showIcon type="warning" message="读取元数据目录失败，请刷新重试。" />}
      <div className="data-topology-workspace">
        <TopologyBrowser catalog={catalog} sources={sources} focusedId={focus?.id} onScope={changeScope}
          onFocus={focusTable} onOverview={() => focusTable()} />
        <section className="data-topology-graph" aria-label={title}>
          <header className="data-topology-graph-header">
            <div className="data-topology-graph-title">
              <h2 title={title}>{title}</h2>
              {scopeFqn && <small title={scopeFqn}>{scopeFqn}</small>}
              <span role="status">{canvas.assets.length} 个资产 · {canvas.links.length} 条血缘
                {!activePair && graph.truncated && ' · 已达到每层节点上限'}
              </span>
            </div>
            {activePair ? <Button size="small" icon={<PartitionOutlined />} onClick={resetLayout}>自动布局</Button>
              : <GraphSettings limits={limits} onChange={focus ? setTableSettings : setOverviewSettings}
                onLayout={resetLayout} />}
          </header>
          {!activePair && visibleGroups.length > 0 && <div className="data-topology-task-strip" aria-label="多表任务">
            {visibleGroups.map(({ task, links }) => {
              const id = taskNodeId(task);
              const name = task.name || `任务 ${task.id}`;
              return (
                <button key={id} type="button" title={name} aria-pressed={selection === id}
                  onClick={() => setSelectedId(id)}>
                  <ApartmentOutlined /><strong>{name}</strong><span>{links.length} 对表</span>
                </button>
              );
            })}
          </div>}
          {(activeFieldAsset || activePair) && <div className="data-topology-field-toolbar">
            {activePair && <Button type="text" size="small" icon={<ArrowLeftOutlined />} onClick={closePair}>返回关系图</Button>}
            <span title={activeFieldAsset?.fullyQualifiedName}>
              {activePair
                ? <strong>{assets.get(activePair.sourceId)?.name} → {assets.get(activePair.targetId)?.name}</strong>
                : <strong>{activeFieldAsset?.name}</strong>}
              {activeFieldAsset && <small role="status">{fieldLabel}</small>}
            </span>
            {activeFieldAsset && <Button type="text" size="small" onClick={() => setFieldId(undefined)}>收起字段</Button>}
          </div>}
          <div className="data-topology-canvas">
            {canvas.assets.length ? <TopologyCanvas
              key={JSON.stringify([scopeFqn, focus?.id, activePair && linkId(activePair), layout])}
              assets={canvas.assets} links={canvas.links} sources={topology?.sources ?? sources} summaries={summaries}
              focusedId={focus?.id} selectedId={selection} fields={fields} mappings={mappings} onSelect={setSelectedId}
              groupedTasks={groupedTasks} onFields={toggleFields} onPair={openPair}
            /> : <div className="data-topology-empty">
              {busy ? <Spin aria-label="正在读取血缘" />
                : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={
                  !source.tableCatalog ? '该数据源暂无血缘' : scopeFqn ? '该 Schema 暂无数据表' : '暂无可用的 Schema'
                } />}
            </div>}
          </div>
        </section>
        <TopologyInspector asset={selectedAsset} group={selectedGroup} assets={catalogAssets} pairId={pairId}
          expanded={Boolean(selectedId && fields.has(selectedId))}
          summary={selectedId ? summaries.get(selectedId) : undefined} readTable={readTable}
          onFields={toggleFields} onOpenTask={openTask} onPair={openPair} />
      </div>
    </>
  );
}
