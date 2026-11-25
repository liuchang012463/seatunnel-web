import { history, useLocation, useParams } from '@umijs/max';
import { useCallback, useEffect, useState } from 'react';
import TaskConfigStatusPanel from '@/pages/common/components/TaskConfigStatusPanel';
import useTaskConfig from '@/pages/common/hooks/useTaskConfig';
import type { ConfigPageScene } from '@/pages/common/utils/configPageScene';
import Workflow from '@/pages/batch-link-up/workflow';
import DatapackCsvImportStep from '../../DatapackCsvImportStep';
import { FILE_RESOURCE_SOURCE, fileTaskDraftKey } from '../../types';
import DatapackMultiConfig from '../DatapackMultiConfig';
import { datapackSourceConfig } from '../datapack';
import { fileIngestTaskApi } from '../../api';
import {
  defaultScheduleConfig,
  mergeEnvConfig,
  mergeScheduleConfig,
  normalizeWorkflowGraph,
  patchSourceConfig,
} from '../runtime';
import '../../index.less';

const defaultTargetType = {
  dbType: 'MYSQL',
  connectorType: 'Jdbc',
  pluginName: 'JDBC-MYSQL',
};

const buildPageParams = (data: any, id: string, scene: 'create' | 'edit') => {
  const sourceType = data?.sourceType || FILE_RESOURCE_SOURCE;
  const targetType = data?.targetType || data?.workflow?.targetType || defaultTargetType;
  const workflow = normalizeWorkflowGraph(
    data?.workflow,
    'FILE_INGEST',
    sourceType,
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
    sourceType,
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
  const { id } = useParams<{ id: string }>();
  const location = useLocation();
  const [params, setParams] = useState<any>();
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

  const scene = new URLSearchParams(location.search).get('scene') === 'edit' ? 'edit' : 'create';
  const initialize = useCallback((data: any, source: ConfigPageScene, taskId: string) => {
    const nextParams = buildPageParams(data, taskId, source);
    const basic = source === 'edit' ? data?.basic : data;
    setParams(nextParams);
    setBasicConfig({
      jobName: basic?.jobName || '',
      jobDesc: basic?.jobDesc || '',
      clientId: basic?.clientId || '',
      mode: 'GUIDE_SINGLE',
      taskType: 'FILE_INGEST',
      sourceType: 'FILE_RESOURCE',
      targetType: nextParams.targetType?.dbType || 'MYSQL',
    });
    setScheduleConfig(mergeScheduleConfig(data?.schedule));
    setEnvConfig(mergeEnvConfig(data?.env));
  }, []);

  const { status, retry } = useTaskConfig({
    id, scene,
    draftKey: fileTaskDraftKey('FILE_INGEST', id || ''),
    readSaved: fileIngestTaskApi.selectEditDetail,
    initialize,
  });

  useEffect(() => {
    if (!id || scene !== 'create' || status !== 'ready' || !params) return;
    sessionStorage.setItem(fileTaskDraftKey('FILE_INGEST', id), JSON.stringify(params));
  }, [id, params, scene, status]);

  const targetType = params?.targetType || defaultTargetType;
  const workflowContextKey = [
    'FILE_INGEST',
    id || 'unknown',
    params?.state?.editorSyncState || 'UNPUBLISHED',
  ].join('-');

  const goBack = () => {
    history.push("/sync/file-ingest");
  };

  if (status !== 'ready') {
    return <TaskConfigStatusPanel status={status} onBack={goBack} onRetry={retry} />;
  }

  if (params.content?.datapack || params.datapackMode === 'multi') {
    return <DatapackMultiConfig key={id} params={params} goBack={goBack} />;
  }

  if (params.datapackCsvImport === true) {
    return (
      <div className="mx-auto w-full max-w-[1080px] p-6">
        <DatapackCsvImportStep
          onBack={goBack}
          onImported={(selection) => {
            const { outputSchema, schemaStatus, schemaError, ...sourceConfig } = datapackSourceConfig(selection);
            setParams((previous: any) => {
              const workflow = patchSourceConfig(previous, sourceConfig);
              return {
                ...previous,
                datapackCsvImport: undefined,
                sourceType: { ...FILE_RESOURCE_SOURCE, fileFormatType: 'csv', formatLocked: true },
                workflow: {
                  ...workflow,
                  nodes: workflow.nodes.map((node: any) => node?.data?.nodeType === 'source'
                    ? {
                        ...node,
                        data: {
                          ...node.data,
                          meta: { ...node.data.meta, outputSchema, schemaStatus, schemaError },
                        },
                      }
                    : node),
                },
              };
            });
          }}
        />
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
        sourceType={params?.sourceType || FILE_RESOURCE_SOURCE}
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
      />
    </div>
  );
};

export default FileIngestConfigPage;
