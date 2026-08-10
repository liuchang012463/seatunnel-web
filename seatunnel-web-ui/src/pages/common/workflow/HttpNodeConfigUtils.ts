export type KeyValueRow = {
  key: string;
  value: string;
};

export const asRecord = (value: unknown): Record<string, any> =>
  value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, any>)
    : {};

export const toRows = (value: unknown): KeyValueRow[] =>
  Object.entries(asRecord(value)).map(([key, entryValue]) => ({
    key,
    value: entryValue == null ? '' : String(entryValue),
  }));

export const toObject = (rows: KeyValueRow[]) => {
  const result: Record<string, string> = {};
  rows.forEach((row) => {
    const key = row.key.trim();
    if (key) {
      result[key] = row.value;
    }
  });
  return result;
};
