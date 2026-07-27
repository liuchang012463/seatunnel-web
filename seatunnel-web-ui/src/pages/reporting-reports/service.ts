import HttpUtils from '@/utils/HttpUtils';
import { isPrototypeMode } from '@/prototype/mode';
import type { ApiResponse } from '@/utils/request';
import {
  filterReports,
  readMockReportPreview,
  readMockReports,
  writeMockReports,
} from './mockData';
import type {
  CollectionReportPage,
  CollectionReportQuery,
  CollectionReportRecord,
  ReportPreview,
} from './types';

const API_PREFIX = '/api/v1/reports';

const paginate = (
  records: CollectionReportRecord[],
  query: CollectionReportQuery,
): CollectionReportPage => {
  const filtered = filterReports(records, {
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

export const fetchReports = async (
  query: CollectionReportQuery = {},
): Promise<ApiResponse<CollectionReportPage>> => {
  if (isPrototypeMode) {
    const data = readMockReports();
    return { code: 0, msg: 'prototype success', data: paginate(data, query) };
  }
  return HttpUtils.post<CollectionReportPage>(`${API_PREFIX}/page`, query);
};

export const generateReport = async (
  payload: Pick<CollectionReportRecord, 'name' | 'source' | 'format' | 'description' | 'relatedForm'>,
): Promise<ApiResponse<CollectionReportRecord>> => {
  if (isPrototypeMode) {
    const data = readMockReports();
    const id = `report-${Date.now()}`;
    const next: CollectionReportRecord = {
      id,
      ...payload,
      status: 'GENERATING',
      generatedAt: '2026-07-27 12:00',
      owner: '当前 SSO 用户',
      rowCount: 0,
    };
    writeMockReports([next, ...data]);
    return { code: 0, msg: 'prototype success', data: next };
  }
  return HttpUtils.post<CollectionReportRecord>(`${API_PREFIX}/generate`, payload);
};

export const previewReport = async (
  id: string,
): Promise<ApiResponse<ReportPreview | undefined>> => {
  if (isPrototypeMode) {
    const preview = readMockReportPreview(id);
    return {
      code: preview ? 0 : 404,
      msg: preview ? 'prototype success' : 'prototype not found',
      data: preview,
    };
  }
  return HttpUtils.get<ReportPreview>(`${API_PREFIX}/${id}/preview`);
};
