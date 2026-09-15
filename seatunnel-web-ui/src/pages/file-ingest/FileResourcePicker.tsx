import ManagedFileResourcePicker from '@/pages/file-resources/components/FileResourcePicker';
import type { FileResourceEntry } from '@/pages/file-resources/types';
import type { FileResource, FileResourcePickerProps } from './types';

const isDirectory = (resource: FileResourceEntry) =>
  resource.kind === 'DIRECTORY' ||
  String(resource.resourceType || resource.type || '').toUpperCase() === 'DIRECTORY' ||
  Boolean(resource.isDirectory || resource.directory);

const normalizeResource = (resource: FileResourceEntry): FileResource => ({
  ...resource,
  id: String(resource.id ?? resource.path ?? resource.objectKey ?? resource.name ?? ''),
  name: String(resource.name || resource.path || resource.objectKey || '未命名资源'),
  path: resource.path || resource.logicalPath || resource.objectKey,
  objectKey: resource.objectKey || resource.path || resource.logicalPath,
  kind: isDirectory(resource) ? 'DIRECTORY' : 'FILE',
  size: resource.size === undefined ? undefined : Number(resource.size),
});

const toManagedResource = (resource?: FileResource | null): FileResourceEntry | undefined =>
  resource
    ? {
        ...resource,
        id: resource.id,
        name: resource.name,
        path: resource.path,
        objectKey: resource.objectKey,
        kind: resource.kind,
      }
    : undefined;

/**
 * Compatibility adapter for task pages. The browser, directory navigation,
 * format filtering, and manager hand-off remain owned by file-resources.
 */
const FileResourcePicker: React.FC<FileResourcePickerProps> = ({
  value,
  onSelect,
  ...props
}) => (
  <ManagedFileResourcePicker
    {...props}
    value={toManagedResource(value)}
    onSelect={(resource) => onSelect(normalizeResource(resource))}
  />
);

export default FileResourcePicker;
