import { history, useLocation, useParams } from '@umijs/max';
import { useCallback, useEffect, useState } from 'react';
import TaskConfigStatusPanel from '@/pages/common/components/TaskConfigStatusPanel';
import useTaskConfig from '@/pages/common/hooks/useTaskConfig';
import type { ConfigPageScene } from '@/pages/common/utils/configPageScene';
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
  const { id } = useParams<{ id: string }>();
  const location = useLocation();
  const [params, setParams] = useState<any>();
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

  const scene = new URLSearchParams(location.search).get('scene') === 'edit' ? 'edit' : 'create';
  const initialize = useCallback((data: any, source: ConfigPageScene, taskId: string) => {
    const nextParams = buildPageParams(data, taskId, source);
    const basic = source === 'edit' ? data?.basic : data;
    setParams(nextParams);
    setBasicConfig({
      jobName: basic?.jobName || '',
      jobDesc: basic?.jobDesc || '',
      clientId: basic?.clientId || '',
      mode: 'FILE_SYNC',
      taskType: 'FILE_TRANSFER',
      sourceType: nextParams.sourceType?.dbType,
      targetType: nextParams.targetType?.dbType || 'MINIO',
    });
    setScheduleConfig(mergeScheduleConfig(data?.schedule));
    setEnvConfig(mergeEnvConfig(data?.env));
  }, []);

  const { status, retry } = useTaskConfig({
    id, scene,
    draftKey: fileTaskDraftKey('FILE_TRANSFER', id || ''),
    readSaved: fileTransferTaskApi.selectEditDetail,
    initialize,
  });

  useEffect(() => {
    if (!id || scene !== 'create' || status !== 'ready' || !params) return;
    sessionStorage.setItem(fileTaskDraftKey('FILE_TRANSFER', id), JSON.stringify(params));
  }, [id, params, scene, status]);

  // The create scene hydrates the canvas from the draft-level endpoints, so the endpoints have to
  // follow a canvas change as well; otherwise the next hydration reverts it.
  useEffect(() => {
    if (scene !== 'create' || status !== 'ready' || !params) return;

    const selection = endpointSelectionFromGraph(params?.workflow);
    if (!selection || sameEndpointSelection(params, selection)) return;

    setParams((previous: any) => ({ ...previous, ...selection }));
  }, [params, scene, status]);

  const targetType = params?.targetType || defaultTargetType;
  const workflowContextKey = ['FILE_TRANSFER', id || 'unknown', params?.state?.editorSyncState || 'UNPUBLISHED'].join('-');

  const goBack = () => {
    history.push("/sync/file-transfer");
  };

  if (status !== 'ready') {
    return <TaskConfigStatusPanel status={status} onBack={goBack} onRetry={retry} />;
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
