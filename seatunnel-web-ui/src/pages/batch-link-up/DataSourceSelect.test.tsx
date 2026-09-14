import {
  generateDataSourceOptions,
  generateOfflineSourceDataSourceOptions,
  generateRealtimeSourceOptions,
  generateSourceDataSourceOptions,
} from './DataSourceSelect';

describe('datasource capability options', () => {
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
