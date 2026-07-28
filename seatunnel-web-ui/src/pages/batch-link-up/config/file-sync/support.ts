export type FileDataSourceType = 'FTP' | 'SFTP' | 'S3' | 'MINIO';

export const FILE_DATASOURCE_TYPES: FileDataSourceType[] = ['FTP', 'SFTP', 'S3', 'MINIO'];

export const isFileDataSourceType = (value?: string): value is FileDataSourceType =>
  FILE_DATASOURCE_TYPES.includes(value as FileDataSourceType);

export const connectorForFileType = (type: FileDataSourceType): 'FtpFile' | 'SftpFile' | 'S3File' => {
  if (type === 'FTP') return 'FtpFile';
  if (type === 'SFTP') return 'SftpFile';
  return 'S3File';
};

export const canUseIncrementalFileSync = (
  sourceType?: FileDataSourceType,
  targetType?: FileDataSourceType,
): boolean => ![sourceType, targetType].some((type) => type === 'S3' || type === 'MINIO');
