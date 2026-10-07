import HttpUtils from "@/utils/HttpUtils";
import { ChartData, SummaryData, TaskType, TimeRange } from "./types";

export const fetchSummaryData = async (
  timeRange: TimeRange,
  taskType: TaskType
): Promise<SummaryData> => {
  const response = await HttpUtils.get<SummaryData>(
    `/api/v1/job/metrics/summary?timeRange=${timeRange}&taskType=${taskType}`,
    { skipErrorHandler: true }
  );

  if (response?.code === 0) {
    return response.data;
  }
  throw new Error(response?.message || "Error");
};

export const fetchChartData = async (
  timeRange: TimeRange,
  taskType: TaskType
): Promise<ChartData> => {
  const response = await HttpUtils.get<ChartData>(
    `/api/v1/job/metrics/charts?timeRange=${timeRange}&taskType=${taskType}`,
    { skipErrorHandler: true }
  );

  if (response?.code === 0) {
    return response.data;
  }
  throw new Error(response?.message || "Error");
};
