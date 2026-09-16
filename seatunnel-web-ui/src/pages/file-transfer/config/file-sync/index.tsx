import { history, useLocation, useParams } from '@umijs/max';
import { App, Empty, Spin } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import FileWorkflow from '@/pages/batch-link-up/config/file-sync/FileWorkflow';
import { fileResourceApi, fileTransferTaskApi } from '../../../file-ingest/api';
import FileTransferSourceCard from '../../FileTransferSourceCard';
import { FILE_RESOURCE_SOURCE, fileTaskDraftKey } from '../../../file-ingest/types';
import {
  defaultScheduleConfig,
  getSourceConfig,
  mergeEnvConfig,
  mergeScheduleConfig,
  normalizeWorkflowGraph,
  patchSourceConfig,
} from '../../../file-ingest/config/runtime';
import '../../index.less';

const defaultTargetType = {
  dbType: 'FTP',
  connectorType: 'FtpFile',
  pluginName: 'FtpFile',
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

const buildPageParams = (data: any, id: string, scene: 'create' | 'edit') => {
  const sourceType = scene === 'create' ? FILE_RESOURCE_SOURCE : sourceTypeFromData(data);
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

const FileTransferConfigPage: React.FC = () => {
  const { message } = App.useApp();
  const { id } = useParams<{ id: string }>();
  const location = useLocation();
  const [params, setParams] = useState<any>();
  const [loading, setLoading] = useState(false);
  const [basicConfig, setBasicConfig] = useState<any>({
    jobName: '',
    jobDesc: '',
    clientId: '',
    mode: 'FILE_SYNC',
    taskType: 'FILE_TRANSFER',
    sourceType: 'FILE_RESOURCE',
    targetType: 'FTP',
  });
  const [scheduleConfig, setScheduleConfig] = useState(defaultScheduleConfig);
  const [envConfig, setEnvConfig] = useState(mergeEnvConfig());

  const scene = useMemo<'create' | 'edit'>(() => {
    const value = new URLSearchParams(location.search).get('scene');
    return value === 'edit' ? 'edit' : 'create';
  }, [location.search]);

  useEffect(() => {
    if (!id) return;
    const draftKey = fileTaskDraftKey('FILE_TRANSFER', id);

    const loadCreate = () => {
      const rawDraft = sessionStorage.getItem(draftKey);
      if (!rawDraft) {
        setParams(undefined);
        return;
      }
      try {
        const data = JSON.parse(rawDraft);
        const nextParams = buildPageParams(data, id, 'create');
        setParams(nextParams);
        setBasicConfig({
          jobName: data?.jobName || '',
          jobDesc: data?.jobDesc || '',
          clientId: data?.clientId || '',
          mode: 'FILE_SYNC',
          taskType: 'FILE_TRANSFER',
          sourceType: nextParams.sourceType?.dbType,
          targetType: nextParams.targetType?.dbType || 'FTP',
        });
        setScheduleConfig(mergeScheduleConfig(data?.schedule));
        setEnvConfig(mergeEnvConfig(data?.env));
      } catch {
        message.error('读取文件传输草稿失败');
        setParams(undefined);
      }
    };

    const loadEdit = async () => {
      setLoading(true);
      try {
        const response = await fileTransferTaskApi.selectEditDetail(id);
        if (response?.code !== 0 || !response?.data) {
          throw new Error(response?.message || response?.msg || '获取任务编辑详情失败');
        }
        const data = response.data;
        const nextParams = buildPageParams(data, id, 'edit');
        setParams(nextParams);
        setBasicConfig({
          jobName: data?.basic?.jobName || '',
          jobDesc: data?.basic?.jobDesc || '',
          clientId: data?.basic?.clientId || '',
          mode: 'FILE_SYNC',
          taskType: 'FILE_TRANSFER',
          sourceType: nextParams.sourceType?.dbType,
          targetType: nextParams.targetType?.dbType || 'FTP',
        });
        setScheduleConfig(mergeScheduleConfig(data?.schedule));
        setEnvConfig(mergeEnvConfig(data?.env));
      } catch (error: any) {
        message.error(error?.message || '获取任务编辑详情失败');
        setParams(undefined);
      } finally {
        setLoading(false);
      }
    };

    if (scene === 'edit') void loadEdit();
    else loadCreate();
  }, [id, scene]);

  useEffect(() => {
    if (!id || scene !== 'create' || !params) return;
    sessionStorage.setItem(fileTaskDraftKey('FILE_TRANSFER', id), JSON.stringify(params));
  }, [id, params, scene]);

  const sourceConfig = useMemo(() => getSourceConfig(params), [params]);

  const updateSourceConfig = useCallback(
    (patch: Record<string, any>) => {
      setParams((previous: any) => {
        if (!previous) return previous;
        return {
          ...previous,
          sourceType: FILE_RESOURCE_SOURCE,
          workflow: patchSourceConfig(previous, patch),
        };
      });
      setBasicConfig((previous: any) => ({ ...previous, sourceType: 'FILE_RESOURCE' }));
    },
    [],
  );

  useEffect(() => {
    const resourceId = new URLSearchParams(location.search).get('fileResourceId');
    if (!resourceId || String(sourceConfig?.fileResourceId || '') === resourceId) return;

    let active = true;
    fileResourceApi
      .get(resourceId)
      .then((response) => {
        if (!active || response?.code !== 0 || !response?.data) return;
        const resource = response.data;
        updateSourceConfig({
          fileResourceId: String(resource.id || resourceId),
          fileResource: resource,
          objectKey: resource.objectKey || resource.path,
          path: resource.path || resource.objectKey,
        });
      })
      .catch(() => message.error('加载返回的文件资源失败'));
    return () => {
      active = false;
    };
  }, [location.search, sourceConfig?.fileResourceId, updateSourceConfig]);

  const openResourceManager = () => {
    if (!id || !params) return;
    sessionStorage.setItem(fileTaskDraftKey('FILE_TRANSFER', id), JSON.stringify(params));
    const returnTo = `${window.location.pathname}${window.location.search}`;
    const resourcePath = String(sourceConfig?.path || sourceConfig?.fileResource?.path || '').trim();
    const pathSegments = resourcePath.split('/').filter(Boolean);
    const managerPath = pathSegments.length > 1 ? `/${pathSegments.slice(0, -1).join('/')}` : '/';
    const query = new URLSearchParams({
      select: '1',
      path: managerPath,
      returnTo,
    });
    history.push(`/sync/file-resources?${query.toString()}`);
  };

  const targetType = params?.targetType || defaultTargetType;
  const sourceType = params?.sourceType || FILE_RESOURCE_SOURCE;
  const workflowContextKey = ['FILE_TRANSFER', id || 'unknown', params?.state?.editorSyncState || 'UNPUBLISHED'].join('-');

  const goBack = () => {
    if (scene === 'edit') {
      history.push('/sync/file-transfer');
      return;
    }
    history.push(`/sync/file-transfer/${id}/detail`);
  };

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50">
        <Spin />
      </div>
    );
  }

  if (!params) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50">
        <Empty description="未找到文件传输配置，请从任务列表重新进入" />
      </div>
    );
  }

  return (
    <div className="file-task-editor-shell min-h-screen bg-slate-50">
      <div className="file-task-source-toolbar mx-4 pt-4">
        <div className="mx-auto max-w-[1540px]">
          <FileTransferSourceCard
            sourceConfig={sourceConfig}
            onChange={updateSourceConfig}
            onOpenManager={openResourceManager}
          />
          {String(sourceType?.dbType || '').toUpperCase() !== 'FILE_RESOURCE' ? (
            <div className="mt-3 rounded-xl border border-slate-200 bg-white px-4 py-3 text-xs leading-5 text-slate-500 shadow-sm">
              当前为 {sourceType?.dbType || '远端'} 文件来源。请在下方画布来源节点配置远端目录或对象前缀；此页面不会显示结构化文件解析选项。
            </div>
          ) : null}
        </div>
      </div>

      <div className="file-task-workflow-shell mt-3">
        <FileWorkflow
          pageScene={scene}
          contextKey={workflowContextKey}
          params={params}
          goBack={goBack}
          basicConfig={basicConfig}
          setBasicConfig={setBasicConfig}
          scheduleConfig={scheduleConfig}
          setScheduleConfig={setScheduleConfig}
          envConfig={envConfig}
          setEnvConfig={setEnvConfig}
          setParams={setParams}
        />
      </div>
    </div>
  );
};

export default FileTransferConfigPage;
