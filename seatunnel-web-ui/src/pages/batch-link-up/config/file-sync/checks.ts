import type { CheckItem, NodeCheckGroup } from '../../workflow/hooks/flowCheckEngine';

/**
 * FILE_SYNC 画布校验：文件任务只有来源/去向两个节点，
 * 规则与 GuideSingle 表任务的 flowCheckEngine 分开维护。
 */
const getNodeMeta = (node: any) => ({
  nodeId: node?.id,
  nodeType: node?.data?.nodeType || '',
  componentType: node?.data?.componentType || '',
  title: node?.data?.title,
  dbType: node?.data?.dbType,
});

const buildError = (node: any, field: string, message: string): CheckItem => ({
  ...getNodeMeta(node),
  level: 'error',
  field,
  message,
});

const buildWarning = (node: any, field: string, message: string): CheckItem => ({
  ...getNodeMeta(node),
  level: 'warning',
  field,
  message,
});

const getConfig = (node: any) => node?.data?.config || {};

const sourceRules: ((node: any) => CheckItem | null)[] = [
  (node) => {
    const config = getConfig(node);
    if (!config.dataSourceId) {
      return buildError(node, 'dataSourceId', '请选择来源数据源');
    }
    return null;
  },
  (node) => {
    const config = getConfig(node);
    if (String(config.readMode || 'remote') === 'upload') {
      if (!String(config.path || '').trim()) {
        return buildError(node, 'path', '本地上传模式请先选择上传目录并上传文件');
      }
      return null;
    }
    if (!String(config.path || '').trim()) {
      return buildError(node, 'path', '请选择来源同步目录');
    }
    return null;
  },
  (node) => {
    const config = getConfig(node);
    const dbType = String(config.dbType || '').toUpperCase();
    if (
      String(config.syncType || 'FULL') === 'INCREMENTAL' &&
      (dbType === 'S3' || dbType === 'MINIO' || dbType === 'LOCAL_FILE')
    ) {
      return buildWarning(node, 'syncType', 'SeaTunnel 2.3.13 的该连接器不支持增量 update，请改用全量复制');
    }
    return null;
  },
];

const sinkRules: ((node: any) => CheckItem | null)[] = [
  (node) => {
    const config = getConfig(node);
    if (!config.dataSourceId) {
      return buildError(node, 'dataSourceId', '请选择去向数据源');
    }
    return null;
  },
  (node) => {
    const config = getConfig(node);
    if (!String(config.targetPath || '').trim()) {
      return buildError(node, 'targetPath', '请选择目标目录');
    }
    return null;
  },
];

export const generateFileSyncCheckList = (nodes: any[]): CheckItem[] => {
  const result: CheckItem[] = [];

  (nodes || []).forEach((node) => {
    const nodeType = node?.data?.nodeType;
    const rules = nodeType === 'source' ? sourceRules : nodeType === 'sink' ? sinkRules : null;
    if (!rules) return;

    rules.forEach((rule) => {
      try {
        const item = rule(node);
        if (item) result.push(item);
      } catch (error) {
        result.push({
          ...getNodeMeta(node),
          level: 'error',
          message: '节点校验执行异常',
        });
      }
    });
  });

  return result;
};

export const classifyFileSyncCheckResult = (list: CheckItem[]) => ({
  errors: list.filter((item) => item.level === 'error'),
  warnings: list.filter((item) => item.level === 'warning'),
  total: list.length,
});

export const groupFileSyncCheckListByNode = (list: CheckItem[]): NodeCheckGroup[] => {
  const groups = new Map<string, NodeCheckGroup>();

  list.forEach((item) => {
    const key = String(item.nodeId || item.nodeType || 'unknown');
    if (!groups.has(key)) {
      groups.set(key, {
        nodeId: item.nodeId,
        nodeType: item.nodeType,
        title: item.title,
        dbType: item.dbType,
        componentType: item.componentType,
        items: [],
      });
    }
    groups.get(key)!.items.push(item);
  });

  return Array.from(groups.values());
};
