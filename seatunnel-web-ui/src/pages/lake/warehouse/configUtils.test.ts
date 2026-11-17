import { buildWarehouseJdbcUrl, isInitialDorisPasswordMissing } from './configUtils';

it('builds a Doris cluster URL without requiring or preserving a default database', () => {
  expect(buildWarehouseJdbcUrl({ fenodes: 'fe:8030', queryPort: 9030 })).toBe('jdbc:mysql://fe:9030/');
  expect(buildWarehouseJdbcUrl({
    fenodes: 'fe:8030', queryPort: 9030, database: 'legacy_ods',
    other: [{ key: 'useSSL', value: 'false' }],
  })).toBe('jdbc:mysql://fe:9030/?useSSL=false');
});

describe('isInitialDorisPasswordMissing', () => {
  it('requires a password when no Doris configuration exists', () => {
    expect(isInitialDorisPasswordMissing(undefined, '')).toBe(true);
    expect(isInitialDorisPasswordMissing({ passwordConfigured: false }, '  ')).toBe(true);
  });

  it('accepts a manually entered password for the initial configuration', () => {
    expect(isInitialDorisPasswordMissing(undefined, 'doris-secret')).toBe(false);
  });

  it('allows an empty password when the existing password is configured', () => {
    expect(isInitialDorisPasswordMissing({ passwordConfigured: true }, '')).toBe(false);
  });
});
