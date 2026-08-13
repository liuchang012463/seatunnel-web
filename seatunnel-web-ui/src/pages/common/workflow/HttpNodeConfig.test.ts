import {
  DEFAULT_HTTP_INCREMENTAL_BODY,
  DEFAULT_HTTP_INCREMENTAL_PARAMS,
  getHttpIncrementalDefaults,
  toObject,
  toRows,
} from './HttpNodeConfigUtils';

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

  it('provides editable window parameters for a new incremental HTTP source', () => {
    expect(DEFAULT_HTTP_INCREMENTAL_BODY).toContain('"from": ${window_start}');
    expect(DEFAULT_HTTP_INCREMENTAL_BODY).toContain('"to": ${window_end}');
    expect(getHttpIncrementalDefaults({})).toEqual({
      params: DEFAULT_HTTP_INCREMENTAL_PARAMS,
      body: DEFAULT_HTTP_INCREMENTAL_BODY,
    });
  });

  it('does not overwrite an existing empty or customized request', () => {
    const config = {
      params: {},
      body: '',
    };

    expect(getHttpIncrementalDefaults(config)).toEqual({});
  });
});
