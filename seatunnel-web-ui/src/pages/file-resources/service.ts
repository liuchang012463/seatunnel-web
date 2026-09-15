import HttpUtils, { type ApiResponse } from '@/utils/HttpUtils';
import type {
  FileResourceEntry,
  FileResourceListParams,
  FileResourcePage,
  FileResourcePagination,
  FileResourceUploadItem,
  FileResourceUploadRecord,
  FileResourceUploadRecordPage,
  FileResourceUploadResponse,
} from './types';
import { normalizeResourcePath } from './utils';

export const FILE_RESOURCE_API_PREFIX = '/api/v1/file-resources';

export type FileResourceApiResponse<T> = ApiResponse<T>;

function queryString(params: Record<string, unknown>): string {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') query.set(key, String(value));
  });
  return query.toString() ? `?${query.toString()}` : '';
}

export function responseError<T>(response: Partial<ApiResponse<T>>, fallback: string): Error {
  return new Error(response.message || response.msg || fallback);
}

export async function fetchFileResourcePage(
  params: FileResourceListParams = {},
): Promise<FileResourcePage> {
  const response = await HttpUtils.get<unknown>(
    `${FILE_RESOURCE_API_PREFIX}${queryString({
      path: normalizeResourcePath(params.path),
      keyword: params.keyword?.trim(),
      pageNo: params.pageNo || 1,
      pageSize: params.pageSize || 20,
    })}`,
  );
  if (response.code !== 0) throw responseError(response, '文件资源列表加载失败');
  return normalizeFileResourcePage(response.data, params);
}

export async function createFileResourceDirectory(
  path: string,
  name?: string,
): Promise<FileResourceEntry | undefined> {
  const response = await HttpUtils.post<FileResourceEntry>(`${FILE_RESOURCE_API_PREFIX}/directories`, {
    path: normalizeResourcePath(path),
    ...(name?.trim() ? { name: name.trim() } : {}),
  });
  if (response.code !== 0) throw responseError(response, '新建目录失败');
  return response.data;
}

export async function uploadFileResources(
  path: string,
  files: FileResourceUploadItem[],
): Promise<FileResourceUploadResponse | undefined> {
  const formData = new FormData();
  formData.append('path', normalizeResourcePath(path));
  files.forEach(({ file, relativePath }) => {
    formData.append('files', file, file.name);
    formData.append('relativePaths', relativePath || file.name);
  });

  const response = await HttpUtils.postForm<FileResourceUploadResponse>(`${FILE_RESOURCE_API_PREFIX}/upload`, formData);
  if (response.code !== 0) throw responseError(response, '文件上传失败');
  if (Array.isArray(response.data)) {
    return {
      resources: response.data,
      uploaded: response.data.length,
      total: response.data.length,
    };
  }
  return response.data;
}

export async function deleteFileResource(resource: Pick<FileResourceEntry, 'id' | 'path' | 'objectKey'>): Promise<void> {
  if (resource.id !== undefined && resource.id !== null && String(resource.id) !== '') {
    const response = await HttpUtils.delete(`${FILE_RESOURCE_API_PREFIX}/${encodeURIComponent(String(resource.id))}`);
    if (response.code !== 0) throw responseError(response, '删除文件资源失败');
    return;
  }

  const response = await HttpUtils.delete(
    `${FILE_RESOURCE_API_PREFIX}${queryString({ path: resource.path || resource.objectKey })}`,
  );
  if (response.code !== 0) throw responseError(response, '删除文件资源失败');
}

export function downloadFileResource(resource: Pick<FileResourceEntry, 'id' | 'path' | 'objectKey'>): Promise<unknown> {
  if (resource.id !== undefined && resource.id !== null && String(resource.id) !== '') {
    return HttpUtils.download(`${FILE_RESOURCE_API_PREFIX}/${encodeURIComponent(String(resource.id))}/download`);
  }
  return HttpUtils.download(`${FILE_RESOURCE_API_PREFIX}/download${queryString({ path: resource.path || resource.objectKey })}`);
}

export async function fetchFileResourceUploadRecords(
  params: Pick<FileResourceListParams, 'pageNo' | 'pageSize' | 'keyword'> = {},
): Promise<FileResourceUploadRecordPage> {
  const response = await HttpUtils.get<unknown>(
    `${FILE_RESOURCE_API_PREFIX}/upload-records${queryString({
      keyword: params.keyword?.trim(),
      pageNo: params.pageNo || 1,
      pageSize: params.pageSize || 10,
    })}`,
  );
  if (response.code !== 0) throw responseError(response, '上传记录加载失败');
  return normalizeUploadRecordPage(response.data, params);
}

export const fileResourceApi = {
  list: fetchFileResourcePage,
  createDirectory: createFileResourceDirectory,
  upload: uploadFileResources,
  delete: deleteFileResource,
  download: downloadFileResource,
  uploadRecords: fetchFileResourceUploadRecords,
};

function normalizePagination(value: unknown, fallback: FileResourcePagination): FileResourcePagination {
  const raw = value && typeof value === 'object' ? (value as Record<string, unknown>) : {};
  const pageNo = Number(raw.pageNo || raw.current || fallback.pageNo) || fallback.pageNo;
  const pageSize = Number(raw.pageSize || raw.size || fallback.pageSize) || fallback.pageSize;
  const total = Number(raw.total || 0);
  return { pageNo, pageSize, total: total >= 0 ? total : 0 };
}

function normalizeRows<T>(value: unknown, keys: string[]): T[] {
  if (Array.isArray(value)) return value as T[];
  if (!value || typeof value !== 'object') return [];
  const object = value as Record<string, unknown>;
  for (const key of keys) {
    if (Array.isArray(object[key])) return object[key] as T[];
  }
  return [];
}

export function normalizeFileResourcePage(
  value: unknown,
  params: FileResourceListParams = {},
): FileResourcePage {
  const fallback: FileResourcePagination = {
    pageNo: params.pageNo || 1,
    pageSize: params.pageSize || 20,
    total: 0,
  };
  const object = value && typeof value === 'object' ? (value as Record<string, unknown>) : {};
  const rows = normalizeRows<FileResourceEntry>(value, ['bizData', 'records', 'items', 'list', 'content', 'resources']);
  const pagination = normalizePagination(object.pagination || object.page || object, fallback);
  return {
    bizData: rows,
    pagination: {
      ...pagination,
      total: pagination.total || (Array.isArray(value) ? rows.length : 0),
    },
  };
}

export function normalizeUploadRecordPage(
  value: unknown,
  params: Pick<FileResourceListParams, 'pageNo' | 'pageSize'> = {},
): FileResourceUploadRecordPage {
  const fallback: FileResourcePagination = {
    pageNo: params.pageNo || 1,
    pageSize: params.pageSize || 10,
    total: 0,
  };
  const object = value && typeof value === 'object' ? (value as Record<string, unknown>) : {};
  const rows = normalizeRows<FileResourceUploadRecord>(value, ['bizData', 'records', 'items', 'list', 'content']);
  const pagination = normalizePagination(object.pagination || object.page || object, fallback);
  return {
    bizData: rows,
    pagination: {
      ...pagination,
      total: pagination.total || (Array.isArray(value) ? rows.length : 0),
    },
  };
}

export function getUploadRecordCount(record: FileResourceUploadRecord): number {
  return Number(record.fileCount ?? record.totalFiles ?? 0) || 0;
}
