import {
  generateDataSourceOptions,
  generateRealtimeSourceOptions,
  generateSourceDataSourceOptions,
} from './DataSourceSelect';

describe('datasource capability options', () => {
  it('shows HTTP only on supported source sides', () => {
    expect(generateSourceDataSourceOptions().some((item) => item.value === 'HTTP')).toBe(true);
    expect(generateRealtimeSourceOptions().some((item) => item.value === 'HTTP')).toBe(true);
    expect(generateDataSourceOptions().some((item) => item.value === 'HTTP')).toBe(false);
  });
});
