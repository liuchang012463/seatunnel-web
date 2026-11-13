import HttpUtils, { type RequestOptions } from '@/utils/HttpUtils';
import type { FileResource } from '../types';

const OFFLINE_IMPORT_API_PREFIX = '/api/v1/offline-import-tasks';

/** DMP/SQL types plus the encrypted single-table package import. */
export type OfflineImportTypeCode = 'KINGBASE_DMP' | 'DAMENG_DMP' | 'KINGBASE_SQL' | 'DAMENG_SQL' | 'DATAPACK';

export type OfflineImportCategory = 'DMP' | 'SQL' | 'DATAPACK';

export interface OfflineDatapackFieldMapping {
  sourceField: string;
  targetField: string;
}

export interface OfflineDatapackTaskConfig {
  packageFingerprint: string;
  formCode: string;
  targetTable: string;
  mappings: OfflineDatapackFieldMapping[];
}

export interface OfflineDatapackForm {
  formCode: string;
  recordCount: number;
  fields: string[];
}

export interface OfflineDatapackInspect {
  packageFingerprint: string;
  packageId: string;
  packageType: 'DATA' | 'DISTRIBUTION';
  recordCount: number;
  forms: OfflineDatapackForm[];
}

export interface OfflineDatapackCsvInspect {
  packageFingerprint: string;
  fileNames: string[];
}

export interface OfflineDatapackCsvSelection {
  fileResource: FileResource;
  schema: Record<string, string>;
  nullValue: string;
}

export interface OfflineImportOptions {
  stopOnError?: boolean;
  encoding?: string;
  /** DMP source-schema filter or SQL source schema for qualified-name mapping. */
  sourceSchema?: string;
  /** Used by Dameng DMP imports; Kingbase DMP has no equivalent option. */
  ignoreErrors?: boolean;
}

export interface OfflineImportTask {
  id: string;
  name: string;
  description?: string;
  importType: OfflineImportTypeCode;
  fileResourceId: string;
  fileName: string;
  targetDatasourceId: string;
  targetDatasourceName?: string;
  targetDatabase?: string;
  targetSchema?: string;
  options?: OfflineImportOptions;
  datapackConfig?: OfflineDatapackTaskConfig;
  scheduleConfig?: Record<string, any>;
  releaseState: 'ONLINE' | 'OFFLINE';
  status: 'PENDING' | 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED';
  totalStatements?: number;
  executedStatements?: number;
  failedStatements?: number;
  errorMessage?: string;
  executionLog?: string;
  startTime?: string;
  endTime?: string;
  createTime?: string;
  updateTime?: string;
}

export interface OfflineImportTaskRun {
  id: string;
  taskName: string;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED';
  totalStatements?: number;
  executedStatements?: number;
  failedStatements?: number;
  errorMessage?: string;
  executionLog?: string;
  startTime?: string;
  endTime?: string;
}

export interface OfflineImportTaskPageParams {
  pageNo?: number;
  pageSize?: number;
  name?: string;
  importType?: string;
  status?: string;
  createTimeStart?: string;
  createTimeEnd?: string;
}

export interface OfflineImportTaskCreatePayload {
  name: string;
  description?: string;
  importType: OfflineImportTypeCode;
  fileResourceId: string | number;
  targetDatasourceId: string | number;
  targetDatabase?: string;
  targetSchema?: string;
  options?: OfflineImportOptions;
  datapackConfig?: OfflineDatapackTaskConfig;
  /** 仅随本次保存请求传输，服务端不会持久化或回显。 */
  datapackPassword?: string;
  scheduleConfig?: Record<string, any>;
}

export const offlineImportTargetDbType = (importType: OfflineImportTypeCode): string =>
  importType.startsWith('KINGBASE') ? 'KINGBASE' : importType === 'DATAPACK' ? 'MYSQL' : 'DAMENG';

export const OFFLINE_IMPORT_TYPE_LABELS: Record<OfflineImportTypeCode, string> = {
  KINGBASE_DMP: '人大金仓 DMP',
  DAMENG_DMP: '达梦 DMP',
  KINGBASE_SQL: '人大金仓 SQL',
  DAMENG_SQL: '达梦 SQL',
  DATAPACK: '离线数据包',
};

export const offlineImportTaskApi = {
  page: (params: OfflineImportTaskPageParams = {}, options?: RequestOptions) =>
    HttpUtils.get(
      `${OFFLINE_IMPORT_API_PREFIX}/page?${Object.entries(params)
        .filter(([, value]) => value !== undefined && value !== '')
        .map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`)
        .join('&')}`,
      options,
    ),

  create: (payload: OfflineImportTaskCreatePayload, options?: RequestOptions) =>
    HttpUtils.post<Pick<OfflineImportTask, 'id'>>(OFFLINE_IMPORT_API_PREFIX, payload, options),

  update: (id: string | number, payload: OfflineImportTaskCreatePayload) =>
    HttpUtils.put(`${OFFLINE_IMPORT_API_PREFIX}/${encodeURIComponent(String(id))}`, payload),

  get: (id: string | number, options?: RequestOptions) =>
    HttpUtils.get(`${OFFLINE_IMPORT_API_PREFIX}/${encodeURIComponent(String(id))}`, options),

  remove: (id: string | number, options?: RequestOptions) =>
    HttpUtils.delete(
      `${OFFLINE_IMPORT_API_PREFIX}/${encodeURIComponent(String(id))}`,
      undefined,
      options,
    ),

  execute: (id: string | number, options?: RequestOptions) =>
    HttpUtils.post(`${OFFLINE_IMPORT_API_PREFIX}/${encodeURIComponent(String(id))}/execute`, undefined, options),

  executeDatapack: (id: string | number, password: string, options?: RequestOptions) =>
    HttpUtils.post(
      `${OFFLINE_IMPORT_API_PREFIX}/${encodeURIComponent(String(id))}/execute`,
      { password },
      options,
    ),

  inspectDatapack: (fileResourceId: string | number, password: string, options?: RequestOptions) =>
    HttpUtils.post<OfflineDatapackInspect>(`${OFFLINE_IMPORT_API_PREFIX}/datapack/inspect`, {
      fileResourceId,
      password,
    }, options),

  inspectDatapackCsv: (fileResourceId: string | number, password: string, options?: RequestOptions) =>
    HttpUtils.post<OfflineDatapackCsvInspect>(`${OFFLINE_IMPORT_API_PREFIX}/datapack/csv/inspect`, {
      fileResourceId,
      password,
    }, options),

  selectDatapackCsv: (payload: {
    fileResourceId: string | number;
    password: string;
    packageFingerprint: string;
    fileName: string;
  }, options?: RequestOptions) =>
    HttpUtils.post<OfflineDatapackCsvSelection>(`${OFFLINE_IMPORT_API_PREFIX}/datapack/csv/select`, payload, options),

  online: (id: string | number) =>
    HttpUtils.put(`${OFFLINE_IMPORT_API_PREFIX}/${encodeURIComponent(String(id))}/online`),

  offline: (id: string | number) =>
    HttpUtils.put(`${OFFLINE_IMPORT_API_PREFIX}/${encodeURIComponent(String(id))}/offline`),

  history: (id: string | number, options?: RequestOptions) =>
    HttpUtils.get<OfflineImportTaskRun[]>(
      `${OFFLINE_IMPORT_API_PREFIX}/${encodeURIComponent(String(id))}/history`,
      options,
    ),
};
