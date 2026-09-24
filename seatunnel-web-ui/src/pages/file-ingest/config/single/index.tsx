import { history, useLocation, useParams } from '@umijs/max';
import { App, Empty, Spin } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import Workflow from '@/pages/batch-link-up/workflow';
import { FILE_RESOURCE_SOURCE, fileTaskDraftKey } from '../../types';
import { fileIngestTaskApi } from '../../api';
import {
  defaultScheduleConfig,
  mergeEnvConfig,
  mergeScheduleConfig,
  normalizeWorkflowGraph,
} from '../runtime';
import '../../index.less';

const defaultTargetType = {
  dbType: 'MYSQL',
  connectorType: 'Jdbc',
  pluginName: 'JDBC-MYSQL',
};

const buildPageParams = (data: any, id: string, scene: 'create' | 'edit') => {
  const targetType = data?.targetType || data?.workflow?.targetType || defaultTargetType;
  const workflow = normalizeWorkflowGraph(
    data?.workflow,
    'FILE_INGEST',
    FILE_RESOURCE_SOURCE,
    targetType,
    data?.sourceDataSourceId,
    data?.targetDataSourceId,
  );

  return {
    ...data,
    id,
    taskType: 'FILE_INGEST',
    mode: 'GUIDE_SINGLE',
    runtimeType: 'BATCH',
    sourceType: FILE_RESOURCE_SOURCE,
    targetType,
    workflow,
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

const FileIngestConfigPage: React.FC = () => {
  const { message } = App.useApp();
  const { id } = useParams<{ id: string }>();
  const location = useLocation();
  const [params, setParams] = useState<any>();
  const [loading, setLoading] = useState(false);
  const [basicConfig, setBasicConfig] = useState<any>({
    jobName: '',
    jobDesc: '',
    clientId: '',
    mode: 'GUIDE_SINGLE',
    taskType: 'FILE_INGEST',
    sourceType: 'FILE_RESOURCE',
    targetType: 'MYSQL',
  });
  const [scheduleConfig, setScheduleConfig] = useState(defaultScheduleConfig);
  const [envConfig, setEnvConfig] = useState(mergeEnvConfig());

  const scene = useMemo<'create' | 'edit'>(() => {
    const value = new URLSearchParams(location.search).get('scene');
    return value === 'edit' ? 'edit' : 'create';
  }, [location.search]);

  useEffect(() => {
    if (!id) return;
    const draftKey = fileTaskDraftKey('FILE_INGEST', id);

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
          mode: 'GUIDE_SINGLE',
          taskType: 'FILE_INGEST',
          sourceType: 'FILE_RESOURCE',
          targetType: nextParams.targetType?.dbType || 'MYSQL',
        });
        setScheduleConfig(mergeScheduleConfig(data?.schedule));
        setEnvConfig(mergeEnvConfig(data?.env));
      } catch {
        message.error('读取离线文件导入草稿失败');
        setParams(undefined);
      }
    };

    const loadEdit = async () => {
      setLoading(true);
      try {
        const response = await fileIngestTaskApi.selectEditDetail(id);
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
          mode: 'GUIDE_SINGLE',
          taskType: 'FILE_INGEST',
          sourceType: 'FILE_RESOURCE',
          targetType: nextParams.targetType?.dbType || 'MYSQL',
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
    sessionStorage.setItem(fileTaskDraftKey('FILE_INGEST', id), JSON.stringify(params));
  }, [id, params, scene]);

  const targetType = params?.targetType || defaultTargetType;
  const workflowContextKey = [
    'FILE_INGEST',
    id || 'unknown',
    params?.state?.editorSyncState || 'UNPUBLISHED',
  ].join('-');

  const goBack = () => {
    if (scene === 'edit') {
      history.push('/sync/file-ingest');
      return;
    }
    history.push(`/sync/file-ingest/${id}/detail`);
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
        <Empty description="未找到离线文件导入配置，请从任务列表重新进入" />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[#ffffff]">
      <Workflow
        pageScene={scene}
        contextKey={workflowContextKey}
        params={params}
        goBack={goBack}
        sourceType={FILE_RESOURCE_SOURCE}
        setSourceType={() => undefined}
        targetType={targetType}
        setTargetType={(value) => {
          setParams((previous: any) => ({ ...previous, targetType: value }));
          setBasicConfig((previous: any) => ({ ...previous, targetType: value?.dbType }));
        }}
        basicConfig={basicConfig}
        setBasicConfig={setBasicConfig}
        scheduleConfig={scheduleConfig}
        setScheduleConfig={setScheduleConfig}
        setParams={setParams}
        envConfig={envConfig}
        setEnvConfig={setEnvConfig}
        showRun={false}
      />
    </div>
  );
};

export default FileIngestConfigPage;
