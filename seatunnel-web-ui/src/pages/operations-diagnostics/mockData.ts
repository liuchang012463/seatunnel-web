import type { FaultEvidence, FaultRecord } from './types';

const STORAGE_KEY = 'seatunnel-mock:operations-diagnostics';

const seed: FaultRecord[] = [
  {
    id: 'fault-001',
    title: 'CDC 位点超时',
    severity: 'ERROR',
    status: 'INVESTIGATING',
    relatedTask: '装备主数据 CDC 引接',
    relatedLink: '/sync/links',
    firstSeen: '2026-07-27 09:12',
    lastSeen: '2026-07-27 10:30',
    owner: '张工',
    classification: '消费位点跳变',
    description: 'CDC 消费者在 09:12 后未提交位点，目标端写入积压。',
  },
  {
    id: 'fault-002',
    title: '目标端写入限流',
    severity: 'WARNING',
    status: 'OPEN',
    relatedTask: '文件归档同步',
    relatedLink: '/sync/batch-link-up',
    firstSeen: '2026-07-27 08:45',
    lastSeen: '2026-07-27 11:00',
    owner: '王工',
    classification: 'OSS 配额限制',
    description: 'OSS 写入被限流触发，写入吞吐下降。',
  },
  {
    id: 'fault-003',
    title: '边缘节点离线',
    severity: 'CRITICAL',
    status: 'MITIGATED',
    relatedTask: '华东节点遥测',
    relatedLink: '/sync/cloud-edge-tasks',
    firstSeen: '2026-07-27 07:30',
    lastSeen: '2026-07-27 09:50',
    owner: '李工',
    classification: '网络中断',
    description: '边缘节点长时间离线，已切换到暂存区缓存。',
  },
];

const evidenceSeeds: Record<string, FaultEvidence> = {
  'fault-001': {
    id: 'fault-001',
    timeline: [
      { time: '2026-07-27 09:12', severity: 'ERROR', event: 'CDC 位点停止提交' },
      { time: '2026-07-27 09:30', severity: 'WARNING', event: '积压水位上升' },
      { time: '2026-07-27 10:30', severity: 'ERROR', event: '目标端提交失败 12 次' },
    ],
    recommendation: '降低并发或重启 CDC 任务，确认源端 binlog 未过期。',
    retryPlan: ['重置消费位点到上一稳定点', '提高 checkpoint 频率', '通知源端 DBA 排查'],
  },
  'fault-002': {
    id: 'fault-002',
    timeline: [
      { time: '2026-07-27 08:45', severity: 'WARNING', event: 'OSS 写入被拒绝' },
      { time: '2026-07-27 09:30', severity: 'WARNING', event: '任务自动降速' },
      { time: '2026-07-27 11:00', severity: 'WARNING', event: '已申请配额扩容' },
    ],
    recommendation: '暂停写入并申请 OSS 配额，临时启用本地缓存。',
    retryPlan: ['申请 OSS 写入配额', '降低并发到 4', '启用本地临时缓存'],
  },
  'fault-003': {
    id: 'fault-003',
    timeline: [
      { time: '2026-07-27 07:30', severity: 'CRITICAL', event: '边缘节点离线' },
      { time: '2026-07-27 08:30', severity: 'WARNING', event: '暂存区开始累积' },
      { time: '2026-07-27 09:50', severity: 'INFO', event: '网络恢复并续传' },
    ],
    recommendation: '保持当前策略，等待节点自动恢复；后续加强网络冗余。',
    retryPlan: ['继续续传暂存数据', '复核边缘节点监控项', '评估增加 4G 备份链路'],
  },
};

export const readMockFaults = (): FaultRecord[] => {
  if (typeof window === 'undefined') return seed;
  const cached = window.localStorage.getItem(STORAGE_KEY);
  if (!cached) {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(seed));
    return [...seed];
  }
  try {
    return JSON.parse(cached) as FaultRecord[];
  } catch {
    return [...seed];
  }
};

export const writeMockFaults = (records: FaultRecord[]) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(records));
  }
};

export const readMockFaultEvidence = (id: string): FaultEvidence | undefined =>
  evidenceSeeds[id];

export const filterFaults = (
  records: FaultRecord[],
  query: { keyword?: string; severity?: string; status?: string },
): FaultRecord[] =>
  records.filter((record) => {
    if (query.keyword && !record.title.toLowerCase().includes(query.keyword.toLowerCase())) {
      return false;
    }
    if (query.severity && record.severity !== query.severity) {
      return false;
    }
    if (query.status && record.status !== query.status) {
      return false;
    }
    return true;
  });
