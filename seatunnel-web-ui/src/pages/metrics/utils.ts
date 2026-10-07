import { ChartDataItem, TimeRange } from './types';


export const transformChartData = (trendData: ChartDataItem[], timeRange: TimeRange) => {
  return {
    data: trendData.map((item) => item.value),
    xAxis: trendData.map((item) => item.date),
  };
};

export const calculateSuccessRate = (successTasks: number, completedTasks: number) => {
  if (completedTasks <= 0) return null;
  return Math.round((successTasks / completedTasks) * 100);
};

export const timeRangeMap = {
  '最近12小时': 'H12' as TimeRange,
  '最近一周': 'D7' as TimeRange,
  '最近24小时': 'H24' as TimeRange,
};

export const taskTypeOptions = [
  { label: '批量数据引接', value: 'BATCH' },
  { label: '实时数据引接', value: 'STREAM' },
  { label: '离线文件导入', value: 'FILE_INGEST' },
  { label: '文件同步任务', value: 'FILE_TRANSFER' },
];
