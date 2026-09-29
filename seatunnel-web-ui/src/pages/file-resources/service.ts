import HttpUtils, { type ApiResponse } from '@/utils/HttpUtils';
import type {
  FileResourceEntry,
  FileResourceListParams,
  FileResourcePage,
  FileResourcePagination,
  FileResourceMultipartPartUrl,
  FileResourceMultipartUploadSession,
  FileResourceUploadProgress,
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

const MAX_PART_URLS_PER_REQUEST = 32;
const MAX_PARALLEL_UPLOAD_PARTS = 4;
let activeUploadParts = 0;
const uploadPartWaiters: Array<() => void> = [];

async function withUploadPartSlot<T>(upload: () => Promise<T>): Promise<T> {
  if (activeUploadParts >= MAX_PARALLEL_UPLOAD_PARTS) {
    await new Promise<void>((resolve) => uploadPartWaiters.push(resolve));
  } else {
    activeUploadParts += 1;
  }
  try {
    return await upload();
  } finally {
    const next = uploadPartWaiters.shift();
    if (next) next();
    else activeUploadParts -= 1;
  }
}

function uploadBlob(
  url: string,
  body: Blob,
  onProgress: (loaded: number) => void,
): Promise<string> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url, true);
    xhr.withCredentials = false;
    xhr.timeout = 30 * 60 * 1000;
    xhr.upload.onprogress = (event) => onProgress(event.loaded);
    xhr.onerror = () => reject(new Error('无法连接 MinIO，请检查浏览器上传地址和跨域配置'));
    xhr.onabort = () => reject(new Error('文件上传已取消'));
    xhr.ontimeout = () => reject(new Error('MinIO 分片上传超时，请重试'));
    xhr.onload = () => {
      if (xhr.status < 200 || xhr.status >= 300) {
        reject(new Error(`MinIO 分片上传失败（HTTP ${xhr.status}）`));
        return;
      }
      const etag = xhr.getResponseHeader('ETag');
      if (!etag) {
        reject(new Error('MinIO 未返回 ETag，请检查跨域响应头配置'));
        return;
      }
      resolve(etag);
    };
    xhr.send(body);
  });
}

async function requestPartUrls(
  uploadRecordId: string,
  partNumbers: number[],
): Promise<FileResourceMultipartPartUrl[]> {
  const response = await HttpUtils.post<FileResourceMultipartPartUrl[]>(
    `${FILE_RESOURCE_API_PREFIX}/multipart-uploads/${encodeURIComponent(uploadRecordId)}/parts`,
    { partNumbers },
  );
  if (response.code !== 0) throw responseError(response, '获取 MinIO 分片上传地址失败');
  if (!Array.isArray(response.data)) throw new Error('MinIO 分片上传地址响应无效');
  return response.data;
}

async function uploadMultipartFile(
  path: string,
  item: FileResourceUploadItem,
  onProgress?: (progress: FileResourceUploadProgress) => void,
): Promise<{ resource: FileResourceEntry; uploadRecordId: string }> {
  const { file } = item;
  const initResponse = await HttpUtils.post<FileResourceMultipartUploadSession>(
    `${FILE_RESOURCE_API_PREFIX}/multipart-uploads`,
    {
      path: normalizeResourcePath(path),
      relativePath: item.relativePath || file.name,
      size: file.size,
      contentType: file.type || 'application/octet-stream',
    },
  );
  if (initResponse.code !== 0) throw responseError(initResponse, '创建 MinIO 分片上传会话失败');
  const session = initResponse.data;
  if (!session?.uploadRecordId || !session.partSizeBytes || !session.totalParts) {
    throw new Error('MinIO 分片上传会话响应无效');
  }

  const uploadedBytes = new Map<number, number>();
  const etags = new Map<number, string>();
  const reportProgress = () => {
    const loaded = Math.min(file.size, [...uploadedBytes.values()].reduce((sum, value) => sum + value, 0));
    onProgress?.({
      loaded,
      total: file.size,
      percent: Math.min(99, Math.floor((loaded / file.size) * 100)),
    });
  };

  try {
    for (let offset = 0; offset < session.totalParts; offset += MAX_PART_URLS_PER_REQUEST) {
      const partNumbers = Array.from(
        { length: Math.min(MAX_PART_URLS_PER_REQUEST, session.totalParts - offset) },
        (_, index) => offset + index + 1,
      );
      const urls = await requestPartUrls(session.uploadRecordId, partNumbers);
      const urlByPart = new Map(urls.map(({ partNumber, url }) => [partNumber, url]));
      if (partNumbers.some((partNumber) => !urlByPart.has(partNumber))) {
        throw new Error('MinIO 分片上传地址不完整');
      }

      let nextIndex = 0;
      const worker = async () => {
        while (nextIndex < partNumbers.length) {
          const partNumber = partNumbers[nextIndex++];
          const start = (partNumber - 1) * session.partSizeBytes;
          const end = Math.min(file.size, start + session.partSizeBytes);
          const blob = file.slice(start, end);
          uploadedBytes.set(partNumber, 0);
          reportProgress();
          const etag = await withUploadPartSlot(() =>
            uploadBlob(urlByPart.get(partNumber)!, blob, (loaded) => {
              uploadedBytes.set(partNumber, Math.min(loaded, blob.size));
              reportProgress();
            }),
          );
          uploadedBytes.set(partNumber, blob.size);
          etags.set(partNumber, etag);
          reportProgress();
        }
      };
      const workerResults = await Promise.allSettled(
        Array.from({ length: Math.min(MAX_PARALLEL_UPLOAD_PARTS, partNumbers.length) }, worker),
      );
      const failedWorker = workerResults.find((result) => result.status === 'rejected');
      if (failedWorker?.status === 'rejected') throw failedWorker.reason;
    }

    const completeResponse = await HttpUtils.post<FileResourceEntry>(
      `${FILE_RESOURCE_API_PREFIX}/multipart-uploads/${encodeURIComponent(session.uploadRecordId)}/complete`,
      {
        parts: Array.from(etags, ([partNumber, etag]) => ({ partNumber, etag }))
          .sort((left, right) => left.partNumber - right.partNumber),
      },
    );
    if (completeResponse.code !== 0) throw responseError(completeResponse, '完成 MinIO 分片上传失败');
    if (!completeResponse.data) throw new Error('MinIO 分片上传完成响应无效');
    onProgress?.({ loaded: file.size, total: file.size, percent: 100 });
    return { resource: completeResponse.data, uploadRecordId: session.uploadRecordId };
  } catch (error) {
    await HttpUtils.delete(
      `${FILE_RESOURCE_API_PREFIX}/multipart-uploads/${encodeURIComponent(session.uploadRecordId)}`,
    ).catch(() => undefined);
    throw error;
  }
}

async function uploadEmptyFile(
  path: string,
  item: FileResourceUploadItem,
): Promise<{ resource: FileResourceEntry; uploadRecordId?: string }> {
  const formData = new FormData();
  formData.append('path', normalizeResourcePath(path));
  formData.append('files', item.file, item.file.name);
  formData.append('relativePaths', item.relativePath || item.file.name);
  const response = await HttpUtils.postForm<FileResourceUploadResponse>(
    `${FILE_RESOURCE_API_PREFIX}/upload`,
    formData,
  );
  if (response.code !== 0) throw responseError(response, '文件上传失败');
  const resource = Array.isArray(response.data) ? response.data[0] : response.data?.resources?.[0];
  if (!resource) throw new Error('文件上传完成响应无效');
  return { resource, uploadRecordId: response.data?.uploadRecordId as string | undefined };
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
  onProgress?: (progress: FileResourceUploadProgress, item: FileResourceUploadItem) => void,
): Promise<FileResourceUploadResponse | undefined> {
  const resources: FileResourceEntry[] = [];
  let uploadRecordId: string | undefined;
  for (const item of files) {
    const result = item.file.size === 0
      ? await uploadEmptyFile(path, item)
      : await uploadMultipartFile(path, item, (progress) => onProgress?.(progress, item));
    resources.push(result.resource);
    uploadRecordId = result.uploadRecordId || uploadRecordId;
  }
  return { resources, uploaded: resources.length, total: files.length, uploadRecordId };
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

export function previewFileResourceAsPdf(resource: Pick<FileResourceEntry, 'id'>): Promise<unknown> {
  if (resource.id === undefined || resource.id === null || String(resource.id) === '') {
    return Promise.reject(new Error('缺少文件资源 ID，无法预览'));
  }
  return HttpUtils.download(`${FILE_RESOURCE_API_PREFIX}/${encodeURIComponent(String(resource.id))}/preview-pdf`);
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
  previewAsPdf: previewFileResourceAsPdf,
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
