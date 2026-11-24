import { fieldRows, groupLineageTasks, lineageNeighborhood, tableLineageRoutes, taskNodeId } from './graph';
import { topologyPositions } from './layout';
import type { LineageAsset, LineageLink, Topology } from './service';

function table(id: string): LineageAsset {
  return { id, name: id, dataSourceId: 'source', type: 'table', fullyQualifiedName: `service.db.schema.${id}` };
}

function topology(pairs: [string, string][]): Topology {
  const edges: LineageLink[] = pairs.map(([sourceId, targetId]) => ({ sourceId, targetId }));
  const assets = [...new Set(pairs.flat())].map(table);
  return { sources: [], assets, edges };
}

describe('lineageNeighborhood', () => {
  it('keeps every table in the selected schema and limits new nodes in each direction and hop', () => {
    const graph = lineageNeighborhood(topology([
      ['root', 'otherRoot'], ['root', 'down1'], ['root', 'down2'], ['down1', 'down3'],
      ['up1', 'root'], ['up2', 'otherRoot'], ['up3', 'up1'],
    ]), [table('root'), table('otherRoot'), table('isolated')], {
      upstream: 1, downstream: 2, nodesPerLayer: 1,
    });

    expect(graph.assets.map(({ id }) => id)).toEqual(['root', 'otherRoot', 'isolated', 'up1', 'down1', 'down3']);
    expect(graph.links).toEqual([
      { sourceId: 'root', targetId: 'otherRoot' },
      { sourceId: 'up1', targetId: 'root' },
      { sourceId: 'root', targetId: 'down1' },
      { sourceId: 'down1', targetId: 'down3' },
    ]);
    expect(graph.truncated).toBe(true);
  });

  it('retains directed cycles and excludes disconnected branches', () => {
    const graph = lineageNeighborhood(topology([
      ['center', 'inCycle'], ['inCycle', 'center'], ['elsewhere', 'unrelated'],
    ]), [table('center')], { upstream: 0, downstream: 10, nodesPerLayer: 1 });

    expect(graph.assets.map(({ id }) => id)).toEqual(['center', 'inCycle']);
    expect(graph.links).toHaveLength(2);
    expect(graph.truncated).toBe(false);
  });
});

describe('fieldRows', () => {
  it('preserves full column order and attaches handles only to recorded mappings', () => {
    const source = table('from');
    const target = table('to');
    const fromColumn = `${source.fullyQualifiedName}."mapped.with.dot"`;
    const toColumn = `${target.fullyQualifiedName}.name`;
    const mapping = { sourceId: source.id, targetId: target.id, fromColumn, toColumn };

    const sourceRows = fieldRows(source, [
      { name: 'id', dataType: 'BIGINT' },
      { name: 'mapped.with.dot', fullyQualifiedName: fromColumn },
      { name: 'created_at' },
    ], [mapping]);
    const targetRows = fieldRows(target, [{ name: 'id' }, { name: 'name', fullyQualifiedName: toColumn }], [mapping]);

    expect(sourceRows.map(({ name }) => name)).toEqual(['id', 'mapped.with.dot', 'created_at']);
    expect(sourceRows.filter(({ outgoing }) => outgoing).map(({ fqn }) => fqn)).toEqual([fromColumn]);
    expect(targetRows.map(({ name, incoming }) => [name, Boolean(incoming)])).toEqual([['id', false], ['name', true]]);
  });
});

it('groups table pairs by task kind and ID without introducing cross-pair edges', () => {
  const task = { id: '9', kind: 'BATCH' as const, deleted: false };
  const first = { sourceId: 'a1', targetId: 'b1', task };
  const second = { sourceId: 'a2', targetId: 'b2', task };
  const streaming = { sourceId: 'a3', targetId: 'b3', task: { ...task, kind: 'STREAMING' as const } };
  const groups = groupLineageTasks([first, streaming, second]);
  expect(groups.get('task:BATCH:9')?.links).toEqual([first, second]);
  expect(groups.get('task:STREAMING:9')?.links).toEqual([streaming]);
});

it('aligns independent source and target pairs with unequal field heights', () => {
  const nodes = [
    { id: 'a1', height: 200 },
    { id: 'a2', height: 100 },
    { id: 'b2', height: 100 },
    { id: 'b1', height: 120 },
  ].map((node) => ({ ...node, width: 236 }));
  const xy = topologyPositions(nodes, [{ source: 'a1', target: 'b1' }, { source: 'a2', target: 'b2' }]);
  expect(xy.get('a1')?.y).toEqual(xy.get('b1')?.y);
  expect(xy.get('a2')?.y).toEqual(xy.get('b2')?.y);
  expect(xy.get('a2')?.y).toBeGreaterThan(200);
});

it('gives repeated tasks separate paths and aligns each node with its own downstream table', () => {
  const task = { id: '9', kind: 'BATCH' as const, deleted: false };
  const first = { sourceId: 'source', targetId: 'first', task };
  const second = { sourceId: 'source', targetId: 'second', task };
  const direct = { sourceId: 'source', targetId: 'direct' };
  const { taskNodes, paths } = tableLineageRoutes([second, first, direct]);
  const firstTask = taskNodeId(first);
  const secondTask = taskNodeId(second);
  expect(firstTask).not.toEqual(secondTask);
  expect(paths.map(({ source, target }) => [source, target])).toEqual([
    ['source', secondTask], [secondTask, 'second'],
    ['source', firstTask], [firstTask, 'first'],
    ['source', 'direct'],
  ]);
  const xy = topologyPositions([
    ...['source', 'first', 'second'].map((id) => ({ id, width: 236, height: 104 })),
    ...taskNodes.map(({ id, link }) => ({ id, width: 100, height: 28, offsetY: 4, tablePair: link })),
  ], paths.filter(({ taskId }) => taskId));
  expect(xy.get(firstTask)?.y).toEqual(xy.get('first')!.y + 4);
  expect(xy.get(secondTask)?.y).toEqual(xy.get('second')!.y + 4);
});
