import {
  generateDataSourceOptions,
  generateOfflineSourceDataSourceOptions,
  generateRealtimeSourceOptions,
  generateSourceDataSourceOptions,
  generateTaskTargetDataSourceOptions,
} from './DataSourceSelect';

describe('datasource capability options', () => {
  it('puts common domestic databases first for task selectors', () => {
    const values = generateSourceDataSourceOptions().map((item) => item.value);
    expect(values.slice(0, 3)).toEqual(['DAMENG', 'KINGBASE', 'VASTBASE']);
  });

  it('limits task target options to ZeoneDB-D and Vastbase', () => {
    expect(generateTaskTargetDataSourceOptions().map((item) => item.value)).toEqual([
      'ZEONEDB',
      'VASTBASE',
    ]);
  });

  it('shows HTTP only on supported source sides', () => {
    expect(generateSourceDataSourceOptions().some((item) => item.value === 'HTTP')).toBe(true);
    expect(generateRealtimeSourceOptions().some((item) => item.value === 'HTTP')).toBe(true);
    expect(generateDataSourceOptions().some((item) => item.value === 'HTTP')).toBe(false);
  });

  it('limits realtime relational sources to CDC connectors', () => {
    const realtime = generateRealtimeSourceOptions();
    const values = realtime.map((item) => item.value);

    expect(values).not.toContain('DAMENG');
    expect(values).not.toContain('KINGBASE');
    expect(values).not.toContain('VASTBASE');
    expect(values).not.toContain('ORACLE');

    const mysqlCdc = realtime.find((item) => item.value === 'MYSQL');
    const postgresCdc = realtime.find((item) => item.value === 'POSTGRE_SQL');

    // 后端 HOCON builder 注册名为全大写，前端选项必须保持一致。
    expect(mysqlCdc?.pluginName).toBe('MYSQL-CDC');
    expect(postgresCdc?.pluginName).toBe('POSTGRESQL-CDC');
    // connectorType 是 HOCON 插件块名，不能复用 JDBC 的 Jdbc。
    expect(mysqlCdc?.connectorType).toBe('MySQL-CDC');
    expect(postgresCdc?.connectorType).toBe('Postgres-CDC');
  });

  it('exposes managed local files only as a source option', () => {
    const localFile = generateOfflineSourceDataSourceOptions().find(
      (item) => item.value === 'WEB_UPLOAD',
    );

    expect(localFile?.rawLabel).toBe('本地文件');
    expect(localFile?.sourceManaged).toBe(true);
    expect(generateSourceDataSourceOptions().some((item) => item.value === 'WEB_UPLOAD')).toBe(false);
    expect(generateRealtimeSourceOptions().some((item) => item.value === 'WEB_UPLOAD')).toBe(false);
    expect(generateDataSourceOptions().some((item) => item.value === 'WEB_UPLOAD')).toBe(false);
  });
});
