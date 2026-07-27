export type FaultSeverity = 'INFO' | 'WARNING' | 'ERROR' | 'CRITICAL';
export type FaultStatus = 'OPEN' | 'INVESTIGATING' | 'RECOVERED' | 'MITIGATED';

export interface FaultRecord {
  id: string;
  title: string;
  severity: FaultSeverity;
  status: FaultStatus;
  relatedTask: string;
  relatedLink: string;
  firstSeen: string;
  lastSeen: string;
  owner: string;
  classification: string;
  description: string;
}

export interface FaultPage {
  bizData: FaultRecord[];
  pagination: { pageNo: number; pageSize: number; total: number };
}

export interface FaultQuery {
  pageNo?: number;
  pageSize?: number;
  keyword?: string;
  severity?: FaultSeverity;
  status?: FaultStatus;
}

export interface FaultEvidence {
  id: string;
  timeline: { time: string; severity: FaultSeverity; event: string }[];
  recommendation: string;
  retryPlan: string[];
}
