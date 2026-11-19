import type { FieldLink, LineageAsset, LineageLink, LineageSource, Topology } from './service';

export type Level = 'source' | 'database' | 'schema' | 'table';
export const levels: Level[] = ['source', 'database', 'schema', 'table'];

export interface Group {
  id: string;
  kind: Level | 'asset';
  name: string;
  source: LineageSource;
  parentId?: string;
  asset?: LineageAsset;
}

export interface Hierarchy {
  groups: Map<string, Group>;
  paths: Map<string, Group[]>;
}

export function buildHierarchy(topology?: Topology): Hierarchy {
  const groups = new Map<string, Group>();
  const paths = new Map<string, Group[]>();
  const sources = new Map(topology?.sources.map((source) => [source.id, source]));
  function group(value: Group) {
    const existing = groups.get(value.id);
    if (existing) return existing;
    groups.set(value.id, value);
    return value;
  }
  for (const asset of topology?.assets ?? []) {
    const source = sources.get(asset.dataSourceId);
    if (!source) continue;
    const root = group({ id: `source:${source.id}`, kind: 'source', name: source.name, source });
    const path = [root];
    if (asset.type === 'table' && asset.database && asset.schema) {
      const database = group({
        id: `database:${source.id}:${encodeURIComponent(asset.database)}`,
        kind: 'database', name: asset.database, source, parentId: root.id,
      });
      path.push(database, group({
        id: `schema:${database.id}:${encodeURIComponent(asset.schema)}`,
        kind: 'schema', name: asset.schema, source, parentId: database.id,
      }));
    }
    path.push(group({
      id: `asset:${asset.id}`, kind: asset.type === 'table' ? 'table' : 'asset',
      name: asset.name, source, asset, parentId: path.at(-1)?.id,
    }));
    paths.set(asset.id, path);
  }
  return { groups, paths };
}

export function ancestry(hierarchy: Hierarchy, id: string | undefined): Group[] {
  const parents: Group[] = [];
  let group = id ? hierarchy.groups.get(id) : undefined;
  while (group) {
    parents.unshift(group);
    group = group.parentId ? hierarchy.groups.get(group.parentId) : undefined;
  }
  return parents;
}

export function linksInFilter(edges: LineageLink[], hierarchy: Hierarchy, filterId?: string) {
  if (!filterId) return edges;
  const matches = (assetId: string) => hierarchy.paths.get(assetId)?.some(({ id }) => id === filterId);
  // Either end can match. The other end stays on screen so a filter never breaks a flow.
  return edges.filter(({ sourceId, targetId }) => matches(sourceId) || matches(targetId));
}

export function openPaths(edges: LineageLink[], hierarchy: Hierarchy, level: number): Set<string> {
  const opened = new Set<string>();
  for (const edge of edges) {
    for (const assetId of [edge.sourceId, edge.targetId]) {
      const path = hierarchy.paths.get(assetId);
      path?.slice(0, Math.min(level, path.length - 1)).forEach(({ id }) => opened.add(id));
    }
  }
  return opened;
}

export function linkId({ sourceId, targetId }: Pick<LineageLink, 'sourceId' | 'targetId'>) {
  return `${sourceId}:${targetId}`;
}

export function taskId(task: NonNullable<LineageLink['task']>) {
  return `${task.kind}:${task.id}`;
}

export function projectGraph(edges: LineageLink[], hierarchy: Hierarchy, opened: Set<string>, fields: FieldLink[]) {
  const groups = new Map<string, { group: Group; assets: Set<string>; internalCount: number }>();
  const tasks = new Map<string, LineageLink>();
  const pairs = new Set<string>();
  const links = new Map<string, { id: string; source: string; target: string; count?: number }>();
  const displayed = (assetId: string) => {
    const path = hierarchy.paths.get(assetId);
    const group = path?.find(({ id }) => !opened.has(id));
    if (!group) return undefined;
    const node = groups.get(group.id) ?? { group, assets: new Set<string>(), internalCount: 0 };
    node.assets.add(assetId);
    groups.set(group.id, node);
    return node;
  };

  for (const edge of edges) {
    const from = displayed(edge.sourceId);
    const to = displayed(edge.targetId);
    if (!from || !to) continue;
    const source = from.group.id;
    const target = to.group.id;
    if (from.group.asset && to.group.asset) {
      const pair = linkId(edge);
      const task = `task:${pair}`;
      tasks.set(task, edge);
      pairs.add(pair);
      links.set(`into:${pair}`, { id: `into:${pair}`, source, target: task });
      links.set(`out:${pair}`, { id: `out:${pair}`, source: task, target });
    } else if (source === target) {
      from.internalCount++;
    } else {
      const id = `summary:${source}:${target}`;
      const link = links.get(id);
      links.set(id, { id, source, target, count: (link?.count ?? 0) + 1 });
    }
  }
  return {
    groups: [...groups.values()],
    tasks: [...tasks.entries()],
    links: [...links.values()],
    fields: fields.filter((field) => pairs.has(linkId(field))),
  };
}
