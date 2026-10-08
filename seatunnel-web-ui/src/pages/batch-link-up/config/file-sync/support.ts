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
  engineVersion?: string,
  sourceDataSourceId?: string | number,
  targetDataSourceId?: string | number,
): boolean =>
  Boolean(sourceDataSourceId)
  && Boolean(targetDataSourceId)
  && String(sourceDataSourceId) === String(targetDataSourceId)
  && sourceType !== 'WEB_UPLOAD'
  && targetType !== 'WEB_UPLOAD'
  && (() => {
    const isObjectStorage = (type?: FileDataSourceType) => type === 'S3' || type === 'MINIO';
    const sourceIsObjectStorage = isObjectStorage(sourceType);
    const targetIsObjectStorage = isObjectStorage(targetType);
    if (sourceIsObjectStorage || targetIsObjectStorage) {
      return sourceType === targetType && engineVersion === '3.0.0';
    }
    return sourceType === targetType
      && (sourceType === 'FTP' || sourceType === 'SFTP');
  })();

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
