import { FILE_RESOURCE_SOURCE } from '../../../file-ingest/types';
import { normalizeWorkflowGraph } from '../../../file-ingest/config/runtime';

export const defaultTargetType = {
  dbType: 'MINIO',
  connectorType: 'S3File',
  pluginName: 'S3File',
};

const MANAGED_SOURCE_MODES = ['FILE_RESOURCE', 'WEB_UPLOAD'];

/**
 * The canvas owns the endpoints once the graph exists, so the draft-level selection has to follow
 * the graph. Without this the next hydration would reset the canvas to the selection the wizard
 * made before the user changed it, silently reverting the change.
 */
export const endpointSelectionFromGraph = (workflow: any) => {
  const nodes = Array.isArray(workflow?.nodes) ? workflow.nodes : [];
  const sourceConfig = nodes.find((node: any) => node?.data?.nodeType === 'source')?.data?.config;
  const sinkConfig = nodes.find((node: any) => node?.data?.nodeType === 'sink')?.data?.config;

  if (!sourceConfig?.dbType || !sinkConfig?.dbType) {
    return null;
  }

  const managedSource = MANAGED_SOURCE_MODES.includes(
    String(sourceConfig.sourceMode || '').toUpperCase(),
  );

  return {
    sourceType: managedSource
      ? FILE_RESOURCE_SOURCE
      : {
          dbType: sourceConfig.dbType,
          connectorType: sourceConfig.connectorType,
          pluginName: sourceConfig.pluginName,
        },
    sourceDataSourceId: managedSource ? undefined : sourceConfig.dataSourceId,
    targetType: {
      dbType: sinkConfig.dbType,
      connectorType: sinkConfig.connectorType,
      pluginName: sinkConfig.pluginName,
    },
    targetDataSourceId: sinkConfig.dataSourceId,
  };
};

const sameText = (left: any, right: any) => String(left ?? '') === String(right ?? '');

const sameType = (left: any, right: any) =>
  sameText(left?.dbType, right?.dbType) &&
  sameText(left?.connectorType, right?.connectorType) &&
  sameText(left?.pluginName, right?.pluginName);

export const sameEndpointSelection = (page: any, selection: any) =>
  sameType(page?.sourceType, selection?.sourceType) &&
  sameText(page?.sourceDataSourceId, selection?.sourceDataSourceId) &&
  sameType(page?.targetType, selection?.targetType) &&
  sameText(page?.targetDataSourceId, selection?.targetDataSourceId);

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
