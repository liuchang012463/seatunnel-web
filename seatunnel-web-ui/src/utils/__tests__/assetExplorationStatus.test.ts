import {
  assetExplorationStatus,
  isAssetExplorationDbType,
  normalizeExplorationDbType,
} from '../assetExplorationStatus';

describe('asset exploration status', () => {
  it('recognizes the non-relational data source types', () => {
    ['KAFKA', 'ELASTICSEARCH', 'HTTP', 'MINIO', 'S3', 'FTP', 'SFTP', 'sftp']
      .forEach((dbType) => expect(isAssetExplorationDbType(dbType)).toBe(true));
    ['MYSQL', 'JDBC', 'DORIS', ''].forEach((dbType) => {
      expect(isAssetExplorationDbType(dbType)).toBe(false);
    });
    expect(normalizeExplorationDbType('s3-file')).toBe('S3_FILE');
  });

  it('maps metadata sync and scan states without the table profiler', () => {
    expect(assetExplorationStatus({ metadataSyncStatus: 'READY', scanStatus: 'SUCCESS' }))
      .toEqual({ label: '元数据已就绪', color: 'success' });
    expect(assetExplorationStatus({ metadataSyncStatus: 'READY', scanStatus: 'RUNNING' }))
      .toEqual({ label: '扫描中', color: 'processing' });
    expect(assetExplorationStatus({ metadataSyncStatus: 'READY', scanStatus: 'FAILED' }))
      .toEqual({ label: '扫描异常', color: 'error' });
    expect(assetExplorationStatus({ metadataSyncStatus: 'READY', scanStatus: 'NEVER' }))
      .toEqual({ label: '等待首次扫描', color: 'processing' });
    expect(assetExplorationStatus({ metadataSyncStatus: 'READY', scanStatus: 'OTHER' }))
      .toEqual({ label: '未扫描', color: 'default' });
    expect(assetExplorationStatus({ metadataSyncStatus: 'PENDING' }))
      .toEqual({ label: '同步中', color: 'processing' });
    expect(assetExplorationStatus({ metadataSyncStatus: 'ERROR' }))
      .toEqual({ label: '同步异常', color: 'error' });
    expect(assetExplorationStatus({ metadataSyncStatus: 'UNSUPPORTED' }))
      .toEqual({ label: '未接入', color: 'default' });
    expect(assetExplorationStatus({}))
      .toEqual({ label: '未初始化', color: 'default' });
  });
});
