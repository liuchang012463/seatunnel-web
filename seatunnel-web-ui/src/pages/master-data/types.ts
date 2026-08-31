/**
 * Master-data records returned by the data-source owning-unit and business
 * system APIs.
 *
 * The backend currently serializes ids as numbers.  The string alternative is
 * kept here because ids can be returned as strings by a gateway or a mock
 * implementation without changing the page contract.
 */
export type MasterDataId = number | string;

export type MasterDataStatus = 0 | 1;

export interface ApiResponse<T> {
  code: number;
  data: T;
  msg?: string;
  message?: string;
}

export interface PaginationInfo {
  pageNo: number;
  pageSize: number;
  total: number;
}
export interface PageData<T> {
  bizData?: T[];
  pagination?: PaginationInfo;
  /** Compatibility with the default MyBatis-Plus page shape. */
  records?: T[];
  total?: number;
}

export interface MasterDataPage<T> {
  records: T[];
  pagination: PaginationInfo;
}

export interface DataSourceUnitRecord {
  id: MasterDataId;
  unitCode: string;
  unitName: string;
  status: MasterDataStatus;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

export interface BusinessSystemRecord {
  id: MasterDataId;
  unitId: MasterDataId;
  unitCode?: string;
  unitName?: string;
  systemCode: string;
  systemName: string;
  status: MasterDataStatus;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

export interface DataSourceUnitPageParams {
  pageNo: number;
  pageSize: number;
  unitCode?: string;
  unitName?: string;
  status?: MasterDataStatus;
}

export interface BusinessSystemPageParams {
  pageNo: number;
  pageSize: number;
  unitId?: MasterDataId;
  systemCode?: string;
  systemName?: string;
  status?: MasterDataStatus;
}

export interface DataSourceUnitPayload {
  unitCode: string;
  unitName: string;
  status: MasterDataStatus;
  remark?: string;
}

export interface BusinessSystemPayload {
  unitId: MasterDataId;
  systemCode: string;
  systemName: string;
  status: MasterDataStatus;
  remark?: string;
}

export interface MasterDataOption {
  id: MasterDataId;
  label: string;
  unitCode?: string;
  unitName?: string;
}

export interface MasterDataPageQuery {
  pageNo: number;
  pageSize: number;
  total: number;
}
