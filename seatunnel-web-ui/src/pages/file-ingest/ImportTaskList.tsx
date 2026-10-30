import { CopyOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { Alert, App, Button, Divider, Empty, Popconfirm, Space, Table, Tag, Tooltip } from 'antd';
import moment from 'moment';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { TASK_TABLE_COLUMN_WIDTHS } from '@/pages/common/components/taskTableLayout';
import AdvancedSearchForm from '@/pages/batch-link-up/components/SyncTaskList/components/AdvancedSearchForm';
import ActionColumn from '@/pages/batch-link-up/components/SyncTaskList/components/ActionColumn';
import DataSourceSyncPlan from '@/pages/batch-link-up/components/SyncTaskList/components/DataSourceSyncPlan';
import ExecutionStatus from '@/pages/batch-link-up/components/SyncTaskList/components/ExecutionStatus';
import ScheduleInfo from '@/pages/batch-link-up/components/SyncTaskList/components/ScheduleInfo';
import TaskStatus from '@/pages/batch-link-up/components/SyncTaskList/components/TaskStatus';
import CustomPagination from '@/pages/batch-link-up/CustomPagination';
import { withTimeout } from '@/utils/withTimeout';
import { TASK_LIST_REQUEST_TIMEOUT } from '@/pages/common/hooks/useTaskListAutoRefresh';
import { fileIngestTaskApi } from './api';
import {
  OFFLINE_IMPORT_TYPE_LABELS,
  offlineImportTargetDbType,
  offlineImportTaskApi,
  type OfflineImportTask,
} from './import/api';
import './index.less';

const API_PAGE_SIZE = 100;

type UnifiedTask = Record<string, any> & {
  taskKind: 'FILE_INGEST' | 'OFFLINE_IMPORT';
};

const OFFLINE_STATUS_TO_JOB_STATUS: Record<string, string> = {
  PENDING: 'UNSTARTED',
  QUEUED: 'RUNNING',
  RUNNING: 'RUNNING',
  SUCCESS: 'FINISHED',
  FAILED: 'FAILED',
};

const OFFLINE_STATUS_FILTERS: Record<string, string[]> = {
  RUNNING: ['RUNNING', 'QUEUED'],
  FINISHED: ['SUCCESS'],
  FAILED: ['FAILED'],
  UNSTARTED: ['PENDING'],
};

const OFFLINE_TYPE_COLORS: Record<string, string> = {
  KINGBASE_DMP: 'geekblue',
  DAMENG_DMP: 'volcano',
  KINGBASE_SQL: 'cyan',
  DAMENG_SQL: 'green',
};

const parsePage = (response: any) => {
  if (response?.code !== undefined && response.code !== 0) {
    throw new Error(response.message || response.msg || '查询任务列表失败');
  }
  const data = response?.data || {};
  const rows = Array.isArray(data?.bizData) ? data.bizData : Array.isArray(data) ? data : [];
  return {
    rows,
    total: Number(data?.pagination?.total ?? data?.total ?? rows.length),
  };
};

const fetchMatchingPages = async (
  requestPage: (pageNo: number, pageSize: number) => Promise<any>,
  requiredCount: number,
) => {
  const first = parsePage(await requestPage(1, API_PAGE_SIZE));
  const pageCount = Math.ceil(Math.min(requiredCount, first.total) / API_PAGE_SIZE);
  const following = await Promise.all(
    Array.from({ length: Math.max(0, pageCount - 1) }, (_, index) => requestPage(index + 2, API_PAGE_SIZE)),
  );
  return {
    rows: [first, ...following.map(parsePage)].flatMap((page) => page.rows),
    total: first.total,
  };
};

const formatTime = (value?: string) => (value ? String(value).replace('T', ' ').slice(0, 19) : '-');

const ImportTaskList: React.FC<{
  taskKind: UnifiedTask['taskKind'];
  onCreate: () => void;
  goDetail: (id: string, item?: any) => void;
}> = ({ taskKind, onCreate, goDetail }) => {
  const { message } = App.useApp();
  const [loading, setLoading] = useState(false);
  const [tasks, setTasks] = useState<UnifiedTask[]>([]);
  const [total, setTotal] = useState(0);
  const [loadError, setLoadError] = useState<string>();
  const [filters, setFilters] = useState<Record<string, any>>({});
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10 });
  const [executingId, setExecutingId] = useState<string>();
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const inFlightRef = useRef(false);
  const requestSeqRef = useRef(0);

  const load = useCallback(async (silent = false) => {
    if (silent && inFlightRef.current) return;
    const seq = ++requestSeqRef.current;
    inFlightRef.current = true;
    if (!silent) setLoading(true);
    const requiredCount = pagination.current * pagination.pageSize;
    const fileParams = {
      jobName: filters.jobName || undefined,
      id: filters.id || undefined,
      status: filters.status || undefined,
      createTimeStart: filters.createTimeStart || undefined,
      createTimeEnd: filters.createTimeEnd || undefined,
      sortField: 'createTime',
      sortOrder: 'desc',
    };
    try {
      let matchingTasks: UnifiedTask[];
      let matchingTotal: number;

      if (taskKind === 'FILE_INGEST') {
        const fileResult = await fetchMatchingPages(
          (pageNo, pageSize) => withTimeout(
            fileIngestTaskApi.page({ ...fileParams, pageNo, pageSize }, { timeout: TASK_LIST_REQUEST_TIMEOUT }),
            TASK_LIST_REQUEST_TIMEOUT,
            '任务列表请求超时，请稍后重试',
          ),
          requiredCount,
        );
        matchingTasks = fileResult.rows.map((item: any) => ({ ...item, taskKind: 'FILE_INGEST' as const }));
        matchingTotal = fileResult.total;
      } else {
        const offlineStatusValues = filters.status
          ? (OFFLINE_STATUS_FILTERS[filters.status] || [])
          : [undefined];
        const offlineResults = await Promise.all(offlineStatusValues.map((status) => fetchMatchingPages(
          (pageNo, pageSize) => withTimeout(
            offlineImportTaskApi.page({
              name: filters.jobName || undefined,
              status,
              createTimeStart: filters.createTimeStart || undefined,
              createTimeEnd: filters.createTimeEnd || undefined,
              pageNo,
              pageSize,
            }, { timeout: TASK_LIST_REQUEST_TIMEOUT }),
            TASK_LIST_REQUEST_TIMEOUT,
            '任务列表请求超时，请稍后重试',
          ),
          requiredCount,
        )));
        matchingTasks = offlineResults
          .flatMap((result) => result.rows)
          .map((item: OfflineImportTask) => ({ ...item, taskKind: 'OFFLINE_IMPORT' as const }));
        matchingTotal = offlineResults.reduce((sum, result) => sum + result.total, 0);
      }

      if (seq !== requestSeqRef.current) return;
      matchingTasks.sort((left, right) => {
        const leftTime = new Date(left.createTime || 0).getTime();
        const rightTime = new Date(right.createTime || 0).getTime();
        return rightTime - leftTime;
      });
      const start = (pagination.current - 1) * pagination.pageSize;
      setTasks(matchingTasks.slice(start, start + pagination.pageSize));
      setTotal(matchingTotal);
      setLoadError(undefined);
    } catch (error: any) {
      if (seq !== requestSeqRef.current) return;
      if (!silent) {
        setLoadError(error?.message || error?.msg || '查询任务列表失败，请稍后重试');
      }
    } finally {
      if (seq === requestSeqRef.current) {
        inFlightRef.current = false;
        if (!silent) setLoading(false);
      }
    }
  }, [filters, pagination.current, pagination.pageSize, taskKind]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadRef = useRef(load);
  useEffect(() => {
    loadRef.current = load;
  }, [load]);

  useEffect(() => {
    const hasActiveTask = tasks.some((task) => task.taskKind === 'OFFLINE_IMPORT'
      ? task.status === 'RUNNING' || task.status === 'QUEUED'
      : ['RUNNING', 'STARTING', 'INITIALIZING', 'CREATED', 'PENDING'].includes(String(task.lastJobStatus || '').toUpperCase()));
    if (!hasActiveTask) {
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = null;
      return;
    }
    if (!pollRef.current) {
      pollRef.current = setInterval(() => void loadRef.current(true), 3000);
    }
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = null;
    };
  }, [tasks]);

  useEffect(() => () => {
    if (pollRef.current) clearInterval(pollRef.current);
  }, []);

  const search = (values: any) => {
    const range = values.createTime;
    setPagination((previous) => ({ ...previous, current: 1 }));
    setFilters({
      jobName: values.jobName?.trim() || undefined,
      id: values.id?.trim() || undefined,
      status: values.status || undefined,
      createTimeStart: range?.[0] ? moment(range[0]).startOf('day').format('YYYY-MM-DD HH:mm:ss') : undefined,
      createTimeEnd: range?.[1] ? moment(range[1]).endOf('day').format('YYYY-MM-DD HH:mm:ss') : undefined,
      createTime: range,
    });
  };

  const reset = () => {
    setPagination((previous) => ({ ...previous, current: 1 }));
    setFilters({});
  };

  const executeOfflineImport = async (task: OfflineImportTask) => {
    setExecutingId(String(task.id));
    try {
      const response = await offlineImportTaskApi.execute(task.id, { skipErrorHandler: true });
      if (response?.code !== 0) throw new Error(response?.message || response?.msg || '执行失败');
      message.success(`任务「${task.name}」已开始执行`);
      void load(true);
    } catch (error: any) {
      message.error(error?.message || '执行失败');
    } finally {
      setExecutingId(undefined);
    }
  };

  const removeOfflineImport = async (task: OfflineImportTask) => {
    try {
      const response = await offlineImportTaskApi.remove(task.id, { skipErrorHandler: true });
      if (response?.code !== 0) throw new Error(response?.message || response?.msg || '删除失败');
      message.success('任务已删除');
      if (tasks.length === 1 && pagination.current > 1) {
        setPagination((previous) => ({ ...previous, current: previous.current - 1 }));
      } else {
        void load();
      }
    } catch (error: any) {
      message.error(error?.message || '删除失败');
    }
  };

  const columns = useMemo(() => [
    {
      title: '任务名称/ID',
      dataIndex: 'jobName',
      width: TASK_TABLE_COLUMN_WIDTHS.name,
      ellipsis: true,
      render: (_: unknown, record: UnifiedTask) => record.taskKind === 'OFFLINE_IMPORT' ? (
        <div className="sync-task-name-cell">
          <a className="sync-task-name-cell__title" onClick={() => history.push(`/sync/file-ingest/import/detail/${record.id}`)}>
            {record.name || '-'}
          </a>
          <div className="sync-task-name-cell__id"><span>{record.id}</span></div>
          <div className="sync-task-name-cell__engine-id" title={record.fileName}>文件：{record.fileName || '-'}</div>
        </div>
      ) : (
        <div className="sync-task-name-cell">
          <div className="sync-task-name-cell__title" title={record?.jobName}>{record?.jobName || '-'}</div>
          <div className="sync-task-name-cell__id">
            <span>{record?.id || '-'}</span>
            <Tooltip title="复制任务定义 ID">
              <button type="button" className="sync-task-copy-btn" aria-label={`复制任务定义 ID ${record?.id ?? ''}`} onClick={(event) => {
                event.stopPropagation();
                navigator.clipboard.writeText(String(record?.id ?? '')).then(() => message.success('任务定义 ID 已复制')).catch(() => message.error('复制失败，请手动复制'));
              }}>
                <CopyOutlined style={{ fontSize: 12 }} />
              </button>
            </Tooltip>
          </div>
          <div className="sync-task-name-cell__engine-id" title={record?.engineJobId ? String(record.engineJobId) : undefined}>
            引擎 Job ID：{record?.engineJobId || '-'}
          </div>
        </div>
      ),
    },
    {
      title: '健康状态',
      dataIndex: 'lastJobStatus',
      width: TASK_TABLE_COLUMN_WIDTHS.status,
      render: (_: unknown, record: UnifiedTask) => (
        <div className="sync-task-status-cell flex w-full justify-center">
          <TaskStatus
            status={record.taskKind === 'OFFLINE_IMPORT'
              ? OFFLINE_STATUS_TO_JOB_STATUS[record.status] || record.status
              : record.lastJobStatus}
            errorMessage={record.taskKind === 'OFFLINE_IMPORT' ? record.errorMessage : record.lastErrorMessage}
          />
        </div>
      ),
    },
    {
      title: '数据源同步方案',
      key: 'syncPlan',
      width: TASK_TABLE_COLUMN_WIDTHS.plan,
      render: (_: unknown, record: UnifiedTask) => record.taskKind === 'OFFLINE_IMPORT' ? (
        <Space direction="vertical" size={4}>
          <Tag color={OFFLINE_TYPE_COLORS[record.importType] || 'default'}>
            {OFFLINE_IMPORT_TYPE_LABELS[record.importType] || record.importType}
          </Tag>
          <DataSourceSyncPlan record={{
            taskType: 'FILE_INGEST',
            mode: 'GUIDE_SINGLE',
            sourceType: 'FILE_RESOURCE',
            sinkType: offlineImportTargetDbType(record.importType),
            sourceDatasourceName: record.fileName,
            sourceTable: record.fileName,
            sinkDatasourceName: record.targetDatasourceName,
            sinkTable: record.targetSchema,
          }} />
        </Space>
      ) : <DataSourceSyncPlan record={record} />,
    },
    {
      title: '执行概况',
      key: 'execution',
      width: TASK_TABLE_COLUMN_WIDTHS.execution,
      render: (_: unknown, record: UnifiedTask) => record.taskKind === 'OFFLINE_IMPORT' ? (
        <div className="task-metric-group">
          <div className="task-metric-group__line">
            <span className="task-metric-group__badge is-manual">手动</span>
            <span className="task-metric"><span className="task-metric__label">进度</span><span className="task-metric__value">
              {String(record.importType || '').endsWith('_SQL')
                ? `${record.executedStatements ?? 0}/${record.totalStatements ?? 0}`
                : record.status === 'SUCCESS' ? '完成' : '-'}
            </span></span>
          </div>
          <div className="task-metric-group__line"><span className="task-metric"><span className="task-metric__label">失败语句</span><span className="task-metric__value">{record.failedStatements ?? 0}</span></span></div>
          <div className="task-metric-group__line"><span className="task-metric"><span className="task-metric__label">耗时</span><span className="task-metric__value">{record.startTime && record.endTime ? `${Math.max(0, Math.round((new Date(record.endTime).getTime() - new Date(record.startTime).getTime()) / 1000))} 秒` : '-'}</span></span></div>
        </div>
      ) : <ExecutionStatus record={record} />,
    },
    {
      title: '链路动态调度',
      key: 'schedule',
      width: TASK_TABLE_COLUMN_WIDTHS.schedule,
      render: (_: unknown, record: UnifiedTask) => record.taskKind === 'OFFLINE_IMPORT'
        ? <ScheduleInfo record={{ executionMode: 'MANUAL', lastScheduleTime: record.endTime || record.startTime }} />
        : <ScheduleInfo record={record} />,
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      width: TASK_TABLE_COLUMN_WIDTHS.createTime,
      ellipsis: true,
      render: (value: string) => <span className="sync-task-time" title={value || undefined}>{formatTime(value)}</span>,
    },
    {
      title: '最近更新时间',
      dataIndex: 'updateTime',
      width: TASK_TABLE_COLUMN_WIDTHS.updateTime,
      ellipsis: true,
      render: (value: string) => <span className="sync-task-time" title={value || undefined}>{formatTime(value)}</span>,
    },
    {
      title: '操作',
      key: 'action',
      width: TASK_TABLE_COLUMN_WIDTHS.action,
      fixed: 'right' as const,
      render: (_: unknown, record: UnifiedTask) => record.taskKind === 'OFFLINE_IMPORT' ? (
        <Space size={2}>
          <Button
            type="link"
            size="small"
            icon={<PlayCircleOutlined />}
            loading={executingId === String(record.id)}
            disabled={executingId === String(record.id) || record.status === 'RUNNING' || record.status === 'QUEUED'}
            onClick={() => void executeOfflineImport(record as OfflineImportTask)}
          >执行</Button>
          <Button type="link" size="small" onClick={() => history.push(`/sync/file-ingest/import/detail/${record.id}`)}>详情</Button>
          <Popconfirm title="确认删除该导入任务？" disabled={record.status === 'RUNNING' || record.status === 'QUEUED'} onConfirm={() => void removeOfflineImport(record as OfflineImportTask)}>
            <Button type="link" size="small" danger disabled={record.status === 'RUNNING' || record.status === 'QUEUED'}>删除</Button>
          </Popconfirm>
        </Space>
      ) : <ActionColumn record={record} cbk={() => void load()} goDetail={goDetail} />,
    },
  ], [executingId, goDetail, load, message]);

  return (
    <div className="batch-link-up-page sync-task-list">
      <div className="config-manage-page">
        <div className="operate-bar task-search-wrap">
          <div className="left">
            <AdvancedSearchForm
              initialValues={filters}
              onSearch={search}
              onReset={reset}
              fileMode
              hideAdvancedFilters
              forceChineseLabels
              showTableFilters={false}
              createAction={(
                <Button type="primary" icon={<PlusOutlined />} onClick={onCreate} className="task-search-create">
                  创建任务
                </Button>
              )}
            />
          </div>
        </div>
        <Divider style={{ margin: '12px 0' }} />
        {loadError ? (
          <Alert
            type="error"
            showIcon
            className="task-list-error"
            message="任务列表加载失败"
            description={loadError}
            action={<Button size="small" icon={<ReloadOutlined />} onClick={() => void load()}>重试</Button>}
          />
        ) : null}
        <div className="task-table-shell">
          <Table
            rowKey={(record) => `${record.taskKind}-${record.id}`}
            columns={columns as any}
            dataSource={tasks}
            loading={loading}
            pagination={false}
            tableLayout="fixed"
            scroll={{ x: '100%' }}
            className="task-table"
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={loadError ? '请重试加载任务列表' : taskKind === 'OFFLINE_IMPORT' ? '暂无 DMP/SQL 导入任务' : '暂无文件导入任务'} /> }}
          />
          {total > 0 ? (
            <div className="task-list-footer">
              <div className="task-pagination">
                <CustomPagination
                  total={total}
                  current={pagination.current}
                  pageSize={pagination.pageSize}
                  onChange={(current, pageSize) => setPagination({ current, pageSize })}
                />
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
};

export default ImportTaskList;
