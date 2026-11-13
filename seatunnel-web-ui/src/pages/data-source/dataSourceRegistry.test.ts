import {
  DATA_SOURCE_CATEGORIES,
  DATA_SOURCE_REGISTRY,
  DEFAULT_TASK_DATASOURCE_TYPE,
  DEFAULT_TASK_TARGET_DATASOURCE_TYPE,
  PLANNED_DATA_SOURCE_CATEGORIES,
  TASK_TARGET_DB_TYPES,
  filterDataSourceTypesByUsage,
  getDataSourceCategory,
  groupDataSourcesByCategory,
  supportsDataSourceUsage,
} from './dataSourceRegistry';

describe('dataSourceRegistry', () => {
  it('defaults new tasks to Dameng source / Kingbase target and lists domestic DBs first', () => {
    expect(DEFAULT_TASK_DATASOURCE_TYPE).toEqual({
      dbType: 'DAMENG',
      connectorType: 'Jdbc',
      pluginName: 'JDBC-DAMENG',
    });
    expect(DEFAULT_TASK_TARGET_DATASOURCE_TYPE).toEqual({
      dbType: 'KINGBASE',
      connectorType: 'Jdbc',
      pluginName: 'JDBC-KINGBASE',
    });
    expect([...TASK_TARGET_DB_TYPES]).toEqual(['KINGBASE', 'VASTBASE']);
    expect(
      DATA_SOURCE_REGISTRY.filter((item) => item.taskSelector)
        .map((item) => item.dbType)
        .slice(0, 3),
    ).toEqual(['DAMENG', 'KINGBASE', 'VASTBASE']);
  });

  it('maps HTTP and known datasource types to their categories', () => {
    expect(getDataSourceCategory('HTTP').label).toBe('API 服务');
    expect(getDataSourceCategory('KAFKA').label).toBe('消息队列');
    expect(getDataSourceCategory('ELASTICSEARCH').label).toBe('OLAP 数据库');
    expect(getDataSourceCategory('MYSQL').label).toBe('关系型数据库');
  });

  it('keeps graph and time-series categories planned until connectors are registered', () => {
    expect(PLANNED_DATA_SOURCE_CATEGORIES.map((item) => item.key)).toEqual([
      'GRAPH',
      'TIME_SERIES',
    ]);
    expect(DATA_SOURCE_CATEGORIES.map((item) => item.key)).not.toEqual(
      expect.arrayContaining(['GRAPH', 'TIME_SERIES']),
    );
    expect(DATA_SOURCE_REGISTRY.some((item) => item.category === 'GRAPH')).toBe(false);
    expect(DATA_SOURCE_REGISTRY.some((item) => item.category === 'TIME_SERIES')).toBe(false);
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
