export type CloudEdgeStatus = 'DISPATCHED' | 'RUNNING' | 'OFFLINE' | 'STAGED' | 'COMPLETED';
export type CloudEdgeTransport = 'FULL_MIRROR' | 'INCREMENTAL' | 'EVENT_FEEDBACK';

export interface CloudEdgeTaskRecord {
  id: string;
  name: string;
  transport: CloudEdgeTransport;
  status: CloudEdgeStatus;
  sourceCluster: string;
  edgeNode: string;
  bytesPlanned: number;
  bytesTransferred: number;
  queuedChunks: number;
  owner: string;
  updatedAt: string;
  description: string;
}

export interface CloudEdgeTaskPage {
  bizData: CloudEdgeTaskRecord[];
  pagination: { pageNo: number; pageSize: number; total: number };
}

export interface CloudEdgeTaskQuery {
  pageNo?: number;
  pageSize?: number;
  keyword?: string;
  status?: CloudEdgeStatus;
  transport?: CloudEdgeTransport;
}

export interface CloudEdgeNetworkEvent {
  taskId: string;
  timestamp: string;
  online: boolean;
  pendingChunks: number;
  message: string;
}
