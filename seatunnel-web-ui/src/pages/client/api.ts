import HttpUtils from "@/utils/HttpUtils";
import type { ApiResponse } from "@/utils/request";

export const apiPrefix = "/api/v1/devops/client";

export interface SeatunnelClient {
  id?: number;
  clientName: string;
  engineType: "FLINK" | "SPARK" | "ZETA";
  baseUrl: string;
  healthStatus?: number;
  healthStatusName?: string;
  clientVersion?: string;
  heartbeatTime?: string;
  version?: string;
  containerId?: string;
  clientAddress?: string;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

export interface SeatunnelClientMetrics {
  cpuUsage?: number;
  memoryUsage?: number;
  threadCount?: number;
  runningOps?: number;
}

export interface SeatunnelClientPageRequest {
  pageNo?: number;
  pageSize?: number;
  keywords?: string;
  engineTypes?: string[];
  healthStatusList?: number[];
  sortField?: string;
  sortType?: "asc" | "desc";
}

export interface SeatunnelClientStatistics {
  total: number;
  liveCount: number;
  downCount: number;
}

export interface SeatunnelClientOption {
  value: string | number;
  label: string;
  description?: string;
}

export interface SeatunnelClientSaveResult {
  id?: number | string;
  value?: number | string;
}

export interface DatasourceVerifyItem {
  code?: string;
  name?: string;
  success?: boolean;
  actualValue?: string;
  expectedValue?: string;
  message?: string;
}

export interface DatasourceVerifyResult {
  success: boolean;
  items: DatasourceVerifyItem[];
  message?: string;
}

export interface SeatunnelClientLog {
  clientId: number;
  clientName: string;
  content: string;
}

export const seatunnelClientApi = {
  saveOrUpdate: (
    data: SeatunnelClient
  ): Promise<ApiResponse<SeatunnelClientSaveResult>> => {
    return HttpUtils.post<SeatunnelClientSaveResult>(
      `${apiPrefix}/saveOrUpdate`,
      data
    );
  },


  selectById: (
    id: number
  ): Promise<{ code: number; data: SeatunnelClient; message?: string }> => {
    return HttpUtils.get(`${apiPrefix}/${id}`);
  },

  delete: (id: number) => {
    return HttpUtils.delete(`${apiPrefix}/${id}`);
  },

  page: (
    data: SeatunnelClientPageRequest
  ): Promise<{ code: number; data: any; message?: string }> => {
    return HttpUtils.post(`${apiPrefix}/page`, data);
  },

  option: (): Promise<{
    code: number;
    data: SeatunnelClientOption[];
    msg?: string;
    message?: string;
  }> => {
    return HttpUtils.get(`${apiPrefix}/option`);
  },

  verifyDatasource: (
    clientId: string,
    params: {
      datasourceId: number | string;
      pluginName?: string;
      connectorType?: string;
      role?: "SOURCE" | "SINK";
      triggerMode?: "AUTO" | "MANUAL";
      forceRefresh?: boolean;
      scene?: "offline" | "realtime";
    }
  ): Promise<ApiResponse<DatasourceVerifyResult>> => {
    return HttpUtils.post<DatasourceVerifyResult>(
      `${apiPrefix}/${clientId}/verify-datasource`,
      {
      ...params,
      timeoutMs: 15000,
      pollIntervalMs: 1000,
      }
    );
  },

  metrics: (
    id: number
  ): Promise<{
    code: number;
    data: SeatunnelClientMetrics;
    msg?: string;
    message?: string;
  }> => {
    return HttpUtils.get(`${apiPrefix}/${id}/metrics`);
  },


};
