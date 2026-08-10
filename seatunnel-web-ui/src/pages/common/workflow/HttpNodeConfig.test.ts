import { toObject, toRows } from './HttpNodeConfigUtils';

describe('HTTP node key-value configuration helpers', () => {
  it('converts a map to editable rows', () => {
    expect(toRows({ Accept: 'application/json', limit: 100 })).toEqual([
      { key: 'Accept', value: 'application/json' },
      { key: 'limit', value: '100' },
    ]);
  });

  it('drops blank keys while preserving values', () => {
    expect(
      toObject([
        { key: ' status ', value: 'active' },
        { key: ' ', value: 'ignored' },
        { key: 'page', value: '${page}' },
      ]),
    ).toEqual({ status: 'active', page: '${page}' });
  });
});
