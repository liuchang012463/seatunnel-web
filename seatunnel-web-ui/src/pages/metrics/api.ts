import HttpUtils from "@/utils/HttpUtils";
import { ApiChartData, SummaryData, TaskType, TimeRange } from "./types";

export const fetchSummaryData = async (
  timeRange: TimeRange,
  taskType: TaskType
): Promise<SummaryData> => {
  const response = await HttpUtils.get<SummaryData>(
    `/api/v1/job/metrics/summary?timeRange=${timeRange}&taskType=${taskType}`,
    { skipErrorHandler: true }
  );

  if (response?.code === 0 && response.data) {
    return response.data;
  }
  throw new Error(response?.message || "任务概览指标暂不可用");
};

export const fetchChartData = async (
  timeRange: TimeRange,
  taskType: TaskType
): Promise<ApiChartData> => {
  const response = await HttpUtils.get<ApiChartData>(
    `/api/v1/job/metrics/charts?timeRange=${timeRange}&taskType=${taskType}`,
    { skipErrorHandler: true }
  );

  if (response?.code === 0 && response.data) {
    return response.data;
  }
  throw new Error(response?.message || "趋势数据暂不可用");
};
