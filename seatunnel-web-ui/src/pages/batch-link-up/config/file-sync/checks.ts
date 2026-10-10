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
    const sourceMode = String(config.sourceMode || '').toUpperCase();
    if (sourceMode === 'WEB_UPLOAD') {
      return null;
    }
    if (sourceMode === 'FILE_RESOURCE') {
      if (!String(config.fileResourceId || '').trim()) {
        return buildError(node, 'fileResourceId', '请选择湖文件区中的文件');
      }
      return null;
    }
    if (!config.dataSourceId) {
      return buildError(node, 'dataSourceId', '请选择来源数据源');
    }
    return null;
  },
  (node) => {
    const config = getConfig(node);
    const sourceMode = String(config.sourceMode || '').toUpperCase();
    if (sourceMode === 'WEB_UPLOAD') {
      const assets = Array.isArray(config.uploadedAssets)
        ? config.uploadedAssets
        : Array.isArray(config.uploadedFiles)
          ? config.uploadedFiles
          : [];
      if (!String(config.uploadSessionId || '').trim()) {
        return buildError(node, 'uploadSessionId', '请先打开上传区域并上传文件或文件夹');
      }
      if (!assets.length) {
        return buildError(node, 'uploadedAssets', '请至少上传一个文件');
      }
      return null;
    }
    if (sourceMode === 'FILE_RESOURCE') {
      return null;
    }
    if (!String(config.path || '').trim()) {
      return buildError(node, 'path', '请选择来源同步目录');
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

export const generateFileSyncCheckList = (
  nodes: any[],
  engineVersion?: string,
): CheckItem[] => {
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

  const source = (nodes || []).find((node) => node?.data?.nodeType === 'source');
  const sink = (nodes || []).find((node) => node?.data?.nodeType === 'sink');
  const sourceConfig = getConfig(source);
  const sinkConfig = getConfig(sink);
  if (String(sourceConfig.syncType || 'FULL').toUpperCase() === 'INCREMENTAL' && source) {
    const sourceType = String(sourceConfig.dbType || '').toUpperCase();
    const sinkType = String(sinkConfig.dbType || '').toUpperCase();
    const sourceIsObjectStorage = sourceType === 'S3' || sourceType === 'MINIO';
    const sinkIsObjectStorage = sinkType === 'S3' || sinkType === 'MINIO';
    const sourceMode = String(sourceConfig.sourceMode || '').toUpperCase();

    if (sourceMode === 'WEB_UPLOAD' || sourceMode === 'FILE_RESOURCE') {
      result.push(buildError(source, 'syncType', '本地文件和湖文件来源只支持全量复制'));
    } else if (sourceIsObjectStorage || sinkIsObjectStorage) {
      if (!sourceIsObjectStorage || !sinkIsObjectStorage || sourceType !== sinkType) {
        result.push(buildError(source, 'syncType', 'S3File 增量 update 要求来源和去向使用同一 S3 / MinIO 数据源'));
      } else if (engineVersion !== '3.0.0') {
        // 版本未知（拿不到客户端版本）与版本不满足是两种问题，提示不能混为一谈。
        result.push(
          buildError(
            source,
            'syncType',
            engineVersion
              ? 'S3File 增量 update 需要 SeaTunnel Engine 3.0.0'
              : '无法确认 SeaTunnel Engine 版本，S3File 增量 update 需要 3.0.0',
          ),
        );
      }
    } else if (sourceType && sinkType && sourceType !== sinkType) {
      result.push(buildError(source, 'syncType', '增量模式要求来源与去向使用相同文件协议'));
    }

    if (
      sourceConfig.dataSourceId &&
      sinkConfig.dataSourceId &&
      String(sourceConfig.dataSourceId) !== String(sinkConfig.dataSourceId)
    ) {
      result.push(buildError(source, 'syncType', '增量模式要求来源与去向使用同一数据源'));
    }
  }

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
