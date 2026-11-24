import type { XYPosition } from 'reactflow';

const COLUMN_GAP = 44;
const ROW_GAP = 24;

export function topologyPositions(
  nodes: { id: string; width: number; height: number; offsetY?: number }[],
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
  const widths = new Map<number, number>();
  const columns = new Map<string, number>();
  let unranked = 0;
  for (const { id, width } of nodes) {
    if (!linked.has(id)) continue;
    const column = depth.get(id) ?? unranked++ % 3;
    columns.set(id, column);
    widths.set(column, Math.max(width, widths.get(column) ?? 0));
  }
  const left = new Map<number, number>();
  let nextX = 0;
  for (const [column, width] of [...widths].sort(([a], [b]) => a - b)) {
    left.set(column, nextX);
    nextX += width + COLUMN_GAP;
  }
  for (const { id, width, height, offsetY = 0 } of nodes) {
    const column = columns.get(id);
    if (column === undefined) continue;
    const y = nextRow.get(column) ?? 0;
    nextRow.set(column, y + offsetY + height + ROW_GAP);
    positions.set(id, { x: left.get(column)! + (widths.get(column)! - width) / 2, y: y + offsetY });
  }
  const startRow = Math.max(0, ...nextRow.values());
  const isolated = nodes.filter(({ id }) => !linked.has(id));
  const isolatedWidth = Math.max(0, ...isolated.map(({ width }) => width));
  const isolatedRows = new Map<number, number>();
  isolated.forEach(({ id, height }, index) => {
    const column = index % 3;
    const y = isolatedRows.get(column) ?? startRow;
    positions.set(id, { x: column * (isolatedWidth + COLUMN_GAP), y });
    isolatedRows.set(column, y + height + ROW_GAP);
  });
  return positions;
}
