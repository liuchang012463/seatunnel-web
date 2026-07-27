import type {
  LogicalMappingRecord,
  LogicalPreviewResult,
} from './types';

const STORAGE_KEY = 'seatunnel-mock:lake-logical-access';

const seed: LogicalMappingRecord[] = [
  {
    id: 'logic-001',
    name: '装备主数据逻辑视图',
    pattern: 'VIEW',
    status: 'ACTIVE',
    sources: ['装备主库.装备表', '装备主库.单位表'],
    target: 'logical://equipment_view',
    lastPreviewedAt: '2026-07-27 09:30',
    previewRowCount: 200,
    owner: '张工',
    updatedAt: '2026-07-27 09:30',
    description: '跨源装备主数据逻辑视图，供查询使用。',
  },
  {
    id: 'logic-002',
    name: '跨域遥测查询',
    pattern: 'UNION',
    status: 'ACTIVE',
    sources: ['遥测 Kafka 主题 A', '遥测 Kafka 主题 B'],
    target: 'logical://telemetry_union',
    lastPreviewedAt: '2026-07-27 11:05',
    previewRowCount: 60,
    owner: '李工',
    updatedAt: '2026-07-27 11:05',
    description: '合并多个遥测主题的逻辑视图。',
  },
  {
    id: 'logic-003',
    name: '采集报告数据集',
    pattern: 'JOIN',
    status: 'PAUSED',
    sources: ['采报表单答卷', '采集任务汇总'],
    target: 'logical://report_dataset',
    lastPreviewedAt: '2026-07-25 14:20',
    previewRowCount: 0,
    owner: '王工',
    updatedAt: '2026-07-20 10:00',
    description: '采集报告与任务汇总关联视图，已暂停。',
  },
];

const previewSeeds: Record<string, LogicalPreviewResult> = {
  'logic-001': {
    id: 'logic-001',
    columns: ['装备编号', '装备名称', '单位名称'],
    rows: [
      ['EQ-001', '雷达一号', '装备一所'],
      ['EQ-002', '通信车二号', '装备二所'],
    ],
    message: '返回 200 行受控模拟结果',
  },
  'logic-002': {
    id: 'logic-002',
    columns: ['topic', 'ts', 'value'],
    rows: [
      ['A', '2026-07-27 10:55:00', '12.3'],
      ['B', '2026-07-27 10:55:00', '15.8'],
    ],
    message: '返回 60 行受控模拟结果',
  },
};

export const readMockLogicalMappings = (): LogicalMappingRecord[] => {
  if (typeof window === 'undefined') return seed;
  const cached = window.localStorage.getItem(STORAGE_KEY);
  if (!cached) {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(seed));
    return [...seed];
  }
  try {
    return JSON.parse(cached) as LogicalMappingRecord[];
  } catch {
    return [...seed];
  }
};

export const writeMockLogicalMappings = (records: LogicalMappingRecord[]) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(records));
  }
};

export const readMockLogicalPreview = (
  id: string,
): LogicalPreviewResult | undefined => previewSeeds[id];

export const filterLogicalMappings = (
  records: LogicalMappingRecord[],
  query: { keyword?: string; pattern?: string; status?: string },
): LogicalMappingRecord[] =>
  records.filter((record) => {
    if (query.keyword && !record.name.toLowerCase().includes(query.keyword.toLowerCase())) {
      return false;
    }
    if (query.pattern && record.pattern !== query.pattern) {
      return false;
    }
    if (query.status && record.status !== query.status) {
      return false;
    }
    return true;
  });
