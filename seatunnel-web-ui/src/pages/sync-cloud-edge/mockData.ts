import type {
  CloudEdgeNetworkEvent,
  CloudEdgeTaskRecord,
} from './types';

const STORAGE_KEY = 'seatunnel-mock:sync-cloud-edge';
const NETWORK_KEY = 'seatunnel-mock:sync-cloud-edge-network';

const seed: CloudEdgeTaskRecord[] = [
  {
    id: 'ce-001',
    name: '华北节点镜像同步',
    transport: 'FULL_MIRROR',
    status: 'DISPATCHED',
    sourceCluster: 'cn-north-1',
    edgeNode: 'edge-huabei-01',
    bytesPlanned: 5368709120,
    bytesTransferred: 3221225472,
    queuedChunks: 18,
    owner: '张工',
    updatedAt: '2026-07-27 10:15',
    description: '云端华北主集群向华北边缘节点同步镜像。',
  },
  {
    id: 'ce-002',
    name: '离线缓存续传',
    transport: 'INCREMENTAL',
    status: 'OFFLINE',
    sourceCluster: 'cn-east-1',
    edgeNode: 'edge-huadong-02',
    bytesPlanned: 2147483648,
    bytesTransferred: 1610612736,
    queuedChunks: 42,
    owner: '李工',
    updatedAt: '2026-07-27 09:50',
    description: '断网期间缓存的增量数据，等待网络恢复续传。',
  },
  {
    id: 'ce-003',
    name: '边缘状态回传',
    transport: 'EVENT_FEEDBACK',
    status: 'RUNNING',
    sourceCluster: 'cn-south-1',
    edgeNode: 'edge-huanan-03',
    bytesPlanned: 268435456,
    bytesTransferred: 134217728,
    queuedChunks: 6,
    owner: '王工',
    updatedAt: '2026-07-27 11:30',
    description: '边缘节点状态与心跳回传链路。',
  },
];

const networkSeed: CloudEdgeNetworkEvent[] = [
  {
    taskId: 'ce-002',
    timestamp: '2026-07-27 09:30',
    online: false,
    pendingChunks: 42,
    message: '边缘节点报告离线，进入暂存区',
  },
  {
    taskId: 'ce-002',
    timestamp: '2026-07-27 09:50',
    online: false,
    pendingChunks: 42,
    message: '续传任务仍在缓存区等待',
  },
];

export const readMockCloudEdgeTasks = (): CloudEdgeTaskRecord[] => {
  if (typeof window === 'undefined') return seed;
  const cached = window.localStorage.getItem(STORAGE_KEY);
  if (!cached) {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(seed));
    return [...seed];
  }
  try {
    return JSON.parse(cached) as CloudEdgeTaskRecord[];
  } catch {
    return [...seed];
  }
};

export const writeMockCloudEdgeTasks = (records: CloudEdgeTaskRecord[]) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(records));
  }
};

export const readMockNetworkEvents = (): CloudEdgeNetworkEvent[] => {
  if (typeof window === 'undefined') return networkSeed;
  const cached = window.localStorage.getItem(NETWORK_KEY);
  if (!cached) {
    window.localStorage.setItem(NETWORK_KEY, JSON.stringify(networkSeed));
    return [...networkSeed];
  }
  try {
    return JSON.parse(cached) as CloudEdgeNetworkEvent[];
  } catch {
    return [...networkSeed];
  }
};

export const appendMockNetworkEvent = (event: CloudEdgeNetworkEvent) => {
  if (typeof window === 'undefined') return;
  const current = readMockNetworkEvents();
  window.localStorage.setItem(NETWORK_KEY, JSON.stringify([event, ...current]));
};

export const filterCloudEdgeTasks = (
  records: CloudEdgeTaskRecord[],
  query: { keyword?: string; status?: string; transport?: string },
): CloudEdgeTaskRecord[] =>
  records.filter((record) => {
    if (query.keyword && !record.name.toLowerCase().includes(query.keyword.toLowerCase())) {
      return false;
    }
    if (query.status && record.status !== query.status) {
      return false;
    }
    if (query.transport && record.transport !== query.transport) {
      return false;
    }
    return true;
  });
