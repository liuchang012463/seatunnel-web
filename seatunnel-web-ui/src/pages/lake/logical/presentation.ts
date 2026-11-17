import type { LakeCatalog, LakeQueryColumnOption, LakeReadOnlyQueryResult } from '@/services/lake';

const STATUS_LABELS: Record<string, string> = {
  READY: '可查询',
  MATCH: '配置一致',
  VALID: '检查通过',
  CONSISTENT: '配置一致',
  MISMATCH: '配置有变更',
  INVALID: '检查未通过',
  ERROR: '需要处理',
  FAILED: '操作失败',
  CREATE_FAILED: '创建失败',
  MISSING: '挂载不存在',
  UNKNOWN: '待检查',
  PENDING_CREATE: '等待创建',
  CREATING: '创建中',
  RUNNING: '处理中',
  DELETING: '移除中',
  DELETED: '已移除',
};

export const catalogStatusLabel = (status?: string) => STATUS_LABELS[status || ''] || '待检查';
export const catalogStatusColor = (status?: string) => {
  if (['READY', 'MATCH', 'VALID', 'CONSISTENT'].includes(status || '')) return 'success';
  if (['ERROR', 'FAILED', 'INVALID', 'CREATE_FAILED', 'MISSING'].includes(status || '')) return 'error';
  if (['PENDING_CREATE', 'CREATING', 'RUNNING', 'DELETING'].includes(status || '')) return 'processing';
  if (status === 'MISMATCH' || status === 'UNKNOWN') return 'warning';
  return 'default';
};

export const adapterLabel = (adapter?: string) => {
  if (adapter === 'MYSQL') return 'MySQL';
  if (adapter === 'POSTGRESQL' || adapter === 'POSTGRE_SQL') return 'PostgreSQL';
  if (adapter === 'ORACLE') return 'Oracle';
  return adapter || '未知类型';
};

export const catalogScopeLabel = (catalog: LakeCatalog) => {
  if (catalog.scope === 'ALL') return '全部库表';
  if (catalog.scope === 'TABLE') return `${catalog.tableInclude?.length || 0} 张指定表`;
  if (catalog.scope === 'DATABASE')
    return `${catalog.databaseInclude?.join('、') || '未选择'}（${
      catalog.adapter === 'MYSQL' ? '整库' : '整个 Schema'
    }）`;
  return '未设置';
};

/** Defaults stay inside the server's selectable allowlist and bound the initial preview width. */
export const defaultQueryColumns = (columns: LakeQueryColumnOption[]) =>
  columns
    .filter((column) => column.selectable !== false)
    .slice(0, 20)
    .map((column) => column.name);

export type DisplayQueryResult = LakeReadOnlyQueryResult & {
  columnTitles?: Record<string, string>;
};

/** Keep API aliases as row keys; human-readable titles also distinguish the two sides of a join. */
export const labelQueryResult = (
  result: LakeReadOnlyQueryResult,
  left: string[],
  right?: string[],
): DisplayQueryResult => ({
  ...result,
  columnTitles: result.explain
    ? {}
    : Object.fromEntries([
        ...left.map((name, index) => [right ? `left_c${index}` : `c${index}`, right ? `左表 · ${name}` : name]),
        ...(right || []).map((name, index) => [`right_c${index}`, `右表 · ${name}`]),
      ]),
});
