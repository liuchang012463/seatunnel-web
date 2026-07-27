import type {
  SyncLinkHealthDetail,
  SyncLinkRecord,
} from './types';

const STORAGE_KEY = 'seatunnel-mock:sync-links';

const seed: SyncLinkRecord[] = [
  {
    id: 'link-001',
    name: '装备主数据 → 基础数据湖',
    linkType: 'BATCH',
    source: 'MySQL 装备主库',
    target: 'Paimon 基础数据湖',
    status: 'ONLINE',
    health: 'HEALTHY',
    bandwidthQuota: 200,
    priority: 1,
    owner: '张工',
    updatedAt: '2026-07-27 10:20',
    jobRef: '/sync/batch-link-up/1001/detail',
    healthScore: 96,
    description: '装备主数据每日全量同步，离线批次任务。',
  },
  {
    id: 'link-002',
    name: '遥测 Kafka → 实时主题湖',
    linkType: 'STREAM',
    source: 'Kafka 遥测主题',
    target: 'Paimon 实时主题湖',
    status: 'ONLINE',
    health: 'WARNING',
    bandwidthQuota: 180,
    priority: 2,
    owner: '李工',
    updatedAt: '2026-07-27 11:05',
    jobRef: '/sync/stream-link-up/2002/detail',
    healthScore: 72,
    description: '实时遥测引接，关注消费位点。',
    lastIssue: '消费位点延迟 18 秒',
  },
  {
    id: 'link-003',
    name: '文件区 → 归档湖',
    linkType: 'FILE',
    source: '文件区 /data/source/',
    target: 'OSS 归档桶',
    status: 'PAUSED',
    health: 'FAILED',
    bandwidthQuota: 60,
    priority: 4,
    owner: '王工',
    updatedAt: '2026-07-27 09:48',
    jobRef: '/sync/batch-link-up/1003/detail',
    healthScore: 30,
    description: '冷数据归档，文件同步任务。',
    lastIssue: 'OSS 写入被限流',
  },
];

const detailSeeds: Record<string, SyncLinkHealthDetail> = {
  'link-001': {
    linkId: 'link-001',
    metrics: { throughput: 180, latencyMs: 240, successRate: 99.7, backlog: 0 },
    recommendation: '保持当前配置，关注夜间全量窗口。',
    timeline: [
      { time: '2026-07-27 10:15', event: '增量任务启动', severity: 'INFO' },
      { time: '2026-07-27 10:18', event: '校验通过', severity: 'INFO' },
      { time: '2026-07-27 10:20', event: '目标端提交', severity: 'INFO' },
    ],
  },
  'link-002': {
    linkId: 'link-002',
    metrics: { throughput: 95, latencyMs: 1800, successRate: 94.2, backlog: 2200 },
    recommendation: '建议提升并发或开启批写，目标端水位偏高。',
    timeline: [
      { time: '2026-07-27 10:50', event: '消费位点跳变', severity: 'WARN' },
      { time: '2026-07-27 10:55', event: '积压上升', severity: 'WARN' },
      { time: '2026-07-27 11:05', event: '水位告警', severity: 'ERROR' },
    ],
  },
  'link-003': {
    linkId: 'link-003',
    metrics: { throughput: 0, latencyMs: 0, successRate: 0, backlog: 450 },
    recommendation: 'OSS 写入限流已被触发，建议降低并发或申请配额。',
    timeline: [
      { time: '2026-07-27 09:30', event: 'OSS 拒绝写入', severity: 'ERROR' },
      { time: '2026-07-27 09:35', event: '任务暂停', severity: 'WARN' },
      { time: '2026-07-27 09:48', event: '手动重试未恢复', severity: 'ERROR' },
    ],
  },
};

export const readMockSyncLinks = (): SyncLinkRecord[] => {
  if (typeof window === 'undefined') return seed;
  const cached = window.localStorage.getItem(STORAGE_KEY);
  if (!cached) {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(seed));
    return [...seed];
  }
  try {
    return JSON.parse(cached) as SyncLinkRecord[];
  } catch {
    return [...seed];
  }
};

export const writeMockSyncLinks = (records: SyncLinkRecord[]) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(records));
  }
};

export const resetMockSyncLinks = () => {
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(STORAGE_KEY);
  }
};

export const readMockLinkHealth = (
  id: string,
): SyncLinkHealthDetail | undefined => detailSeeds[id];

export const applyLinkFilters = (
  records: SyncLinkRecord[],
  query: { keyword?: string; linkType?: string; health?: string; status?: string },
): SyncLinkRecord[] =>
  records.filter((record) => {
    if (query.keyword && !record.name.toLowerCase().includes(query.keyword.toLowerCase())) {
      return false;
    }
    if (query.linkType && record.linkType !== query.linkType) {
      return false;
    }
    if (query.health && record.health !== query.health) {
      return false;
    }
    if (query.status && record.status !== query.status) {
      return false;
    }
    return true;
  });
