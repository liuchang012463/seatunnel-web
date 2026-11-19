import type { XYPosition } from 'reactflow';
import type { Topology } from './service';

const COLUMN_GAP = 300;
const ROW_GAP = 86;

export function topologyPositions({ nodes, edges }: Topology): Map<string, XYPosition> {
  const incoming = new Map(nodes.map(({ id }) => [id, 0]));
  const targets = new Map<string, string[]>();
  const linked = new Set<string>();
  for (const { sourceId, targetId } of edges) {
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
  const rowsByColumn = new Map<number, number>();
  let unranked = 0;
  for (const { id } of nodes) {
    if (!linked.has(id)) continue;
    // Real lineage can contain cycles. Place nodes without a rank in a stable order.
    const column = depth.get(id) ?? unranked++ % 3;
    const row = rowsByColumn.get(column) ?? 0;
    rowsByColumn.set(column, row + 1);
    positions.set(id, { x: column * COLUMN_GAP, y: row * ROW_GAP });
  }
  const startRow = Math.max(0, ...rowsByColumn.values());
  nodes.filter(({ id }) => !linked.has(id)).forEach(({ id }, index) => {
    positions.set(id, {
      x: (index % 3) * COLUMN_GAP,
      y: (startRow + Math.floor(index / 3)) * ROW_GAP,
    });
  });
  return positions;
}
