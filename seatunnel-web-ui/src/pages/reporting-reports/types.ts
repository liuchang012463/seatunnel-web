export type ReportFormat = 'PDF' | 'WORD' | 'EXCEL' | 'CSV';
export type ReportStatus = 'DRAFT' | 'GENERATING' | 'READY' | 'FAILED';
export type ReportSource = 'FORM_RESPONSE' | 'BATCH_TASK' | 'STREAM_TASK' | 'MANUAL';

export interface CollectionReportRecord {
  id: string;
  name: string;
  source: ReportSource;
  format: ReportFormat;
  status: ReportStatus;
  generatedAt: string;
  owner: string;
  relatedForm?: string;
  rowCount: number;
  description: string;
}

export interface CollectionReportPage {
  bizData: CollectionReportRecord[];
  pagination: { pageNo: number; pageSize: number; total: number };
}

export interface CollectionReportQuery {
  pageNo?: number;
  pageSize?: number;
  keyword?: string;
  format?: ReportFormat;
  status?: ReportStatus;
}

export interface ReportPreview {
  id: string;
  sections: { title: string; rows: string[][] }[];
}
