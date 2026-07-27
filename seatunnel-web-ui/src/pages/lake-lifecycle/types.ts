export type LifecycleTarget = 'HOT' | 'WARM' | 'COLD' | 'ARCHIVE';
export type LifecycleAction = 'TTL' | 'ARCHIVE' | 'PURGE' | 'COMPRESS';
export type LifecycleStatus = 'ACTIVE' | 'PAUSED' | 'DRAFT';

export interface LifecyclePolicyRecord {
  id: string;
  name: string;
  target: LifecycleTarget;
  action: LifecycleAction;
  status: LifecycleStatus;
  retentionDays: number;
  scope: string;
  lastRunAt: string;
  nextRunAt: string;
  owner: string;
  updatedAt: string;
  description: string;
  executionStats: { executed: number; purged: number; archived: number };
}

export interface LifecyclePolicyPage {
  bizData: LifecyclePolicyRecord[];
  pagination: { pageNo: number; pageSize: number; total: number };
}

export interface LifecyclePolicyQuery {
  pageNo?: number;
  pageSize?: number;
  keyword?: string;
  target?: LifecycleTarget;
  status?: LifecycleStatus;
}

export interface LifecycleExecutionRecord {
  id: string;
  policyId: string;
  policyName: string;
  startAt: string;
  endAt: string;
  result: 'SUCCESS' | 'FAILED' | 'PARTIAL';
  processedRows: number;
  message: string;
}
