import HttpUtils from '@/utils/HttpUtils';
import {
  fetchDataExplorationDatabases,
  fetchDataExplorationSchemas,
  fetchDataExplorationTable,
  fetchDataExplorationTables,
} from '@/pages/data-source/service';
import type {
  ApiResponse,
  DeleteImpact,
  LakePage,
  ManagedTable,
  ManagedTablePreview,
  OdsDatabase,
  OdsSourceTable,
  OdsSourceTableDetail,
  PhysicalDataSource,
  PhysicalInventory,
} from './types';

const PHYSICAL_API = '/api/v1/lake/physical';

export const fetchPhysicalSources = (params: Record<string, unknown>): Promise<ApiResponse<LakePage<PhysicalDataSource>>> =>
  HttpUtils.post(`${PHYSICAL_API}/datasources/page`, params);

export const fetchPhysicalSource = (sourceDataSourceId: string | number): Promise<ApiResponse<PhysicalDataSource>> =>
  HttpUtils.get(`${PHYSICAL_API}/datasources/${encodeURIComponent(String(sourceDataSourceId))}`);

export const fetchReadyOdsDatabases = (sourceDataSourceId: number): Promise<ApiResponse<OdsDatabase[]>> =>
  HttpUtils.get(`${PHYSICAL_API}/datasources/${encodeURIComponent(String(sourceDataSourceId))}/ready-databases`);

export const createOdsDatabase = async (sourceDataSourceId: string | number, customName: string): Promise<OdsDatabase> => {
  const response: ApiResponse<OdsDatabase> = await HttpUtils.post(
    `${PHYSICAL_API}/datasources/${encodeURIComponent(String(sourceDataSourceId))}/database`, { customName },
  );
  const database = response.data;
  if (response.code !== 0 || !database) {
    throw new Error(response.message || response.msg || '入湖库准备失败，请检查数据源的单位和业务系统归属');
  }
  if (database.sourceDataSourceId != null && String(database.sourceDataSourceId) !== String(sourceDataSourceId)) {
    console.warn('[lake-physical] 新入湖库的资源归属不匹配', { sourceDataSourceId, bindingId: database.id });
    throw new Error('入湖库与当前数据源不匹配，请刷新后重试。');
  }
  return database;
};

export const retryOdsDatabase = (id: number): Promise<ApiResponse<OdsDatabase>> =>
  HttpUtils.post(`${PHYSICAL_API}/databases/${encodeURIComponent(String(id))}/retry`);

export const reconcileOdsDatabase = (id: number): Promise<ApiResponse<OdsDatabase>> =>
  HttpUtils.post(`${PHYSICAL_API}/databases/${encodeURIComponent(String(id))}/reconcile`);

export const fetchPhysicalInventory = (bindingId: number): Promise<ApiResponse<PhysicalInventory>> =>
  HttpUtils.get(`${PHYSICAL_API}/databases/${encodeURIComponent(String(bindingId))}/inventory`);

export const previewManagedTable = (payload: Record<string, unknown>): Promise<ApiResponse<ManagedTablePreview>> =>
  HttpUtils.post(`${PHYSICAL_API}/tables/preview`, payload);

export const createManagedTable = (planFingerprint: string): Promise<ApiResponse<ManagedTable>> =>
  HttpUtils.post(`${PHYSICAL_API}/tables`, { planFingerprint });

export const fetchManagedTable = (mappingId: number): Promise<ApiResponse<ManagedTable>> =>
  HttpUtils.get(`${PHYSICAL_API}/tables/${encodeURIComponent(String(mappingId))}`);

export const retryManagedTable = (mappingId: number): Promise<ApiResponse<ManagedTable>> =>
  HttpUtils.post(`${PHYSICAL_API}/tables/${encodeURIComponent(String(mappingId))}/retry`);

export const reconcileManagedTable = (mappingId: number): Promise<ApiResponse<ManagedTable>> =>
  HttpUtils.post(`${PHYSICAL_API}/tables/${encodeURIComponent(String(mappingId))}/reconcile`);

export const fetchManagedTableDeleteImpact = (mappingId: number): Promise<ApiResponse<DeleteImpact>> =>
  HttpUtils.get(`${PHYSICAL_API}/tables/${encodeURIComponent(String(mappingId))}/delete-impact`);

export const deleteManagedTable = (
  mappingId: number,
  payload: { targetTableName: string; impactHash: string },
): Promise<ApiResponse<void>> => HttpUtils.delete(`${PHYSICAL_API}/tables/${encodeURIComponent(String(mappingId))}`, payload);

export const bindUnmanagedTable = (payload: {
  odsDatabaseBindingId: number;
  targetTableName: string;
  sourceDataSourceId: number;
  omEntityId: string;
}): Promise<ApiResponse<ManagedTable>> => HttpUtils.post(`${PHYSICAL_API}/unmanaged/bind`, payload);

export const unbindUnmanagedTable = (mappingId: number): Promise<ApiResponse<ManagedTable>> =>
  HttpUtils.delete(`${PHYSICAL_API}/unmanaged/${encodeURIComponent(String(mappingId))}/binding`);

export const fetchSourceDatabases = (sourceId: number) => fetchDataExplorationDatabases(String(sourceId));
export const fetchSourceSchemas = (sourceId: number, databaseFqn: string) =>
  fetchDataExplorationSchemas(String(sourceId), databaseFqn);
export const fetchSourceTables = (sourceId: number, databaseFqn: string, schemaFqn: string) =>
  fetchDataExplorationTables(String(sourceId), databaseFqn, schemaFqn, 1, 200);
export const fetchSourceTableDetail = (sourceId: number, tableId: string) =>
  fetchDataExplorationTable(String(sourceId), tableId) as Promise<ApiResponse<OdsSourceTableDetail>>;

export type { OdsSourceTable };

export const fetchTaskPrefill = (mappingId: number): Promise<ApiResponse<ManagedTable>> =>
  HttpUtils.get(`${PHYSICAL_API}/tables/${encodeURIComponent(String(mappingId))}/task-prefill`);
