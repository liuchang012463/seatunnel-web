import {
  DATA_SOURCE_CATEGORIES,
  DATA_SOURCE_REGISTRY,
  filterDataSourceTypesByUsage,
  getDataSourceCategory,
  groupDataSourcesByCategory,
  supportsDataSourceUsage,
} from './dataSourceRegistry';

describe('dataSourceRegistry', () => {
  it('maps HTTP and known datasource types to their categories', () => {
    expect(getDataSourceCategory('HTTP').label).toBe('API 服务');
    expect(getDataSourceCategory('KAFKA').label).toBe('消息队列');
    expect(getDataSourceCategory('ELASTICSEARCH').label).toBe('OLAP 数据库');
    expect(getDataSourceCategory('MYSQL').label).toBe('关系型数据库');
  });

  it('falls unknown types back to OTHER', () => {
    expect(getDataSourceCategory('FUTURE_CONNECTOR').key).toBe('OTHER');
  });

  it('groups records in registry category order', () => {
    const groups = groupDataSourcesByCategory([
      { id: '1', dbType: 'HTTP' },
      { id: '2', dbType: 'MYSQL' },
      { id: '3', dbType: 'FUTURE_CONNECTOR' },
    ]);

    expect(groups.map((group) => group.category.key)).toEqual([
      'RELATIONAL',
      'API',
      'OTHER',
    ]);
    expect(
      DATA_SOURCE_CATEGORIES.find((category) => category.key === 'API')?.dbTypes,
    ).toEqual(['HTTP']);
  });

  it('keeps connectors with omitted capability flags available in management views', () => {
    const types = ['S3', 'SFTP', 'FTP', 'MINIO', 'H2'];
    const byType = new Map(DATA_SOURCE_REGISTRY.map((item) => [item.dbType, item]));

    types.forEach((dbType) => {
      const connector = byType.get(dbType);
      expect(supportsDataSourceUsage(connector, 'SOURCE')).toBe(true);
      expect(supportsDataSourceUsage(connector, 'SINK')).toBe(true);
    });

    expect(filterDataSourceTypesByUsage(DATA_SOURCE_REGISTRY, 'SOURCE').map((item) => item.dbType))
      .toEqual(expect.arrayContaining(types));
    expect(filterDataSourceTypesByUsage(DATA_SOURCE_REGISTRY, 'SINK').map((item) => item.dbType))
      .toEqual(expect.arrayContaining(types));
    expect(supportsDataSourceUsage(byType.get('HTTP'), 'SOURCE')).toBe(true);
    expect(supportsDataSourceUsage(byType.get('HTTP'), 'SINK')).toBe(false);
  });
});
