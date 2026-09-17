import type {
  DataSourceOption,
  DimensionOption,
  IngestionReportRow,
  IngestionTaskType,
  SystemOption,
} from './types';

export const TASK_TYPE_LABEL: Record<IngestionTaskType, string> = {
  BATCH: '批量引接',
  STREAM: '实时引接',
  FILE: '文件引接',
};

export const UNITS: DimensionOption[] = [
  { id: 'u-nsa', name: '核安全局' },
  { id: 'u-research', name: '研究院' },
];

/** 仅保留业务指定的系统，两个单位共用。 */
export const SYSTEMS: SystemOption[] = [
  { id: 's-radiation-online', name: '核辐射在线监测系统' },
  { id: 's-env-radiation', name: '外环境辐射监测系统' },
  { id: 's-position', name: '阵地综合管理系统' },
  { id: 's-accident', name: '核事故现场数据系统' },
];

export const DATA_SOURCES: DataSourceOption[] = [
  { id: 'ds-rad-mysql', name: '辐射监测站-MySQL', systemId: 's-radiation-online' },
  { id: 'ds-rad-kafka', name: '剂量率遥测-Kafka', systemId: 's-radiation-online' },
  { id: 'ds-env-oracle', name: '外环境采样库-Oracle', systemId: 's-env-radiation' },
  { id: 'ds-env-file', name: '环境监测文件目录', systemId: 's-env-radiation' },
  { id: 'ds-pos-pg', name: '阵地业务库-PostgreSQL', systemId: 's-position' },
  { id: 'ds-pos-kafka', name: '阵地告警事件-Kafka', systemId: 's-position' },
  { id: 'ds-acc-mysql', name: '事故现场库-MySQL', systemId: 's-accident' },
  { id: 'ds-acc-file', name: '现场应急数据包', systemId: 's-accident' },
];

const TASK_TYPES: IngestionTaskType[] = ['BATCH', 'STREAM', 'FILE'];

/** Deterministic pseudo-random for stable sample data across reloads. */
function hash(seed: string): number {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i += 1) {
    h ^= seed.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

function pick<T>(seed: string, items: T[]): T {
  return items[hash(seed) % items.length];
}

function ranged(seed: string, min: number, max: number): number {
  return min + (hash(seed) % (max - min + 1));
}

function formatDate(d: Date): string {
  const y = d.getFullYear();
  const m = `${d.getMonth() + 1}`.padStart(2, '0');
  const day = `${d.getDate()}`.padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function buildRows(): IngestionReportRow[] {
  const rows: IngestionReportRow[] = [];
  const today = new Date();
  today.setHours(12, 0, 0, 0);

  for (let offset = 29; offset >= 0; offset -= 1) {
    const day = new Date(today);
    day.setDate(today.getDate() - offset);
    const date = formatDate(day);

    UNITS.forEach((unit) => {
      DATA_SOURCES.forEach((ds, dsIndex) => {
        const system = SYSTEMS.find((s) => s.id === ds.systemId)!;
        const dailySlots = 1 + (hash(`${date}-${unit.id}-${ds.id}`) % 2);

        for (let slot = 0; slot < dailySlots; slot += 1) {
          const seed = `${date}|${unit.id}|${ds.id}|${slot}`;
          const taskType = pick(seed, TASK_TYPES);

          let resolvedType = taskType;
          if (ds.name.includes('文件') || ds.name.includes('数据包')) {
            resolvedType = 'FILE';
          } else if (ds.name.includes('Kafka')) {
            resolvedType = 'STREAM';
          } else if (hash(seed) % 5 === 0) {
            resolvedType = 'BATCH';
          }

          const taskCount = ranged(`${seed}-tc`, 1, 6);
          const successRate = 0.82 + (hash(`${seed}-sr`) % 18) / 100;
          const successCount = Math.max(1, Math.round(taskCount * successRate));
          const recordBase =
            resolvedType === 'STREAM'
              ? ranged(`${seed}-rc`, 80_000, 420_000)
              : resolvedType === 'FILE'
                ? ranged(`${seed}-rc`, 5_000, 60_000)
                : ranged(`${seed}-rc`, 20_000, 180_000);
          const bytesPerRecord =
            resolvedType === 'FILE' ? ranged(`${seed}-bpr`, 800, 4_000) : ranged(`${seed}-bpr`, 120, 800);

          rows.push({
            id: `row-${date}-${unit.id}-${dsIndex}-${slot}`,
            date,
            unitId: unit.id,
            unitName: unit.name,
            systemId: system.id,
            systemName: system.name,
            dataSourceId: ds.id,
            dataSourceName: ds.name,
            taskType: resolvedType,
            taskCount,
            successCount,
            recordCount: recordBase * taskCount,
            byteCount: recordBase * taskCount * bytesPerRecord,
          });
        }
      });
    });
  }

  return rows;
}

export const REPORT_ROWS: IngestionReportRow[] = buildRows();
