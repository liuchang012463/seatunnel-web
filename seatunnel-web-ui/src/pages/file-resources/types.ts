import type { ReactNode } from 'react';

export type FileResourceId = string | number;

export type FileResourceKind = 'FILE' | 'DIRECTORY';

export type FileResourceFormat = 'csv' | 'excel' | 'json' | 'text';

export type FileResourceStatus = 'READY' | 'UPLOADING' | 'FAILED' | 'DELETED' | string;

export interface FileResourceEntry {
  id?: FileResourceId;
  name?: string;
  path?: string;
  logicalPath?: string;
  objectKey?: string;
  resourceType?: FileResourceKind | string;
  kind?: FileResourceKind | string;
  type?: FileResourceKind | string;
  isDirectory?: boolean;
  directory?: boolean;
  size?: number | string;
  contentType?: string;
  mimeType?: string;
  etag?: string;
  status?: FileResourceStatus;
  modifiedTime?: string | number;
  createTime?: string | number;
  updateTime?: string | number;
  [key: string]: unknown;
}

export interface FileResourcePagination {
  pageNo: number;
  pageSize: number;
  total: number;
}

export interface FileResourcePage {
  bizData: FileResourceEntry[];
  pagination: FileResourcePagination;
}

export interface FileResourceListParams {
  path?: string;
  keyword?: string;
  pageNo?: number;
  pageSize?: number;
}

export interface FileResourceUploadItem {
  file: File;
  /** The path relative to the selected folder. Directory uploads use webkitRelativePath. */
  relativePath?: string;
}

export interface FileResourceUploadResponse {
  resources?: FileResourceEntry[];
  uploaded?: number;
  total?: number;
  uploadRecordId?: FileResourceId;
  [key: string]: unknown;
}

export type FileResourceUploadRecordStatus = 'PENDING' | 'UPLOADING' | 'SUCCESS' | 'PARTIAL' | 'FAILED' | string;

export interface FileResourceUploadRecord {
  id?: FileResourceId;
  path?: string;
  targetPath?: string;
  fileCount?: number;
  totalFiles?: number;
  totalSize?: number | string;
  status?: FileResourceUploadRecordStatus;
  errorMessage?: string;
  createTime?: string | number;
  startTime?: string | number;
  finishTime?: string | number;
  [key: string]: unknown;
}

export interface FileResourceUploadRecordPage {
  bizData: FileResourceUploadRecord[];
  pagination: FileResourcePagination;
}

export type FileResourceSelectionContext = Record<string, unknown>;

export interface FileResourcePickerProps {
  open: boolean;
  onCancel: () => void;
  onSelect: (resource: FileResourceEntry) => void;
  value?: FileResourceEntry | null;
  allowedFormats?: FileResourceFormat[];
  selectionMode?: 'file' | 'file-or-directory';
  /** Route to return to when the picker is opened from a task editor. */
  returnTo?: string;
  /** Opaque selection context preserved in the resource-manager URL. */
  selection?: string;
  selectionContext?: FileResourceSelectionContext;
  initialPath?: string;
  title?: ReactNode;
  /** Override navigation for hosts that use a custom router. */
  onOpenResourceManager?: (url: string) => void;
  /** Compatibility callback for task pages that own their manager navigation. */
  onOpenManager?: () => void;
}

export interface FileResourceManagerQuery {
  returnTo?: string;
  selection?: string;
  selectionMode?: 'single';
}
