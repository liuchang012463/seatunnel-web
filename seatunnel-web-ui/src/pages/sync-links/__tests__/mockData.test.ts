import { applyLinkFilters, readMockLinkHealth, readMockSyncLinks } from '../mockData';

describe('sync-links mockData', () => {
  it('seeds three link records covering healthy, warning and failed states', () => {
    const records = readMockSyncLinks();
    expect(records).toHaveLength(3);
    expect(new Set(records.map((r) => r.health))).toEqual(
      new Set(['HEALTHY', 'WARNING', 'FAILED']),
    );
  });

  it('filters by keyword, link type and health', () => {
    const records = readMockSyncLinks();
    expect(applyLinkFilters(records, { keyword: '遥测' })).toHaveLength(1);
    expect(applyLinkFilters(records, { linkType: 'STREAM' })).toHaveLength(1);
    expect(applyLinkFilters(records, { health: 'FAILED' })).toHaveLength(1);
    expect(applyLinkFilters(records, { status: 'PAUSED' })).toHaveLength(1);
  });

  it('returns health detail for known link ids and undefined otherwise', () => {
    expect(readMockLinkHealth('link-002')).toBeDefined();
    expect(readMockLinkHealth('unknown')).toBeUndefined();
  });
});
