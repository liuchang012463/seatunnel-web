import { lakeTaskConfigPath } from './paths';

test('links a saved single-table batch task to its existing configuration', () => {
  expect(lakeTaskConfigPath(47, 'BATCH', 'GUIDE_SINGLE', 'ONLINE', 88))
    .toBe('/sync/batch-link-up/47/config/single?scene=view&lakeMappingId=88');
  expect(lakeTaskConfigPath(47, 'BATCH', 'GUIDE_SINGLE', 'OFFLINE', 88))
    .toBe('/sync/batch-link-up/47/config/single?scene=edit&lakeMappingId=88');
  expect(lakeTaskConfigPath(47, 'STREAMING', 'GUIDE_SINGLE', 'OFFLINE', 88))
    .toBe('/sync/stream-link-up/47/config/single?scene=edit');
  expect(lakeTaskConfigPath(47, 'BATCH', undefined, undefined, 88)).toBeUndefined();
});
