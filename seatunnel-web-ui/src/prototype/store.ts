import type { PrototypePageMeta, PrototypeRecord } from './types';

const STORAGE_PREFIX = 'seatunnel-prototype:';

const seedNames: Record<PrototypePageMeta['prototypeKind'], string[]> = {
  forms: ['装备台账采集表', '边缘节点巡检表', '基础数据补录表'],
  reports: ['数据采报周报', '装备数据清查报告', '采集质量月报'],
  discovery: ['装备主数据', '边缘遥测库', '任务运行仓'],
  'cloud-edge': ['华北节点镜像同步', '离线缓存续传', '边缘状态回传'],
  'edge-access': ['Modbus 设备接入', 'MQTT 遥测接入', 'SFTP 文件接入'],
  links: ['装备库到基础湖', '遥测 Kafka 到实时湖', '文件区到归档湖'],
  topology: ['装备主数据拓扑', '遥测实时链路', '采报入湖链路'],
  diagnostics: ['CDC 位点超时', '目标端写入限流', '边缘节点离线'],
  'lake-resources': ['基础数据湖', '实时主题湖', '文件归档区'],
  lifecycle: ['暂存数据 7 天清理', '主数据长期保留', '日志数据 90 天归档'],
  'logical-access': ['装备主数据逻辑视图', '跨域遥测查询', '采集报告数据集'],
  reused: ['复用页面能力'],
};

const createSeedRecords = (meta: PrototypePageMeta): PrototypeRecord[] =>
  seedNames[meta.prototypeKind].map((name, index) => ({
    id: `${meta.id}-${index + 1}`,
    name,
    type:
      meta.prototypeKind === 'edge-access'
        ? ['Modbus TCP', 'MQTT', 'SFTP'][index]
        : meta.secondMenu.replace('管理', ''),
    status: ['运行中', '待发布', '已完成'][index],
    owner: ['张工', '李工', '王工'][index],
    updatedAt: `2026-07-${String(27 - index).padStart(2, '0')} ${10 + index}:2${index}`,
    description: `${meta.description}（演示数据 ${index + 1}）`,
    progress: [86, 45, 100][index],
  }));

const keyFor = (pageId: string) => `${STORAGE_PREFIX}${pageId}`;

export const readPrototypeRecords = (
  meta: PrototypePageMeta,
): PrototypeRecord[] => {
  if (typeof window === 'undefined') return createSeedRecords(meta);
  const cached = window.localStorage.getItem(keyFor(meta.id));
  if (!cached) {
    const initial = createSeedRecords(meta);
    window.localStorage.setItem(keyFor(meta.id), JSON.stringify(initial));
    return initial;
  }
  try {
    return JSON.parse(cached) as PrototypeRecord[];
  } catch {
    return createSeedRecords(meta);
  }
};

export const writePrototypeRecords = (
  meta: PrototypePageMeta,
  records: PrototypeRecord[],
) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(keyFor(meta.id), JSON.stringify(records));
  }
};

export const resetPrototypeData = () => {
  if (typeof window === 'undefined') return;
  Object.keys(window.localStorage)
    .filter((key) => key.startsWith(STORAGE_PREFIX))
    .forEach((key) => window.localStorage.removeItem(key));
};
