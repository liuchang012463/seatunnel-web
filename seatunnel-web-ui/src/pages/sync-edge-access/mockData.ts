import type {
  EdgeAccessRecord,
  EdgeAccessTestResult,
} from './types';

const STORAGE_KEY = 'seatunnel-mock:sync-edge-access';

const seed: EdgeAccessRecord[] = [
  {
    id: 'edge-001',
    name: 'Modbus 设备接入',
    protocol: 'MODBUS_TCP',
    status: 'RUNNING',
    accessStatus: 'ONLINE',
    endpoint: 'modbus://edge-huabei-01:502',
    deviceCount: 18,
    bytesIngested: 268435456,
    owner: '张工',
    updatedAt: '2026-07-27 10:00',
    description: 'Modbus TCP 设备统一接入，采集 PLC 指标。',
    latencyMs: 120,
  },
  {
    id: 'edge-002',
    name: 'MQTT 遥测接入',
    protocol: 'MQTT',
    status: 'RUNNING',
    accessStatus: 'DEGRADED',
    endpoint: 'mqtt://edge-huadong-02:1883',
    deviceCount: 32,
    bytesIngested: 536870912,
    owner: '李工',
    updatedAt: '2026-07-27 10:45',
    description: 'MQTT 遥测主题接入，处于降级状态。',
    latencyMs: 480,
  },
  {
    id: 'edge-003',
    name: 'SFTP 文件接入',
    protocol: 'SFTP_FILE',
    status: 'PENDING',
    accessStatus: 'OFFLINE',
    endpoint: 'sftp://edge-huanan-03:22/data',
    deviceCount: 4,
    bytesIngested: 0,
    owner: '王工',
    updatedAt: '2026-07-27 09:30',
    description: 'SFTP 文件接入待启用，边缘节点离线。',
    latencyMs: 0,
  },
];

export const readMockEdgeAccess = (): EdgeAccessRecord[] => {
  if (typeof window === 'undefined') return seed;
  const cached = window.localStorage.getItem(STORAGE_KEY);
  if (!cached) {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(seed));
    return [...seed];
  }
  try {
    return JSON.parse(cached) as EdgeAccessRecord[];
  } catch {
    return [...seed];
  }
};

export const writeMockEdgeAccess = (records: EdgeAccessRecord[]) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(records));
  }
};

export const resetMockEdgeAccess = () => {
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(STORAGE_KEY);
  }
};

export const filterEdgeAccess = (
  records: EdgeAccessRecord[],
  query: { keyword?: string; protocol?: string; status?: string },
): EdgeAccessRecord[] =>
  records.filter((record) => {
    if (query.keyword && !record.name.toLowerCase().includes(query.keyword.toLowerCase())) {
      return false;
    }
    if (query.protocol && record.protocol !== query.protocol) {
      return false;
    }
    if (query.status && record.status !== query.status) {
      return false;
    }
    return true;
  });

export const buildMockEdgeTestResult = (id: string): EdgeAccessTestResult => {
  const records = readMockEdgeAccess();
  const record = records.find((r) => r.id === id);
  if (!record) {
    return { id, success: false, latencyMs: 0, sampleRows: 0, message: '未找到对应边缘接入' };
  }
  if (record.status === 'PENDING') {
    return { id, success: false, latencyMs: 0, sampleRows: 0, message: '资源待启用，请先启用边缘节点' };
  }
  if (record.accessStatus === 'OFFLINE') {
    return { id, success: false, latencyMs: 0, sampleRows: 0, message: '边缘节点离线，无法连通' };
  }
  return {
    id,
    success: true,
    latencyMs: record.latencyMs,
    sampleRows: 16,
    message: '连通成功，已读取 16 条样例数据',
  };
};
