/**
 * Non-relational data sources are explored from OpenMetadata assets, not from the
 * table profiler. Their status therefore comes from metadata sync/scan, never from
 * `profileStatus`.
 */
export const ASSET_EXPLORATION_DB_TYPES = [
  'KAFKA',
  'ELASTICSEARCH',
  'HTTP',
  'MINIO',
  'S3',
  'FTP',
  'SFTP',
] as const;

export type AssetExplorationDbType = (typeof ASSET_EXPLORATION_DB_TYPES)[number];

export function normalizeExplorationDbType(dbType?: string): string {
  return String(dbType || '').trim().toUpperCase().replace(/-/g, '_');
}

export function isAssetExplorationDbType(dbType?: string): boolean {
  return (ASSET_EXPLORATION_DB_TYPES as readonly string[])
    .includes(normalizeExplorationDbType(dbType));
}

export interface AssetExplorationStatus {
  label: string;
  color: 'success' | 'error' | 'processing' | 'default' | 'warning';
}

export function assetExplorationStatus(record: {
  metadataSyncStatus?: string;
  scanStatus?: string;
}): AssetExplorationStatus {
  const sync = String(record.metadataSyncStatus || '').toUpperCase();
  const scan = String(record.scanStatus || '').toUpperCase();
  if (sync === 'ERROR') return { label: '同步异常', color: 'error' };
  if (sync === 'UNSUPPORTED') return { label: '未接入', color: 'default' };
  if (sync === 'PENDING' || sync === 'SYNCING' || sync === 'WAITING') {
    return { label: '同步中', color: 'processing' };
  }
  if (sync === 'READY') {
    if (scan === 'SUCCESS') return { label: '元数据已就绪', color: 'success' };
    if (scan === 'RUNNING' || scan === 'QUEUED') {
      return { label: '扫描中', color: 'processing' };
    }
    if (scan === 'FAILED') return { label: '扫描异常', color: 'error' };
    if (scan === 'UNKNOWN') return { label: '扫描状态未知', color: 'warning' };
    if (!scan || scan === 'NEVER') return { label: '等待首次扫描', color: 'processing' };
    return { label: '未扫描', color: 'default' };
  }
  return { label: '未初始化', color: 'default' };
}

