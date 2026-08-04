import type {
  DataSourceConnectionFormValues,
  DataSourceFormValues,
  DataSourceRecord,
} from './types';

export function filterDataSourceList(
  list: DataSourceRecord[],
  keyword: string,
): DataSourceRecord[] {
  const searchKeyword = keyword.trim().toLowerCase();

  if (!searchKeyword) {
    return list;
  }

  return list.filter((item) => {
    const name = item.name?.toLowerCase() || '';
    const jdbcUrl = item.jdbcUrl?.toLowerCase() || '';
    const environmentName = item.environmentName?.toLowerCase() || '';
    const dataSourceUnit = item.dataSourceUnit?.toLowerCase() || '';
    const dbType = String(item.dbType || '').toLowerCase();
    const status = String(item.status || '').toLowerCase();

    return (
      name.includes(searchKeyword) ||
      jdbcUrl.includes(searchKeyword) ||
      environmentName.includes(searchKeyword) ||
      dataSourceUnit.includes(searchKeyword) ||
      status.includes(searchKeyword) ||
      dbType.includes(searchKeyword)
    );
  });
}

export function buildSubmitPayload(
  dbType: string,
  basicValues: DataSourceFormValues,
  connectionValues: DataSourceConnectionFormValues,
) {
  return {
    dbType,
    ...basicValues,
    connectionParams: JSON.stringify({
      ...connectionValues,
      dbType,
    }),
  };
}

export function parseOriginalJson(originalJson?: string): Record<string, unknown> {
  if (!originalJson) {
    return {};
  }

  try {
    return JSON.parse(originalJson);
  } catch (error) {
    return {};
  }
}
