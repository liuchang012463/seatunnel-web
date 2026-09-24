import type { ApiResponse } from '@/utils/HttpUtils';

/** Stable business task types. JobMode remains the SeaTunnel runtime mode. */
export type FileTaskType = 'FILE_INGEST' | 'FILE_TRANSFER';

export type FileFormat = 'csv' | 'excel' | 'json' | 'text';

export type FileResourceKind = 'FILE' | 'DIRECTORY';

export interface FileResource {
  id: string;
  name: string;
  path?: string;
  objectKey?: string;
  kind: FileResourceKind;
  size?: number;
  contentType?: string;
  etag?: string;
  modifiedTime?: string | number;
  providerType?: string;
  bucket?: string;
  [key: string]: unknown;
}

export interface FileResourceListData {
  bizData?: FileResource[];
  items?: FileResource[];
  entries?: FileResource[];
  pagination?: {
    pageNo?: number;
    pageSize?: number;
    total?: number;
  };
}

export interface FileResourcePreviewOptions {
  limit?: number;
  fileFormatType?: FileFormat;
  encoding?: string;
  fieldDelimiter?: string;
}

export interface FileResourceApi {
  list: (path?: string) => Promise<ApiResponse<FileResourceListData | FileResource[]>>;
  get: (id: string | number) => Promise<ApiResponse<FileResource>>;
  createDirectory: (
    path: string,
    name: string,
  ) => Promise<ApiResponse<FileResource>>;
  upload: (
    path: string,
    files: File[],
    relativePaths?: string[],
  ) => Promise<ApiResponse<FileResource[]>>;
  remove: (id: string | number) => Promise<ApiResponse<boolean>>;
  preview: (
    id: string | number,
    options?: FileResourcePreviewOptions,
  ) => Promise<ApiResponse<unknown>>;
  download: (id: string | number) => Promise<unknown>;
  uploadRecords: (params?: Record<string, unknown>) => Promise<ApiResponse<unknown>>;
}

export interface FileResourcePickerProps {
  open: boolean;
  value?: FileResource | null;
  allowedFormats?: FileFormat[];
  selectionMode?: 'file' | 'file-or-directory';
  initialPath?: string;
  title?: string;
  onCancel: () => void;
  onSelect: (resource: FileResource) => void;
}

export interface FileTaskDetailConfig {
  taskType: FileTaskType;
  mode: 'GUIDE_SINGLE' | 'FILE_SYNC';
  listPath: string;
  configPath: string;
  title: string;
  description: string;
  sourceOptions: Array<{
    value: string;
    label: React.ReactNode;
    rawLabel?: string;
    connectorType?: string;
    pluginName?: string;
    sourceManaged?: boolean;
  }>;
  targetOptions: Array<{
    value: string;
    label: React.ReactNode;
    rawLabel?: string;
    connectorType?: string;
    pluginName?: string;
  }>;
  defaultSource: {
    dbType: string;
    connectorType?: string;
    pluginName?: string;
    sourceManaged?: boolean;
  };
  defaultTarget: {
    dbType: string;
    connectorType?: string;
    pluginName?: string;
  };
}

export const FILE_RESOURCE_SOURCE = {
  dbType: 'FILE_RESOURCE',
  connectorType: 'S3File',
  pluginName: 'S3File',
  sourceManaged: true,
} as const;

export const fileTaskDraftKey = (taskType: FileTaskType, id: string | number) =>
  `file-task-draft-${taskType}-${id}`;
