export type FileDataSourceType = 'FTP' | 'SFTP' | 'S3' | 'MINIO' | 'LOCAL_FILE';

export const FILE_DATASOURCE_TYPES: FileDataSourceType[] = [
  'FTP',
  'SFTP',
  'S3',
  'MINIO',
  'LOCAL_FILE',
];

/** 通过 SeaTunnel 引擎直接读取远端的文件数据源。 */
export const REMOTE_FILE_DATASOURCE_TYPES: FileDataSourceType[] = [
  'FTP',
  'SFTP',
  'S3',
  'MINIO',
];

/** 由 Web 服务本机落盘、经 LocalFile 连接器读取的来源类型。 */
export const LOCAL_UPLOAD_SOURCE_TYPE: FileDataSourceType = 'LOCAL_FILE';

export const isFileDataSourceType = (value?: string): value is FileDataSourceType =>
  FILE_DATASOURCE_TYPES.includes(value as FileDataSourceType);

export const isRemoteFileDataSourceType = (value?: string): value is FileDataSourceType =>
  REMOTE_FILE_DATASOURCE_TYPES.includes(value as FileDataSourceType);

export const connectorForFileType = (
  type: FileDataSourceType,
): 'FtpFile' | 'SftpFile' | 'S3File' | 'LocalFile' => {
  if (type === 'FTP') return 'FtpFile';
  if (type === 'SFTP') return 'SftpFile';
  if (type === 'LOCAL_FILE') return 'LocalFile';
  return 'S3File';
};

export const canUseIncrementalFileSync = (
  sourceType?: FileDataSourceType,
  targetType?: FileDataSourceType,
): boolean =>
  ![sourceType, targetType].some((type) => {
    if (!type) return false;
    // SeaTunnel 2.3.13 的 S3File/LocalFile 不支持 FILE_SYNC 的增量 update。
    return type === 'S3' || type === 'MINIO' || type === 'LOCAL_FILE';
  });

export const fileDataSourceLabel = (type?: string): string => {
  switch (type) {
    case 'FTP':
      return 'FTP';
    case 'SFTP':
      return 'SFTP';
    case 'S3':
      return 'Amazon S3';
    case 'MINIO':
      return 'MinIO';
    case 'LOCAL_FILE':
      return '本地文件';
    default:
      return type || '';
  }
};
