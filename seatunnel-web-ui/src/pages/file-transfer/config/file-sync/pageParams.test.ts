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
});
