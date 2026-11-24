import { displayOwner, normalizeDataSourceList, withoutSystemManagedSources } from '@/pages/data-exploration/shared';
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

export interface CatalogSource extends LineageSource {
  ready?: boolean;
  tableCatalog: boolean;
  unitId?: string;
  businessSystemId?: string;
  unitName?: string;
  systemName?: string;
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

export async function fetchCatalogSources(): Promise<CatalogSource[]> {
  const records = withoutSystemManagedSources(normalizeDataSourceList(await readMetadata(fetchDataSourceAll())));
  return records.flatMap((record) => {
    if (!record.id) return [];
    const { unit, system } = displayOwner(record);
    return [{
      id: String(record.id),
      name: record.name || String(record.id),
      dbType: record.dbType,
      ready: record.metadataSyncStatus === 'READY',
      tableCatalog: !isAssetExplorationDbType(record.dbType),
      unitId: record.unitId == null ? undefined : String(record.unitId),
      businessSystemId: record.businessSystemId == null ? undefined : String(record.businessSystemId),
      unitName: unit,
      systemName: system,
    }];
  });
}

export function catalogSources(records: CatalogSource[], topology?: Topology) {
  const linked = new Set(topology?.sources.map(({ id }) => id));
  const sources = new Map(records.filter(({ id, ready }) => ready || linked.has(id)).map((item) => [item.id, item]));
  for (const source of topology?.sources ?? []) {
    if (!sources.has(source.id)) {
      sources.set(source.id, { ...source, tableCatalog: !isAssetExplorationDbType(source.dbType ?? undefined) });
    }
  }
  return [...sources.values()];
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

function fetchTableMetadata(asset: LineageAsset) {
  return readMetadata(fetchDataExplorationTable(asset.dataSourceId, asset.id));
}

export type ReadTableMetadata = typeof fetchTableMetadata;

export function createTableMetadataReader(): ReadTableMetadata {
  const requests = new Map<string, Promise<DataExplorationTableDetail>>();
  return (asset) => {
    const existing = requests.get(asset.id);
    if (existing) return existing;
    // The canvas, field view, and inspector share the same in-flight table read.
    const request = fetchTableMetadata(asset).catch((error) => {
      requests.delete(asset.id);
      throw error;
    });
    requests.set(asset.id, request);
    return request;
  };
}

export async function fetchTableDetails(assets: LineageAsset[], signal: AbortSignal, readTable: ReadTableMetadata) {
  const details = new Map<string, DataExplorationTableDetail>();
  const unavailable = new Set<string>();
  const tables = assets.filter(({ type }) => type === 'table');
  // Wide fan-out should not issue hundreds of profile and metadata reads at once.
  for (let start = 0; start < tables.length && !signal.aborted; start += 8) {
    await Promise.all(tables.slice(start, start + 8).map(async (asset) => {
      try {
        details.set(asset.id, await readTable(asset));
      } catch {
        unavailable.add(asset.id);
      }
    }));
  }
  return { details, unavailable };
}
