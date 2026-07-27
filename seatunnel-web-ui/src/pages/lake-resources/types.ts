export type LakeFormat = 'PAIMON' | 'ICEBERG' | 'HUDI' | 'DELTA' | 'OSS' | 'HDFS';
export type LakeStatus = 'REGISTERED' | 'PENDING' | 'FAILED';
export type LakeAccessMode = 'READ_WRITE' | 'READ_ONLY' | 'APPEND_ONLY';

export interface LakeResourceRecord {
  id: string;
  name: string;
  format: LakeFormat;
  accessMode: LakeAccessMode;
  status: LakeStatus;
  endpoint: string;
  database: string;
  partitionSpec?: string;
  referencedTasks: number;
  storageQuotaGB?: number;
  owner: string;
  updatedAt: string;
  description: string;
}

export interface LakeResourcePage {
  bizData: LakeResourceRecord[];
  pagination: { pageNo: number; pageSize: number; total: number };
}

export interface LakeResourceQuery {
  pageNo?: number;
  pageSize?: number;
  keyword?: string;
  format?: LakeFormat;
  status?: LakeStatus;
}

export interface LakeResourceTestResult {
  id: string;
  success: boolean;
  message: string;
  latencyMs: number;
  sampleRows?: number;
}
