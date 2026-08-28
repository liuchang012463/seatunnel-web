import {
  genericAssetTerm,
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

  it('uses connector-specific asset terminology', () => {
    expect(genericAssetTerm('KAFKA')).toBe('Kafka 主题');
    expect(genericAssetTerm('ELASTICSEARCH')).toBe('ES 索引');
    expect(genericAssetTerm('HTTP')).toBe('HTTP 接口');
    expect(genericAssetTerm('S3')).toBe('S3 对象');
    expect(genericAssetTerm('FTP')).toBe('文件');
  });
});
