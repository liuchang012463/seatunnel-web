import { DATA_SOURCE_REGISTRY, supportsDataSourceUsage } from './dataSourceRegistry';
import type { DataSourceConnectionFormValues, DataSourceFormValues, DataSourceRecord } from './types';

export function filterDataSourceList(list: DataSourceRecord[], keyword: string): DataSourceRecord[] {
  const searchKeyword = keyword.trim().toLowerCase();

  if (!searchKeyword) {
    return list;
  }

  return list.filter((item) => {
    const name = item.name?.toLowerCase() || '';
    const jdbcUrl = item.jdbcUrl?.toLowerCase() || '';
    const environmentName = item.environmentName?.toLowerCase() || '';
    const dataSourceUnit = item.dataSourceUnit?.toLowerCase() || '';
    const unitName = item.unitName?.toLowerCase() || '';
    const businessSystemName = (item.businessSystemName || item.systemName || '').toLowerCase();
    const dbType = String(item.dbType || '').toLowerCase();
    const status = String(item.status || '').toLowerCase();

    return (
      name.includes(searchKeyword) ||
      jdbcUrl.includes(searchKeyword) ||
      environmentName.includes(searchKeyword) ||
      dataSourceUnit.includes(searchKeyword) ||
      unitName.includes(searchKeyword) ||
      businessSystemName.includes(searchKeyword) ||
      status.includes(searchKeyword) ||
      dbType.includes(searchKeyword)
    );
  });
}

export function buildSubmitPayload(
  dbType: string,
  basicValues: DataSourceFormValues,
  connectionValues: DataSourceConnectionFormValues,
  previousUsage?: { sourceEnabled?: boolean; sinkEnabled?: boolean },
) {
  // `unitId` is only used to populate the cascading selector. The server's
  // canonical ownership key is `businessSystemId`; the old free-text
  // `dataSourceUnit` is deliberately never sent by this form.
  const {
    dataSourceUnit: _deprecatedDataSourceUnit,
    unitId: _unitId,
    usages = [],
    ...canonicalBasicValues
  } = basicValues as DataSourceFormValues & { dataSourceUnit?: string };

  const connector = DATA_SOURCE_REGISTRY.find((item) => item.dbType === dbType);
  const sourceEnabled = !supportsDataSourceUsage(connector, 'SOURCE')
    ? Boolean(previousUsage?.sourceEnabled)
    : usages.includes('SOURCE');
  const sinkEnabled = !supportsDataSourceUsage(connector, 'SINK')
    ? Boolean(previousUsage?.sinkEnabled)
    : usages.includes('SINK');

  return {
    dbType,
    ...canonicalBasicValues,
    sourceEnabled,
    sinkEnabled,
    // Soft metadata: UI no longer collects env; default DEVELOP when missing.
    // Edit forms hydrate record.environment so PROD/TEST are preserved.
    environment: canonicalBasicValues.environment || 'DEVELOP',
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
