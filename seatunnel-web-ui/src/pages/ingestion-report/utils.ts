import { TASK_TYPE_LABEL } from './mock';
import type {
  DailyTrendPoint,
  IngestionReportRow,
  ReportFilters,
  ReportSummary,
  TaskTypeBreakdown,
} from './types';

export function filterRows(rows: IngestionReportRow[], filters: ReportFilters): IngestionReportRow[] {
  const [start, end] = filters.dateRange;
  return rows.filter((row) => {
    if (row.date < start || row.date > end) return false;
    if (filters.unitId && row.unitId !== filters.unitId) return false;
    if (filters.systemId && row.systemId !== filters.systemId) return false;
    if (filters.dataSourceId && row.dataSourceId !== filters.dataSourceId) return false;
    if (filters.taskType && row.taskType !== filters.taskType) return false;
    return true;
  });
}

export function buildSummary(rows: IngestionReportRow[]): ReportSummary {
  if (rows.length === 0) {
    return {
      totalBytes: 0,
      totalRecords: 0,
      totalTasks: 0,
      successRate: 0,
      activeDataSources: 0,
      coveredUnits: 0,
    };
  }

  let totalBytes = 0;
  let totalRecords = 0;
  let totalTasks = 0;
  let successCount = 0;
  const dataSources = new Set<string>();
  const units = new Set<string>();

  rows.forEach((row) => {
    totalBytes += row.byteCount;
    totalRecords += row.recordCount;
    totalTasks += row.taskCount;
    successCount += row.successCount;
    if (row.successCount > 0) {
      dataSources.add(row.dataSourceId);
      units.add(row.unitId);
    }
  });

  return {
    totalBytes,
    totalRecords,
    totalTasks,
    successRate: totalTasks === 0 ? 0 : successCount / totalTasks,
    activeDataSources: dataSources.size,
    coveredUnits: units.size,
  };
}

export function buildDailyTrend(rows: IngestionReportRow[]): DailyTrendPoint[] {
  const map = new Map<string, DailyTrendPoint>();
  rows.forEach((row) => {
    const current = map.get(row.date) || {
      date: row.date,
      recordCount: 0,
      byteCount: 0,
      taskCount: 0,
    };
    current.recordCount += row.recordCount;
    current.byteCount += row.byteCount;
    current.taskCount += row.taskCount;
    map.set(row.date, current);
  });
  return Array.from(map.values()).sort((a, b) => a.date.localeCompare(b.date));
}

export function buildTaskTypeBreakdown(rows: IngestionReportRow[]): TaskTypeBreakdown[] {
  const map = new Map<string, TaskTypeBreakdown>();
  rows.forEach((row) => {
    const current = map.get(row.taskType) || {
      taskType: row.taskType,
      label: TASK_TYPE_LABEL[row.taskType],
      taskCount: 0,
      recordCount: 0,
      byteCount: 0,
      successRate: 0,
    };
    current.taskCount += row.taskCount;
    current.recordCount += row.recordCount;
    current.byteCount += row.byteCount;
    map.set(row.taskType, current);
  });

  return Array.from(map.values())
    .map((item) => {
      const successTasks = rows
        .filter((r) => r.taskType === item.taskType)
        .reduce((sum, r) => sum + r.successCount, 0);
      return {
        ...item,
        successRate: item.taskCount === 0 ? 0 : successTasks / item.taskCount,
      };
    })
    .sort((a, b) => b.byteCount - a.byteCount);
}

export function formatBytes(bytes: number): string {
  if (bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let idx = 0;
  while (value >= 1024 && idx < units.length - 1) {
    value /= 1024;
    idx += 1;
  }
  const digits = value >= 100 || idx === 0 ? 0 : value >= 10 ? 1 : 2;
  return `${value.toFixed(digits)} ${units[idx]}`;
}

export function formatCount(value: number): string {
  if (value >= 100_000_000) return `${(value / 100_000_000).toFixed(2)} 亿`;
  if (value >= 10_000) return `${(value / 10_000).toFixed(1)} 万`;
  return `${value}`;
}

export function formatPercent(rate: number): string {
  return `${(rate * 100).toFixed(1)}%`;
}

function csvEscape(value: string | number): string {
  const text = String(value);
  if (/[",\n\r]/.test(text)) {
    return `"${text.replace(/"/g, '""')}"`;
  }
  return text;
}

export function rowsToCsv(rows: IngestionReportRow[]): string {
  const header = [
    '日期',
    '单位',
    '系统',
    '数据源',
    '引接任务类型',
    '任务数',
    '成功任务数',
    '引接记录数',
    '引接数据量(字节)',
    '成功率',
  ];
  const lines = rows.map((row) =>
    [
      row.date,
      row.unitName,
      row.systemName,
      row.dataSourceName,
      TASK_TYPE_LABEL[row.taskType],
      row.taskCount,
      row.successCount,
      row.recordCount,
      row.byteCount,
      formatPercent(row.taskCount === 0 ? 0 : row.successCount / row.taskCount),
    ]
      .map(csvEscape)
      .join(','),
  );
  return `\uFEFF${[header.join(','), ...lines].join('\n')}`;
}

export function downloadCsv(filename: string, content: string): void {
  const blob = new Blob([content], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}
