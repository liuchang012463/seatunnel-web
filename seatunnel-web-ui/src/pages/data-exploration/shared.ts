import type { DataSourceRecord, DataSourceTopologyNode } from '@/pages/data-source/types';
import type { TreeDataNode } from 'antd';

export const explorationStatus = (status?: string) => {
  if (status === 'SUCCESS') return { label: '已完成', color: 'success' as const };
  if (status === 'FAILED') return { label: '异常', color: 'error' as const };
  if (status === 'RUNNING' || status === 'QUEUED') return { label: '处理中', color: 'processing' as const };
  return { label: '未探查', color: 'default' as const };
};

export const metadataStatus = (status?: string) => {
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
