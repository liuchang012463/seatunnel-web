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
  DataExplorationErDiagram,
  DataExplorationMetadataJob,
  DataExplorationMetadataUpdate,
  DataInventoryDistributionItem,
  DataInventoryFilter,
  DataInventoryProfileCoverage,
  DataInventorySummary,
  DataInventoryOverview,
  DataSourceTopologyNode,
  DataSourceTopologyNodeType,
  DataSourceMetadataStatus,
  DataSourceCatalogFileEntry,
  DataSourceCatalogOption,
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

/**
 * Some legacy deployments return all matching rows but leave the MyBatis
 * pagination total at zero. Keep the page usable while the server is being
 * upgraded; a real positive total always wins.
 */
export function normalizeDataSourcePageResult(data?: Partial<DataSourcePageResult> | null): DataSourcePageResult {
  const bizData = Array.isArray(data?.bizData) ? data.bizData : [];
  const rawPagination = data?.pagination;
  const total = Number(rawPagination?.total || 0);
  return {
    bizData,
    pagination: {
      pageNo: Number(rawPagination?.pageNo || 1),
      pageSize: Number(rawPagination?.pageSize || 10),
      total: total > 0 ? total : bizData.length,
    },
  };
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

/**
 * Updates only the canonical ownership binding. Connection parameters are
 * deliberately not accepted by this endpoint, so legacy/job-referenced
 * sources can be assigned safely during a data-governance backfill.
 */
export async function assignDataSourceBusinessSystem(
  id: string,
  businessSystemId: string | number,
): Promise<CommonApiResponse<DataSourceRecord>> {
  return HttpUtils.put(`${DATA_SOURCE_API_PREFIX}/${id}/ownership`, { businessSystemId });
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

export async function fetchDataSourceMetadataStatus(
  id: string,
): Promise<CommonApiResponse<DataSourceMetadataStatus>> {
  return HttpUtils.get(`${DATA_SOURCE_API_PREFIX}/${id}/metadata-status`);
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

export async function fetchDataExplorationErDiagram(
  id: string,
  databaseFqn: string,
  schemaFqn?: string,
): Promise<CommonApiResponse<DataExplorationErDiagram>> {
  const query = new URLSearchParams({
    dataSourceId: id,
    databaseFqn,
  });
  if (schemaFqn) query.set('schemaFqn', schemaFqn);
  return HttpUtils.get(`${DATA_EXPLORATION_API_PREFIX}/er-diagram?${query.toString()}`);
}

export async function updateDataExplorationMetadata(
  id: string,
  tableId: string,
  payload: DataExplorationMetadataUpdate,
): Promise<CommonApiResponse<DataExplorationTableDetail>> {
  return HttpUtils.request(
    `${DATA_EXPLORATION_API_PREFIX}/tables/${encodeURIComponent(tableId)}/metadata?dataSourceId=${encodeURIComponent(id)}`,
    'PATCH',
    payload,
  );
}

export async function startDataExplorationMetadataCompletion(
  id: string,
  tableId: string,
): Promise<CommonApiResponse<DataExplorationMetadataJob>> {
  return HttpUtils.post(
    `${DATA_EXPLORATION_API_PREFIX}/tables/${encodeURIComponent(tableId)}/metadata-completion?dataSourceId=${encodeURIComponent(id)}`,
    {},
  );
}

export async function fetchDataExplorationMetadataCompletion(
  id: string,
  tableId: string,
  jobId: string,
): Promise<CommonApiResponse<DataExplorationMetadataJob>> {
  return HttpUtils.get(
    `${DATA_EXPLORATION_API_PREFIX}/tables/${encodeURIComponent(tableId)}/metadata-completion/jobs/${encodeURIComponent(jobId)}?dataSourceId=${encodeURIComponent(id)}`,
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

export async function fetchDataInventoryOverview(
  filter: DataInventoryFilter = {},
): Promise<CommonApiResponse<DataInventoryOverview>> {
  const query = new URLSearchParams();
  Object.entries(filter).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') query.set(key, String(value));
  });
  const suffix = query.toString() ? `?${query.toString()}` : '';
  return HttpUtils.get(`${DATA_INVENTORY_API_PREFIX}/overview${suffix}`);
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

/**
 * Catalog endpoints used by the non-database exploration workspace. These
 * typed functions sit beside the legacy dataSourceCatalogApi object so new
 * callers do not have to depend on `any` response payloads.
 */
export async function fetchDataSourceCatalogOptions(
  id: string,
): Promise<CommonApiResponse<DataSourceCatalogOption[]>> {
  return HttpUtils.get(`${apiPrefixCatalog}/list/${encodeURIComponent(id)}`);
}

export async function fetchDataSourceCatalogFiles(
  id: string,
  path?: string,
): Promise<CommonApiResponse<DataSourceCatalogFileEntry[]>> {
  const query = path ? `?path=${encodeURIComponent(path)}` : '';
  return HttpUtils.get(`${apiPrefixCatalog}/files/${encodeURIComponent(id)}${query}`);
}

export const dataSourceCatalogApi = {
  listFiles: (id: string, path?: string): Promise<{ code: number; data: any[]; message?: string }> => {
    const query = path ? `?path=${encodeURIComponent(path)}` : '';
    return HttpUtils.get(`${apiPrefixCatalog}/files/${id}${query}`);
  },

  uploadFiles: (
    id: string,
    path: string | undefined,
    files: File[],
  ): Promise<{ code: number; data: any[]; message?: string }> => {
    const formData = new FormData();
    files.forEach((file) => formData.append('files', file));
    const query = path ? `?path=${encodeURIComponent(path)}` : '';
    return HttpUtils.postForm(`${apiPrefixCatalog}/files/${id}/upload${query}`, formData);
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
