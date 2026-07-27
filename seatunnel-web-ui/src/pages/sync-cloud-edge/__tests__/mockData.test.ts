import {
  filterCloudEdgeTasks,
  readMockCloudEdgeTasks,
  readMockNetworkEvents,
} from '../mockData';

describe('sync-cloud-edge mockData', () => {
  it('seeds cloud-edge tasks', () => {
    const records = readMockCloudEdgeTasks();
    expect(records).toHaveLength(3);
    expect(new Set(records.map((r) => r.transport))).toEqual(
      new Set(['FULL_MIRROR', 'INCREMENTAL', 'EVENT_FEEDBACK']),
    );
  });

  it('filters by status and transport', () => {
    const records = readMockCloudEdgeTasks();
    expect(filterCloudEdgeTasks(records, { status: 'OFFLINE' })).toHaveLength(1);
    expect(filterCloudEdgeTasks(records, { transport: 'EVENT_FEEDBACK' })).toHaveLength(1);
    expect(filterCloudEdgeTasks(records, { keyword: '镜像' })).toHaveLength(1);
  });

  it('records offline events for cloud-edge tasks', () => {
    const events = readMockNetworkEvents();
    expect(events.some((e) => !e.online)).toBe(true);
  });
});
