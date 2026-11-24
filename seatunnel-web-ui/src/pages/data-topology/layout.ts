import type { XYPosition } from 'reactflow';

const COLUMN_GAP = 92;
const ROW_GAP = 24;

interface LayoutNode {
  id: string;
  width: number;
  height: number;
  offsetY?: number;
  tablePair?: { sourceId: string; targetId: string };
}

export function topologyPositions(
  nodes: LayoutNode[],
  edges: { source: string; target: string }[],
): Map<string, XYPosition> {
  const incoming = new Map(nodes.map(({ id }) => [id, 0]));
  const targets = new Map<string, string[]>();
  const neighbors = new Map<string, Set<string>>();
  for (const { source: sourceId, target: targetId } of edges) {
    const children = targets.get(sourceId) ?? [];
    children.push(targetId);
    targets.set(sourceId, children);
    incoming.set(targetId, (incoming.get(targetId) ?? 0) + 1);
    for (const [a, b] of [[sourceId, targetId], [targetId, sourceId]]) {
      const connected = neighbors.get(a) ?? new Set();
      connected.add(b);
      neighbors.set(a, connected);
    }
  }

  const queue = nodes.filter(({ id }) => neighbors.has(id) && incoming.get(id) === 0).map(({ id }) => id);
  const depth = new Map(queue.map((id) => [id, 0]));
  const remainingIncoming = new Map(incoming);
  for (let index = 0; index < queue.length; index++) {
    const source = queue[index];
    const nextDepth = (depth.get(source) ?? 0) + 1;
    for (const target of targets.get(source) ?? []) {
      depth.set(target, Math.max(depth.get(target) ?? 0, nextDepth));
      const remaining = (remainingIncoming.get(target) ?? 0) - 1;
      remainingIncoming.set(target, remaining);
      if (remaining === 0) queue.push(target);
    }
  }

  const positions = new Map<string, XYPosition>();
  const widths = new Map<number, number>();
  const columns = new Map<string, number>();
  const components = new Map<string, number>();
  const bands: typeof nodes[] = [];
  let unranked = 0;
  for (const node of nodes) {
    const { id, width } = node;
    if (!neighbors.has(id)) continue;
    if (!components.has(id)) {
      const band = bands.length;
      bands.push([]);
      const pending = [id];
      components.set(id, band);
      for (let index = 0; index < pending.length; index++) {
        for (const adjacent of neighbors.get(pending[index]) ?? []) {
          if (components.has(adjacent)) continue;
          components.set(adjacent, band);
          pending.push(adjacent);
        }
      }
    }
    bands[components.get(id)!].push(node);
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
  let startRow = 0;
  // Each connected component starts on a shared row. Expanding one card
  // cannot move the unrelated target of another pair onto the wrong row.
  for (const band of bands) {
    const nextRow = new Map<number, number>();
    const tableColumns = new Set(band.filter(({ tablePair }) => !tablePair).map(({ id }) => columns.get(id)));
    const deferredTasks: LayoutNode[] = [];
    function place({ id, width, height, offsetY = 0 }: LayoutNode, preferredRow = startRow) {
      const column = columns.get(id)!;
      const y = Math.max(preferredRow, nextRow.get(column) ?? startRow);
      nextRow.set(column, y + offsetY + height + ROW_GAP);
      positions.set(id, { x: left.get(column)! + (widths.get(column)! - width) / 2, y: y + offsetY });
    }
    for (const node of band) {
      if (node.tablePair && !tableColumns.has(columns.get(node.id))) deferredTasks.push(node);
      else place(node);
    }
    // Connect each task to the row of its branch, even when the other endpoint is shared.
    const taskRows = deferredTasks.map((node) => {
      const { sourceId, targetId } = node.tablePair!;
      const converges = (incoming.get(targetId) ?? 0) > 1 && targets.get(sourceId)?.length === 1;
      return { node, row: positions.get(converges ? sourceId : targetId)!.y };
    }).sort((a, b) => a.row - b.row);
    for (const { node, row } of taskRows) place(node, row);
    startRow = Math.max(startRow, ...nextRow.values());
  }
  const isolated = nodes.filter(({ id }) => !neighbors.has(id));
  const isolatedWidth = Math.max(0, ...isolated.map(({ width }) => width));
  const isolatedColumnCount = Math.min(3, Math.max(2, columns.size ? widths.size : 3));
  const isolatedRows = new Map<number, number>();
  isolated.forEach(({ id, height }, index) => {
    const column = index % isolatedColumnCount;
    const y = isolatedRows.get(column) ?? startRow;
    positions.set(id, { x: column * (isolatedWidth + COLUMN_GAP), y });
    isolatedRows.set(column, y + height + ROW_GAP);
  });
  return positions;
}
