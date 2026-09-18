import { ChartDataItem, TimeRange } from './types';


export const transformChartData = (trendData: ChartDataItem[], timeRange: TimeRange) => {
  return {
    data: trendData.map((item) => item.value),
    xAxis: trendData.map((item) => item.date),
  };
};

export const timeRangeMap = {
  '最近12小时': 'H12' as TimeRange,
  '最近一周': 'D7' as TimeRange,
  '最近24小时': 'H24' as TimeRange,
};

export const taskTypeOptions = [
  { label: '批量数据引接', value: 'BATCH' },
  { label: '实时数据引接', value: 'STREAM', disabled: true },
  { label: '文件数据引接', value: 'FILE_INGEST', disabled: true },
  { label: '文件传输', value: 'FILE_TRANSFER', disabled: true },
];