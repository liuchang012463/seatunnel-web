import {
  filterFaults,
  readMockFaultEvidence,
  readMockFaults,
} from '../mockData';

describe('operations-diagnostics mockData', () => {
  it('returns seeded fault records', () => {
    const records = readMockFaults();
    expect(records).toHaveLength(3);
    expect(new Set(records.map((r) => r.severity))).toEqual(
      new Set(['ERROR', 'WARNING', 'CRITICAL']),
    );
  });

  it('filters by severity and status', () => {
    const records = readMockFaults();
    expect(filterFaults(records, { severity: 'CRITICAL' })).toHaveLength(1);
    expect(filterFaults(records, { status: 'OPEN' })).toHaveLength(1);
    expect(filterFaults(records, { keyword: '限流' })).toHaveLength(1);
  });

  it('exposes evidence per fault', () => {
    expect(readMockFaultEvidence('fault-001')?.retryPlan.length).toBeGreaterThan(0);
    expect(readMockFaultEvidence('unknown')).toBeUndefined();
  });
});
