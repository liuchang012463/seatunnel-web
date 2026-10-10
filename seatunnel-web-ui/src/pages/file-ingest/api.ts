import HttpUtils, { RequestOptions } from '@/utils/HttpUtils';
import { seatunnelJobDefinitionApi } from '@/pages/batch-link-up/api';
import {
  FILE_RESOURCE_API_PREFIX,
  createFileResourceDirectory,
  deleteFileResource,
  downloadFileResource,
  fetchFileResourcePage,
  fetchFileResourceUploadRecords,
  uploadFileResources,
} from '@/pages/file-resources/service';
import type {
  FileResourceApi,
  FileResource,
  FileResourceListData,
  FileResourcePreviewOptions,
  FileTaskType,
} from './types';

/** API contract shared by the file resource picker and the resource manager. */
export const fileResourceApi: FileResourceApi = {
  list: async (path) => ({
    code: 0,
    msg: '',
    data: (await fetchFileResourcePage({ path })) as unknown as FileResourceListData,
  }),

  get: (id) => HttpUtils.get(`${FILE_RESOURCE_API_PREFIX}/${encodeURIComponent(String(id))}`),

  createDirectory: async (path, name) => ({
    code: 0,
    msg: '',
    data: (await createFileResourceDirectory(path, name)) as unknown as FileResource,
  }),

  upload: async (path, files, relativePaths = []) => ({
    code: 0,
    msg: '',
    data: ((await uploadFileResources(
      path,
      files.map((file, index) => ({ file, relativePath: relativePaths[index] || file.name })),
    ))?.resources || []) as unknown as FileResource[],
  }),

  remove: async (id) => {
    await deleteFileResource({ id });
    return { code: 0, msg: '', data: true };
  },

  preview: (id, options = {}) =>
    HttpUtils.post(
      `${FILE_RESOURCE_API_PREFIX}/${encodeURIComponent(String(id))}/preview`,
      options,
    ),

  download: (id) =>
    HttpUtils.download(
      `${FILE_RESOURCE_API_PREFIX}/${encodeURIComponent(String(id))}/download`,
    ),

  uploadRecords: async (params = {}) => ({
    code: 0,
    msg: '',
    data: await fetchFileResourceUploadRecords(params as { pageNo?: number; pageSize?: number; keyword?: string }),
  }),
};

const taskPage = (
  taskType: FileTaskType,
  mode: 'GUIDE_SINGLE' | 'FILE_SYNC',
  params: any,
  options?: RequestOptions
) =>
  seatunnelJobDefinitionApi.page(
    {
      ...params,
      taskType,
      mode,
    },
    options
  );

/** Keep the physical batch-definition API while exposing the new business type. */
export const fileIngestTaskApi = {
  page: (params: any = {}, options?: RequestOptions) =>
    taskPage('FILE_INGEST', 'GUIDE_SINGLE', params, options),
  getUniqueId: seatunnelJobDefinitionApi.getUniqueId,
  selectEditDetail: seatunnelJobDefinitionApi.selectEditDetail,
  delete: seatunnelJobDefinitionApi.delete,
  online: seatunnelJobDefinitionApi.online,
  offline: seatunnelJobDefinitionApi.offline,
};

export const fileTransferTaskApi = {
  page: (params: any = {}, options?: RequestOptions) =>
    taskPage('FILE_TRANSFER', 'FILE_SYNC', params, options),
  getUniqueId: seatunnelJobDefinitionApi.getUniqueId,
  selectEditDetail: seatunnelJobDefinitionApi.selectEditDetail,
  delete: seatunnelJobDefinitionApi.delete,
  online: seatunnelJobDefinitionApi.online,
  offline: seatunnelJobDefinitionApi.offline,
};

export const buildFileResourcePreviewOptions = (
  fileFormatType: string,
  sourceConfig?: Record<string, any>,
): FileResourcePreviewOptions => ({
  limit: 20,
  fileFormatType: fileFormatType as FileResourcePreviewOptions['fileFormatType'],
  encoding: sourceConfig?.encoding || undefined,
  fieldDelimiter:
    fileFormatType === 'csv' ? sourceConfig?.fieldDelimiter || undefined : undefined,
  sheetName: fileFormatType === 'excel' ? sourceConfig?.sheetName || undefined : undefined,
});
