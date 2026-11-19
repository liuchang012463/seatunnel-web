import type { XYPosition } from 'reactflow';

const COLUMN_GAP = 345;
const ROW_GAP = 34;

export function topologyPositions(
  nodes: { id: string; height: number }[],
  edges: { source: string; target: string }[],
): Map<string, XYPosition> {
  const incoming = new Map(nodes.map(({ id }) => [id, 0]));
  const targets = new Map<string, string[]>();
  const linked = new Set<string>();
  for (const { source: sourceId, target: targetId } of edges) {
    const children = targets.get(sourceId) ?? [];
    children.push(targetId);
    targets.set(sourceId, children);
    incoming.set(targetId, (incoming.get(targetId) ?? 0) + 1);
    linked.add(sourceId);
    linked.add(targetId);
  }

  const queue = nodes.filter(({ id }) => linked.has(id) && incoming.get(id) === 0).map(({ id }) => id);
  const depth = new Map(queue.map((id) => [id, 0]));
  for (let index = 0; index < queue.length; index++) {
    const source = queue[index];
    const nextDepth = (depth.get(source) ?? 0) + 1;
    for (const target of targets.get(source) ?? []) {
      depth.set(target, Math.max(depth.get(target) ?? 0, nextDepth));
      const remaining = (incoming.get(target) ?? 0) - 1;
      incoming.set(target, remaining);
      if (remaining === 0) queue.push(target);
    }
  }

  const positions = new Map<string, XYPosition>();
  const nextRow = new Map<number, number>();
  let unranked = 0;
  for (const { id, height } of nodes) {
    if (!linked.has(id)) continue;
    // Real lineage can contain cycles. Place nodes without a rank in a stable order.
    const column = depth.get(id) ?? unranked++ % 3;
    const y = nextRow.get(column) ?? 0;
    nextRow.set(column, y + height + ROW_GAP);
    positions.set(id, { x: column * COLUMN_GAP, y });
  }
  // A visible group with only internal lineage still needs its own card.
  const startRow = Math.max(0, ...nextRow.values());
  nodes.filter(({ id }) => !linked.has(id)).forEach(({ id, height }, index) => {
    const column = index % 3;
    const y = Math.max(startRow, nextRow.get(column) ?? 0);
    positions.set(id, { x: column * COLUMN_GAP, y });
    nextRow.set(column, y + height + ROW_GAP);
  });
  return positions;
}
