import type { LakeResourceRecord, LakeResourceTestResult } from './types';

const STORAGE_KEY = 'seatunnel-mock:lake-resources';

const seed: LakeResourceRecord[] = [
  {
    id: 'lake-001',
    name: '基础数据湖',
    format: 'PAIMON',
    accessMode: 'READ_WRITE',
    status: 'REGISTERED',
    endpoint: 'paimon://lake-host:6123/db',
    database: 'base_data',
    partitionSpec: 'dt=YYYY-MM-dd',
    referencedTasks: 6,
    storageQuotaGB: 4096,
    owner: '张工',
    updatedAt: '2026-07-27 10:00',
    description: '核心业务数据湖主目录，用于装备与基础数据。',
  },
  {
    id: 'lake-002',
    name: '实时主题湖',
    format: 'PAIMON',
    accessMode: 'APPEND_ONLY',
    status: 'REGISTERED',
    endpoint: 'paimon://lake-host:6123/stream',
    database: 'stream_topic',
    partitionSpec: 'hour=YYYY-MM-dd-HH',
    referencedTasks: 12,
    storageQuotaGB: 2048,
    owner: '李工',
    updatedAt: '2026-07-27 11:20',
    description: '实时主题写入湖，供查询和回放使用。',
  },
  {
    id: 'lake-003',
    name: '文件归档区',
    format: 'OSS',
    accessMode: 'READ_WRITE',
    status: 'PENDING',
    endpoint: 'oss://archive-bucket/data',
    database: 'archive_data',
    referencedTasks: 2,
    owner: '王工',
    updatedAt: '2026-07-27 09:30',
    description: '冷数据归档对象存储，写入限流待申请。',
  },
];

export const readMockLakeResources = (): LakeResourceRecord[] => {
  if (typeof window === 'undefined') return seed;
  const cached = window.localStorage.getItem(STORAGE_KEY);
  if (!cached) {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(seed));
    return [...seed];
  }
  try {
    return JSON.parse(cached) as LakeResourceRecord[];
  } catch {
    return [...seed];
  }
};

export const writeMockLakeResources = (records: LakeResourceRecord[]) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(records));
  }
};

export const resetMockLakeResources = () => {
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(STORAGE_KEY);
  }
};

export const filterLakeResources = (
  records: LakeResourceRecord[],
  query: { keyword?: string; format?: string; status?: string },
): LakeResourceRecord[] =>
  records.filter((record) => {
    if (query.keyword && !record.name.toLowerCase().includes(query.keyword.toLowerCase())) {
      return false;
    }
    if (query.format && record.format !== query.format) {
      return false;
    }
    if (query.status && record.status !== query.status) {
      return false;
    }
    return true;
  });

export const buildMockLakeTestResult = (id: string): LakeResourceTestResult => {
  const records = readMockLakeResources();
  const record = records.find((r) => r.id === id);
  if (!record) {
    return { id, success: false, message: '未找到对应入湖资源', latencyMs: 0 };
  }
  if (record.status === 'FAILED') {
    return { id, success: false, message: '连通失败：endpoint 不可达', latencyMs: 0 };
  }
  if (record.status === 'PENDING') {
    return {
      id,
      success: false,
      message: '资源待注册，请先在入湖控制台启用',
      latencyMs: 0,
    };
  }
  return {
    id,
    success: true,
    message: '连接成功，预览 30 行数据',
    latencyMs: 240,
    sampleRows: 30,
  };
};
