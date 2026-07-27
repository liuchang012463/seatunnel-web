import HttpUtils from '@/utils/HttpUtils';
import { isPrototypeMode } from '@/prototype/mode';
import type { ApiResponse } from '@/utils/request';
import {
  filterLogicalMappings,
  readMockLogicalMappings,
  readMockLogicalPreview,
  writeMockLogicalMappings,
} from './mockData';
import type {
  LogicalMappingPage,
  LogicalMappingQuery,
  LogicalMappingRecord,
  LogicalPreviewResult,
} from './types';

const API_PREFIX = '/api/v1/lake-logical-access';

const paginate = (
  records: LogicalMappingRecord[],
  query: LogicalMappingQuery,
): LogicalMappingPage => {
  const filtered = filterLogicalMappings(records, {
    keyword: query.keyword,
    pattern: query.pattern,
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

export const fetchLogicalMappings = async (
  query: LogicalMappingQuery = {},
): Promise<ApiResponse<LogicalMappingPage>> => {
  if (isPrototypeMode) {
    return {
      code: 0,
      msg: 'prototype success',
      data: paginate(readMockLogicalMappings(), query),
    };
  }
  return HttpUtils.post<LogicalMappingPage>(`${API_PREFIX}/page`, query);
};

export const createLogicalMapping = async (
  payload: Omit<
    LogicalMappingRecord,
    'id' | 'updatedAt' | 'lastPreviewedAt' | 'previewRowCount' | 'status'
  > & { status?: LogicalMappingRecord['status'] },
): Promise<ApiResponse<LogicalMappingRecord>> => {
  if (isPrototypeMode) {
    const data = readMockLogicalMappings();
    const id = `logic-${Date.now()}`;
    const next: LogicalMappingRecord = {
      ...payload,
      id,
      status: payload.status ?? 'DRAFT',
      lastPreviewedAt: '-',
      previewRowCount: 0,
      updatedAt: '2026-07-27 12:00',
    };
    writeMockLogicalMappings([next, ...data]);
    return { code: 0, msg: 'prototype success', data: next };
  }
  return HttpUtils.post<LogicalMappingRecord>(API_PREFIX, payload);
};

export const previewLogicalMapping = async (
  id: string,
): Promise<ApiResponse<LogicalPreviewResult | undefined>> => {
  if (isPrototypeMode) {
    const preview = readMockLogicalPreview(id);
    return {
      code: preview ? 0 : 404,
      msg: preview ? 'prototype success' : 'prototype not found',
      data: preview,
    };
  }
  return HttpUtils.get<LogicalPreviewResult>(`${API_PREFIX}/${id}/preview`);
};
