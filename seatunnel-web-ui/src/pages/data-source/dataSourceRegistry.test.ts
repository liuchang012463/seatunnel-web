import {
  DATA_SOURCE_CATEGORIES,
  getDataSourceCategory,
  groupDataSourcesByCategory,
} from './dataSourceRegistry';

describe('dataSourceRegistry', () => {
  it('maps HTTP and known datasource types to their categories', () => {
    expect(getDataSourceCategory('HTTP').label).toBe('API 服务');
    expect(getDataSourceCategory('KAFKA').label).toBe('消息队列');
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
});
