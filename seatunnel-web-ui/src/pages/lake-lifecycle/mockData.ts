import type {
  LifecycleExecutionRecord,
  LifecyclePolicyRecord,
} from './types';

const STORAGE_KEY = 'seatunnel-mock:lake-lifecycle';
const EXECUTION_KEY = 'seatunnel-mock:lake-lifecycle-executions';

const seed: LifecyclePolicyRecord[] = [
  {
    id: 'lc-001',
    name: '暂存数据 7 天清理',
    target: 'COLD',
    action: 'PURGE',
    status: 'ACTIVE',
    retentionDays: 7,
    scope: 'lake://base_data/staging/**',
    lastRunAt: '2026-07-27 02:00',
    nextRunAt: '2026-07-28 02:00',
    owner: '张工',
    updatedAt: '2026-07-26 18:00',
    description: '对临时落地表执行 7 天过期清理。',
    executionStats: { executed: 4, purged: 380000, archived: 0 },
  },
  {
    id: 'lc-002',
    name: '主数据长期保留',
    target: 'HOT',
    action: 'ARCHIVE',
    status: 'ACTIVE',
    retentionDays: 365,
    scope: 'lake://base_data/master/**',
    lastRunAt: '2026-07-25 03:00',
    nextRunAt: '2026-07-28 03:00',
    owner: '李工',
    updatedAt: '2026-07-25 03:10',
    description: '主数据一年后归档到冷存储。',
    executionStats: { executed: 2, purged: 0, archived: 15000 },
  },
  {
    id: 'lc-003',
    name: '日志数据 90 天归档',
    target: 'WARM',
    action: 'COMPRESS',
    status: 'PAUSED',
    retentionDays: 90,
    scope: 'lake://stream_topic/logs/**',
    lastRunAt: '2026-07-15 03:00',
    nextRunAt: '-',
    owner: '王工',
    updatedAt: '2026-07-20 11:00',
    description: '日志压缩归档策略，运维已暂停。',
    executionStats: { executed: 1, purged: 0, archived: 9000 },
  },
];

const executionSeed: LifecycleExecutionRecord[] = [
  {
    id: 'lc-exec-001',
    policyId: 'lc-001',
    policyName: '暂存数据 7 天清理',
    startAt: '2026-07-27 02:00',
    endAt: '2026-07-27 02:18',
    result: 'SUCCESS',
    processedRows: 380000,
    message: '按 TTL 清理 380000 行',
  },
  {
    id: 'lc-exec-002',
    policyId: 'lc-002',
    policyName: '主数据长期保留',
    startAt: '2026-07-25 03:00',
    endAt: '2026-07-25 03:35',
    result: 'PARTIAL',
    processedRows: 15000,
    message: '部分主数据归档失败：权限不足',
  },
];

export const readMockLifecyclePolicies = (): LifecyclePolicyRecord[] => {
  if (typeof window === 'undefined') return seed;
  const cached = window.localStorage.getItem(STORAGE_KEY);
  if (!cached) {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(seed));
    return [...seed];
  }
  try {
    return JSON.parse(cached) as LifecyclePolicyRecord[];
  } catch {
    return [...seed];
  }
};

export const writeMockLifecyclePolicies = (records: LifecyclePolicyRecord[]) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(records));
  }
};

export const resetMockLifecycle = () => {
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(STORAGE_KEY);
    window.localStorage.removeItem(EXECUTION_KEY);
  }
};

export const readMockLifecycleExecutions = (): LifecycleExecutionRecord[] => {
  if (typeof window === 'undefined') return executionSeed;
  const cached = window.localStorage.getItem(EXECUTION_KEY);
  if (!cached) {
    window.localStorage.setItem(EXECUTION_KEY, JSON.stringify(executionSeed));
    return [...executionSeed];
  }
  try {
    return JSON.parse(cached) as LifecycleExecutionRecord[];
  } catch {
    return [...executionSeed];
  }
};

export const appendMockLifecycleExecution = (record: LifecycleExecutionRecord) => {
  if (typeof window === 'undefined') return;
  const current = readMockLifecycleExecutions();
  window.localStorage.setItem(EXECUTION_KEY, JSON.stringify([record, ...current]));
};

export const filterLifecyclePolicies = (
  records: LifecyclePolicyRecord[],
  query: { keyword?: string; target?: string; status?: string },
): LifecyclePolicyRecord[] =>
  records.filter((record) => {
    if (query.keyword && !record.name.toLowerCase().includes(query.keyword.toLowerCase())) {
      return false;
    }
    if (query.target && record.target !== query.target) {
      return false;
    }
    if (query.status && record.status !== query.status) {
      return false;
    }
    return true;
  });
