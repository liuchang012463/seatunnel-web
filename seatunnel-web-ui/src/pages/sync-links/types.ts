export type LinkHealth = 'HEALTHY' | 'WARNING' | 'FAILED' | 'UNKNOWN';
export type LinkType = 'BATCH' | 'STREAM' | 'FILE';
export type LinkStatus = 'ONLINE' | 'OFFLINE' | 'PAUSED' | 'DRAFT';

export interface SyncLinkRecord {
  id: string;
  name: string;
  linkType: LinkType;
  source: string;
  target: string;
  status: LinkStatus;
  health: LinkHealth;
  bandwidthQuota: number;
  priority: number;
  owner: string;
  updatedAt: string;
  jobRef: string;
  healthScore: number;
  description: string;
  lastIssue?: string;
}

export interface SyncLinkPage {
  bizData: SyncLinkRecord[];
  pagination: {
    pageNo: number;
    pageSize: number;
    total: number;
  };
}

export interface SyncLinkQuery {
  pageNo?: number;
  pageSize?: number;
  keyword?: string;
  linkType?: LinkType;
  health?: LinkHealth;
  status?: LinkStatus;
}

export interface SyncLinkHealthDetail {
  linkId: string;
  timeline: { time: string; event: string; severity: 'INFO' | 'WARN' | 'ERROR' }[];
  metrics: {
    throughput: number;
    latencyMs: number;
    successRate: number;
    backlog: number;
  };
  recommendation: string;
}
