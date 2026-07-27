import {
  buildMockEdgeTestResult,
  filterEdgeAccess,
  readMockEdgeAccess,
} from '../mockData';

describe('sync-edge-access mockData', () => {
  it('seeds three edge access records', () => {
    const records = readMockEdgeAccess();
    expect(records).toHaveLength(3);
    expect(new Set(records.map((r) => r.protocol))).toEqual(
      new Set(['MODBUS_TCP', 'MQTT', 'SFTP_FILE']),
    );
  });

  it('filters by keyword, protocol and status', () => {
    const records = readMockEdgeAccess();
    expect(filterEdgeAccess(records, { keyword: 'MQTT' })).toHaveLength(1);
    expect(filterEdgeAccess(records, { protocol: 'MODBUS_TCP' })).toHaveLength(1);
    expect(filterEdgeAccess(records, { status: 'PENDING' })).toHaveLength(1);
  });

  it('returns success only for active records', () => {
    expect(buildMockEdgeTestResult('edge-001').success).toBe(true);
    expect(buildMockEdgeTestResult('edge-003').success).toBe(false);
    expect(buildMockEdgeTestResult('unknown').success).toBe(false);
  });
});
