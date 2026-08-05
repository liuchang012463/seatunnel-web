import { generateCheckList } from './flowCheckEngine';

const sinkNode = (dbType: string, config: Record<string, any> = {}) => ({
  id: 'sink-1',
  data: {
    nodeType: 'sink',
    dbType,
    config: {
      dataSourceId: '1',
      writeMode: 'append',
      targetMode: 'table',
      pluginInput: 'source',
      ...config,
    },
  },
});

const sourceNode = (dbType: string, config: Record<string, any> = {}) => ({
  id: 'source-1',
  data: {
    nodeType: 'source',
    dbType,
    config: {
      dataSourceId: '1',
      pluginOutput: 'source-1',
      ...config,
    },
  },
});

describe('stream flow check engine sink targets', () => {
  it('accepts an Elasticsearch index as the sink target', () => {
    expect(generateCheckList([sinkNode('ELASTICSEARCH', { index: 'orders' })])).toEqual([]);
  });

  it('still requires table for relational sinks', () => {
    expect(generateCheckList([sinkNode('MYSQL')])).toEqual([
      expect.objectContaining({ field: 'table', message: '按表写入时必须选择目标表' }),
    ]);
  });
});

describe('stream flow check engine source targets', () => {
  it('accepts an Elasticsearch index as the source target', () => {
    expect(generateCheckList([sourceNode('ELASTICSEARCH', { index: 'orders' })])).toEqual([]);
  });

  it('requires an index for Elasticsearch sources', () => {
    expect(generateCheckList([sourceNode('ELASTICSEARCH')])).toEqual([
      expect.objectContaining({ field: 'index', message: '请选择来源索引' }),
    ]);
  });

  it('still requires table for relational sources', () => {
    expect(generateCheckList([sourceNode('MYSQL', { readMode: 'table' })])).toEqual([
      expect.objectContaining({ field: 'table', message: '按表读取时必须选择源表' }),
    ]);
  });
});
