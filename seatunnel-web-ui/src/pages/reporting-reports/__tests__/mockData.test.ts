import { filterReports, readMockReportPreview, readMockReports } from '../mockData';

describe('reporting-reports mockData', () => {
  it('returns seeded reports', () => {
    const records = readMockReports();
    expect(records.length).toBeGreaterThanOrEqual(3);
    const statuses = records.map((r) => r.status);
    expect(statuses).toEqual(expect.arrayContaining(['READY', 'GENERATING']));
  });

  it('filters by keyword and format', () => {
    const records = readMockReports();
    expect(filterReports(records, { keyword: '周报' }).length).toBeGreaterThanOrEqual(1);
    expect(filterReports(records, { format: 'PDF' }).length).toBeGreaterThanOrEqual(1);
    expect(filterReports(records, { status: 'GENERATING' })).toHaveLength(1);
  });

  it('exposes preview seeds for known ids', () => {
    expect(readMockReportPreview('report-001')).toBeDefined();
    expect(readMockReportPreview('unknown')).toBeUndefined();
  });
});
