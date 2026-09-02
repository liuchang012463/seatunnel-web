import { history, useLocation, useParams } from '@umijs/max';
import { Empty, message, Spin } from 'antd';
import { useEffect, useState } from 'react';
import { seatunnelJobDefinitionApi } from '../../api';
import {
  defaultEnvConfig,
  EnvConfig,
  ScheduleConfig,
} from '../../workflow/components/ScheduleConfigContent/types';
import FileWorkflow from './FileWorkflow';

type PageScene = 'create' | 'edit';

type JobDefinitionState = {
  editorSyncState?: 'UNPUBLISHED' | 'SYNCED' | 'DIRTY' | string;
  releaseState?: 'ONLINE' | 'OFFLINE' | string;
  jobVersion?: number | null;
  contentVersion?: number | null;
};

const defaultScheduleConfig: ScheduleConfig = {
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
  minuteValue: {
    intervalMinute: 5,
  },
  hourlyRangeValue: {
    startTime: '00:00',
    intervalHour: 1,
    endTime: '23:59',
  },
  hourlyAppointValue: {
    hours: [0],
    minute: '00',
  },
  dailyValue: {
    time: '00:17',
  },
  weeklyValue: {
    weekdays: ['MON'],
    time: '00:17',
  },
  effectType: 'forever',
  cronExpression: undefined,
};

const defaultBasicConfig = {
  jobName: '',
  jobDesc: '',
  clientId: '',
  mode: 'FILE_SYNC',
  sourceType: 'LOCAL_FILE',
  targetType: 'FTP',
  sourceDataSourceId: '',
  targetDataSourceId: '',
};

const buildInitialScheduleConfig = (rawSchedule?: any): ScheduleConfig => {
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
    dailyValue: {
      ...defaultScheduleConfig.dailyValue,
      ...(schedule?.dailyValue || {}),
    },
    weeklyValue: {
      ...defaultScheduleConfig.weeklyValue,
      ...(schedule?.weeklyValue || {}),
    },
    executionMode,
    cronExpression:
      executionMode === 'MANUAL' ? undefined : schedule?.cronExpression,
  } as ScheduleConfig;
};

const buildPageParamsForCreate = (rawData: any, routeId?: string) => ({
  ...rawData,
  id: rawData?.id || routeId,
  __pageScene: 'create',
  state: {
    editorSyncState: 'UNPUBLISHED',
    releaseState: 'OFFLINE',
    jobVersion: null,
    contentVersion: null,
  } as JobDefinitionState,
});

const buildPageParamsForEdit = (editData?: any) => ({
  id: editData?.id,
  mode: editData?.mode,
  runtimeType: editData?.runtimeType,
  jobName: editData?.basic?.jobName || '',
  jobDesc: editData?.basic?.jobDesc || '',
  clientId: editData?.basic?.clientId || '',
  sourceType: editData?.workflow?.sourceType || null,
  targetType: editData?.workflow?.targetType || null,
  workflow: editData?.workflow || {},
  basic: editData?.basic || {},
  schedule: editData?.schedule || {},
  env: editData?.env || {},
  __pageScene: 'edit',
  state: {
    editorSyncState: 'SYNCED',
    releaseState: editData?.releaseState || 'OFFLINE',
    jobVersion: editData?.jobVersion ?? null,
    contentVersion: editData?.contentVersion ?? null,
  } as JobDefinitionState,
});

const FileSyncWorkflowPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const location = useLocation();

  const [pageScene, setPageScene] = useState<PageScene>('create');
  const [params, setParams] = useState<any>(null);
  const [basicConfig, setBasicConfig] = useState<any>(defaultBasicConfig);
  const [scheduleConfig, setScheduleConfig] = useState<ScheduleConfig>(defaultScheduleConfig);
  const [envConfig, setEnvConfig] = useState<EnvConfig>(defaultEnvConfig);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!id) return;

    const searchParams = new URLSearchParams(location.search);
    const scene = searchParams.get('scene');
    const cacheKey = `batch-link-up-detail-${id}`;

    const applyCreate = (cache: string) => {
      try {
        const data = JSON.parse(cache);
        setPageScene('create');
        setParams(buildPageParamsForCreate(data, id));
        setBasicConfig({
          ...defaultBasicConfig,
          jobName: data?.jobName || '',
          jobDesc: data?.jobDesc || '',
          clientId: data?.clientId ? String(data.clientId) : '',
          mode: 'FILE_SYNC',
          sourceType: data?.sourceType?.dbType || 'LOCAL_FILE',
          targetType: data?.targetType?.dbType || 'FTP',
          sourceDataSourceId: data?.sourceDataSourceId || '',
          targetDataSourceId: data?.targetDataSourceId || '',
        });
        setScheduleConfig(defaultScheduleConfig);
        setEnvConfig({ ...defaultEnvConfig, jobMode: 'BATCH' });
      } catch (error) {
        message.error('读取配置缓存失败，请返回重新配置');
        setParams(null);
      }
    };

    const applyEdit = (data: any) => {
      setParams(buildPageParamsForEdit(data));
      setBasicConfig({
        ...defaultBasicConfig,
        jobName: data?.basic?.jobName || '',
        jobDesc: data?.basic?.jobDesc || '',
        clientId: data?.basic?.clientId ? String(data.basic.clientId) : '',
        mode: 'FILE_SYNC',
        sourceType: data?.workflow?.sourceType || 'SOURCE',
        targetType: data?.workflow?.targetType || 'SINK',
      });
      setScheduleConfig(buildInitialScheduleConfig(data?.schedule));
      setEnvConfig({ ...defaultEnvConfig, ...(data?.env || {}) });
    };

    const initCreate = () => {
      const cache = sessionStorage.getItem(cacheKey);
      if (!cache) {
        setParams(null);
        return;
      }
      applyCreate(cache);
    };

    const initEdit = async () => {
      try {
        setLoading(true);
        setPageScene('edit');

        const res = await seatunnelJobDefinitionApi.selectEditDetail(id);
        if (res?.code !== 0 || !res?.data) {
          message.error(res?.message || res?.msg || '获取编辑详情失败');
          setParams(null);
          return;
        }
        applyEdit(res.data);
      } catch (error) {
        message.error('获取编辑详情失败');
        setParams(null);
      } finally {
        setLoading(false);
      }
    };

    if (scene === 'create') {
      initCreate();
      return;
    }

    if (scene === 'edit') {
      initEdit();
      return;
    }

    const cache = sessionStorage.getItem(cacheKey);
    if (cache) {
      initCreate();
    } else {
      initEdit();
    }
  }, [id, location.search]);

  const goBack = () => {
    const searchParams = new URLSearchParams(location.search);
    const scene = searchParams.get('scene');
    const isFileRoute = location.pathname.startsWith('/sync/file-link-up');
    const listPath = isFileRoute ? '/sync/file-link-up' : '/sync/batch-link-up';
    const detailPath = isFileRoute
      ? `/sync/file-link-up/${id}/detail`
      : `/sync/batch-link-up/${id}/detail`;

    if (scene === 'edit') {
      history.push(listPath);
      return;
    }

    history.push(detailPath);
  };

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-[#F8FAFC]">
        <Spin />
      </div>
    );
  }

  if (!params) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-[#F8FAFC]">
        <Empty description="未找到配置数据，请从任务列表重新进入" />
      </div>
    );
  }

  const actualPageScene = (params?.__pageScene || pageScene) as PageScene;

  const workflowContextKey = [
    actualPageScene,
    params?.id || id || 'unknown',
    params?.state?.editorSyncState ?? 'none',
    params?.state?.jobVersion ?? 'none',
    params?.state?.contentVersion ?? 'none',
  ].join('-');

  return (
    <div className="min-h-screen bg-[#ffffff]">
      <FileWorkflow
        pageScene={actualPageScene}
        contextKey={workflowContextKey}
        params={params}
        goBack={goBack}
        basicConfig={basicConfig}
        setBasicConfig={setBasicConfig}
        scheduleConfig={scheduleConfig}
        setScheduleConfig={setScheduleConfig}
        envConfig={envConfig}
        setEnvConfig={setEnvConfig}
      />
    </div>
  );
};

export default FileSyncWorkflowPage;
