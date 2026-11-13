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

  it('limits task target options to Kingbase and Vastbase', () => {
    expect(generateTaskTargetDataSourceOptions().map((item) => item.value)).toEqual([
      'KINGBASE',
      'VASTBASE',
    ]);
  });

  it('shows HTTP only on supported source sides', () => {
    expect(generateSourceDataSourceOptions().some((item) => item.value === 'HTTP')).toBe(true);
    expect(generateRealtimeSourceOptions().some((item) => item.value === 'HTTP')).toBe(true);
    expect(generateDataSourceOptions().some((item) => item.value === 'HTTP')).toBe(false);
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
