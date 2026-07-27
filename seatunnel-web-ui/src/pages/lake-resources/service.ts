import HttpUtils from '@/utils/HttpUtils';
import { isPrototypeMode } from '@/prototype/mode';
import type { ApiResponse } from '@/utils/request';
import {
  buildMockLakeTestResult,
  filterLakeResources,
  readMockLakeResources,
  writeMockLakeResources,
} from './mockData';
import type {
  LakeResourcePage,
  LakeResourceQuery,
  LakeResourceRecord,
  LakeResourceTestResult,
} from './types';

const API_PREFIX = '/api/v1/lake-resources';

const paginate = (records: LakeResourceRecord[], query: LakeResourceQuery): LakeResourcePage => {
  const filtered = filterLakeResources(records, {
    keyword: query.keyword,
    format: query.format,
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

export const fetchLakeResources = async (
  query: LakeResourceQuery = {},
): Promise<ApiResponse<LakeResourcePage>> => {
  if (isPrototypeMode) {
    const data = readMockLakeResources();
    return { code: 0, msg: 'prototype success', data: paginate(data, query) };
  }
  return HttpUtils.post<LakeResourcePage>(`${API_PREFIX}/page`, query);
};

export const registerLakeResource = async (
  payload: Omit<LakeResourceRecord, 'id' | 'updatedAt' | 'referencedTasks' | 'status'>,
): Promise<ApiResponse<LakeResourceRecord>> => {
  if (isPrototypeMode) {
    const data = readMockLakeResources();
    const id = `lake-${Date.now()}`;
    const next: LakeResourceRecord = {
      ...payload,
      id,
      status: 'REGISTERED',
      referencedTasks: 0,
      updatedAt: '2026-07-27 12:00',
    };
    writeMockLakeResources([next, ...data]);
    return { code: 0, msg: 'prototype success', data: next };
  }
  return HttpUtils.post<LakeResourceRecord>(API_PREFIX, payload);
};

export const testLakeConnection = async (
  id: string,
): Promise<ApiResponse<LakeResourceTestResult>> => {
  if (isPrototypeMode) {
    return {
      code: 0,
      msg: 'prototype success',
      data: buildMockLakeTestResult(id),
    };
  }
  return HttpUtils.post<LakeResourceTestResult>(`${API_PREFIX}/${id}/test`);
};
