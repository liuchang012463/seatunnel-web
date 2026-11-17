import type { OdsSourceColumn, OdsSourceTableDetail } from './types';

/** OM may publish a primary key on the table only; it still implies NOT NULL. */
export const primaryKeyNames = (table: OdsSourceTableDetail): Set<string> => new Set([
  ...(table.tableConstraints || []).filter((item) => /PRIMARY/i.test(item.constraintType || '')).flatMap((item) => item.columns || []),
  ...(table.columns || []).filter((item) => /PRIMARY/i.test(item.constraint || '')).map((item) => item.name),
].map((name) => name.toLowerCase()));

export const sourceNullable = (column: OdsSourceColumn, keys: Set<string>): boolean =>
  !keys.has(column.name.toLowerCase()) && !/NOT[_ ]NULL|PRIMARY/i.test(column.constraint || '');

/** Only suggest known scalar conversions; leave other values as explicit strings. */
export function suggestedDorisType(column: OdsSourceColumn, key = false): string {
  const type = String(column.dataType || column.dataTypeDisplay || '').toUpperCase().split('(')[0].trim();
  const numeric: Record<string, string> = { BYTE: 'TINYINT', TINYINT: 'TINYINT', SMALLINT: 'SMALLINT', SHORT: 'SMALLINT', INTEGER: 'INT', INT: 'INT', LONG: 'BIGINT', BIGINT: 'BIGINT', LARGEINT: 'LARGEINT', BOOLEAN: 'BOOLEAN', BOOL: 'BOOLEAN', FLOAT: 'FLOAT', DOUBLE: 'DOUBLE', REAL: 'DOUBLE' };
  if (numeric[type] && !(key && ['FLOAT', 'DOUBLE'].includes(numeric[type]))) return numeric[type];
  if (type === 'DATE') return 'DATE';
  if (['DATETIME', 'TIMESTAMP', 'DATETIMEV2'].includes(type)) {
    const scale = Number(column.scale || 0);
    return scale > 0 && scale <= 6 ? `DATETIME(${scale})` : 'DATETIME';
  }
  if (['DECIMAL', 'NUMERIC', 'NUMBER'].includes(type)) {
    const parameters = /\((\d+)\s*,\s*(\d+)\)/.exec(column.dataTypeDisplay || '');
    const precision = Number(column.precision ?? parameters?.[1]);
    const scale = Number(column.scale ?? parameters?.[2] ?? 0);
    if (precision >= 1 && precision <= 38 && scale >= 0 && scale <= precision) return `DECIMAL(${precision},${scale})`;
  }
  if (key || ['VARCHAR', 'CHAR'].includes(type)) {
    const parameter = /\((\d+)\)/.exec(column.dataTypeDisplay || '');
    const length = Number(column.dataLength || parameter?.[1] || 255);
    return `VARCHAR(${Math.max(1, Math.min(1048576, length))})`;
  }
  return 'STRING';
}
