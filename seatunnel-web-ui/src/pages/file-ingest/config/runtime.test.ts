import { normalizeWorkflowGraph } from './runtime';

describe('normalizeWorkflowGraph', () => {
  it('preserves the selected remote source and connection references when editing', () => {
    const workflow = {
      nodes: [
        {
          id: 'source',
          data: {
            nodeType: 'source',
            config: {
              dbType: 'S3',
              connectorType: 'S3File',
              pluginName: 'S3File',
              fileFormatType: 'csv',
            },
          },
        },
        {
          id: 'sink',
          data: {
            nodeType: 'sink',
            config: { dbType: 'MINIO', connectorType: 'S3File', pluginName: 'S3File' },
          },
        },
      ],
      edges: [],
    };

    const result = normalizeWorkflowGraph(
      workflow,
      'FILE_TRANSFER',
      { dbType: 'S3', connectorType: 'S3File', pluginName: 'S3File' },
      { dbType: 'MINIO', connectorType: 'S3File', pluginName: 'S3File' },
      '123',
      '456',
    );

    expect(result.nodes[0].data.dbType).toBe('S3');
    expect(result.nodes[0].data.config.dataSourceId).toBe('123');
    expect(result.nodes[0].data.config.fileFormatType).toBe('binary');
    expect(result.nodes[1].data.config.dataSourceId).toBe('456');
  });

  it('creates a remote source config with the selected connection references', () => {
    const result = normalizeWorkflowGraph(
      undefined,
      'FILE_TRANSFER',
      { dbType: 'S3', connectorType: 'S3File', pluginName: 'S3File' },
      { dbType: 'MINIO', connectorType: 'S3File', pluginName: 'S3File' },
      '123',
      '456',
    );

    expect(result.nodes[0].data.dbType).toBe('S3');
    expect(result.nodes[0].data.config.dataSourceId).toBe('123');
    expect(result.nodes[0].data.config.fileFormatType).toBe('binary');
    expect(result.nodes[1].data.config.dataSourceId).toBe('456');
  });
});
