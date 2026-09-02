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
  LakePhysicalSummary,
} from './types';

const PHYSICAL_API = '/api/v1/lake/physical';

export interface LakeRecommendation {
  mode?: 'PHYSICAL' | 'LOGICAL' | 'UNSUPPORTED';
  recommendation?: 'PHYSICAL' | 'LOGICAL' | 'UNSUPPORTED';
  reason?: string;
  reasonCode?: string;
  disabledReasons?: string[];
  physicalCapability?: { supported?: boolean; disabledReasons?: string[] };
  logicalCapability?: { supported?: boolean; disabledReasons?: string[] };
  targetScope?: string;
  adapter?: string;
}

export const fetchPhysicalSources = (params: Record<string, unknown>): Promise<ApiResponse<LakePage<PhysicalDataSource>>> =>
  HttpUtils.post(`${PHYSICAL_API}/datasources/page`, params);

export const fetchPhysicalSummary = (): Promise<ApiResponse<LakePhysicalSummary>> =>
  HttpUtils.get(`${PHYSICAL_API}/summary`);

export const recommendLakeMode = (payload: Record<string, unknown>): Promise<ApiResponse<LakeRecommendation>> =>
  HttpUtils.post('/api/v1/lake/recommend', payload);

export const fetchPhysicalSource = (sourceDataSourceId: number): Promise<ApiResponse<PhysicalDataSource>> =>
  HttpUtils.get(`${PHYSICAL_API}/datasources/${encodeURIComponent(String(sourceDataSourceId))}`);

export const createOdsDatabase = (sourceDataSourceId: number, customName: string): Promise<ApiResponse<OdsDatabase>> =>
  HttpUtils.post(`${PHYSICAL_API}/datasources/${encodeURIComponent(String(sourceDataSourceId))}/database`, { customName });

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
