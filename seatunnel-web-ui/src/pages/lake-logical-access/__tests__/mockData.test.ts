import {
  filterLogicalMappings,
  readMockLogicalMappings,
  readMockLogicalPreview,
} from '../mockData';

describe('lake-logical-access mockData', () => {
  it('returns seeded logical mappings', () => {
    const records = readMockLogicalMappings();
    expect(records).toHaveLength(3);
    expect(new Set(records.map((r) => r.pattern))).toEqual(
      new Set(['VIEW', 'UNION', 'JOIN']),
    );
  });

  it('filters by keyword, pattern and status', () => {
    const records = readMockLogicalMappings();
    expect(filterLogicalMappings(records, { keyword: '遥测' })).toHaveLength(1);
    expect(filterLogicalMappings(records, { pattern: 'JOIN' })).toHaveLength(1);
    expect(filterLogicalMappings(records, { status: 'PAUSED' })).toHaveLength(1);
  });

  it('exposes preview seeds for active mappings', () => {
    expect(readMockLogicalPreview('logic-001')).toBeDefined();
    expect(readMockLogicalPreview('logic-003')).toBeUndefined();
  });
});
