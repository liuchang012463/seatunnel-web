export interface ChartDataItem {
  date: string;
  value: number | null;
  unit?: string;
}

export interface ChartDataSet {
  data: any[];
  xAxis: string[];
}

export interface ChartData {
  recordsTrend: ChartDataSet;
  bytesTrend: ChartDataSet;
  recordsSpeedTrend: ChartDataSet;
  bytesSpeedTrend: ChartDataSet;
}

/** `/api/v1/job/metrics/charts` 的原始响应：每个趋势是一组按日期排列的点。 */
export interface ApiChartData {
  recordsTrend: ChartDataItem[];
  bytesTrend: ChartDataItem[];
  recordsSpeedTrend: ChartDataItem[];
  bytesSpeedTrend: ChartDataItem[];
}

export interface SummaryData {
  totalRecords: number;
  totalBytes: number;
  totalTasks: number;
  successTasks: number;
  failedTasks: number;
  runningTasks: number;
  stoppedTasks: number;
  avgRecordDelay: number;
  totalBytesUnit: any;
  totalRecordsUnit: any;
}

export type TimeRange = 'H24' | 'D30' | 'D7' | 'H1' | 'H6' | 'H12';
export type TaskType = 'BATCH' | 'STREAM' | 'FILE_INGEST' | 'FILE_TRANSFER';
