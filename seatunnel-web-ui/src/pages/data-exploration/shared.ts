import type { DataSourceRecord, DataSourceTopologyNode } from '@/pages/data-source/types';
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
  | 'DATABASE'
  | 'MESSAGE'
  | 'SEARCH'
  | 'OBJECT_STORAGE'
  | 'API'
  | 'FILE';

export interface ExplorationTaskCategory {
  key: ExplorationTaskCategoryKey;
  label: string;
  dbTypes: readonly string[];
  supportsExploration: boolean;
}

/** Category tabs for the probe-task configuration page. */
export const EXPLORATION_TASK_CATEGORIES: readonly ExplorationTaskCategory[] = [
  {
    key: 'DATABASE',
    label: '数据库',
    dbTypes: ['MYSQL', 'POSTGRE_SQL', 'ORACLE', 'DORIS', 'DAMENG', 'KINGBASE', 'JDBC', 'H2'],
    supportsExploration: true,
  },
  {
    key: 'MESSAGE',
    label: '消息',
    dbTypes: ['KAFKA'],
    supportsExploration: false,
  },
  {
    key: 'SEARCH',
    label: '搜索',
    dbTypes: ['ELASTICSEARCH'],
    supportsExploration: false,
  },
  {
    key: 'OBJECT_STORAGE',
    label: '对象存储',
    dbTypes: ['S3', 'MINIO'],
    supportsExploration: false,
  },
  {
    key: 'API',
    label: 'API',
    dbTypes: ['HTTP'],
    supportsExploration: false,
  },
  {
    key: 'FILE',
    label: '文件',
    dbTypes: ['SFTP'],
    supportsExploration: false,
  },
];

export const DEFAULT_EXPLORATION_TASK_CATEGORY: ExplorationTaskCategoryKey = 'DATABASE';

export function getExplorationTaskCategory(
  key: ExplorationTaskCategoryKey = DEFAULT_EXPLORATION_TASK_CATEGORY,
): ExplorationTaskCategory {
  return EXPLORATION_TASK_CATEGORIES.find((category) => category.key === key)
    ?? EXPLORATION_TASK_CATEGORIES[0];
}

export function explorationTaskCategorySupportsExploration(
  key: ExplorationTaskCategoryKey = DEFAULT_EXPLORATION_TASK_CATEGORY,
): boolean {
  return getExplorationTaskCategory(key).supportsExploration;
}
