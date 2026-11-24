import { fieldRows, lineageNeighborhood } from './graph';
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
