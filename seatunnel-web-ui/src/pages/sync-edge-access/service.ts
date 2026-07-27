import HttpUtils from '@/utils/HttpUtils';
import { isPrototypeMode } from '@/prototype/mode';
import type { ApiResponse } from '@/utils/request';
import {
  buildMockEdgeTestResult,
  filterEdgeAccess,
  readMockEdgeAccess,
  writeMockEdgeAccess,
} from './mockData';
import type {
  EdgeAccessPage,
  EdgeAccessQuery,
  EdgeAccessRecord,
  EdgeAccessTestResult,
} from './types';

const API_PREFIX = '/api/v1/sync-edge-access';

const paginate = (records: EdgeAccessRecord[], query: EdgeAccessQuery): EdgeAccessPage => {
  const filtered = filterEdgeAccess(records, {
    keyword: query.keyword,
    protocol: query.protocol,
    status: query.status,
  });
  const pageNo = query.pageNo ?? 1;
  const pageSize = query.pageSize ?? 10;
  const start = (pageNo - 1) * pageSize;
  return {
    bizData: filtered.slice(start, start + pageSize),
    pagination: { pageNo, pageSize, total: filtered.length },
  };
};

export const fetchEdgeAccess = async (
  query: EdgeAccessQuery = {},
): Promise<ApiResponse<EdgeAccessPage>> => {
  if (isPrototypeMode) {
    return {
      code: 0,
      msg: 'prototype success',
      data: paginate(readMockEdgeAccess(), query),
    };
  }
  return HttpUtils.post<EdgeAccessPage>(`${API_PREFIX}/page`, query);
};

export const registerEdgeAccess = async (
  payload: Omit<EdgeAccessRecord, 'id' | 'updatedAt' | 'status' | 'bytesIngested' | 'latencyMs'>,
): Promise<ApiResponse<EdgeAccessRecord>> => {
  if (isPrototypeMode) {
    const data = readMockEdgeAccess();
    const id = `edge-${Date.now()}`;
    const next: EdgeAccessRecord = {
      ...payload,
      id,
      status: 'REGISTERED',
      bytesIngested: 0,
      latencyMs: 0,
      updatedAt: '2026-07-27 12:00',
    };
    writeMockEdgeAccess([next, ...data]);
    return { code: 0, msg: 'prototype success', data: next };
  }
  return HttpUtils.post<EdgeAccessRecord>(API_PREFIX, payload);
};

export const testEdgeConnection = async (
  id: string,
): Promise<ApiResponse<EdgeAccessTestResult>> => {
  if (isPrototypeMode) {
    return {
      code: 0,
      msg: 'prototype success',
      data: buildMockEdgeTestResult(id),
    };
  }
  return HttpUtils.post<EdgeAccessTestResult>(`${API_PREFIX}/${id}/test`);
};
