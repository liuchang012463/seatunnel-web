import HttpUtils from '@/utils/HttpUtils';
import { isPrototypeMode } from '@/prototype/mode';
import type { ApiResponse } from '@/utils/request';
import {
  appendMockNetworkEvent,
  filterCloudEdgeTasks,
  readMockCloudEdgeTasks,
  readMockNetworkEvents,
  writeMockCloudEdgeTasks,
} from './mockData';
import type {
  CloudEdgeNetworkEvent,
  CloudEdgeTaskPage,
  CloudEdgeTaskQuery,
  CloudEdgeTaskRecord,
} from './types';

const API_PREFIX = '/api/v1/sync-cloud-edge';

const paginate = (
  records: CloudEdgeTaskRecord[],
  query: CloudEdgeTaskQuery,
): CloudEdgeTaskPage => {
  const filtered = filterCloudEdgeTasks(records, {
    keyword: query.keyword,
    status: query.status,
    transport: query.transport,
  });
  const pageNo = query.pageNo ?? 1;
  const pageSize = query.pageSize ?? 10;
  const start = (pageNo - 1) * pageSize;
  return {
    bizData: filtered.slice(start, start + pageSize),
    pagination: { pageNo, pageSize, total: filtered.length },
  };
};

export const fetchCloudEdgeTasks = async (
  query: CloudEdgeTaskQuery = {},
): Promise<ApiResponse<CloudEdgeTaskPage>> => {
  if (isPrototypeMode) {
    return {
      code: 0,
      msg: 'prototype success',
      data: paginate(readMockCloudEdgeTasks(), query),
    };
  }
  return HttpUtils.post<CloudEdgeTaskPage>(`${API_PREFIX}/page`, query);
};

export const dispatchCloudEdgeTask = async (
  payload: Pick<
    CloudEdgeTaskRecord,
    'name' | 'transport' | 'sourceCluster' | 'edgeNode' | 'bytesPlanned' | 'description'
  >,
): Promise<ApiResponse<CloudEdgeTaskRecord>> => {
  if (isPrototypeMode) {
    const data = readMockCloudEdgeTasks();
    const id = `ce-${Date.now()}`;
    const next: CloudEdgeTaskRecord = {
      ...payload,
      id,
      status: 'DISPATCHED',
      bytesTransferred: 0,
      queuedChunks: 0,
      owner: '当前 SSO 用户',
      updatedAt: '2026-07-27 12:00',
    };
    writeMockCloudEdgeTasks([next, ...data]);
    return { code: 0, msg: 'prototype success', data: next };
  }
  return HttpUtils.post<CloudEdgeTaskRecord>(`${API_PREFIX}/dispatch`, payload);
};

export const toggleNetworkState = async (
  id: string,
  online: boolean,
): Promise<ApiResponse<CloudEdgeNetworkEvent>> => {
  if (isPrototypeMode) {
    const event: CloudEdgeNetworkEvent = {
      taskId: id,
      timestamp: '2026-07-27 12:00',
      online,
      pendingChunks: online ? 0 : 42,
      message: online
        ? '网络已恢复，续传任务已启动'
        : '已模拟断网，数据进入暂存区',
    };
    appendMockNetworkEvent(event);
    const tasks = readMockCloudEdgeTasks();
    writeMockCloudEdgeTasks(
      tasks.map((record) =>
        record.id === id
          ? {
              ...record,
              status: online ? 'RUNNING' : 'OFFLINE',
              updatedAt: '2026-07-27 12:00',
            }
          : record,
      ),
    );
    return { code: 0, msg: 'prototype success', data: event };
  }
  return HttpUtils.post<CloudEdgeNetworkEvent>(`${API_PREFIX}/${id}/network`, { online });
};

export const fetchNetworkEvents = async (
  taskId?: string,
): Promise<ApiResponse<CloudEdgeNetworkEvent[]>> => {
  if (isPrototypeMode) {
    const events = readMockNetworkEvents();
    return {
      code: 0,
      msg: 'prototype success',
      data: taskId ? events.filter((event) => event.taskId === taskId) : events,
    };
  }
  return HttpUtils.post<CloudEdgeNetworkEvent[]>(`${API_PREFIX}/network-events`, taskId ? { taskId } : {});
};
