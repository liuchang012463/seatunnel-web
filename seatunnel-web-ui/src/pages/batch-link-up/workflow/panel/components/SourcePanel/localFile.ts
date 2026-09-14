export const LOCAL_FILE_FORMATS = ['csv', 'excel', 'json', 'text'] as const;

export type LocalFileFormat = (typeof LOCAL_FILE_FORMATS)[number];

export const isLocalFileFormat = (value: unknown): value is LocalFileFormat =>
  LOCAL_FILE_FORMATS.includes(String(value || '').toLowerCase() as LocalFileFormat);

export const getSchemaFields = (schema: any): Record<string, string> => {
  const fields = schema?.fields;
  if (!fields || typeof fields !== 'object' || Array.isArray(fields)) return {};
  return Object.entries(fields).reduce<Record<string, string>>((result, [name, type]) => {
    if (String(name).trim() && String(type).trim()) {
      result[String(name)] = String(type);
    }
    return result;
  }, {});
};

export const hasSchemaFields = (schema: any) => Object.keys(getSchemaFields(schema)).length > 0;

export const buildOutputSchema = (schema: any) =>
  Object.entries(getSchemaFields(schema)).map(([name, type]) => ({
    originFieldName: name,
    fieldName: name,
    name,
    type,
    nullable: true,
  }));

export const localFileAccept = (format?: string) => {
  switch (String(format || '').toLowerCase()) {
    case 'csv':
      return '.csv,text/csv';
    case 'excel':
      return '.xls,.xlsx,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
    case 'json':
      return '.json,application/json';
    case 'text':
      return '.txt,.text,.log,text/plain';
    default:
      return '.csv,.xls,.xlsx,.json,.txt,.text,.log';
  }
};

export const localFileExtensionMatches = (name: string, format?: string) => {
  const extension = String(name || '').toLowerCase().split('.').pop() || '';
  switch (String(format || '').toLowerCase()) {
    case 'csv':
      return extension === 'csv';
    case 'excel':
      return extension === 'xls' || extension === 'xlsx';
    case 'json':
      return extension === 'json';
    case 'text':
      return ['txt', 'text', 'log'].includes(extension);
    default:
      return false;
  }
};
