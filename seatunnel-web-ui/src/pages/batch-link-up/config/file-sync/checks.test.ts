import { generateFileSyncCheckList } from './checks';

const buildNodes = (sourceConfig: Record<string, any>, sinkConfig: Record<string, any>) => [
  {
    id: 'source-1',
    data: { nodeType: 'source', config: sourceConfig },
  },
  {
    id: 'sink-1',
    data: { nodeType: 'sink', config: sinkConfig },
  },
];

describe('file sync incremental validation', () => {
  const s3Nodes = buildNodes(
    { dataSourceId: '12', dbType: 'S3', path: '/source', syncType: 'INCREMENTAL' },
    { dataSourceId: '12', dbType: 'S3', targetPath: '/target' },
  );

  it('allows S3File update sync on SeaTunnel 3.0.0 when both endpoints use one datasource', () => {
    expect(generateFileSyncCheckList(s3Nodes, '3.0.0')).toEqual([]);
  });

  it('rejects S3File update sync on SeaTunnel 2.3.13', () => {
    expect(generateFileSyncCheckList(s3Nodes, '2.3.13')).toEqual([
      expect.objectContaining({
        nodeId: 'source-1',
        field: 'syncType',
        message: 'S3File 增量 update 需要 SeaTunnel Engine 3.0.0',
      }),
    ]);
  });

  it('reports an unknown engine version separately from an unsupported one', () => {
    expect(generateFileSyncCheckList(s3Nodes)).toEqual([
      expect.objectContaining({
        nodeId: 'source-1',
        field: 'syncType',
        message: '无法确认 SeaTunnel Engine 版本，S3File 增量 update 需要 3.0.0',
      }),
    ]);
  });

  it('requires matching object storage types for S3File update sync', () => {
    const nodes = buildNodes(
      { dataSourceId: '12', dbType: 'S3', path: '/source', syncType: 'INCREMENTAL' },
      { dataSourceId: '12', dbType: 'MINIO', targetPath: '/target' },
    );

    expect(generateFileSyncCheckList(nodes, '3.0.0')).toEqual([
      expect.objectContaining({
        nodeId: 'source-1',
        field: 'syncType',
        message: 'S3File 增量 update 要求来源和去向使用同一 S3 / MinIO 数据源',
      }),
    ]);
  });

  it.each([
    ['FTP', 'SFTP'],
    ['SFTP', 'FTP'],
  ])('rejects incremental sync across %s and %s despite a matching datasource id', (sourceType, sinkType) => {
    const nodes = buildNodes(
      { dataSourceId: '12', dbType: sourceType, path: '/source', syncType: 'INCREMENTAL' },
      { dataSourceId: '12', dbType: sinkType, targetPath: '/target' },
    );

    expect(generateFileSyncCheckList(nodes)).toEqual([
      expect.objectContaining({
        nodeId: 'source-1',
        field: 'syncType',
        message: '增量模式要求来源与去向使用相同文件协议',
      }),
    ]);
  });

  it('requires a single datasource for remote file update sync', () => {
    const nodes = buildNodes(
      { dataSourceId: '12', dbType: 'SFTP', path: '/source', syncType: 'INCREMENTAL' },
      { dataSourceId: '34', dbType: 'SFTP', targetPath: '/target' },
    );

    expect(generateFileSyncCheckList(nodes)).toEqual([
      expect.objectContaining({
        nodeId: 'source-1',
        field: 'syncType',
        message: '增量模式要求来源与去向使用同一数据源',
      }),
    ]);
  });
});
