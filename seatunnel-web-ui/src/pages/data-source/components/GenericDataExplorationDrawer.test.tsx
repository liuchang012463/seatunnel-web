import {
  genericMetadataTerm,
  isGenericExplorationDbType,
  normalizeExplorationDbType,
} from './GenericDataExplorationDrawer';

describe('generic data exploration routing', () => {
  it('normalizes connector type names and routes supported non-database sources', () => {
    expect(normalizeExplorationDbType('elasticsearch')).toBe('ELASTICSEARCH');
    expect(normalizeExplorationDbType('s3-file')).toBe('S3_FILE');
    expect(isGenericExplorationDbType('KAFKA')).toBe(true);
    expect(isGenericExplorationDbType('JDBC')).toBe(false);
  });

  it('uses connector-specific resource terminology', () => {
    expect(genericMetadataTerm('KAFKA')).toBe('Kafka 主题');
    expect(genericMetadataTerm('ELASTICSEARCH')).toBe('ES 索引');
    expect(genericMetadataTerm('HTTP')).toBe('HTTP 接口');
    expect(genericMetadataTerm('S3')).toBe('S3 对象');
    expect(genericMetadataTerm('FTP')).toBe('文件');
    expect(genericMetadataTerm('UNKNOWN')).toBe('资源');
  });
});
