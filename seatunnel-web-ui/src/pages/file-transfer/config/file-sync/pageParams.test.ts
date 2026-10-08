import { buildPageParams } from './pageParams';

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
});
