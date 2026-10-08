import {
  defaultEnvConfig,
  type EnvConfig,
  type ScheduleConfig,
} from '@/pages/batch-link-up/workflow/components/ScheduleConfigContent/types';
import type { FileTaskType } from '../types';

export const defaultScheduleConfig: ScheduleConfig = {
  executionMode: 'MANUAL',
  paramsList: [],
  instanceGenerateMode: 'nextDay',
  scheduleRunType: 'pause',
  timeoutMode: 'system',
  timeoutValue: 1,
  timeoutUnit: 'hour',
  rerunPolicy: 'success_or_fail',
  autoRetry: true,
  retryTimes: 1,
  retryInterval: 1,
  scheduleType: 'day',
  hourMode: 'range',
  minuteValue: { intervalMinute: 5 },
  hourlyRangeValue: { startTime: '00:00', intervalHour: 1, endTime: '23:59' },
  hourlyAppointValue: { hours: [0], minute: '00' },
  dailyValue: { time: '00:17' },
  weeklyValue: { weekdays: ['MON'], time: '00:17' },
  effectType: 'forever',
  cronExpression: undefined,
};

export const mergeScheduleConfig = (rawSchedule?: any): ScheduleConfig => {
  const schedule = rawSchedule || {};
  const executionMode = schedule.executionMode || (schedule.cronExpression ? 'AUTO' : 'MANUAL');
  return {
    ...defaultScheduleConfig,
    ...schedule,
    hourlyRangeValue: {
      ...defaultScheduleConfig.hourlyRangeValue,
      ...(schedule?.hourlyRangeValue || {}),
    },
    hourlyAppointValue: {
      ...defaultScheduleConfig.hourlyAppointValue,
      ...(schedule?.hourlyAppointValue || {}),
    },
    dailyValue: { ...defaultScheduleConfig.dailyValue, ...(schedule?.dailyValue || {}) },
    weeklyValue: { ...defaultScheduleConfig.weeklyValue, ...(schedule?.weeklyValue || {}) },
    executionMode,
    cronExpression: executionMode === 'MANUAL' ? undefined : schedule?.cronExpression,
  } as ScheduleConfig;
};

export const mergeEnvConfig = (rawEnv?: any): EnvConfig => ({
  ...defaultEnvConfig,
  ...(rawEnv || {}),
  jobMode: 'BATCH',
});

const normalizeStructuredFileDefaults = (config: Record<string, any>) => ({
  fileFormatType: config.fileFormatType || 'csv',
  encoding: config.encoding || 'UTF-8',
  fieldDelimiter: config.fieldDelimiter ?? ',',
  csvUseHeaderLine: config.csvUseHeaderLine ?? config.skipHeader !== false,
  skipHeader: config.skipHeader ?? config.csvUseHeaderLine !== false,
  skipHeaderRowNumber: config.skipHeaderRowNumber ?? 0,
  quoteChar: config.quoteChar ?? '"',
  escapeChar: config.escapeChar ?? '',
});

const normalizeResourceSourceConfig = (rawConfig: any, taskType: FileTaskType) => {
  const config = rawConfig || {};
  const legacyUpload = String(config.sourceMode || '').toUpperCase() === 'WEB_UPLOAD';
  const resourceMode = taskType === 'FILE_INGEST' || legacyUpload || String(config.sourceMode || '').toUpperCase() === 'FILE_RESOURCE';

  if (!resourceMode) {
    // File transfer copies the bytes as-is. The S3 connector requires a schema
    // for structured formats such as CSV, which does not apply to this task.
    return taskType === 'FILE_TRANSFER'
      ? { ...config, fileFormatType: 'binary' }
      : config;
  }

  return {
    ...config,
    sourceMode: 'FILE_RESOURCE',
    dbType: 'MINIO',
    pluginName: 'S3File',
    connectorType: 'S3File',
    readMode: taskType === 'FILE_TRANSFER' ? 'resource' : 'file',
    syncType: 'FULL',
    fileResourceId:
      config.fileResourceId ||
      config.resourceId ||
      config.uploadedAssetId ||
      config.uploadedAssets?.[0]?.fileResourceId,
    fileResource: config.fileResource || config.resource,
    ...(taskType === 'FILE_TRANSFER'
      ? {
          fileFormatType: 'binary',
          binaryChunkSize: config.binaryChunkSize || 1048576,
          binaryCompleteFileMode: config.binaryCompleteFileMode ?? false,
        }
      : normalizeStructuredFileDefaults(config)),
  };
};

export const normalizeWorkflowGraph = (
  workflow: any,
  taskType: FileTaskType,
  sourceType: any,
  targetType: any,
  sourceDataSourceId?: string | number,
  targetDataSourceId?: string | number,
  syncEndpointSelection = false,
) => {
  const rawNodes = Array.isArray(workflow?.nodes) ? workflow.nodes : [];
  if (rawNodes.length > 0) {
    return {
      nodes: rawNodes.map((node: any, index: number) => {
        const isSource = node?.data?.nodeType === 'source';
        const syncFileTransferSelection = taskType === 'FILE_TRANSFER' && syncEndpointSelection;
        const selectedSourceIsResource = sourceType?.dbType === 'FILE_RESOURCE';
        const existingConfig = node?.data?.config || {};
        const baseConfig = isSource
          ? normalizeResourceSourceConfig(
              syncFileTransferSelection
                ? selectedSourceIsResource
                  ? {
                      ...existingConfig,
                      sourceMode: 'FILE_RESOURCE',
                      dbType: 'MINIO',
                      pluginName: 'S3File',
                      connectorType: 'S3File',
                      dataSourceId: undefined,
                      readMode: 'resource',
                    }
                  : {
                      ...existingConfig,
                      sourceMode: undefined,
                      dbType: sourceType?.dbType,
                      pluginName: sourceType?.pluginName,
                      connectorType: sourceType?.connectorType,
                      dataSourceId: sourceDataSourceId,
                    }
                : existingConfig,
              taskType,
            )
          : existingConfig;
        const config = isSource
          ? baseConfig.sourceMode === 'FILE_RESOURCE'
            ? { ...baseConfig, pluginOutput: baseConfig.pluginOutput || node?.id }
            : {
                ...baseConfig,
                dataSourceId: syncFileTransferSelection
                  ? sourceDataSourceId
                  : baseConfig.dataSourceId || sourceDataSourceId,
                pluginOutput: baseConfig.pluginOutput || node?.id,
              }
          : {
              ...baseConfig,
              ...(syncFileTransferSelection
                ? {
                    dbType: targetType?.dbType,
                    pluginName: targetType?.pluginName,
                    connectorType: targetType?.connectorType,
                    dataSourceId: targetDataSourceId,
                  }
                : { dataSourceId: baseConfig.dataSourceId || targetDataSourceId }),
              pluginInput: baseConfig.pluginInput || node?.id,
            };
        return {
          ...node,
          type: 'custom',
          position: node?.position || { x: index === 0 ? 80 : 480, y: 160 },
          data: {
            ...node?.data,
            nodeType: node?.data?.nodeType,
            ...(isSource
              ? {
                  title: config.sourceMode === 'FILE_RESOURCE' ? '文件资源' : config.dbType,
                  description: taskType === 'FILE_TRANSFER' ? '读取二进制文件对象' : '读取结构化文件',
                  sourceMode: config.sourceMode,
                  dbType: config.dbType,
                  pluginName: config.pluginName,
                  connectorType: config.connectorType,
                }
              : syncFileTransferSelection
                ? {
                    title: config.dbType || '目标端',
                    description: '写入二进制文件对象',
                    dbType: config.dbType,
                    pluginName: config.pluginName,
                    connectorType: config.connectorType,
                  }
                : {}),
            config,
          },
        };
      }),
      edges: Array.isArray(workflow?.edges) ? workflow.edges : [],
    };
  }

  const sourceId = taskType === 'FILE_TRANSFER' ? 'file-transfer-source' : 'file-ingest-source';
  const sinkId = taskType === 'FILE_TRANSFER' ? 'file-transfer-sink' : 'file-ingest-sink';
  const sourceConfig = normalizeResourceSourceConfig(
    {
      sourceMode: sourceType?.dbType === 'FILE_RESOURCE' ? 'FILE_RESOURCE' : undefined,
      dbType: sourceType?.dbType === 'FILE_RESOURCE' ? 'MINIO' : sourceType?.dbType,
      connectorType: sourceType?.dbType === 'FILE_RESOURCE' ? 'S3File' : sourceType?.connectorType,
      pluginName: sourceType?.dbType === 'FILE_RESOURCE' ? 'S3File' : sourceType?.pluginName,
      dataSourceId: sourceType?.dbType === 'FILE_RESOURCE' ? undefined : sourceDataSourceId,
      readMode: taskType === 'FILE_TRANSFER' ? 'resource' : 'file',
      fileFormatType: taskType === 'FILE_TRANSFER' ? 'binary' : 'csv',
      syncType: 'FULL',
      pluginOutput: sourceId,
    },
    taskType,
  );

  return {
    nodes: [
      {
        id: sourceId,
        type: 'custom',
        position: { x: 80, y: 160 },
        data: {
          nodeType: 'source',
          title: sourceConfig.sourceMode === 'FILE_RESOURCE' ? '文件资源' : sourceConfig.dbType,
          description: taskType === 'FILE_TRANSFER' ? '读取二进制文件对象' : '读取结构化文件',
          sourceMode: sourceConfig.sourceMode,
          dbType: sourceConfig.dbType,
          pluginName: sourceConfig.pluginName,
          connectorType: sourceConfig.connectorType,
          config: sourceConfig,
          meta: { outputSchema: [], schemaStatus: 'idle', schemaError: '' },
        },
      },
      {
        id: sinkId,
        type: 'custom',
        position: { x: 480, y: 160 },
        data: {
          nodeType: 'sink',
          title: targetType?.dbType || '目标端',
          description: taskType === 'FILE_TRANSFER' ? '写入二进制文件对象' : '写入目标表',
          dbType: targetType?.dbType,
          pluginName: targetType?.pluginName,
          connectorType: targetType?.connectorType,
          config:
            taskType === 'FILE_TRANSFER'
              ? {
                  dataSourceId: targetDataSourceId,
                  dbType: targetType?.dbType,
                  pluginName: targetType?.pluginName,
                  connectorType: targetType?.connectorType,
                  targetPath: undefined,
                  pluginInput: sinkId,
                }
              : {
                  dataSourceId: targetDataSourceId,
                  dbType: targetType?.dbType,
                  pluginName: targetType?.pluginName,
                  connectorType: targetType?.connectorType,
                  targetMode: 'table',
                  table: undefined,
                  targetTableName: '',
                  writeMode: 'append',
                  autoCreateTable: false,
                  pluginInput: sinkId,
                },
        },
      },
    ],
    edges: [{ id: `${sourceId}-${sinkId}`, source: sourceId, target: sinkId, type: 'custom', data: {} }],
  };
};

export const getSourceConfig = (params: any) =>
  params?.workflow?.nodes?.find((node: any) => node?.data?.nodeType === 'source')?.data?.config || {};

export const getTargetConfig = (params: any) =>
  params?.workflow?.nodes?.find((node: any) => node?.data?.nodeType === 'sink')?.data?.config || {};

export const patchSourceConfig = (params: any, patch: Record<string, any>) => {
  const workflow = params?.workflow || { nodes: [], edges: [] };
  return {
    ...workflow,
    nodes: (workflow.nodes || []).map((node: any) => {
      if (node?.data?.nodeType !== 'source') return node;
      const config = {
        ...(node?.data?.config || {}),
        ...patch,
        sourceMode: 'FILE_RESOURCE',
        dbType: 'MINIO',
        pluginName: 'S3File',
        connectorType: 'S3File',
      };
      return {
        ...node,
        data: {
          ...node.data,
          title: '文件资源',
          sourceMode: 'FILE_RESOURCE',
          dbType: 'MINIO',
          pluginName: 'S3File',
          connectorType: 'S3File',
          config,
        },
      };
    }),
  };
};
