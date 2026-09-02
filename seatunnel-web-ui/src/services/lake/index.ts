import HttpUtils from '@/utils/HttpUtils';
import type {
  LakeApiResponse,
  LakeCatalog,
  LakeDeleteImpact,
  LakeInventoryTable,
  LakeLifecyclePolicy,
  LakeLifecycleValidation,
  LakeLogicalCapability,
  LakeManagedTable,
  LakeManagedTablePreview,
  LakeOdsDatabase,
  LakePage,
  LakePhysicalDataSource,
  LakePhysicalInventory,
  LakeRecommendation,
  LakeReadOnlyQueryResult,
  LakeReadOnlyQueryPreview,
  LakeQueryColumnOption,
  LakeResourceOperation,
} from './types';

export * from './types';

const LAKE = '/api/v1/lake';
const PHYSICAL = `${LAKE}/physical`;
const LIFECYCLE = `${LAKE}/lifecycle`;
const LOGICAL = `${LAKE}/logical`;
const pathId = (id: string | number) => encodeURIComponent(String(id));

/** Converts the existing PaginationResult shape to the ProTable request shape. */
export function normalizeLakePage<T>(data?: LakePage<T> | T[] | null): {
  data: T[];
  total: number;
  current: number;
  pageSize: number;
} {
  if (Array.isArray(data)) {
    return { data, total: data.length, current: 1, pageSize: data.length || 10 };
  }
  const rows = Array.isArray(data?.bizData) ? data.bizData : [];
  return {
    data: rows,
    total: Number(data?.pagination?.total || rows.length),
    current: Number(data?.pagination?.pageNo || 1),
    pageSize: Number(data?.pagination?.pageSize || 10),
  };
}

export async function recommendLakeMode(payload: Record<string, unknown>): Promise<LakeApiResponse<LakeRecommendation>> {
  return HttpUtils.post(`${LAKE}/recommend`, payload);
}

export async function fetchPhysicalSources(
  params: { pageNo: number; pageSize: number; keyword?: string; resourceStatus?: string },
): Promise<LakeApiResponse<LakePage<LakePhysicalDataSource>>> {
  return HttpUtils.post(`${PHYSICAL}/datasources/page`, params);
}

export async function fetchPhysicalSource(sourceDataSourceId: string | number): Promise<LakeApiResponse<LakePhysicalDataSource>> {
  return HttpUtils.get(`${PHYSICAL}/datasources/${pathId(sourceDataSourceId)}`);
}

export async function createOdsDatabase(
  sourceDataSourceId: string | number,
  customName: string,
): Promise<LakeApiResponse<LakeOdsDatabase>> {
  return HttpUtils.post(`${PHYSICAL}/datasources/${pathId(sourceDataSourceId)}/database`, { customName });
}

export async function retryOdsDatabase(id: string | number): Promise<LakeApiResponse<LakeOdsDatabase>> {
  return HttpUtils.post(`${PHYSICAL}/databases/${pathId(id)}/retry`);
}

export async function reconcileOdsDatabase(id: string | number): Promise<LakeApiResponse<LakeOdsDatabase>> {
  return HttpUtils.post(`${PHYSICAL}/databases/${pathId(id)}/reconcile`);
}

export async function deleteOdsDatabase(id: string | number): Promise<LakeApiResponse<void>> {
  return HttpUtils.delete(`${PHYSICAL}/databases/${pathId(id)}`);
}

export async function fetchPhysicalInventory(id: string | number): Promise<LakeApiResponse<LakePhysicalInventory>> {
  return HttpUtils.get(`${PHYSICAL}/databases/${pathId(id)}/inventory`);
}

export async function previewManagedTable(
  payload: Record<string, unknown>,
): Promise<LakeApiResponse<LakeManagedTablePreview>> {
  return HttpUtils.post(`${PHYSICAL}/tables/preview`, payload);
}

export async function createManagedTable(previewToken: string): Promise<LakeApiResponse<LakeManagedTable>> {
  return HttpUtils.post(`${PHYSICAL}/tables`, { previewToken });
}

export async function fetchManagedTable(id: string | number): Promise<LakeApiResponse<LakeManagedTable>> {
  return HttpUtils.get(`${PHYSICAL}/tables/${pathId(id)}`);
}

export async function retryManagedTable(id: string | number): Promise<LakeApiResponse<LakeManagedTable>> {
  return HttpUtils.post(`${PHYSICAL}/tables/${pathId(id)}/retry`);
}

export async function reconcileManagedTable(id: string | number): Promise<LakeApiResponse<LakeManagedTable>> {
  return HttpUtils.post(`${PHYSICAL}/tables/${pathId(id)}/reconcile`);
}

export async function fetchManagedTableDeleteImpact(id: string | number): Promise<LakeApiResponse<LakeDeleteImpact>> {
  return HttpUtils.get(`${PHYSICAL}/tables/${pathId(id)}/delete-impact`);
}

export async function deleteManagedTable(
  id: string | number,
  payload: { targetTableName: string; impactHash?: string },
): Promise<LakeApiResponse<void>> {
  return HttpUtils.delete(`${PHYSICAL}/tables/${pathId(id)}`, payload);
}

export async function bindUnmanagedTable(payload: Record<string, unknown>): Promise<LakeApiResponse<LakeManagedTable>> {
  return HttpUtils.post(`${PHYSICAL}/unmanaged/bind`, payload);
}

export async function unbindUnmanagedTable(id: string | number): Promise<LakeApiResponse<LakeManagedTable>> {
  return HttpUtils.delete(`${PHYSICAL}/unmanaged/${pathId(id)}/binding`);
}

export async function fetchLifecyclePolicies(
  params: { pageNo: number; pageSize: number; policyName?: string; status?: string; granularity?: string },
): Promise<LakeApiResponse<LakePage<LakeLifecyclePolicy>>> {
  return HttpUtils.post(`${LIFECYCLE}/policies/page`, params);
}

export async function createLifecyclePolicy(payload: Record<string, unknown>): Promise<LakeApiResponse<LakeLifecyclePolicy>> {
  return HttpUtils.post(`${LIFECYCLE}/policies`, payload);
}

export async function updateLifecyclePolicy(
  id: string | number,
  payload: Record<string, unknown>,
): Promise<LakeApiResponse<LakeLifecyclePolicy>> {
  return HttpUtils.put(`${LIFECYCLE}/policies/${pathId(id)}`, payload);
}

export async function disableLifecyclePolicy(
  id: string | number,
  payload: { expectedVersion: number },
): Promise<LakeApiResponse<LakeLifecyclePolicy>> {
  return HttpUtils.post(`${LIFECYCLE}/policies/${pathId(id)}/disable`, payload);
}

export async function validateLifecycle(payload: {
  mappingId: number;
  policyId: number;
}): Promise<LakeApiResponse<LakeLifecycleValidation>> {
  return HttpUtils.post(`${LIFECYCLE}/validate`, payload);
}

export async function applyLifecycle(payload: {
  mappingId: number;
  policyId: number;
}): Promise<LakeApiResponse<LakeLifecycleValidation>> {
  return HttpUtils.post(`${LIFECYCLE}/apply`, payload);
}

export async function previewRetention(
  mappingId: string | number,
  payload: { policyId: number },
): Promise<LakeApiResponse<Record<string, unknown>>> {
  return HttpUtils.post(`${LIFECYCLE}/tables/${pathId(mappingId)}/retention/preview`, payload);
}

export async function updateRetention(
  mappingId: string | number,
  payload: { policyId: number; confirmationToken?: string },
): Promise<LakeApiResponse<LakeLifecycleValidation>> {
  return HttpUtils.put(`${LIFECYCLE}/tables/${pathId(mappingId)}/retention`, payload);
}

/** Cached lifecycle detail; this GET never triggers a remote observation. */
export async function fetchLifecycleDetail(
  mappingId: string | number,
): Promise<LakeApiResponse<LakeLifecycleValidation>> {
  return HttpUtils.get(`${LIFECYCLE}/tables/${pathId(mappingId)}`);
}

export async function fetchCatalogCapability(
  sourceDataSourceId: string | number,
  params?: { adapter?: string; scope?: string },
): Promise<LakeApiResponse<LakeLogicalCapability>> {
  const query = new URLSearchParams();
  if (params?.adapter) query.set('adapter', params.adapter);
  if (params?.scope) query.set('scope', params.scope);
  const suffix = query.toString() ? `?${query.toString()}` : '';
  return HttpUtils.get(`${LOGICAL}/datasources/${pathId(sourceDataSourceId)}/capability${suffix}`);
}

/** Explicitly probes the source from Doris FE/BE; unlike capability GET this may create/drop a temporary catalog. */
export async function probeCatalogCapability(
  sourceDataSourceId: string | number,
  params?: { adapter?: string; scope?: string },
): Promise<LakeApiResponse<LakeLogicalCapability>> {
  const query = new URLSearchParams();
  if (params?.adapter) query.set('adapter', params.adapter);
  if (params?.scope) query.set('scope', params.scope);
  const suffix = query.toString() ? `?${query.toString()}` : '';
  return HttpUtils.post(`${LOGICAL}/datasources/${pathId(sourceDataSourceId)}/capability/probe${suffix}`);
}

export async function fetchCatalogs(
  params: Record<string, unknown>,
): Promise<LakeApiResponse<LakePage<LakeCatalog>>> {
  return HttpUtils.post(`${LOGICAL}/catalogs/page`, params);
}

export async function createCatalog(payload: Record<string, unknown>): Promise<LakeApiResponse<LakeCatalog>> {
  return HttpUtils.post(`${LOGICAL}/catalogs`, payload);
}

export async function updateCatalog(
  id: string | number,
  payload: Record<string, unknown>,
): Promise<LakeApiResponse<LakeCatalog>> {
  return HttpUtils.put(`${LOGICAL}/catalogs/${pathId(id)}`, payload);
}

export async function fetchCatalog(id: string | number): Promise<LakeApiResponse<LakeCatalog>> {
  return HttpUtils.get(`${LOGICAL}/catalogs/${pathId(id)}`);
}

export async function validateCatalog(id: string | number): Promise<LakeApiResponse<LakeCatalog>> {
  return HttpUtils.post(`${LOGICAL}/catalogs/${pathId(id)}/validate`);
}

export async function refreshCatalog(id: string | number): Promise<LakeApiResponse<LakeCatalog>> {
  return HttpUtils.post(`${LOGICAL}/catalogs/${pathId(id)}/refresh`);
}

export async function reconcileCatalog(id: string | number): Promise<LakeApiResponse<LakeCatalog>> {
  return HttpUtils.post(`${LOGICAL}/catalogs/${pathId(id)}/reconcile`);
}

export async function deleteCatalog(id: string | number): Promise<LakeApiResponse<LakeCatalog>> {
  return HttpUtils.delete(`${LOGICAL}/catalogs/${pathId(id)}`);
}

export async function queryCatalogSingle(
  id: string | number,
  payload: Record<string, unknown>,
): Promise<LakeApiResponse<LakeReadOnlyQueryResult>> {
  return HttpUtils.post(`${LOGICAL}/query/single-table?catalogBindingId=${pathId(id)}`, payload);
}

export async function previewCatalogSingle(
  id: string | number,
  payload: Record<string, unknown>,
): Promise<LakeApiResponse<LakeReadOnlyQueryPreview>> {
  return HttpUtils.post(`${LOGICAL}/query/single-table/preview?catalogBindingId=${pathId(id)}`, payload);
}

export async function queryCatalogJoin(
  payload: Record<string, unknown>,
): Promise<LakeApiResponse<LakeReadOnlyQueryResult>> {
  return HttpUtils.post(`${LOGICAL}/query/join`, payload);
}

export async function previewCatalogJoin(
  payload: Record<string, unknown>,
): Promise<LakeApiResponse<LakeReadOnlyQueryPreview>> {
  return HttpUtils.post(`${LOGICAL}/query/join/preview`, payload);
}

export async function fetchCatalogQueryDatabases(
  id: string | number,
): Promise<LakeApiResponse<string[]>> {
  return HttpUtils.get(`${LOGICAL}/query/catalogs/${pathId(id)}/databases`);
}

export async function fetchCatalogQueryTables(
  id: string | number,
  database: string,
): Promise<LakeApiResponse<string[]>> {
  return HttpUtils.get(`${LOGICAL}/query/catalogs/${pathId(id)}/tables?database=${encodeURIComponent(database)}`);
}

export async function fetchCatalogQueryColumns(
  id: string | number,
  database: string,
  table: string,
): Promise<LakeApiResponse<LakeQueryColumnOption[]>> {
  return HttpUtils.get(`${LOGICAL}/query/catalogs/${pathId(id)}/columns?database=${encodeURIComponent(database)}&table=${encodeURIComponent(table)}`);
}

export async function cancelCatalogQuery(queryId: string): Promise<LakeApiResponse<boolean>> {
  return HttpUtils.post(`${LOGICAL}/query/cancel/${pathId(queryId)}`);
}

export async function fetchLakeOperations(
  resourceType: string,
  resourceId: string | number,
): Promise<LakeApiResponse<LakeResourceOperation[]>> {
  return HttpUtils.get(`${LAKE}/operations/${pathId(resourceType)}/${pathId(resourceId)}`);
}
