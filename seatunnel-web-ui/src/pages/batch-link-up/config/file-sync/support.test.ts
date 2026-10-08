import {
  canUseIncrementalFileSync,
  connectorForFileType,
  fileDataSourceLabel,
  isFileDataSourceType,
} from './support'

describe('file sync datasource support', () => {
  it.each([
    ['FTP', 'FtpFile'],
    ['SFTP', 'SftpFile'],
    ['S3', 'S3File'],
    ['MINIO', 'S3File'],
  ] as const)('maps %s to %s', (dbType, connectorType) => {
    expect(isFileDataSourceType(dbType)).toBe(true)
    expect(connectorForFileType(dbType)).toBe(connectorType)
  })

  it('rejects non-file datasource types', () => {
    expect(isFileDataSourceType('MYSQL')).toBe(false)
  })

  it('uses a user-facing label for local files', () => {
    expect(fileDataSourceLabel('WEB_UPLOAD')).toBe('本地文件')
  })

  it.each([
    ['FTP', 'FTP', true],
    ['FTP', 'SFTP', false],
    ['SFTP', 'FTP', false],
    ['SFTP', 'SFTP', true],
    ['S3', 'FTP', false],
    ['FTP', 'MINIO', false],
    ['S3', 'S3', false],
  ] as const)(
    'evaluates incremental support for %s to %s',
    (sourceType, sinkType, supported) => {
      expect(canUseIncrementalFileSync(sourceType, sinkType, undefined, '12', '12')).toBe(supported)
    },
  )

  it('allows same-datasource S3File incremental update only on SeaTunnel 3.0.0', () => {
    expect(canUseIncrementalFileSync('S3', 'S3', '2.3.13', '12', '12')).toBe(false)
    expect(canUseIncrementalFileSync('S3', 'S3', '3.0.0', '12', '12')).toBe(true)
    expect(canUseIncrementalFileSync('S3', 'MINIO', '3.0.0', '12', '12')).toBe(false)
    expect(canUseIncrementalFileSync('S3', 'S3', '3.0.0', '12', '34')).toBe(false)
    expect(canUseIncrementalFileSync('S3', 'S3', '3.0.0')).toBe(false)
  })
})
