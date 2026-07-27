import type { CollectionReportRecord, ReportPreview } from './types';

const STORAGE_KEY = 'seatunnel-mock:reporting-reports';

const seed: CollectionReportRecord[] = [
  {
    id: 'report-001',
    name: '装备台账采集周报',
    source: 'FORM_RESPONSE',
    format: 'PDF',
    status: 'READY',
    generatedAt: '2026-07-27 09:00',
    owner: '张工',
    relatedForm: '装备台账采集表',
    rowCount: 1280,
    description: '本周装备台账填报汇总，含完成率和异常清单。',
  },
  {
    id: 'report-002',
    name: '遥测引接质量月报',
    source: 'STREAM_TASK',
    format: 'EXCEL',
    status: 'READY',
    generatedAt: '2026-07-26 18:30',
    owner: '李工',
    rowCount: 248,
    description: '实时任务成功率、吞吐、时延和积压。',
  },
  {
    id: 'report-003',
    name: '边缘节点巡检报告',
    source: 'MANUAL',
    format: 'WORD',
    status: 'GENERATING',
    generatedAt: '2026-07-27 11:20',
    owner: '王工',
    rowCount: 0,
    description: '生成中，预计 3 分钟内完成。',
  },
];

const previewSeeds: Record<string, ReportPreview> = {
  'report-001': {
    id: 'report-001',
    sections: [
      {
        title: '总体进度',
        rows: [
          ['计划份数', '42'],
          ['已回收', '40'],
          ['完成率', '95.2%'],
        ],
      },
      {
        title: '异常单位',
        rows: [
          ['单位 A', '未提交'],
          ['单位 B', '字段缺失'],
        ],
      },
    ],
  },
  'report-002': {
    id: 'report-002',
    sections: [
      {
        title: '吞吐与时延',
        rows: [
          ['平均吞吐', '95 MB/s'],
          ['P95 时延', '1.8s'],
        ],
      },
      {
        title: '成功率',
        rows: [
          ['整体成功率', '94.2%'],
          ['告警次数', '6'],
        ],
      },
    ],
  },
};

export const readMockReports = (): CollectionReportRecord[] => {
  if (typeof window === 'undefined') return seed;
  const cached = window.localStorage.getItem(STORAGE_KEY);
  if (!cached) {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(seed));
    return [...seed];
  }
  try {
    return JSON.parse(cached) as CollectionReportRecord[];
  } catch {
    return [...seed];
  }
};

export const writeMockReports = (records: CollectionReportRecord[]) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(records));
  }
};

export const resetMockReports = () => {
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(STORAGE_KEY);
  }
};

export const readMockReportPreview = (id: string): ReportPreview | undefined =>
  previewSeeds[id];

export const filterReports = (
  records: CollectionReportRecord[],
  query: { keyword?: string; format?: string; status?: string },
): CollectionReportRecord[] =>
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
