import { Divider, message, Modal } from 'antd';
import moment from 'moment';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { history } from 'umi';

import RunLogDrawer from '../batch-link-up/components/SyncTaskList/components/RunLogDrawer';
import {
  seatunnelCheckpointApi,
  seatunnelStreamingJobExecuteApi,
  seatunnelStremJobDefinitionApi,
} from './api';
import BottomActionBar from './components/BottomActionBar';
import RealtimeCheckpointModal from './components/RealtimeCheckpointModal';
import RealtimeHeader from './components/RealtimeHeader';
import RealtimeTaskTable from './components/RealtimeTaskTable';
import RealtimeTaskViewModal from './components/RealtimeTaskViewModal';
import SearchToolbar from './components/SearchToolbar';
import TaskViewModal from './components/TaskViewModal';
import BatchCreateJobModal, {
  BatchCreateValues,
} from '@/pages/common/components/BatchCreateJobModal';
import './index.less';

const REALTIME_DETAIL_CACHE_PREFIX = 'stream-link-up-detail';
const DATE_TIME_FORMAT = 'YYYY-MM-DD HH:mm:ss';

const getDefaultTimeRange = () => [moment().subtract(4, 'days'), moment().add(1, 'days')];

interface StreamingJobDefinitionVO {
  id: string | number;
  jobName?: string;
  jobDesc?: string;
  mode?: string;
  jobType?: string;
  clientId?: string | number;
  jobVersion?: number;
  releaseState?: 'ONLINE' | 'OFFLINE' | string | number;
  lastJobStatus?: string;
  instanceId?: string | number;
  engineJobId?: string | number;
  sourceType?: string;
  sinkType?: string;
  sourceTable?: string;
  sinkTable?: string;
  sourceDatasourceId?: string | number;
  sinkDatasourceId?: string | number;
  createTime?: string;
  updateTime?: string;
  savepointPath?: string;
  checkpointPath?: string;
  lastErrorMessage?: string;
  checkpointConfig?: string;
}

interface SearchValues {
  jobName?: string;
  id?: string | number;
  status?: string;
  sourceType?: string;
  sinkType?: string;
  sourceTable?: string;
  sinkTable?: string;
  createTime?: any[];
}

interface PaginationState {
  current: number;
  pageSize: number;
  total: number;
}

const searchParamKeys: Array<Exclude<keyof SearchValues, 'createTime'>> = [
  'jobName',
  'id',
  'status',
  'sourceType',
  'sinkType',
  'sourceTable',
  'sinkTable',
];

const isSuccessResponse = (res: any): boolean => {
  if (Array.isArray(res)) {
    return res.every(isSuccessResponse);
  }

  return res?.code === undefined || res.code === 0;
};

const getErrorMessage = (res: any, fallback: string) => {
  const failedRes = Array.isArray(res) ? res.find((item) => !isSuccessResponse(item)) : res;

  return failedRes?.message || failedRes?.msg || fallback;
};

const buildQueryParams = (searchValues: SearchValues, pagination: PaginationState) => {
  const params: any = {
    pageNo: pagination.current,
    pageSize: pagination.pageSize,
  };

  searchParamKeys.forEach((key) => {
    const value = searchValues?.[key];
    const nextValue = typeof value === 'string' ? value.trim() : value;

    if (nextValue) {
      params[key] = nextValue;
    }
  });

  if (searchValues?.createTime?.length === 2) {
    params.createTimeStart = moment(searchValues.createTime[0]).format(DATE_TIME_FORMAT);
    params.createTimeEnd = moment(searchValues.createTime[1]).format(DATE_TIME_FORMAT);
  }

  return params;
};

const parseSearchParamsFromUrl = (): SearchValues => {
  const params = new URLSearchParams(window.location.search);
  const createTimeStart = params.get('createTimeStart');
  const createTimeEnd = params.get('createTimeEnd');

  return {
    jobName: params.get('jobName') || undefined,
    id: params.get('id') || undefined,
    status: params.get('status') || undefined,
    sourceType: params.get('sourceType') || undefined,
    sinkType: params.get('sinkType') || undefined,
    sourceTable: params.get('sourceTable') || undefined,
    sinkTable: params.get('sinkTable') || undefined,
    createTime:
      createTimeStart && createTimeEnd
        ? [moment(createTimeStart, DATE_TIME_FORMAT), moment(createTimeEnd, DATE_TIME_FORMAT)]
        : getDefaultTimeRange(),
  };
};

const parsePaginationFromUrl = (): PaginationState => {
  const params = new URLSearchParams(window.location.search);
  const current = Number(params.get('current') || 1);
  const pageSize = Number(params.get('pageSize') || 10);

  return {
    current: Number.isNaN(current) || current <= 0 ? 1 : current,
    pageSize: Number.isNaN(pageSize) || pageSize <= 0 ? 10 : pageSize,
    total: 0,
  };
};

const syncUrlParams = (params: SearchValues, pageInfo: { current: number; pageSize: number }) => {
  const query = new URLSearchParams();

  searchParamKeys.forEach((key) => {
    const value = params?.[key];

    if (value) {
      query.set(key, String(value));
    }
  });

  if (params?.createTime?.length === 2) {
    query.set('createTimeStart', moment(params.createTime[0]).format(DATE_TIME_FORMAT));
    query.set('createTimeEnd', moment(params.createTime[1]).format(DATE_TIME_FORMAT));
  }

  query.set('current', String(pageInfo.current || 1));
  query.set('pageSize', String(pageInfo.pageSize || 10));

  history.replace({
    search: `?${query.toString()}`,
  });
};

const getPageRecords = (res: any) => {
  const payload = res?.data;

  return {
    records: payload?.bizData || [],
    total: Number(payload?.pagination?.total || 0),
  };
};

const isReleaseOnline = (releaseState?: string | number) => releaseState === 'ONLINE' || releaseState === 1;

const isRunningStatus = (status?: string) => String(status || '').toUpperCase() === 'RUNNING';

const RealtimeSyncPage: React.FC = () => {
  const [sourceType, setSourceType] = useState<any>({
    dbType: 'MYSQL',
    connectorType: 'MySQL-CDC',
    pluginName: 'MySQL-CDC',
  });

  const [sinkType, setSinkType] = useState<any>({
    dbType: 'MYSQL',
    connectorType: 'Jdbc',
    pluginName: 'JDBC-MYSQL',
  });

  const ref = useRef<any>(null);
  const refDetail = useRef<any>(null);
  const latestListRequestRef = useRef(0);

  const [searchValues, setSearchValues] = useState<SearchValues>(() => parseSearchParamsFromUrl());
  const [pagination, setPagination] = useState<PaginationState>(() => parsePaginationFromUrl());
  const [dataSource, setDataSource] = useState<StreamingJobDefinitionVO[]>([]);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [logRecord, setLogRecord] = useState<StreamingJobDefinitionVO | null>(null);

  const [loading, setLoading] = useState(false);
  const [creating, setCreating] = useState(false);
  const [batchCreateOpen, setBatchCreateOpen] = useState(false);
  const [batchCreateLoading, setBatchCreateLoading] = useState(false);
  const [logOpen, setLogOpen] = useState(false);

  const [checkpointOpen, setCheckpointOpen] = useState(false);
  const [checkpointRecord, setCheckpointRecord] = useState<StreamingJobDefinitionVO | null>(null);
  const [checkpointOverview, setCheckpointOverview] = useState<any>(null);
  const [checkpointHistory, setCheckpointHistory] = useState<any[]>([]);
  const [checkpointLoading, setCheckpointLoading] = useState(false);

  const hasSelected = selectedRowKeys.length > 0;

  const getSelectedRecords = useCallback(() => {
    const selectedKeySet = new Set(selectedRowKeys.map(String));
    return dataSource.filter((record) => selectedKeySet.has(String(record.id)));
  }, [dataSource, selectedRowKeys]);

  const queryParams = useMemo(
    () => buildQueryParams(searchValues, pagination),
    [pagination.current, pagination.pageSize, searchValues],
  );

  const loadData = useCallback(async () => {
    const requestId = latestListRequestRef.current + 1;
    latestListRequestRef.current = requestId;

    try {
      setLoading(true);

      const res = await seatunnelStremJobDefinitionApi.page(queryParams);

      if (latestListRequestRef.current !== requestId) {
        return;
      }

      if (!isSuccessResponse(res)) {
        setDataSource([]);
        setPagination((prev) => ({ ...prev, total: 0 }));
        return;
      }

      const { records, total: nextTotal } = getPageRecords(res);

      setDataSource(records);
      setPagination((prev) => ({ ...prev, total: nextTotal }));
    } catch (error) {
      if (latestListRequestRef.current === requestId) {
        setDataSource([]);
        setPagination((prev) => ({ ...prev, total: 0 }));
      }
    } finally {
      if (latestListRequestRef.current === requestId) {
        setLoading(false);
      }
    }
  }, [queryParams]);

  const runJobAction = useCallback(
    async (
      action: () => Promise<any>,
      messages: { success: string; error: string },
      refresh = true,
    ) => {
      try {
        const res = await action();

        if (!isSuccessResponse(res)) {
          message.error(getErrorMessage(res, messages.error));
          return false;
        }

        message.success(messages.success);
        if (refresh) {
          loadData();
        }
        return true;
      } catch (error) {
        message.error(messages.error);
        return false;
      }
    },
    [loadData],
  );

  useEffect(() => {
    syncUrlParams(searchValues, {
      current: pagination.current,
      pageSize: pagination.pageSize,
    });
  }, [searchValues, pagination.current, pagination.pageSize]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const handleCreate = async () => {
    if (!sourceType?.dbType) {
      message.warning('请选择来源类型');
      return;
    }

    if (!sinkType?.dbType) {
      message.warning('请选择去向类型');
      return;
    }

    try {
      setCreating(true);

      const data = await seatunnelStremJobDefinitionApi.getUniqueId();

      if (!isSuccessResponse(data)) {
        message.error(getErrorMessage(data, '获取实时任务定义 ID 失败'));
        return;
      }

      const returnId = data?.data;

      if (!returnId) {
        message.error('创建实时任务失败：未获取到任务定义 ID');
        return;
      }

      sessionStorage.setItem(
        `${REALTIME_DETAIL_CACHE_PREFIX}-${returnId}`,
        JSON.stringify({
          id: returnId,
          sourceType,
          targetType: sinkType,
        }),
      );

      history.push(`/sync/stream-link-up/${returnId}/detail`);
    } catch (error) {
      message.error('创建实时任务失败');
    } finally {
      setCreating(false);
    }
  };

  const handleSearch = (values: SearchValues) => {
    setSearchValues(values || {});
    setPagination((prev) => ({ ...prev, current: 1 }));
    setSelectedRowKeys([]);
  };

  const handleReset = () => {
    setSearchValues({
      createTime: getDefaultTimeRange(),
    });
    setPagination((prev) => ({ ...prev, current: 1 }));
    setSelectedRowKeys([]);
  };

  const handlePaginationChange = (page: number, pageSize: number) => {
    setPagination((prev) => ({ ...prev, current: page, pageSize }));
    setSelectedRowKeys([]);
  };

  const handleView = (record: StreamingJobDefinitionVO) => {
    ref.current?.onOpen(true, record, () => {});
  };

  const handleDetail = (record: StreamingJobDefinitionVO) => {
    refDetail.current?.onOpen(true, record, () => {});
  };

  const handleEdit = (item: StreamingJobDefinitionVO) => {
    if (!item?.id) {
      message.warning('任务定义 ID 不能为空');
      return;
    }

    const configPathMap: Record<string, string> = {
      GUIDE_SINGLE: 'single',
      GUIDE_MULTI: 'multi',
      SCRIPT: 'script',
    };
    const configPath = item.mode ? configPathMap[item.mode] : undefined;

    if (configPath) {
      history.push(`/sync/stream-link-up/${item.id}/config/${configPath}?scene=edit`);
    }
  };

  const handleRun = async (record: StreamingJobDefinitionVO) => {
    if (!record?.id) {
      message.warning('任务定义 ID 不存在');
      return;
    }

    if (!isReleaseOnline(record.releaseState)) {
      message.warning('请先上线任务，再执行启动操作');
      return;
    }

    await runJobAction(() => seatunnelStreamingJobExecuteApi.execute(record.id), {
      success: '实时任务已启动',
      error: '启动实时任务失败',
    });
  };

  const handleStop = async (record: StreamingJobDefinitionVO) => {
    if (!record?.instanceId) {
      message.warning('当前任务没有运行实例');
      return;
    }

    await runJobAction(() => seatunnelStreamingJobExecuteApi.pause(record.instanceId), {
      success: '实时任务已终止',
      error: '终止实时任务失败',
    });
  };

  const handleOnline = async (record: StreamingJobDefinitionVO) => {
    if (!record?.id) {
      message.warning('任务定义 ID 不存在');
      return;
    }

    await runJobAction(() => seatunnelStremJobDefinitionApi.online(record.id), {
      success: '实时任务已上线',
      error: '上线实时任务失败',
    });
  };

  const handleOffline = async (record: StreamingJobDefinitionVO) => {
    if (!record?.id) {
      message.warning('任务定义 ID 不存在');
      return;
    }

    await runJobAction(() => seatunnelStremJobDefinitionApi.offline(record.id), {
      success: '实时任务已下线',
      error: '下线实时任务失败',
    });
  };

  const handleDelete = (record: StreamingJobDefinitionVO) => {
    if (!record?.id) {
      message.warning('任务定义 ID 不存在');
      return;
    }

    Modal.confirm({
      title: '确认删除实时任务？',
      centered: true,
      content: (
        <span>
          删除后不可恢复：
          <span className="font-medium text-orange-500">{record.jobName || record.id}</span>
        </span>
      ),
      okText: '删除',
      cancelText: '取消',
      okButtonProps: {
        danger: true,
        size: 'small',
      },
      cancelButtonProps: {
        size: 'small',
      },
      async onOk() {
        const shouldTurnPage = dataSource.length === 1 && pagination.current > 1;
        const success = await runJobAction(
          () => seatunnelStremJobDefinitionApi.delete(String(record.id)),
          {
            success: '实时任务已删除',
            error: '删除实时任务失败',
          },
          !shouldTurnPage,
        );

        if (!success) {
          return;
        }

        setSelectedRowKeys((prev) => prev.filter((key) => key !== record.id));

        if (shouldTurnPage) {
          setPagination((prev) => ({ ...prev, current: prev.current - 1 }));
        }
      },
    });
  };

  const handleLog = (record: StreamingJobDefinitionVO) => {
    if (!record?.instanceId) {
      message.warning('当前任务没有运行实例，暂无日志');
      return;
    }

    setLogRecord(record);
    setLogOpen(true);
  };

  const loadCheckpointData = async (record: StreamingJobDefinitionVO) => {
    if (!record?.clientId) {
      message.warning('当前任务没有绑定 SeaTunnel Client');
      return;
    }

    if (!record?.engineJobId) {
      message.warning('当前任务没有 Engine Job ID，暂无检查点数据');
      setCheckpointOverview(null);
      setCheckpointHistory([]);
      return;
    }

    const clientId = record.clientId;
    const engineJobId = String(record.engineJobId);

    try {
      setCheckpointLoading(true);

      const [overviewRes, historyRes] = await Promise.all([
        seatunnelCheckpointApi.overview(clientId, engineJobId),
        seatunnelCheckpointApi.history(clientId, engineJobId, {
          limit: 20,
        }),
      ]);

      if (!isSuccessResponse(overviewRes)) {
        message.error(getErrorMessage(overviewRes, '获取检查点概览失败'));
        setCheckpointOverview(null);
      } else {
        setCheckpointOverview(overviewRes?.data || null);
      }

      if (!isSuccessResponse(historyRes)) {
        message.error(getErrorMessage(historyRes, '获取检查点历史失败'));
        setCheckpointHistory([]);
      } else {
        setCheckpointHistory(historyRes?.data || []);
      }
    } catch (error) {
      message.error('获取检查点数据失败');
      setCheckpointOverview(null);
      setCheckpointHistory([]);
    } finally {
      setCheckpointLoading(false);
    }
  };

  const handleCheckpoint = async (record: StreamingJobDefinitionVO) => {
    if (!record?.clientId) {
      message.warning('当前任务没有绑定 SeaTunnel Client');
      return;
    }

    if (!record?.engineJobId) {
      message.warning('当前任务没有 Engine Job ID，暂无检查点数据');
      return;
    }

    setCheckpointRecord(record);
    setCheckpointOpen(true);
    setCheckpointOverview(null);
    setCheckpointHistory([]);

    await loadCheckpointData(record);
  };

  const getSelectedJobLabels = (records: StreamingJobDefinitionVO[]) =>
    records
      .slice(0, 3)
      .map((record) => `${record.jobName || '-'}(${record.id})`)
      .join('、') + (records.length > 3 ? ` 等 ${records.length} 个任务` : '');

  const runBatchOperations = async (
    records: StreamingJobDefinitionVO[],
    operation: (record: StreamingJobDefinitionVO) => Promise<any>,
    successLabel: string,
    errorLabel: string,
  ) => {
    const responses = await Promise.allSettled(records.map(operation));
    const successCount = responses.filter(
      (item) => item.status === 'fulfilled' && isSuccessResponse(item.value),
    ).length;
    const failedCount = responses.length - successCount;

    if (successCount > 0) {
      message.success(`${successLabel}：成功 ${successCount} 个`);
      await loadData();
    }
    if (failedCount > 0) {
      message.error(`${errorLabel}：失败 ${failedCount} 个`);
    }

    setSelectedRowKeys([]);
    return successCount > 0 && failedCount === 0;
  };

  const getBatchActionState = () => {
    const selectedRecords = getSelectedRecords();
    if (selectedRecords.length === 0) {
      return {
        onlineDisabled: true,
        offlineDisabled: true,
        startDisabled: true,
        terminateDisabled: true,
        pauseDisabled: true,
        resumeDisabled: true,
        onlineTooltip: '请先选择任务',
        offlineTooltip: '请先选择任务',
        startTooltip: '请先选择任务',
        terminateTooltip: '请先选择任务',
        pauseTooltip: '请先选择任务',
        resumeTooltip: '请先选择任务',
      };
    }

    const onlineRecords = selectedRecords.filter((record) => isReleaseOnline(record.releaseState));
    const offlineRecords = selectedRecords.filter((record) => !isReleaseOnline(record.releaseState));
    const runningRecords = selectedRecords.filter((record) => isRunningStatus(record.lastJobStatus));
    const notRunningRecords = selectedRecords.filter((record) => !isRunningStatus(record.lastJobStatus));
    const recordsWithoutInstance = selectedRecords.filter((record) => !record.instanceId);
    const recordsWithoutSavepoint = selectedRecords.filter((record) => !record.savepointPath);

    return {
      onlineDisabled: offlineRecords.length === 0,
      offlineDisabled: onlineRecords.length === 0 || runningRecords.length > 0,
      startDisabled: offlineRecords.length > 0 || runningRecords.length > 0,
      terminateDisabled: notRunningRecords.length > 0 || recordsWithoutInstance.length > 0,
      pauseDisabled: notRunningRecords.length > 0 || recordsWithoutInstance.length > 0,
      resumeDisabled:
        offlineRecords.length > 0 || runningRecords.length > 0 || recordsWithoutInstance.length > 0 || recordsWithoutSavepoint.length > 0,
      onlineTooltip: offlineRecords.length === 0 ? '所选任务已经全部上线' : undefined,
      offlineTooltip:
        runningRecords.length > 0
          ? `存在运行中的任务，请先终止后再下线：${getSelectedJobLabels(runningRecords)}`
          : onlineRecords.length === 0
            ? '所选任务已经全部下线'
            : undefined,
      startTooltip:
        offlineRecords.length > 0
          ? `存在未上线任务，请先上线后再启动：${getSelectedJobLabels(offlineRecords)}`
          : runningRecords.length > 0
            ? `存在运行中的任务：${getSelectedJobLabels(runningRecords)}`
            : undefined,
      terminateTooltip:
        notRunningRecords.length > 0 || recordsWithoutInstance.length > 0
          ? '批量终止仅支持全部为运行中且存在运行实例的任务'
          : undefined,
      pauseTooltip:
        notRunningRecords.length > 0 || recordsWithoutInstance.length > 0
          ? '批量暂停仅支持全部为运行中且存在运行实例的任务'
          : undefined,
      resumeTooltip:
        offlineRecords.length > 0 || runningRecords.length > 0 || recordsWithoutInstance.length > 0 || recordsWithoutSavepoint.length > 0
          ? '批量恢复要求任务已上线、未运行且都有可用检查点'
          : undefined,
    };
  };

  const batchActionState = getBatchActionState();

  const handleBatchOnline = async () => {
    const records = getSelectedRecords().filter((record) => !isReleaseOnline(record.releaseState));
    if (records.length === 0) {
      message.warning('所选任务已经全部上线');
      return;
    }
    await runBatchOperations(
      records,
      (record) => seatunnelStremJobDefinitionApi.online(record.id),
      '批量上线完成',
      '批量上线失败',
    );
  };

  const handleBatchOffline = async () => {
    const selectedRecords = getSelectedRecords();
    const runningRecords = selectedRecords.filter((record) => isRunningStatus(record.lastJobStatus));
    if (runningRecords.length > 0) {
      message.warning(`存在运行中的任务，请先终止后再下线：${getSelectedJobLabels(runningRecords)}`);
      return;
    }

    const records = selectedRecords.filter((record) => isReleaseOnline(record.releaseState));
    if (records.length === 0) {
      message.warning('所选任务已经全部下线');
      return;
    }
    await runBatchOperations(
      records,
      (record) => seatunnelStremJobDefinitionApi.offline(record.id),
      '批量下线完成',
      '批量下线失败',
    );
  };

  const handleBatchStart = async () => {
    const selectedRecords = getSelectedRecords();
    const offlineRecords = selectedRecords.filter((record) => !isReleaseOnline(record.releaseState));
    const runningRecords = selectedRecords.filter((record) => isRunningStatus(record.lastJobStatus));
    if (selectedRecords.length === 0) {
      message.warning('请先选择要启动的任务');
      return;
    }
    if (offlineRecords.length > 0 || runningRecords.length > 0) {
      message.warning('批量启动要求任务全部已上线且未运行');
      return;
    }
    await runBatchOperations(
      selectedRecords,
      (record) => seatunnelStreamingJobExecuteApi.execute(record.id),
      '批量启动完成',
      '批量启动失败',
    );
  };

  const handleBatchTerminate = async () => {
    const selectedRecords = getSelectedRecords();
    const invalidRecords = selectedRecords.filter(
      (record) => !isRunningStatus(record.lastJobStatus) || !record.instanceId,
    );
    if (selectedRecords.length === 0) {
      message.warning('请先选择要终止的任务');
      return;
    }
    if (invalidRecords.length > 0) {
      message.warning('批量终止要求任务全部处于运行中且存在运行实例');
      return;
    }
    await runBatchOperations(
      selectedRecords,
      (record) => seatunnelStreamingJobExecuteApi.pause(record.instanceId),
      '批量终止完成',
      '批量终止失败',
    );
  };

  const handleBatchPause = async () => {
    const selectedRecords = getSelectedRecords();
    const invalidRecords = selectedRecords.filter(
      (record) => !isRunningStatus(record.lastJobStatus) || !record.instanceId,
    );
    if (selectedRecords.length === 0) {
      message.warning('请先选择要暂停的任务');
      return;
    }
    if (invalidRecords.length > 0) {
      message.warning('批量暂停要求任务全部处于运行中且存在运行实例');
      return;
    }
    await runBatchOperations(
      selectedRecords,
      (record) => seatunnelStreamingJobExecuteApi.stopWithSavepoint(record.instanceId),
      '批量暂停并保存检查点完成',
      '批量暂停失败',
    );
  };

  const handleBatchResume = async () => {
    const selectedRecords = getSelectedRecords();
    const invalidRecords = selectedRecords.filter(
      (record) =>
        !isReleaseOnline(record.releaseState) ||
        isRunningStatus(record.lastJobStatus) ||
        !record.instanceId ||
        !record.savepointPath,
    );
    if (selectedRecords.length === 0) {
      message.warning('请先选择要恢复的任务');
      return;
    }
    if (invalidRecords.length > 0) {
      message.warning('批量恢复要求任务已上线、未运行且都有可用检查点');
      return;
    }
    await runBatchOperations(
      selectedRecords,
      (record) => seatunnelStreamingJobExecuteApi.resumeFromSavepoint(record.instanceId),
      '批量恢复完成',
      '批量恢复失败',
    );
  };

  const openBatchCreate = () => {
    if (!hasSelected) {
      message.warning('请先选择一个或多个模板任务');
      return;
    }
    setBatchCreateOpen(true);
  };

  const handleBatchCreate = async (values: BatchCreateValues) => {
    const templates = getSelectedRecords();
    if (templates.length === 0) {
      message.warning('请先选择一个或多个模板任务');
      return;
    }

    try {
      setBatchCreateLoading(true);
      const response: any = await seatunnelStremJobDefinitionApi.batchCreate({
        templateJobDefinitionIds: templates.map((record) => record.id),
        copiesPerTemplate: values.copiesPerTemplate,
        jobNamePrefix: values.jobNamePrefix,
      });
      if (!isSuccessResponse(response)) {
        message.error(getErrorMessage(response, '批量创建失败'));
        return;
      }

      message.success(`批量创建完成：成功创建 ${response?.data?.createdCount || 0} 个任务`);
      setBatchCreateOpen(false);
      setSelectedRowKeys([]);
      loadData();
    } catch (error) {
      message.error(getErrorMessage(error, '批量创建失败'));
    } finally {
      setBatchCreateLoading(false);
    }
  };

  const handleStopWithSavepoint = async (record: StreamingJobDefinitionVO) => {
    if (!record?.instanceId) {
      message.warning('当前任务没有运行实例');
      return;
    }

    Modal.confirm({
      title: '停止并保存检查点？',
      centered: true,
      content: (
        <div className="leading-6">
          该操作会先保存当前实时任务状态，然后停止运行实例。
          <br />
          后续可以基于该检查点继续恢复运行。
        </div>
      ),
      okText: '确认保存并停止',
      cancelText: '取消',
      okButtonProps: {
        danger: true,
        size: 'small',
      },
      cancelButtonProps: {
        size: 'small',
      },
      async onOk() {
        await runJobAction(() => seatunnelStreamingJobExecuteApi.stopWithSavepoint(record.instanceId), {
          success: '已停止任务，并保存检查点',
          error: '停止并保存检查点失败',
        });
      },
    });
  };

  const handleResumeFromSavepoint = async (record: StreamingJobDefinitionVO) => {
    if (!record?.instanceId) {
      message.warning('当前任务没有可恢复的历史实例');
      return;
    }

    if (!isReleaseOnline(record.releaseState)) {
      message.warning('请先上线任务，再从检查点恢复');
      return;
    }

    if (record.lastJobStatus === 'RUNNING') {
      message.warning('任务正在运行中，不能重复恢复');
      return;
    }

    if (!record.savepointPath) {
      message.warning('当前任务没有可恢复的检查点，请先使用“停止并保存检查点”');
      return;
    }

    Modal.confirm({
      title: '从检查点恢复实时任务？',
      centered: true,
      content: (
        <div className="leading-6">
          系统会基于最近一次保存的检查点创建新的运行实例。
          <br />
          适用于 MySQL CDC、实时同步等需要断点续跑的任务。
        </div>
      ),
      okText: '确认恢复',
      cancelText: '取消',
      okButtonProps: {
        size: 'small',
      },
      cancelButtonProps: {
        size: 'small',
      },
      async onOk() {
        await runJobAction(() => seatunnelStreamingJobExecuteApi.resumeFromSavepoint(record.instanceId), {
          success: '已从检查点恢复实时任务',
          error: '从检查点恢复失败',
        });
      },
    });
  };

  return (
    <>
      <div className="stream-link-page min-h-screen pb-24 pt-5">
        <RealtimeHeader
          sourceType={sourceType}
          sinkType={sinkType}
          onSourceChange={setSourceType}
          onSinkChange={setSinkType}
          onCreate={handleCreate}
          creating={creating}
        />

        <SearchToolbar initialValues={searchValues} onSearch={handleSearch} onReset={handleReset} />
        <Divider style={{ margin: "16px 0" }} />

        <RealtimeTaskTable
          loading={loading}
          dataSource={dataSource}
          selectedRowKeys={selectedRowKeys}
          onSelectedRowKeysChange={setSelectedRowKeys}
          pagination={false}
          onView={handleView}
          onDetail={handleDetail}
          onEdit={handleEdit}
          onRun={handleRun}
          onStopWithSavepoint={handleStopWithSavepoint}
          onResumeFromSavepoint={handleResumeFromSavepoint}
          onStop={handleStop}
          onOnline={handleOnline}
          onOffline={handleOffline}
          onDelete={handleDelete}
          onLog={handleLog}
          onCheckpoint={handleCheckpoint}
        />

        <BottomActionBar
          total={pagination.total}
          selectedCount={selectedRowKeys.length}
          disabled={!hasSelected}
          onCreate={openBatchCreate}
          onOnline={handleBatchOnline}
          onOffline={handleBatchOffline}
          onStart={handleBatchStart}
          onTerminate={handleBatchTerminate}
          onPause={handleBatchPause}
          onResume={handleBatchResume}
          onlineDisabled={batchActionState.onlineDisabled}
          offlineDisabled={batchActionState.offlineDisabled}
          startDisabled={batchActionState.startDisabled}
          terminateDisabled={batchActionState.terminateDisabled}
          pauseDisabled={batchActionState.pauseDisabled}
          resumeDisabled={batchActionState.resumeDisabled}
          onlineTooltip={batchActionState.onlineTooltip}
          offlineTooltip={batchActionState.offlineTooltip}
          startTooltip={batchActionState.startTooltip}
          terminateTooltip={batchActionState.terminateTooltip}
          pauseTooltip={batchActionState.pauseTooltip}
          resumeTooltip={batchActionState.resumeTooltip}
          current={pagination.current}
          pageSize={pagination.pageSize}
          onPageChange={handlePaginationChange}
        />
      </div>

      <BatchCreateJobModal
        open={batchCreateOpen}
        loading={batchCreateLoading}
        templates={getSelectedRecords()}
        onCancel={() => setBatchCreateOpen(false)}
        onSubmit={handleBatchCreate}
      />

      <RealtimeTaskViewModal ref={ref} />
      <TaskViewModal ref={refDetail} />

      <RunLogDrawer
        open={logOpen}
        jobMode="STREAMING"
        instanceId={logRecord?.instanceId}
        onClose={() => {
          setLogOpen(false);
          setLogRecord(null);
        }}
        title="运行日志"
        subtitle={logRecord?.jobName ? `任务：${logRecord.jobName}` : '查看任务运行输出'}
      />

      <RealtimeCheckpointModal
        open={checkpointOpen}
        record={checkpointRecord}
        overview={checkpointOverview}
        history={checkpointHistory}
        loading={checkpointLoading}
        onRefresh={() => {
          if (checkpointRecord) {
            loadCheckpointData(checkpointRecord);
          }
        }}
        onClose={() => {
          setCheckpointOpen(false);
          setCheckpointRecord(null);
          setCheckpointOverview(null);
          setCheckpointHistory([]);
        }}
      />
    </>
  );
};

export default RealtimeSyncPage;
