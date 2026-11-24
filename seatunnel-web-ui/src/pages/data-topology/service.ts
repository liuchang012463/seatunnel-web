import { normalizeDataSourceList, withoutSystemManagedSources } from '@/pages/data-exploration/shared';
import { fetchDataExplorationTable, fetchDataExplorationTables, fetchDataSourceAll } from '@/pages/data-source/service';
import type { CommonApiResponse, DataExplorationTable, DataExplorationTableDetail } from '@/pages/data-source/types';
import { isAssetExplorationDbType } from '@/utils/assetExplorationStatus';
import HttpUtils, { type RequestOptions } from '@/utils/HttpUtils';
import type { TaskKind } from '@/pages/common/workflow/TaskDefinitionView';

export interface LineageSource {
  id: string;
  name: string;
  dbType?: string | null;
}

export interface LineageAsset {
  id: string;
  dataSourceId: string;
  type: string;
  name: string;
  database?: string | null;
  schema?: string | null;
  fullyQualifiedName: string;
}

export interface LineageTask {
  id: string;
  kind: TaskKind;
  name?: string | null;
  deleted: boolean;
}

export interface LineageLink {
  sourceId: string;
  targetId: string;
  task?: LineageTask | null;
}

export interface Topology {
  sources: LineageSource[];
  assets: LineageAsset[];
  edges: LineageLink[];
  readAt?: string | null;
}

export interface FieldLink {
  sourceId: string;
  targetId: string;
  fromColumn: string;
  toColumn: string;
}

const LINEAGE_URL = '/api/v1/data-source-topology/lineage';
const topologyRequestOptions: RequestOptions = { timeout: 60_000, skipErrorHandler: true };

async function readLineage<T>(url: string): Promise<T> {
  const response = await HttpUtils.get<T>(url, topologyRequestOptions);
  if (response?.code !== 0 || !response.data) {
    throw new Error('读取血缘失败');
  }
  return response.data;
}

export async function fetchTopology() {
  const graph = await readLineage<Topology>(LINEAGE_URL);
  if (!Array.isArray(graph.sources) || !Array.isArray(graph.assets) || !Array.isArray(graph.edges)) {
    throw new Error('血缘响应格式无效');
  }
  return graph;
}

export function fetchFieldLineage(asset: LineageAsset) {
  const query = new URLSearchParams({ entityType: asset.type, entityId: asset.id });
  return readLineage<FieldLink[]>(`${LINEAGE_URL}/fields?${query}`);
}

export async function readMetadata<T>(request: Promise<CommonApiResponse<T>>) {
  const response = await request;
  if (response?.code !== 0 || !response.data) throw new Error('读取元数据失败');
  return response.data;
}

export async function fetchCatalogSources(): Promise<LineageSource[]> {
  const records = withoutSystemManagedSources(normalizeDataSourceList(await readMetadata(fetchDataSourceAll())));
  return records.flatMap(({ id, name, dbType, metadataSyncStatus }) =>
    id && metadataSyncStatus === 'READY' && !isAssetExplorationDbType(dbType)
      ? [{ id: String(id), name: name || String(id), dbType }]
      : [],
  );
}

export async function fetchSchemaTables(sourceId: string, databaseFqn: string, schemaFqn: string, signal: AbortSignal) {
  const tables = new Map<string, DataExplorationTable>();
  const pageSize = 100;
  for (let pageNo = 1; !signal.aborted; pageNo++) {
    const page = await readMetadata(fetchDataExplorationTables(sourceId, databaseFqn, schemaFqn, pageNo, pageSize));
    const previousSize = tables.size;
    for (const table of page.records) tables.set(table.id, table);
    if (tables.size === previousSize || pageNo * pageSize >= page.total) break;
  }
  return [...tables.values()];
}

export function fetchTableMetadata(asset: LineageAsset) {
  return readMetadata(fetchDataExplorationTable(asset.dataSourceId, asset.id));
}

export async function fetchFieldMetadata(assets: LineageAsset[], signal: AbortSignal) {
  const details = new Map<string, DataExplorationTableDetail>();
  const unavailable = new Set<string>();
  const tables = assets.filter(({ type }) => type === 'table');
  // Wide fan-out should not issue hundreds of profile and metadata reads at once.
  for (let start = 0; start < tables.length && !signal.aborted; start += 8) {
    await Promise.all(tables.slice(start, start + 8).map(async (asset) => {
      try {
        details.set(asset.id, await fetchTableMetadata(asset));
      } catch {
        unavailable.add(asset.id);
      }
    }));
  }
  return { details, unavailable };
}
