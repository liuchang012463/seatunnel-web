export type FileDataSourceType = 'WEB_UPLOAD' | 'FTP' | 'SFTP' | 'S3' | 'MINIO';

export const FILE_DATASOURCE_TYPES: FileDataSourceType[] = [
  'WEB_UPLOAD',
  'FTP',
  'SFTP',
  'S3',
  'MINIO',
];

/** 通过 SeaTunnel 引擎直接读取远端的文件数据源。 */
export const REMOTE_FILE_DATASOURCE_TYPES: FileDataSourceType[] = [
  'FTP',
  'SFTP',
  'S3',
  'MINIO',
];

/** 本地文件来源在任务运行时使用平台内部存储。 */
export const LOCAL_UPLOAD_SOURCE_TYPE: FileDataSourceType = 'WEB_UPLOAD';

export const isFileDataSourceType = (value?: string): value is FileDataSourceType =>
  FILE_DATASOURCE_TYPES.includes(value as FileDataSourceType);

export const isRemoteFileDataSourceType = (value?: string): value is FileDataSourceType =>
  REMOTE_FILE_DATASOURCE_TYPES.includes(value as FileDataSourceType);

export const connectorForFileType = (
  type: FileDataSourceType,
): 'FtpFile' | 'SftpFile' | 'S3File' => {
  if (type === 'WEB_UPLOAD') return 'S3File';
  if (type === 'FTP') return 'FtpFile';
  if (type === 'SFTP') return 'SftpFile';
  return 'S3File';
};

export const canUseIncrementalFileSync = (
  sourceType?: FileDataSourceType,
  targetType?: FileDataSourceType,
): boolean =>
  ![sourceType, targetType].some((type) => {
    if (!type) return false;
    // SeaTunnel 2.3.13 的 S3File 不支持 FILE_SYNC 的增量 update。
    return type === 'WEB_UPLOAD' || type === 'S3' || type === 'MINIO';
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
    case 'WEB_UPLOAD':
      return '本地文件';
    default:
      return type || '';
  }
};
