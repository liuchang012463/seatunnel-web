import { buildPageParams, endpointSelectionFromGraph, sameEndpointSelection } from './pageParams';

describe('buildPageParams', () => {
  it.each([
    { dbType: 'FTP', connectorType: 'FtpFile', pluginName: 'FtpFile' },
    { dbType: 'SFTP', connectorType: 'SftpFile', pluginName: 'SftpFile' },
    { dbType: 'S3', connectorType: 'S3File', pluginName: 'S3File' },
  ])('forwards the selected $dbType source into a new file-transfer graph', (sourceType) => {
    const params = buildPageParams(
      {
        sourceType,
        sourceDataSourceId: 'source-123',
        targetDataSourceId: 'target-456',
        workflow: { nodes: [], edges: [] },
      },
      'draft-1',
      'create',
    );
    const sourceNode = params.workflow.nodes.find((node: any) => node.data.nodeType === 'source');
    const sinkNode = params.workflow.nodes.find((node: any) => node.data.nodeType === 'sink');

    expect(params.sourceType).toEqual(sourceType);
    expect(sourceNode.data.dbType).toBe(sourceType.dbType);
    expect(sourceNode.data.config.dataSourceId).toBe('source-123');
    expect(sourceNode.data.config.fileFormatType).toBe('binary');
    expect(sinkNode.data.config.dataSourceId).toBe('target-456');
  });

  it('uses the current create selections when restoring a draft with stale endpoint nodes', () => {
    const params = buildPageParams(
      {
        sourceType: { dbType: 'S3', connectorType: 'S3File', pluginName: 'S3File' },
        targetType: { dbType: 'S3', connectorType: 'S3File', pluginName: 'S3File' },
        sourceDataSourceId: 'source-current',
        targetDataSourceId: 'target-current',
        workflow: {
          nodes: [
            {
              id: 'source',
              data: {
                nodeType: 'source',
                dbType: 'FTP',
                config: {
                  dbType: 'FTP',
                  connectorType: 'FtpFile',
                  pluginName: 'FtpFile',
                  dataSourceId: 'source-stale',
                  path: '/incoming',
                },
              },
            },
            {
              id: 'sink',
              data: {
                nodeType: 'sink',
                dbType: 'MINIO',
                config: {
                  dbType: 'MINIO',
                  connectorType: 'S3File',
                  pluginName: 'S3File',
                  dataSourceId: 'target-stale',
                  targetPath: '/archive',
                },
              },
            },
          ],
          edges: [],
        },
      },
      'draft-1',
      'create',
    );
    const sourceNode = params.workflow.nodes.find((node: any) => node.data.nodeType === 'source');
    const sinkNode = params.workflow.nodes.find((node: any) => node.data.nodeType === 'sink');

    expect(sourceNode.data).toMatchObject({ dbType: 'S3', connectorType: 'S3File', pluginName: 'S3File' });
    expect(sourceNode.data.config).toMatchObject({
      dbType: 'S3',
      connectorType: 'S3File',
      pluginName: 'S3File',
      dataSourceId: 'source-current',
      fileFormatType: 'binary',
      path: '/incoming',
    });
    expect(sinkNode.data).toMatchObject({ dbType: 'S3', connectorType: 'S3File', pluginName: 'S3File' });
    expect(sinkNode.data.config).toMatchObject({
      dbType: 'S3',
      connectorType: 'S3File',
      pluginName: 'S3File',
      dataSourceId: 'target-current',
      targetPath: '/archive',
    });
  });

  it('keeps persisted endpoint types when opening an existing task for editing', () => {
    const params = buildPageParams(
      {
        workflow: {
          nodes: [
            { id: 'source', data: { nodeType: 'source', config: { dbType: 'S3', connectorType: 'S3File', pluginName: 'S3File' } } },
            { id: 'sink', data: { nodeType: 'sink', dbType: 'MINIO', config: { dbType: 'MINIO', connectorType: 'S3File', pluginName: 'S3File', dataSourceId: 'saved-target' } } },
          ],
          edges: [],
        },
      },
      'task-1',
      'edit',
    );
    const sinkNode = params.workflow.nodes.find((node: any) => node.data.nodeType === 'sink');

    expect(sinkNode.data.dbType).toBe('MINIO');
    expect(sinkNode.data.config.dataSourceId).toBe('saved-target');
  });

  it('derives the draft endpoints from the canvas so a re-hydration cannot revert a canvas change', () => {
    const canvasWorkflow = {
      nodes: [
        {
          id: 'source',
          data: {
            nodeType: 'source',
            config: { dbType: 'S3', connectorType: 'S3File', pluginName: 'S3File', dataSourceId: 'canvas-source' },
          },
        },
        {
          id: 'sink',
          data: {
            nodeType: 'sink',
            config: { dbType: 'SFTP', connectorType: 'SftpFile', pluginName: 'SftpFile', dataSourceId: 'canvas-target' },
          },
        },
      ],
      edges: [],
    };
    const draft = {
      sourceType: { dbType: 'FTP', connectorType: 'FtpFile', pluginName: 'FtpFile' },
      targetType: { dbType: 'MINIO', connectorType: 'S3File', pluginName: 'S3File' },
      sourceDataSourceId: 'wizard-source',
      targetDataSourceId: 'wizard-target',
      workflow: canvasWorkflow,
    };

    const selection = endpointSelectionFromGraph(canvasWorkflow);
    expect(selection).toEqual({
      sourceType: { dbType: 'S3', connectorType: 'S3File', pluginName: 'S3File' },
      sourceDataSourceId: 'canvas-source',
      targetType: { dbType: 'SFTP', connectorType: 'SftpFile', pluginName: 'SftpFile' },
      targetDataSourceId: 'canvas-target',
    });
    expect(sameEndpointSelection(draft, selection)).toBe(false);

    const params = buildPageParams({ ...draft, ...selection }, 'draft-1', 'create');
    const sourceNode = params.workflow.nodes.find((node: any) => node.data.nodeType === 'source');
    const sinkNode = params.workflow.nodes.find((node: any) => node.data.nodeType === 'sink');

    expect(sourceNode.data.config.dataSourceId).toBe('canvas-source');
    expect(sourceNode.data.dbType).toBe('S3');
    expect(sinkNode.data.config.dataSourceId).toBe('canvas-target');
    expect(sinkNode.data.dbType).toBe('SFTP');
    expect(sameEndpointSelection({ ...draft, ...selection }, selection)).toBe(true);
  });

  it('reports a managed source as the file-resource source', () => {
    const selection = endpointSelectionFromGraph({
      nodes: [
        { id: 'source', data: { nodeType: 'source', config: { sourceMode: 'FILE_RESOURCE', dbType: 'MINIO', connectorType: 'S3File', pluginName: 'S3File' } } },
        { id: 'sink', data: { nodeType: 'sink', config: { dbType: 'MINIO', connectorType: 'S3File', pluginName: 'S3File', dataSourceId: 'target-1' } } },
      ],
      edges: [],
    });

    expect(selection?.sourceType.dbType).toBe('FILE_RESOURCE');
    expect(selection?.sourceDataSourceId).toBeUndefined();
    expect(selection?.targetDataSourceId).toBe('target-1');
  });

  it('ignores an incomplete graph', () => {
    expect(endpointSelectionFromGraph({ nodes: [], edges: [] })).toBeNull();
    expect(
      endpointSelectionFromGraph({
        nodes: [{ id: 'source', data: { nodeType: 'source', config: {} } }],
        edges: [],
      }),
    ).toBeNull();
  });
});
