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
