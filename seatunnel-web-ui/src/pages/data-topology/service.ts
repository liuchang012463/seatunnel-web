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
