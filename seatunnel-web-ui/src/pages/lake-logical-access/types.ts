export type LogicalMappingStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED';
export type LogicalAccessPattern = 'UNION' | 'JOIN' | 'VIEW' | 'PASSTHROUGH';

export interface LogicalMappingRecord {
  id: string;
  name: string;
  pattern: LogicalAccessPattern;
  status: LogicalMappingStatus;
  sources: string[];
  target: string;
  lastPreviewedAt: string;
  previewRowCount: number;
  owner: string;
  updatedAt: string;
  description: string;
}

export interface LogicalMappingPage {
  bizData: LogicalMappingRecord[];
  pagination: { pageNo: number; pageSize: number; total: number };
}

export interface LogicalMappingQuery {
  pageNo?: number;
  pageSize?: number;
  keyword?: string;
  pattern?: LogicalAccessPattern;
  status?: LogicalMappingStatus;
}

export interface LogicalPreviewResult {
  id: string;
  columns: string[];
  rows: string[][];
  message: string;
}
