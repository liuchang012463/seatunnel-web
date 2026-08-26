import HttpUtils from '@/utils/HttpUtils';
import type {
  BusinessSystemOption,
  CommonApiResponse,
  DataSourceLifecycleStatus,
  DataSourcePageParams,
  DataSourcePageResult,
  DataSourceRecord,
  DataSourceUnitOption,
  DataExplorationDatabase,
  DataExplorationSchema,
  DataExplorationTablePage,
  DataExplorationTableDetail,
  DataExplorationProfile,
  DataExplorationPreview,
  DataInventoryDistributionItem,
  DataInventoryFilter,
  DataInventoryProfileCoverage,
  DataInventorySummary,
  DataSourceTopologyNode,
  DataSourceTopologyNodeType,
} from './types';

const DATA_SOURCE_API_PREFIX = '/api/v1/data-source';
const DATA_SOURCE_UNIT_API_PREFIX = '/api/v1/data-source-units';
const BUSINESS_SYSTEM_API_PREFIX = '/api/v1/business-systems';
const DATA_EXPLORATION_API_PREFIX = '/api/v1/data-exploration';
const DATA_INVENTORY_API_PREFIX = '/api/v1/data-inventory';
const DATA_SOURCE_TOPOLOGY_API_PREFIX = '/api/v1/data-source-topology';

export type MasterDataList<T> =
  | T[]
  | {
      bizData: T[];
      pagination: {
        pageNo: number;
        pageSize: number;
        total: number;
      };
    };

export type MasterDataListResponse<T> = CommonApiResponse<MasterDataList<T>>;

export function unwrapMasterDataList<T>(response?: MasterDataListResponse<T>): T[] {
  const data = response?.data;
  return Array.isArray(data) ? data : data?.bizData || [];
}

export async function fetchDataSourcePage(
  params: DataSourcePageParams,
): Promise<CommonApiResponse<DataSourcePageResult>> {
  return HttpUtils.post(`${DATA_SOURCE_API_PREFIX}/page`, params);
}

export async function fetchDataSourceDetail(id: string): Promise<CommonApiResponse<DataSourceRecord>> {
  return HttpUtils.get(`${DATA_SOURCE_API_PREFIX}/${id}`);
}

export async function fetchDataSourceAll(): Promise<CommonApiResponse<DataSourcePageResult>> {
  return HttpUtils.get(`${DATA_SOURCE_API_PREFIX}/all`);
}

export async function createDataSource(payload: Record<string, unknown>): Promise<CommonApiResponse<boolean>> {
  return HttpUtils.post(DATA_SOURCE_API_PREFIX, payload);
}

export async function updateDataSource(
  id: string,
  payload: Record<string, unknown>,
): Promise<CommonApiResponse<boolean>> {
  return HttpUtils.put(`${DATA_SOURCE_API_PREFIX}/${id}`, payload);
}

export async function selectDataSourceById(id: any): Promise<any> {
  return HttpUtils.get(`${DATA_SOURCE_API_PREFIX}/${id}`);
}

export async function deleteDataSource(id: string): Promise<CommonApiResponse<boolean>> {
  return HttpUtils.delete(`${DATA_SOURCE_API_PREFIX}/${id}`);
}

export async function checkDataSourceUsage(id: string): Promise<CommonApiResponse<boolean>> {
  return HttpUtils.get(`${DATA_SOURCE_API_PREFIX}/${id}/usage`);
}

export async function updateDataSourceStatus(
  id: string,
  status: DataSourceLifecycleStatus,
): Promise<CommonApiResponse<boolean>> {
  return HttpUtils.put(`${DATA_SOURCE_API_PREFIX}/${id}/status`, { status });
}

export async function testDataSourceConnection(id: string): Promise<CommonApiResponse<boolean>> {
  return HttpUtils.get(`${DATA_SOURCE_API_PREFIX}/${id}/connect-test`);
}

export async function triggerDataSourceScan(id: string): Promise<CommonApiResponse<boolean>> {
  return HttpUtils.post(`${DATA_SOURCE_API_PREFIX}/${id}/scan`);
}

export async function triggerDataSourceExploration(
  id: string,
  databaseFqn: string,
): Promise<CommonApiResponse<boolean>> {
  return HttpUtils.post(`${DATA_SOURCE_API_PREFIX}/${id}/explore`, { databaseFqn });
}

export async function fetchDataSourceMetadataDatabases(
  id: string,
): Promise<CommonApiResponse<Array<{ value: string; label: string }>>> {
  return HttpUtils.get(`${DATA_SOURCE_API_PREFIX}/${id}/metadata-databases`);
}

export async function fetchDataSourceMetadataRuns(
  id: string,
  type: 'SCAN' | 'EXPLORATION',
  limit = 5,
): Promise<CommonApiResponse<Array<{ runId: string; status: string; startTime?: string; endTime?: string }>>> {
  return HttpUtils.get(`${DATA_SOURCE_API_PREFIX}/${id}/runs?type=${type}&limit=${limit}`);
}

export async function fetchDataExplorationDatabases(
  id: string,
): Promise<CommonApiResponse<DataExplorationDatabase[]>> {
  return HttpUtils.get(`${DATA_EXPLORATION_API_PREFIX}/databases?dataSourceId=${encodeURIComponent(id)}`);
}

export async function fetchDataExplorationSchemas(
  id: string,
  databaseFqn: string,
): Promise<CommonApiResponse<DataExplorationSchema[]>> {
  return HttpUtils.get(
    `${DATA_EXPLORATION_API_PREFIX}/schemas?dataSourceId=${encodeURIComponent(id)}&databaseFqn=${encodeURIComponent(databaseFqn)}`,
  );
}

export async function fetchDataExplorationTables(
  id: string,
  databaseFqn: string,
  schemaFqn: string,
  pageNo = 1,
  pageSize = 20,
): Promise<CommonApiResponse<DataExplorationTablePage>> {
  const query = new URLSearchParams({
    dataSourceId: id,
    databaseFqn,
    schemaFqn,
    pageNo: String(pageNo),
    pageSize: String(pageSize),
  });
  return HttpUtils.get(`${DATA_EXPLORATION_API_PREFIX}/tables?${query.toString()}`);
}

export async function fetchDataExplorationTable(
  id: string,
  tableId: string,
): Promise<CommonApiResponse<DataExplorationTableDetail>> {
  return HttpUtils.get(
    `${DATA_EXPLORATION_API_PREFIX}/tables/${encodeURIComponent(tableId)}?dataSourceId=${encodeURIComponent(id)}`,
  );
}

export async function fetchDataExplorationProfile(
  id: string,
  tableId: string,
): Promise<CommonApiResponse<DataExplorationProfile>> {
  return HttpUtils.get(
    `${DATA_EXPLORATION_API_PREFIX}/tables/${encodeURIComponent(tableId)}/profile?dataSourceId=${encodeURIComponent(id)}`,
  );
}

export async function previewDataExplorationTable(
  id: string,
  tableId: string,
): Promise<CommonApiResponse<DataExplorationPreview>> {
  return HttpUtils.post(
    `${DATA_EXPLORATION_API_PREFIX}/tables/${encodeURIComponent(tableId)}/preview?dataSourceId=${encodeURIComponent(id)}`,
    {},
  );
}

export async function fetchDataInventorySummary(
  filter: DataInventoryFilter = {},
): Promise<CommonApiResponse<DataInventorySummary>> {
  const query = new URLSearchParams();
  Object.entries(filter).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      query.set(key, String(value));
    }
  });
  const suffix = query.toString() ? `?${query.toString()}` : '';
  return HttpUtils.get(`${DATA_INVENTORY_API_PREFIX}/summary${suffix}`);
}

export async function fetchDataInventorySourceTypes(
  filter: DataInventoryFilter = {},
): Promise<CommonApiResponse<DataInventoryDistributionItem[]>> {
  return fetchInventoryDistribution('source-type', filter);
}

export async function fetchDataInventoryUnits(
  filter: DataInventoryFilter = {},
): Promise<CommonApiResponse<DataInventoryDistributionItem[]>> {
  return fetchInventoryDistribution('unit', filter);
}

export async function fetchDataInventoryBusinessSystems(
  filter: DataInventoryFilter = {},
): Promise<CommonApiResponse<DataInventoryDistributionItem[]>> {
  return fetchInventoryDistribution('business-system', filter);
}

export async function fetchDataInventoryProfileCoverage(
  filter: DataInventoryFilter = {},
): Promise<CommonApiResponse<DataInventoryProfileCoverage>> {
  const query = new URLSearchParams();
  Object.entries(filter).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      query.set(key, String(value));
    }
  });
  const suffix = query.toString() ? `?${query.toString()}` : '';
  return HttpUtils.get(`${DATA_INVENTORY_API_PREFIX}/profile-coverage${suffix}`);
}

async function fetchInventoryDistribution(
  dimension: string,
  filter: DataInventoryFilter,
): Promise<CommonApiResponse<DataInventoryDistributionItem[]>> {
  const query = new URLSearchParams();
  Object.entries(filter).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      query.set(key, String(value));
    }
  });
  const suffix = query.toString() ? `?${query.toString()}` : '';
  return HttpUtils.get(`${DATA_INVENTORY_API_PREFIX}/distribution/${dimension}${suffix}`);
}

export async function downloadDataExplorationExport(filter: DataInventoryFilter = {}): Promise<any> {
  return HttpUtils.downloadPost(`${DATA_EXPLORATION_API_PREFIX}/export`, filter);
}

export async function fetchDataSourceTopologyTree(
  filter: Pick<DataInventoryFilter, 'unitId' | 'businessSystemId' | 'dataSourceId'> = {},
): Promise<CommonApiResponse<DataSourceTopologyNode[]>> {
  const query = new URLSearchParams();
  Object.entries(filter).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      query.set(key, String(value));
    }
  });
  const suffix = query.toString() ? `?${query.toString()}` : '';
  return HttpUtils.get(`${DATA_SOURCE_TOPOLOGY_API_PREFIX}/tree${suffix}`);
}

export async function fetchDataSourceTopologyChildren(
  nodeType: DataSourceTopologyNodeType,
  nodeId: string,
): Promise<CommonApiResponse<DataSourceTopologyNode[]>> {
  const query = new URLSearchParams({ nodeType, nodeId });
  return HttpUtils.get(`${DATA_SOURCE_TOPOLOGY_API_PREFIX}/children?${query.toString()}`);
}

export async function testDataSourceConnectionWithParams(
  payload: Record<string, unknown>,
): Promise<CommonApiResponse<boolean>> {
  return HttpUtils.post(`${DATA_SOURCE_API_PREFIX}/connect-test-with-param`, payload);
}

/**
 * Loads active data-source owning units for the unit/system selectors.
 * The endpoint returns a Result containing the active option list.
 */
export async function fetchDataSourceUnitOptions(): Promise<MasterDataListResponse<DataSourceUnitOption>> {
  return HttpUtils.get(`${DATA_SOURCE_UNIT_API_PREFIX}/active`);
}

/**
 * Loads active business systems belonging to a selected unit.
 */
export async function fetchBusinessSystemOptions(
  unitId: string | number,
): Promise<MasterDataListResponse<BusinessSystemOption>> {
  const query = `?unitId=${encodeURIComponent(String(unitId))}`;
  return HttpUtils.get(`${BUSINESS_SYSTEM_API_PREFIX}/active${query}`);
}

/** @deprecated Use fetchDataSourceUnitOptions. Kept as a compatibility alias for page integrations. */
export async function fetchDataSourceUnits(): Promise<MasterDataListResponse<DataSourceUnitOption>> {
  return fetchDataSourceUnitOptions();
}

export interface DataSourceOptionRecord {
  id?: string | number;
  value?: string | number;
  name?: string;
  label?: string;
  dbType?: string;
  [key: string]: unknown;
}

export async function fetchDataSourceOptions(dbType: string): Promise<CommonApiResponse<DataSourceOptionRecord[]>> {
  return HttpUtils.get(`${DATA_SOURCE_API_PREFIX}/option?dbType=${dbType}`);
}

export const apiPrefixCatalog = '/api/v1/data-source/catalog';

export const dataSourceCatalogApi = {
  listFiles: (id: string, path?: string): Promise<{ code: number; data: any[]; message?: string }> => {
    const query = path ? `?path=${encodeURIComponent(path)}` : '';
    return HttpUtils.get(`${apiPrefixCatalog}/files/${id}${query}`);
  },

  listTable: (id: string): Promise<{ code: number; data: any; message?: string }> => {
    return HttpUtils.get(`${apiPrefixCatalog}/list/${id}`);
  },

  listTableReference: (
    id: string,
    matchMode: any,
    keyword: any,
  ): Promise<{ code: number; data: any[]; message?: string }> => {
    return HttpUtils.get(`${apiPrefixCatalog}/listByMatchMode/${id}?matchMode=${matchMode}&keyword=${keyword}`);
  },

  count: (datasourceId: string, requestBody: any): Promise<{ code: number; data: number; message?: string }> => {
    return HttpUtils.post(`${apiPrefixCatalog}/count/${datasourceId}`, requestBody);
  },

  listColumn: (id: any, requestBody: any): Promise<{ code: number; data: any[]; message?: string }> => {
    return HttpUtils.post(`${apiPrefixCatalog}/column/${id}`, requestBody);
  },

  getTop20Data: (datasourceId: string, requestBody: any): Promise<{ code: number; data: any[]; message?: string }> => {
    return HttpUtils.post(`${apiPrefixCatalog}/getTop20Data/${datasourceId}`, requestBody);
  },

  parseHttpResponse: (
    datasourceId: string,
    requestBody: any,
  ): Promise<{ code: number; data: { status: number; body: string; json: unknown }; message?: string }> => {
    return HttpUtils.post(`${apiPrefixCatalog}/parse-http/${datasourceId}`, requestBody);
  },

  buildSqlTemplate: (
    datasourceId: string,
    requestBody: any,
  ): Promise<{ code: number; data: string; message?: string }> => {
    return HttpUtils.post(`${apiPrefixCatalog}/sql-template/${datasourceId}`, requestBody);
  },

  resolveSql: (datasourceId: string, requestBody: any): Promise<{ code: number; data: string; message?: string }> => {
    return HttpUtils.post(`${apiPrefixCatalog}/resolve-sql/${datasourceId}`, requestBody);
  },
};
