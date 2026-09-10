import type { DataSourceRecord, DataSourceTopologyNode } from '@/pages/data-source/types';
import {
  DATA_SOURCE_CATEGORIES,
  type DataSourceCategoryKey,
} from '@/pages/data-source/dataSourceRegistry';
import type { TreeDataNode } from 'antd';

/**
 * Temporarily hide exploration XLSX export until a non-OM-N+1 strategy lands.
 *
 * Findings for follow-up:
 * - Export/overview walk service→database→schema→table, then call
 *   GET /v1/tables/{fqn}/tableProfile/latest?includeColumnProfile=true per table.
 * - OpenMetadata 1.13 (and current TableResource) has no service/database-FQN
 *   bulk profile API; list tables `fields` cannot include `profile`.
 * - Preferred fix: materialize an inventory snapshot after Metadata/Profiler
 *   SUCCESS and serve export/overview from that local read model.
 *
 * Flip to true (and restore UI handlers) when that path is ready.
 * Backend POST /api/v1/data-exploration/export remains available.
 */
export const DATA_EXPLORATION_EXPORT_ENABLED = false;

export const explorationStatus = (status?: string) => {
  if (status === 'SUCCESS') return { label: '已完成', color: 'success' as const };
  if (status === 'FAILED') return { label: '异常', color: 'error' as const };
  if (status === 'RUNNING' || status === 'QUEUED') return { label: '处理中', color: 'processing' as const };
  return { label: '未探查', color: 'default' as const };
};

export const metadataStatus = (status?: string, scanStatus?: string) => {
  if (status === 'READY' && scanStatus === 'FAILED') {
    return { label: '扫描异常', color: 'error' as const };
  }
  if (status === 'READY') return { label: '已就绪', color: 'success' as const };
  if (status === 'SYNCING' || status === 'WAITING' || status === 'PENDING') {
    return { label: '同步中', color: 'processing' as const };
  }
  if (status === 'UNSUPPORTED') return { label: '未接入', color: 'default' as const };
  if (status === 'ERROR') return { label: '同步异常', color: 'error' as const };
  if (status === 'DELETING') return { label: '删除中', color: 'warning' as const };
  return { label: '未初始化', color: 'default' as const };
};

/**
 * `/data-source/all` is a legacy endpoint whose payload is an array, while
 * some test doubles and older gateways wrap it in a pagination object. Keep
 * the page integrations tolerant of both shapes without adding a second API.
 */
export function normalizeDataSourceList(payload: unknown): DataSourceRecord[] {
  if (Array.isArray(payload)) return payload as DataSourceRecord[];
  if (payload && typeof payload === 'object' && Array.isArray((payload as { bizData?: unknown }).bizData)) {
    return (payload as { bizData: DataSourceRecord[] }).bizData;
  }
  return [];
}

export function displayOwner(record: DataSourceRecord) {
  return {
    unit: record.unitName || record.dataSourceUnit || '待归属',
    system: record.businessSystemName || record.systemName || '待归属',
  };
}

export function topologyKey(node: DataSourceTopologyNode) {
  return `${node.nodeType}:${node.id}`;
}

export function topologyTreeData(nodes: DataSourceTopologyNode[]): TreeDataNode[] {
  return nodes.map((node) => ({
    key: topologyKey(node),
    title: node.name || node.id,
    isLeaf: node.nodeType === 'TABLE',
    children: node.children && node.children.length > 0 ? topologyTreeData(node.children) : undefined,
  }));
}

export function replaceTopologyChildren(
  nodes: DataSourceTopologyNode[],
  key: string,
  children: DataSourceTopologyNode[],
): DataSourceTopologyNode[] {
  return nodes.map((node) => {
    if (topologyKey(node) === key) return { ...node, children };
    if (node.children && node.children.length > 0) {
      return { ...node, children: replaceTopologyChildren(node.children, key, children) };
    }
    return node;
  });
}

export function sourceMatches(
  record: DataSourceRecord,
  filters: { unitId?: string; businessSystemId?: string; dataSourceId?: string },
) {
  if (filters.unitId && String(record.unitId ?? '') !== filters.unitId) return false;
  if (filters.businessSystemId && String(record.businessSystemId ?? '') !== filters.businessSystemId) return false;
  if (filters.dataSourceId && String(record.id ?? '') !== filters.dataSourceId) return false;
  return true;
}

export type ExplorationTaskCategoryKey =
  | 'ALL'
  | DataSourceCategoryKey;

export interface ExplorationTaskCategory {
  key: ExplorationTaskCategoryKey;
  label: string;
  dbTypes: readonly string[];
}

/** Same taxonomy as data-source management, plus an "全部" option for the probe task filter. */
export const EXPLORATION_TASK_CATEGORIES: readonly ExplorationTaskCategory[] = [
  { key: 'ALL', label: '全部类型', dbTypes: [] },
  ...DATA_SOURCE_CATEGORIES.map((category) => ({
    key: category.key as ExplorationTaskCategoryKey,
    label: category.label,
    dbTypes: category.dbTypes,
  })),
];

/** Default matches the probe page's database-first workflow. */
export const DEFAULT_EXPLORATION_TASK_CATEGORY: ExplorationTaskCategoryKey = 'RELATIONAL';

/** DbTypes that OpenMetadata profiler / DataExplorationService.context accept. */
export const OM_PROFILER_DB_TYPES = [
  'MYSQL',
  'POSTGRE_SQL',
  'JDBC',
  'DORIS',
  'ORACLE',
  'DAMENG',
  'KINGBASE',
] as const;

export function getExplorationTaskCategory(
  key: ExplorationTaskCategoryKey = DEFAULT_EXPLORATION_TASK_CATEGORY,
): ExplorationTaskCategory {
  return EXPLORATION_TASK_CATEGORIES.find((category) => category.key === key)
    ?? EXPLORATION_TASK_CATEGORIES.find((category) => category.key === DEFAULT_EXPLORATION_TASK_CATEGORY)!;
}

export function supportsOmProfiler(dbType?: string): boolean {
  const normalized = String(dbType || '').trim().toUpperCase();
  return (OM_PROFILER_DB_TYPES as readonly string[]).includes(normalized);
}
