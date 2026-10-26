import HttpUtils, { RequestOptions } from '@/utils/HttpUtils';

const OFFLINE_IMPORT_API_PREFIX = '/api/v1/offline-import-tasks';

/** DMP/SQL offline import task types. Oracle SQL is listed in the UI but not yet enabled. */
export type OfflineImportTypeCode = 'KINGBASE_DMP' | 'DAMENG_DMP' | 'KINGBASE_SQL' | 'DAMENG_SQL';

export type OfflineImportCategory = 'DMP' | 'SQL';

export interface OfflineImportOptions {
  stopOnError?: boolean;
  encoding?: string;
  /** DMP source schema or SQL source schema for qualified-name mapping. */
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
  targetSchema?: string;
  options?: OfflineImportOptions;
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

export interface OfflineImportTaskPageParams {
  pageNo?: number;
  pageSize?: number;
  name?: string;
  importType?: string;
  status?: string;
}

export interface OfflineImportTaskCreatePayload {
  name: string;
  description?: string;
  importType: OfflineImportTypeCode;
  fileResourceId: string | number;
  targetDatasourceId: string | number;
  targetSchema?: string;
  options?: OfflineImportOptions;
}

export const offlineImportTargetDbType = (importType: OfflineImportTypeCode): string =>
  importType.startsWith('KINGBASE') ? 'KINGBASE' : 'DAMENG';

export const OFFLINE_IMPORT_TYPE_LABELS: Record<OfflineImportTypeCode, string> = {
  KINGBASE_DMP: '人大金仓 DMP',
  DAMENG_DMP: '达梦 DMP',
  KINGBASE_SQL: '人大金仓 SQL',
  DAMENG_SQL: '达梦 SQL',
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
    HttpUtils.post(OFFLINE_IMPORT_API_PREFIX, payload, options),

  get: (id: string | number, options?: RequestOptions) =>
    HttpUtils.get(`${OFFLINE_IMPORT_API_PREFIX}/${encodeURIComponent(String(id))}`, options),

  remove: (id: string | number, options?: RequestOptions) =>
    HttpUtils.delete(`${OFFLINE_IMPORT_API_PREFIX}/${encodeURIComponent(String(id))}`, options),

  execute: (id: string | number, options?: RequestOptions) =>
    HttpUtils.post(`${OFFLINE_IMPORT_API_PREFIX}/${encodeURIComponent(String(id))}/execute`, undefined, options),
};
