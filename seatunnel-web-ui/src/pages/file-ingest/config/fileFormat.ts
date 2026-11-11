import type { FileFormat } from '../types';

export const isDatabaseFileFormat = (format: FileFormat): boolean =>
  format === 'duckdb' || format === 'sqlite';

/** Infer common reusable-file formats. A bare .db defaults to SQLite. */
export const detectFormatFromName = (name?: string): FileFormat | undefined => {
  const extension = String(name || '').split('.').pop()?.toLowerCase();
  if (extension === 'csv') return 'csv';
  if (extension === 'xls' || extension === 'xlsx') return 'excel';
  if (extension === 'json') return 'json';
  if (extension === 'txt' || extension === 'text') return 'text';
  if (extension === 'sqlite' || extension === 'sqlite3' || extension === 'db') return 'sqlite';
  if (extension === 'duckdb') return 'duckdb';
  return undefined;
};

/** Keep the user's explicit database choice for the ambiguous .db extension. */
export const resolveFormatFromFileName = (
  name: string | undefined,
  selectedFormat: FileFormat,
): FileFormat | undefined => {
  const extension = String(name || '').split('.').pop()?.toLowerCase();
  if (extension === 'db' && (selectedFormat === 'sqlite' || selectedFormat === 'duckdb')) {
    return selectedFormat;
  }
  return detectFormatFromName(name);
};
