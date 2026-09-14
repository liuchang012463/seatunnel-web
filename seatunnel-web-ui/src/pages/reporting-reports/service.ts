import HttpUtils from '@/utils/HttpUtils';
import type { ApiResponse } from '@/utils/request';
import type {
  CollectionReportPage,
  CollectionReportQuery,
  CollectionReportRecord,
  ReportPreview,
} from './types';

const API_PREFIX = '/api/v1/reports';

export const fetchReports = async (
  query: CollectionReportQuery = {},
): Promise<ApiResponse<CollectionReportPage>> => {
  return HttpUtils.post<CollectionReportPage>(`${API_PREFIX}/page`, query);
};

export const generateReport = async (
  payload: Pick<CollectionReportRecord, 'name' | 'source' | 'format' | 'description' | 'relatedForm'>,
): Promise<ApiResponse<CollectionReportRecord>> => {
  return HttpUtils.post<CollectionReportRecord>(`${API_PREFIX}/generate`, payload);
};

export const previewReport = async (
  id: string,
): Promise<ApiResponse<ReportPreview | undefined>> => {
  return HttpUtils.get<ReportPreview>(`${API_PREFIX}/${id}/preview`);
};
