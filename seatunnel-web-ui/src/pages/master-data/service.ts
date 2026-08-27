import HttpUtils from '@/utils/HttpUtils';
import type {
  ApiResponse,
  BusinessSystemPageParams,
  BusinessSystemPayload,
  BusinessSystemRecord,
  DataSourceUnitPageParams,
  DataSourceUnitPayload,
  DataSourceUnitRecord,
  MasterDataId,
  MasterDataOption,
  MasterDataPage,
  MasterDataPageQuery,
  PageData,
} from './types';

export const DATA_SOURCE_UNIT_API_PREFIX = '/api/v1/data-source-units';
export const BUSINESS_SYSTEM_API_PREFIX = '/api/v1/business-systems';

/**
 * The pagination wrapper is shared by the two master-data endpoints.  Keep
 * the normalisation in one place so the page remains compatible with both the
 * current PaginationResult shape and older IPage-shaped responses.
 */
export function normalizePageData<T>(
  response?: ApiResponse<PageData<T> | T[]>,
  defaults: Pick<MasterDataPageQuery, 'pageNo' | 'pageSize'> = { pageNo: 1, pageSize: 10 },
): MasterDataPage<T> {
  const data = response?.data;
  if (Array.isArray(data)) {
    return {
      records: data,
      pagination: { ...defaults, total: data.length },
    };
  }

  const records = Array.isArray(data?.bizData)
    ? data.bizData
    : Array.isArray(data?.records)
      ? data.records
      : [];
  const pagination = data?.pagination;

  return {
    records,
    pagination: {
      pageNo: pagination?.pageNo ?? defaults.pageNo,
      pageSize: pagination?.pageSize ?? defaults.pageSize,
      total: pagination?.total ?? data?.total ?? records.length,
    },
  };
}

export function normalizeOptions<T extends { id: MasterDataId }>(
  response?: ApiResponse<T[] | { bizData?: T[] }>,
): T[] {
  const data = response?.data;
  if (Array.isArray(data)) {
    return data;
  }
  return Array.isArray(data?.bizData) ? data.bizData : [];
}

export async function fetchDataSourceUnitPage(
  params: DataSourceUnitPageParams,
): Promise<ApiResponse<PageData<DataSourceUnitRecord>>> {
  return HttpUtils.post(`${DATA_SOURCE_UNIT_API_PREFIX}/page`, params);
}

export async function fetchActiveDataSourceUnits(): Promise<ApiResponse<DataSourceUnitRecord[]>> {
  return HttpUtils.get(`${DATA_SOURCE_UNIT_API_PREFIX}/active`);
}

export async function createDataSourceUnit(
  payload: DataSourceUnitPayload,
): Promise<ApiResponse<MasterDataId>> {
  return HttpUtils.post(DATA_SOURCE_UNIT_API_PREFIX, payload);
}

export async function updateDataSourceUnit(
  id: MasterDataId,
  payload: DataSourceUnitPayload,
): Promise<ApiResponse<boolean>> {
  return HttpUtils.put(`${DATA_SOURCE_UNIT_API_PREFIX}/${encodeURIComponent(String(id))}`, payload);
}

export async function deleteDataSourceUnit(
  id: MasterDataId,
): Promise<ApiResponse<boolean>> {
  return HttpUtils.delete(`${DATA_SOURCE_UNIT_API_PREFIX}/${encodeURIComponent(String(id))}`);
}

export async function fetchBusinessSystemPage(
  params: BusinessSystemPageParams,
): Promise<ApiResponse<PageData<BusinessSystemRecord>>> {
  return HttpUtils.post(`${BUSINESS_SYSTEM_API_PREFIX}/page`, params);
}

export async function fetchActiveBusinessSystems(
  unitId: MasterDataId,
): Promise<ApiResponse<BusinessSystemRecord[]>> {
  const query = new URLSearchParams({ unitId: String(unitId) });
  return HttpUtils.get(`${BUSINESS_SYSTEM_API_PREFIX}/active?${query.toString()}`);
}

export async function createBusinessSystem(
  payload: BusinessSystemPayload,
): Promise<ApiResponse<MasterDataId>> {
  return HttpUtils.post(BUSINESS_SYSTEM_API_PREFIX, payload);
}

export async function updateBusinessSystem(
  id: MasterDataId,
  payload: BusinessSystemPayload,
): Promise<ApiResponse<boolean>> {
  return HttpUtils.put(`${BUSINESS_SYSTEM_API_PREFIX}/${encodeURIComponent(String(id))}`, payload);
}

export async function deleteBusinessSystem(
  id: MasterDataId,
): Promise<ApiResponse<boolean>> {
  return HttpUtils.delete(`${BUSINESS_SYSTEM_API_PREFIX}/${encodeURIComponent(String(id))}`);
}

export function toUnitOptions(units: DataSourceUnitRecord[]): MasterDataOption[] {
  return units.map((unit) => ({
    id: unit.id,
    label: unit.unitName,
    unitCode: unit.unitCode,
    unitName: unit.unitName,
  }));
}
