import {
  buildMockLakeTestResult,
  filterLakeResources,
  readMockLakeResources,
} from '../mockData';

describe('lake-resources mockData', () => {
  it('returns seeded lake resources', () => {
    const records = readMockLakeResources();
    expect(records).toHaveLength(3);
    expect(new Set(records.map((r) => r.format))).toEqual(
      new Set(['PAIMON', 'OSS']),
    );
  });

  it('filters by keyword, format and status', () => {
    const records = readMockLakeResources();
    expect(filterLakeResources(records, { keyword: '实时' })).toHaveLength(1);
    expect(filterLakeResources(records, { format: 'PAIMON' })).toHaveLength(2);
    expect(filterLakeResources(records, { status: 'PENDING' })).toHaveLength(1);
  });

  it('produces test results tied to the resource status', () => {
    expect(buildMockLakeTestResult('lake-001').success).toBe(true);
    expect(buildMockLakeTestResult('lake-003').success).toBe(false);
    expect(buildMockLakeTestResult('unknown').success).toBe(false);
  });
});
