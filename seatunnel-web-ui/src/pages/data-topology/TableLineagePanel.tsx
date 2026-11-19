import { fetchDataSourceTopologyChildren } from '@/pages/data-source/service';
import type { DataSourceTopologyNode } from '@/pages/data-source/types';
import { Alert, Button, Cascader, Spin } from 'antd';
import { useEffect, useState } from 'react';
import { fetchTableLineage, type LineageAsset, type TableLineage, topologyRequestOptions } from './service';

interface TableOption {
  value: string;
  label: string;
  nodeType: DataSourceTopologyNode['nodeType'];
  isLeaf: boolean;
  disabled?: boolean;
  children?: TableOption[];
}

function toTableOption({ id, name, nodeType }: DataSourceTopologyNode): TableOption {
  return { value: id, label: name || id, nodeType, isLeaf: nodeType === 'TABLE' };
}

async function loadCatalog(nodeType: TableOption['nodeType'], id: string) {
  const result = await fetchDataSourceTopologyChildren(nodeType, id, topologyRequestOptions);
  if (result?.code !== 0 || !Array.isArray(result.data)) {
    throw new Error('读取库表目录失败');
  }
  return result.data.map(toTableOption);
}

function AssetList({ title, assets }: { title: string; assets: LineageAsset[] }) {
  return (
    <section>
      <h4>{title}</h4>
      {assets.length === 0 ? <p className="data-topology-muted">暂无关系</p> : (
        <ul className="data-topology-assets">
          {assets.map((asset) => (
            <li key={asset.id}>
              <div>{asset.dataSourceName} · {asset.name || asset.fullyQualifiedName}</div>
              <div className="data-topology-muted">{asset.fullyQualifiedName}</div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export default function TableLineagePanel({ dataSourceId, readAt }: { dataSourceId: string; readAt?: string | null }) {
  const [options, setOptions] = useState<TableOption[]>([]);
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [catalogError, setCatalogError] = useState(false);
  const [catalogAttempt, setCatalogAttempt] = useState(0);
  const [tableId, setTableId] = useState<string>();
  const [detail, setDetail] = useState<TableLineage>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState(false);
  const [detailAttempt, setDetailAttempt] = useState(0);

  useEffect(() => {
    let current = true;
    setCatalogLoading(true);
    setCatalogError(false);
    loadCatalog('DATA_SOURCE', dataSourceId)
      .then((items) => { if (current) setOptions(items); })
      .catch(() => { if (current) setCatalogError(true); })
      .finally(() => { if (current) setCatalogLoading(false); });
    return () => { current = false; };
  }, [dataSourceId, catalogAttempt]);

  useEffect(() => {
    if (!tableId) return;
    let current = true;
    setDetail(undefined);
    setDetailLoading(true);
    setDetailError(false);
    fetchTableLineage(dataSourceId, tableId)
      .then((lineage) => { if (current) setDetail(lineage); })
      .catch(() => { if (current) setDetailError(true); })
      .finally(() => { if (current) setDetailLoading(false); });
    return () => { current = false; };
  }, [dataSourceId, tableId, detailAttempt, readAt]);

  async function loadChildren(path: TableOption[]) {
    const parent = path.at(-1);
    if (!parent || parent.children) return;
    try {
      parent.children = await loadCatalog(parent.nodeType, parent.value);
      // An empty database or schema can be expanded, but cannot be selected as a table.
      if (parent.children.length === 0) parent.disabled = parent.isLeaf = true;
      setOptions((previous) => [...previous]);
    } catch {
      setCatalogError(true);
    }
  }

  return (
    <section className="data-topology-table">
      <h3>表级血缘</h3>
      {catalogError ? (
        <Alert showIcon type="error" message="读取库表目录失败" action={
          <Button onClick={() => { setTableId(undefined); setCatalogAttempt((attempt) => attempt + 1); }}>重试</Button>
        } />
      ) : (
        <Cascader<TableOption, 'value'>
          key={catalogAttempt}
          aria-label="选择血缘表"
          placeholder={catalogLoading || options.length > 0 ? '选择库、模式和表' : '暂无可选表'}
          disabled={catalogLoading || options.length === 0}
          loading={catalogLoading}
          options={options}
          loadData={loadChildren}
          onChange={(path) => {
            const selected = path?.at(-1);
            const next = typeof selected === 'string' ? selected : undefined;
            if (next !== tableId) {
              setDetail(undefined);
              setDetailError(false);
              setTableId(next);
            }
          }}
        />
      )}
      {tableId && (
        detailLoading ? <Spin /> : detailError ? (
          <Alert showIcon type="error" message="读取表级血缘失败" action={
            <Button onClick={() => setDetailAttempt((attempt) => attempt + 1)}>重试</Button>
          } />
        ) : detail && (
          <>
            <AssetList title="直接上游" assets={detail.upstream} />
            <AssetList title="直接下游" assets={detail.downstream} />
          </>
        )
      )}
    </section>
  );
}
