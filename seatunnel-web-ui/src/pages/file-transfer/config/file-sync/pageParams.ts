import { FILE_RESOURCE_SOURCE } from '../../../file-ingest/types';
import { normalizeWorkflowGraph } from '../../../file-ingest/config/runtime';

export const defaultTargetType = {
  dbType: 'MINIO',
  connectorType: 'S3File',
  pluginName: 'S3File',
};

const sourceTypeFromData = (data: any) => {
  const sourceNode = data?.workflow?.nodes?.find((node: any) => node?.data?.nodeType === 'source');
  const sourceConfig = sourceNode?.data?.config || {};
  const sourceMode = String(sourceConfig?.sourceMode || '').toUpperCase();
  if (sourceMode === 'WEB_UPLOAD' || sourceMode === 'FILE_RESOURCE') return FILE_RESOURCE_SOURCE;
  return data?.workflow?.sourceType || {
    dbType: sourceConfig?.dbType || 'FTP',
    connectorType: sourceConfig?.connectorType || 'FtpFile',
    pluginName: sourceConfig?.pluginName || 'FtpFile',
  };
};

export const buildPageParams = (data: any, id: string, scene: 'create' | 'edit') => {
  const sourceType = scene === 'create'
    ? data?.sourceType || FILE_RESOURCE_SOURCE
    : sourceTypeFromData(data);
  const targetType = data?.targetType || data?.workflow?.targetType || defaultTargetType;
  return {
    ...data,
    id,
    taskType: 'FILE_TRANSFER',
    mode: 'FILE_SYNC',
    runtimeType: 'BATCH',
    sourceType,
    targetType,
    workflow: normalizeWorkflowGraph(
      data?.workflow,
      'FILE_TRANSFER',
      sourceType,
      targetType,
      data?.sourceDataSourceId,
      data?.targetDataSourceId,
      scene === 'create',
    ),
    __pageScene: scene,
    state:
      scene === 'edit'
        ? {
            editorSyncState: 'SYNCED',
            releaseState: data?.releaseState || data?.state?.releaseState || 'OFFLINE',
            jobVersion: data?.jobVersion ?? data?.state?.jobVersion ?? null,
            contentVersion: data?.contentVersion ?? data?.state?.contentVersion ?? null,
          }
        : {
            editorSyncState: 'UNPUBLISHED',
            releaseState: 'OFFLINE',
            jobVersion: null,
            contentVersion: null,
          },
  };
};
