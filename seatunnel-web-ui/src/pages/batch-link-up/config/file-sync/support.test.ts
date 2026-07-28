import {
  canUseIncrementalFileSync,
  connectorForFileType,
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

  it.each([
    ['FTP', 'FTP', true],
    ['FTP', 'SFTP', true],
    ['SFTP', 'FTP', true],
    ['S3', 'FTP', false],
    ['FTP', 'MINIO', false],
    ['S3', 'MINIO', false],
  ] as const)(
    'evaluates incremental support for %s to %s',
    (sourceType, sinkType, supported) => {
      expect(canUseIncrementalFileSync(sourceType, sinkType)).toBe(supported)
    },
  )
})
