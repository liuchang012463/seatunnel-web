import { canUseOfflineMode, isWebUploadSource, normalizeOfflineMode } from './modeUtils';

describe('offline local file modes', () => {
  const source = { dbType: 'WEB_UPLOAD', sourceManaged: true };

  it('recognizes managed Web Upload sources', () => {
    expect(isWebUploadSource(source)).toBe(true);
  });

  it('falls back to single-table mode and hides unsupported modes', () => {
    expect(normalizeOfflineMode(source, 'GUIDE_MULTI')).toBe('GUIDE_SINGLE');
    expect(canUseOfflineMode(source, 'GUIDE_SINGLE_INCREMENTAL')).toBe(false);
    expect(canUseOfflineMode(source, 'GUIDE_MULTI')).toBe(false);
    expect(canUseOfflineMode(source, 'GUIDE_SINGLE')).toBe(true);
  });
});
