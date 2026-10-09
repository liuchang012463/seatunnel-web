import { history, useLocation, useParams } from '@umijs/max';
import { App, Empty, Spin } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import FileWorkflow from '@/pages/batch-link-up/config/file-sync/FileWorkflow';
import { fileTransferTaskApi } from '../../../file-ingest/api';
import { fileTaskDraftKey } from '../../../file-ingest/types';
import {
  defaultScheduleConfig,
  mergeEnvConfig,
  mergeScheduleConfig,
} from '../../../file-ingest/config/runtime';
import { buildPageParams, defaultTargetType, endpointSelectionFromGraph, sameEndpointSelection } from './pageParams';
import '../../index.less';

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
    targetType: 'MINIO',
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
          targetType: nextParams.targetType?.dbType || 'MINIO',
        });
        setScheduleConfig(mergeScheduleConfig(data?.schedule));
        setEnvConfig(mergeEnvConfig(data?.env));
      } catch {
        message.error('读取文件同步任务草稿失败');
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
          targetType: nextParams.targetType?.dbType || 'MINIO',
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

  // The create scene hydrates the canvas from the draft-level endpoints, so the endpoints have to
  // follow a canvas change as well; otherwise the next hydration reverts it.
  useEffect(() => {
    if (scene !== 'create' || !params) return;

    const selection = endpointSelectionFromGraph(params?.workflow);
    if (!selection || sameEndpointSelection(params, selection)) return;

    setParams((previous: any) => ({ ...previous, ...selection }));
  }, [params, scene]);

  const targetType = params?.targetType || defaultTargetType;
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
        <Empty description="未找到文件同步任务配置，请从任务列表重新进入" />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[#ffffff]">
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
  );
};

export default FileTransferConfigPage;
