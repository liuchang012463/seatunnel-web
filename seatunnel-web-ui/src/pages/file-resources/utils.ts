import type { FileResourceEntry, FileResourceFormat, FileResourceId, FileResourceManagerQuery } from './types';

export const ROOT_RESOURCE_PATH = '/';

export function normalizeResourcePath(path?: string): string {
  const normalized = String(path || '')
    .trim()
    .replace(/\\/g, '/')
    .replace(/\/+/g, '/');

  if (!normalized || normalized === '/') return ROOT_RESOURCE_PATH;
  return `/${normalized.replace(/^\/+|\/+$/g, '')}`;
}

export function joinResourcePath(parent: string | undefined, child: string | undefined): string {
  const childPath = String(child || '').trim().replace(/\\/g, '/');
  if (!childPath) return normalizeResourcePath(parent);

  const normalizedChild = childPath.replace(/^\/+/, '');
  return normalizeResourcePath(`${normalizeResourcePath(parent)}/${normalizedChild}`);
}

export function parentResourcePath(path?: string): string {
  const normalized = normalizeResourcePath(path);
  if (normalized === ROOT_RESOURCE_PATH) return ROOT_RESOURCE_PATH;
  const segments = normalized.split('/').filter(Boolean);
  segments.pop();
  return segments.length ? `/${segments.join('/')}` : ROOT_RESOURCE_PATH;
}

export function resourceName(resource: FileResourceEntry): string {
  const name = String(resource.name || '').trim();
  if (name) return name.replace(/\/$/, '');

  const path = String(resource.path || resource.logicalPath || resource.objectKey || '').replace(/\/$/, '');
  return path.split('/').filter(Boolean).pop() || '未命名资源';
}

export function resourcePath(resource: FileResourceEntry, currentPath = ROOT_RESOURCE_PATH): string {
  const rawPath = String(resource.path || resource.logicalPath || resource.objectKey || '').trim();
  if (!rawPath) return joinResourcePath(currentPath, resourceName(resource));

  const normalized = normalizeResourcePath(rawPath);
  const normalizedCurrent = normalizeResourcePath(currentPath);
  if (normalizedCurrent === ROOT_RESOURCE_PATH || normalized === normalizedCurrent || normalized.startsWith(`${normalizedCurrent}/`)) {
    return normalized;
  }
  return joinResourcePath(currentPath, normalized);
}

export function isDirectoryResource(resource: FileResourceEntry): boolean {
  const kind = String(resource.resourceType || resource.kind || resource.type || '').toUpperCase();
  if (resource.isDirectory === true || resource.directory === true) return true;
  if (kind === 'DIRECTORY' || kind === 'DIR' || kind === 'FOLDER') return true;
  return String(resource.name || resource.path || resource.logicalPath || resource.objectKey || '').endsWith('/');
}

export function resourceId(resource: FileResourceEntry): FileResourceId | undefined {
  if (resource.id !== undefined && resource.id !== null && String(resource.id) !== '') return resource.id;
  return resource.objectKey || resource.path;
}

export function resourceMatchesFormats(resource: FileResourceEntry, formats?: FileResourceFormat[]): boolean {
  if (!formats?.length) return true;
  const extension = resourceName(resource).split('.').pop()?.toLowerCase();
  if (!extension) return false;
  const extensions: Record<FileResourceFormat, string[]> = {
    csv: ['csv'],
    excel: ['xls', 'xlsx'],
    json: ['json'],
    text: ['txt', 'text'],
  };
  return formats.some((format) => extensions[format]?.includes(extension));
}

export function getUploadRelativePath(file: File): string {
  const relativePath = (file as File & { webkitRelativePath?: string }).webkitRelativePath;
  return String(relativePath || file.name || '').replace(/\\/g, '/').replace(/^\/+/, '');
}

export function formatBytes(value?: number | string): string {
  const bytes = Number(value);
  if (!Number.isFinite(bytes) || bytes < 0) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10240 ? 1 : 0)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(bytes < 10485760 ? 1 : 0)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`;
}

export function formatResourceTime(value?: string | number): string {
  if (value === undefined || value === null || value === '') return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

export function serializeSelectionContext(context?: Record<string, unknown>): string | undefined {
  if (!context || Object.keys(context).length === 0) return undefined;
  try {
    return JSON.stringify(context);
  } catch {
    return undefined;
  }
}

export function buildResourceManagerUrl(query: FileResourceManagerQuery = {}): string {
  const params = new URLSearchParams();
  params.set('selectionMode', query.selectionMode || 'single');
  if (query.returnTo) params.set('returnTo', query.returnTo);
  if (query.selection) params.set('selection', query.selection);
  return `/lake/file-resources?${params.toString()}`;
}

export function appendResourceSelection(
  returnTo: string,
  resource: FileResourceEntry,
  selection?: string,
): string {
  const separator = returnTo.includes('?') ? '&' : '?';
  const params = new URLSearchParams();
  const id = resourceId(resource);
  if (id !== undefined) params.set('fileResourceId', String(id));
  params.set('fileResourcePath', resourcePath(resource));
  params.set('fileResourceName', resourceName(resource));
  params.set('selectionMode', 'single');
  if (selection) params.set('selection', selection);
  return `${returnTo}${separator}${params.toString()}`;
}
