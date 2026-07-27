import {
  filterLifecyclePolicies,
  readMockLifecycleExecutions,
  readMockLifecyclePolicies,
} from '../mockData';

describe('lake-lifecycle mockData', () => {
  it('seeds three lifecycle policies', () => {
    const records = readMockLifecyclePolicies();
    expect(records).toHaveLength(3);
    expect(new Set(records.map((r) => r.status))).toEqual(
      new Set(['ACTIVE', 'PAUSED']),
    );
  });

  it('filters by keyword and status', () => {
    const records = readMockLifecyclePolicies();
    expect(filterLifecyclePolicies(records, { keyword: '日志' })).toHaveLength(1);
    expect(filterLifecyclePolicies(records, { target: 'HOT' })).toHaveLength(1);
    expect(filterLifecyclePolicies(records, { status: 'PAUSED' })).toHaveLength(1);
  });

  it('seeds executions across multiple policies', () => {
    const executions = readMockLifecycleExecutions();
    expect(executions.length).toBeGreaterThanOrEqual(2);
    expect(new Set(executions.map((e) => e.policyId)).size).toBeGreaterThanOrEqual(2);
  });
});
