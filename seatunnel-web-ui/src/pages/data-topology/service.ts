import HttpUtils, { type RequestOptions } from '@/utils/HttpUtils';

export interface DataSourceNode {
  id: string;
  name: string;
  dbType?: string;
  tableCatalogAvailable: boolean;
}

export interface LineageLink {
  sourceId: string;
  targetId: string;
  assetCount?: number | null;
}

export interface Topology {
  nodes: DataSourceNode[];
  edges: LineageLink[];
  readAt?: string | null;
}

export interface LineageAsset {
  id: string;
  name?: string;
  fullyQualifiedName: string;
  dataSourceName: string;
}

export interface TableLineage {
  upstream: LineageAsset[];
  downstream: LineageAsset[];
}

const LINEAGE_URL = '/api/v1/data-source-topology/lineage';
export const topologyRequestOptions: RequestOptions = { timeout: 60_000, skipErrorHandler: true };

async function readLineage<T>(url: string): Promise<T> {
  const response = await HttpUtils.get<T>(url, topologyRequestOptions);
  if (response?.code !== 0 || !response.data) {
    throw new Error('读取血缘失败');
  }
  return response.data;
}

export function fetchTopology() {
  return readLineage<Topology>(LINEAGE_URL);
}

export function fetchTableLineage(dataSourceId: string, tableId: string) {
  const query = new URLSearchParams({ dataSourceId, tableId });
  return readLineage<TableLineage>(`${LINEAGE_URL}/table?${query}`);
}
