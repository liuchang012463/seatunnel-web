export type EdgeProtocol = 'MODBUS_TCP' | 'MQTT' | 'OPCUA' | 'SFTP_FILE' | 'HTTP_HOOK';
export type EdgeStatus = 'REGISTERED' | 'PENDING' | 'TESTING' | 'FAILED' | 'RUNNING';
export type EdgeAccessStatus = 'ONLINE' | 'OFFLINE' | 'DEGRADED';

export interface EdgeAccessRecord {
  id: string;
  name: string;
  protocol: EdgeProtocol;
  status: EdgeStatus;
  accessStatus: EdgeAccessStatus;
  endpoint: string;
  deviceCount: number;
  bytesIngested: number;
  owner: string;
  updatedAt: string;
  description: string;
  latencyMs: number;
}

export interface EdgeAccessPage {
  bizData: EdgeAccessRecord[];
  pagination: { pageNo: number; pageSize: number; total: number };
}

export interface EdgeAccessQuery {
  pageNo?: number;
  pageSize?: number;
  keyword?: string;
  protocol?: EdgeProtocol;
  status?: EdgeStatus;
}

export interface EdgeAccessTestResult {
  id: string;
  success: boolean;
  latencyMs: number;
  sampleRows: number;
  message: string;
}
