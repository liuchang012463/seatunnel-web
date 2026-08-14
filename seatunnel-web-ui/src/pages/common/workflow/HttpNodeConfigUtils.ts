export type KeyValueRow = {
  key: string;
  value: string;
};

export const DEFAULT_HTTP_INCREMENTAL_PARAMS = {
  from: '${window_start}',
  to: '${window_end}',
};

export const DEFAULT_HTTP_INCREMENTAL_BODY = `{
  "from": \${window_start},
  "to": \${window_end}
}`;

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

export const getHttpIncrementalDefaults = (config: Record<string, any>) => {
  const patch: Record<string, any> = {};
  if (config.params === undefined || config.params === null) {
    patch.params = { ...DEFAULT_HTTP_INCREMENTAL_PARAMS };
  }
  if (config.body === undefined || config.body === null) {
    patch.body = DEFAULT_HTTP_INCREMENTAL_BODY;
  }
  return patch;
};
