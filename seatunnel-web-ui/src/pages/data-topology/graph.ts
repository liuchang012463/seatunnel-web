import type { DataExplorationColumn } from '@/pages/data-source/types';
import type { FieldLink, LineageAsset, LineageLink, Topology } from './service';

export interface GraphLimits {
  upstream: number;
  downstream: number;
  nodesPerLayer: number;
}

export const schemaLimits: GraphLimits = { upstream: 2, downstream: 2, nodesPerLayer: 50 };
export const tableLimits: GraphLimits = { upstream: 3, downstream: 3, nodesPerLayer: 100 };

export function linkId({ sourceId, targetId }: Pick<LineageLink, 'sourceId' | 'targetId'>) {
  return `${sourceId}:${targetId}`;
}

export function lineageNeighborhood(topology: Topology | undefined, roots: LineageAsset[], limits: GraphLimits) {
  const assets = new Map(topology?.assets.map((asset) => [asset.id, asset]));
  const visible = new Map(roots.map((asset) => [asset.id, asset]));
  const rootIds = new Set(visible.keys());
  const links = new Map<string, LineageLink>();
  const incoming = new Map<string, LineageLink[]>();
  const outgoing = new Map<string, LineageLink[]>();
  let truncated = false;

  for (const link of topology?.edges ?? []) {
    if (!assets.has(link.sourceId) || !assets.has(link.targetId)) continue;
    if (rootIds.has(link.sourceId) && rootIds.has(link.targetId)) links.set(linkId(link), link);
    const upstream = incoming.get(link.targetId) ?? [];
    upstream.push(link);
    incoming.set(link.targetId, upstream);
    const downstream = outgoing.get(link.sourceId) ?? [];
    downstream.push(link);
    outgoing.set(link.sourceId, downstream);
  }

  function expand(adjacency: typeof incoming, endpoint: 'sourceId' | 'targetId', depth: number) {
    // Count new assets independently at each hop and in each direction. All
    // schema tables are roots, including tables that have no lineage yet.
    const visited = new Set(rootIds);
    let frontier = [...rootIds];
    for (let hop = 0; hop < depth && frontier.length > 0; hop++) {
      const candidates = frontier.flatMap((id) => adjacency.get(id) ?? []);
      const neighbors = [...new Set(candidates.map((link) => link[endpoint]))]
        .filter((id) => !visited.has(id))
        .sort((a, b) => assets.get(a)!.fullyQualifiedName.localeCompare(assets.get(b)!.fullyQualifiedName)
          || a.localeCompare(b));
      truncated ||= neighbors.length > limits.nodesPerLayer;
      frontier = neighbors.slice(0, limits.nodesPerLayer);
      for (const id of frontier) {
        visited.add(id);
        visible.set(id, assets.get(id)!);
      }
      for (const link of candidates) {
        if (visited.has(link[endpoint])) links.set(linkId(link), link);
      }
    }
  }

  expand(incoming, 'sourceId', limits.upstream);
  expand(outgoing, 'targetId', limits.downstream);
  return { assets: [...visible.values()], links: [...links.values()], truncated };
}

export interface FieldRow {
  fqn: string;
  name: string;
  type?: string;
  incoming?: boolean;
  outgoing?: boolean;
}

export function fieldRows(asset: LineageAsset, columns: DataExplorationColumn[], mappings: FieldLink[]) {
  const connected = new Map<string, FieldRow>();
  function port(fqn: string, direction: 'incoming' | 'outgoing') {
    const suffix = fqn.startsWith(`${asset.fullyQualifiedName}.`)
      ? fqn.slice(asset.fullyQualifiedName.length + 1) : fqn;
    const name = suffix.startsWith('"') && suffix.endsWith('"') ? suffix.slice(1, -1) : suffix;
    const row = connected.get(fqn) ?? { fqn, name };
    row[direction] = true;
    connected.set(fqn, row);
  }
  for (const mapping of mappings) {
    if (mapping.sourceId === asset.id) port(mapping.fromColumn, 'outgoing');
    if (mapping.targetId === asset.id) port(mapping.toColumn, 'incoming');
  }

  // Metadata determines the row order and includes every unmapped column.
  const rows = columns.map((column) => {
    const mapped = column.fullyQualifiedName ? connected.get(column.fullyQualifiedName)
      : [...connected.values()].find(({ name }) => name === column.name);
    if (mapped) connected.delete(mapped.fqn);
    return {
      fqn: mapped?.fqn ?? column.fullyQualifiedName ?? column.name,
      name: column.name,
      type: column.dataTypeDisplay ?? column.dataType,
      incoming: mapped?.incoming,
      outgoing: mapped?.outgoing,
    };
  });
  return [...rows, ...connected.values()];
}
