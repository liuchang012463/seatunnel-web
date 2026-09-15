import {
  appendResourceSelection,
  getUploadRelativePath,
  isDirectoryResource,
  joinResourcePath,
  normalizeResourcePath,
  resourceMatchesFormats,
} from './utils';

describe('file resource path helpers', () => {
  it('normalizes and joins logical paths without changing the root contract', () => {
    expect(normalizeResourcePath()).toBe('/');
    expect(normalizeResourcePath('incoming\\2026/')).toBe('/incoming/2026');
    expect(joinResourcePath('/incoming', '2026/09')).toBe('/incoming/2026/09');
  });

  it('keeps folder-relative paths from directory uploads', () => {
    const file = new File(['id,name'], 'users.csv', { type: 'text/csv' });
    Object.defineProperty(file, 'webkitRelativePath', { value: 'daily/users.csv' });
    expect(getUploadRelativePath(file)).toBe('daily/users.csv');
  });

  it('detects directories and validates file formats in the picker', () => {
    expect(isDirectoryResource({ resourceType: 'DIRECTORY', name: 'daily' })).toBe(true);
    expect(resourceMatchesFormats({ name: 'users.xlsx' }, ['excel'])).toBe(true);
    expect(resourceMatchesFormats({ name: 'users.xlsx' }, ['csv'])).toBe(false);
  });

  it('serializes an internal return route with the selected resource context', () => {
    const result = appendResourceSelection('/file-ingest/12/config/single', {
      id: 'resource-42',
      name: 'users.csv',
      logicalPath: '/incoming/users.csv',
    });
    const query = new URLSearchParams(result.split('?')[1]);
    expect(query.get('fileResourceId')).toBe('resource-42');
    expect(query.get('fileResourcePath')).toBe('/incoming/users.csv');
    expect(query.get('fileResourceName')).toBe('users.csv');
    expect(query.get('selectionMode')).toBe('single');
  });
});
