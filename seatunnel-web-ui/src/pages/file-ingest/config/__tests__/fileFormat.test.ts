import { detectFormatFromName, resolveFormatFromFileName } from '../fileFormat';

describe('file format inference', () => {
  it('defaults an ambiguous .db file to SQLite', () => {
    expect(detectFormatFromName('native.db')).toBe('sqlite');
    expect(resolveFormatFromFileName('native.db', 'csv')).toBe('sqlite');
  });

  it('keeps an explicit database choice for .db files', () => {
    expect(resolveFormatFromFileName('warehouse.db', 'sqlite')).toBe('sqlite');
    expect(resolveFormatFromFileName('warehouse.db', 'duckdb')).toBe('duckdb');
  });

  it('uses distinct extensions to infer SQLite and DuckDB', () => {
    expect(detectFormatFromName('source.sqlite')).toBe('sqlite');
    expect(detectFormatFromName('source.sqlite3')).toBe('sqlite');
    expect(detectFormatFromName('source.duckdb')).toBe('duckdb');
  });
});
