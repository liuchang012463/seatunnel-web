import HttpUtils from '@/utils/HttpUtils';
import { isPrototypeMode } from '@/prototype/mode';
import type { ApiResponse } from '@/utils/request';
import {
  applyLinkFilters,
  readMockLinkHealth,
  readMockSyncLinks,
  writeMockSyncLinks,
} from './mockData';
import type {
  SyncLinkHealthDetail,
  SyncLinkPage,
  SyncLinkQuery,
  SyncLinkRecord,
} from './types';

const API_PREFIX = '/api/v1/sync-links';

const paginate = (records: SyncLinkRecord[], query: SyncLinkQuery): SyncLinkPage => {
  const filtered = applyLinkFilters(records, {
    keyword: query.keyword,
    linkType: query.linkType,
    health: query.health,
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

export const fetchSyncLinks = async (
  query: SyncLinkQuery = {},
): Promise<ApiResponse<SyncLinkPage>> => {
  if (isPrototypeMode) {
    const data = readMockSyncLinks();
    return {
      code: 0,
      msg: 'prototype success',
      data: paginate(data, query),
    };
  }
  return HttpUtils.post<SyncLinkPage>(`${API_PREFIX}/page`, query);
};

export const fetchSyncLinkDetail = async (
  id: string,
): Promise<ApiResponse<SyncLinkHealthDetail | undefined>> => {
  if (isPrototypeMode) {
    const detail = readMockLinkHealth(id);
    return {
      code: detail ? 0 : 404,
      msg: detail ? 'prototype success' : 'prototype not found',
      data: detail,
    };
  }
  return HttpUtils.get<SyncLinkHealthDetail>(`${API_PREFIX}/${id}/health`);
};

export const toggleSyncLinkStatus = async (
  id: string,
  nextStatus: 'ONLINE' | 'OFFLINE' | 'PAUSED',
): Promise<ApiResponse<boolean>> => {
  if (isPrototypeMode) {
    const data = readMockSyncLinks();
    const next = data.map((record) =>
      record.id === id
        ? { ...record, status: nextStatus, updatedAt: '2026-07-27 12:00' }
        : record,
    );
    writeMockSyncLinks(next);
    return { code: 0, msg: 'prototype success', data: true };
  }
  return HttpUtils.post<boolean>(`${API_PREFIX}/${id}/status`, { status: nextStatus });
};
