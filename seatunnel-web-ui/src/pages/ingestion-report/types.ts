export type IngestionTaskType = 'BATCH' | 'STREAM' | 'FILE';

export interface DimensionOption {
  id: string;
  name: string;
}

export interface SystemOption extends DimensionOption {}

export interface DataSourceOption extends DimensionOption {
  systemId: string;
}

export interface IngestionReportRow {
  id: string;
  date: string;
  unitId: string;
  unitName: string;
  systemId: string;
  systemName: string;
  dataSourceId: string;
  dataSourceName: string;
  taskType: IngestionTaskType;
  taskCount: number;
  successCount: number;
  recordCount: number;
  byteCount: number;
}

export interface ReportFilters {
  unitId?: string;
  systemId?: string;
  dataSourceId?: string;
  taskType?: IngestionTaskType;
  dateRange: [string, string];
}

export interface ReportSummary {
  totalBytes: number;
  totalRecords: number;
  totalTasks: number;
  successRate: number;
  activeDataSources: number;
  coveredUnits: number;
}

export interface DailyTrendPoint {
  date: string;
  recordCount: number;
  byteCount: number;
  taskCount: number;
}

export interface TaskTypeBreakdown {
  taskType: IngestionTaskType;
  label: string;
  taskCount: number;
  recordCount: number;
  byteCount: number;
  successRate: number;
}
