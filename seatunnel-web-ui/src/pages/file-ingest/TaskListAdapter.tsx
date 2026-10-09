import { CopyOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, App, Button, Divider, Empty, Table, Tooltip } from 'antd';
import type { TablePaginationConfig } from 'antd';
import moment from 'moment';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useIntl } from '@umijs/max';
import { fileIngestTaskApi, fileTransferTaskApi } from './api';
import type { FileTaskType } from './types';
import type { TaskSortField, TaskSortOrder } from '@/pages/common/components/TaskSortControls';
import { TASK_TABLE_COLUMN_WIDTHS } from '@/pages/common/components/taskTableLayout';
import AdvancedSearchForm, {
  TaskFilterOption,
} from '@/pages/batch-link-up/components/SyncTaskList/components/AdvancedSearchForm';
import ActionColumn from '@/pages/batch-link-up/components/SyncTaskList/components/ActionColumn';
import DataSourceSyncPlan from '@/pages/batch-link-up/components/SyncTaskList/components/DataSourceSyncPlan';
import ExecutionStatus from '@/pages/batch-link-up/components/SyncTaskList/components/ExecutionStatus';
import ScheduleInfo from '@/pages/batch-link-up/components/SyncTaskList/components/ScheduleInfo';
import TaskStatus from '@/pages/batch-link-up/components/SyncTaskList/components/TaskStatus';
import CustomPagination from '@/pages/batch-link-up/CustomPagination';
import useTaskListAutoRefresh from '@/pages/common/hooks/useTaskListAutoRefresh';
import { withTimeout } from '@/utils/withTimeout';
import '@/pages/batch-link-up/components/SyncTaskList/index.less';

interface FileTaskListProps {
  taskType: FileTaskType;
  mode: 'GUIDE_SINGLE' | 'FILE_SYNC';
  goDetail: (id: string, item?: any) => void;
  emptyDescription: string;
  fileMode?: boolean;
}

const getErrorMessage = (error: any, fallback: string) =>
  error?.response?.data?.message ||
  error?.response?.data?.msg ||
  error?.data?.message ||
  error?.data?.msg ||
  error?.message ||
  fallback;

const FileTaskList: React.FC<FileTaskListProps> = ({
  taskType,
  mode,
  goDetail,
  emptyDescription,
  fileMode = false,
}) => {
  const { message } = App.useApp();
  const intl = useIntl();
  const [taskList, setTaskList] = useState<any[]>([]);
  const [searchParams, setSearchParams] = useState<any>({});
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [sort, setSort] = useState<{ field: TaskSortField; order: TaskSortOrder }>({
    field: 'createTime',
    order: 'desc',
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();

  const taskApi = taskType === 'FILE_TRANSFER' ? fileTransferTaskApi : fileIngestTaskApi;

  const fetchTaskList = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    const requestParams: Record<string, any> = {
      ...searchParams,
      taskType,
      mode,
      pageNo: pagination.current,
      pageSize: pagination.pageSize,
      sortField: sort.field,
      sortOrder: sort.order,
    };

    if (searchParams.createTime?.length === 2) {
      requestParams.createTimeStart = moment(searchParams.createTime[0]).format('YYYY-MM-DD HH:mm:ss');
      requestParams.createTimeEnd = moment(searchParams.createTime[1]).format('YYYY-MM-DD HH:mm:ss');
      delete requestParams.createTime;
    }

    try {
      const response = await withTimeout(taskApi.page(requestParams), 10000, '任务列表请求超时，请稍后重试');
      if (response?.code !== undefined && response.code !== 0) {
        throw new Error(response.message || '查询任务列表失败');
      }
      const data = response?.data || {};
      setTaskList(Array.isArray(data?.bizData) ? data.bizData : Array.isArray(data) ? data : []);
      setPagination((previous) => ({
        ...previous,
        total: Number(data?.pagination?.total || 0),
      }));
    } catch (fetchError: any) {
      // Keep the rows of the last successful query: the list also refreshes itself while a run is
      // active, and blanking it would stop that refresh until the operator retries by hand.
      setError(getErrorMessage(fetchError, '查询任务列表失败，请稍后重试'));
    } finally {
      setLoading(false);
    }
  }, [mode, pagination.current, pagination.pageSize, searchParams, sort.field, sort.order, taskApi, taskType]);

  useEffect(() => {
    void fetchTaskList();
  }, [fetchTaskList]);

  // 存在运行中任务时自动刷新当前页，全部终态后停止。
  useTaskListAutoRefresh(taskList, () => {
    void fetchTaskList();
  });

  const handleSearch = (values: any) => {
    setSearchParams(values);
    setPagination((previous) => ({ ...previous, current: 1 }));
  };

  const handleReset = () => {
    setSearchParams({});
    setSort({ field: 'createTime', order: 'desc' });
    setPagination((previous) => ({ ...previous, current: 1 }));
  };

  const sourceOptions = useMemo<TaskFilterOption[]>(
    () =>
      taskType === 'FILE_INGEST'
        ? [{ label: '湖文件', value: 'FILE_RESOURCE' }]
        : [
            { label: '湖文件', value: 'FILE_RESOURCE' },
            { label: 'FTP', value: 'FTP' },
            { label: 'SFTP', value: 'SFTP' },
            { label: 'S3', value: 'S3' },
            { label: 'MinIO', value: 'MINIO' },
          ],
    [taskType],
  );

  const sinkOptions = useMemo<TaskFilterOption[]>(
    () =>
      taskType === 'FILE_TRANSFER'
        ? [
            { label: 'FTP', value: 'FTP' },
            { label: 'SFTP', value: 'SFTP' },
            { label: 'S3', value: 'S3' },
            { label: 'MinIO', value: 'MINIO' },
          ]
        : [],
    [taskType],
  );

  const copyId = async (id: string | number) => {
    try {
      await navigator.clipboard.writeText(String(id));
      message.success('任务定义 ID 已复制');
    } catch {
      message.error('复制失败，请手动复制');
    }
  };

  const columns = useMemo(
    () => [
      {
        title: intl.formatMessage({
          id: 'pages.job.table.col.name',
          defaultMessage: '链路名称/ID',
        }),
        dataIndex: 'jobName',
        width: TASK_TABLE_COLUMN_WIDTHS.name,
        ellipsis: true,
        sorter: true,
        sortOrder: sort.field === 'name' ? (sort.order === 'asc' ? 'ascend' : 'descend') : undefined,
        render: (_value: unknown, record: any) => (
          <div className="sync-task-name-cell">
            <div className="sync-task-name-cell__title" title={record?.jobName}>
              {record?.jobName || '-'}
            </div>
            <div className="sync-task-name-cell__id">
              <span>{record?.id || '-'}</span>
              <Tooltip title="复制任务定义 ID">
                <button
                  type="button"
                  className="sync-task-copy-btn"
                  aria-label={`复制任务定义 ID ${record?.id ?? ""}`}
                  onClick={(event) => {
                    event.stopPropagation();
                    void copyId(record?.id);
                  }}
                >
                  <CopyOutlined style={{ fontSize: 12 }} />
                </button>
              </Tooltip>
            </div>
          </div>
        ),
      },
      {
        title: intl.formatMessage({
          id: 'pages.job.table.col.status',
          defaultMessage: '健康状态',
        }),
        dataIndex: 'lastJobStatus',
        width: TASK_TABLE_COLUMN_WIDTHS.status,
        render: (_value: unknown, record: any) => (
          <div className="sync-task-status-cell flex w-full justify-center">
            <TaskStatus status={record?.lastJobStatus} errorMessage={record?.lastErrorMessage} />
          </div>
        ),
      },
      {
        title: intl.formatMessage({
          id: 'pages.job.table.col.syncPlan',
          defaultMessage: '数据源同步方案',
        }),
        key: 'syncPlan',
        width: TASK_TABLE_COLUMN_WIDTHS.plan,
        render: (_value: unknown, record: any) => <DataSourceSyncPlan record={record} />,
      },
      {
        title: intl.formatMessage({
          id: 'pages.job.table.col.execution',
          defaultMessage: '执行概况',
        }),
        key: 'execution',
        width: TASK_TABLE_COLUMN_WIDTHS.execution,
        render: (_value: unknown, record: any) => <ExecutionStatus record={record} />,
      },
      {
        title: intl.formatMessage({
          id: 'pages.job.table.col.schedule',
          defaultMessage: '链路动态调度',
        }),
        key: 'schedule',
        width: TASK_TABLE_COLUMN_WIDTHS.schedule,
        render: (_value: unknown, record: any) => <ScheduleInfo record={record} />,
      },
      {
        title: intl.formatMessage({
          id: 'pages.job.table.col.createTime',
          defaultMessage: '创建时间',
        }),
        dataIndex: 'createTime',
        width: TASK_TABLE_COLUMN_WIDTHS.createTime,
        sorter: true,
        sortOrder: sort.field === 'createTime' ? (sort.order === 'asc' ? 'ascend' : 'descend') : undefined,
        render: (value: string) => <span className="sync-task-time">{value || '-'}</span>,
      },
      {
        title: intl.formatMessage({
          id: 'pages.job.table.col.operate',
          defaultMessage: '操作',
        }),
        key: 'action',
        width: TASK_TABLE_COLUMN_WIDTHS.action,
        fixed: 'right',
        render: (_value: unknown, record: any) => (
          <ActionColumn record={record} cbk={() => void fetchTaskList()} goDetail={goDetail} />
        ),
      },
    ],
    [fetchTaskList, goDetail, intl, sort.field, sort.order],
  );

  return (
    <div className="batch-link-up-page sync-task-list">
      <div className="config-manage-page">
        <div className="operate-bar task-search-wrap">
          <div className="left">
            <AdvancedSearchForm
              initialValues={searchParams}
              onSearch={handleSearch}
              onReset={handleReset}
              fileMode={fileMode}
              sourceOptions={sourceOptions}
              sinkOptions={taskType === 'FILE_TRANSFER' ? sinkOptions : undefined}
              showTableFilters={taskType === 'FILE_INGEST'}
            />
          </div>
        </div>
        <Divider style={{ margin: '16px 0' }} />
        {error ? (
          <Alert
            type="error"
            showIcon
            className="task-list-error"
            message="任务列表加载失败"
            description={error}
            action={
              <Button size="small" icon={<ReloadOutlined />} onClick={() => void fetchTaskList()}>
                重试
              </Button>
            }
          />
        ) : null}
        <div className="task-table-shell">
          <Table
            rowKey="id"
            columns={columns as any}
            dataSource={taskList}
            loading={loading}
            pagination={false}
            tableLayout="fixed"
            onChange={(_tablePagination: TablePaginationConfig, _filters, sorter) => {
              const active = Array.isArray(sorter) ? sorter[0] : sorter;
              if (!active?.order) return;
              setSort({
                field: active.field === 'jobName' ? 'name' : 'createTime',
                order: active.order === 'ascend' ? 'asc' : 'desc',
              });
              setPagination((previous) => ({ ...previous, current: 1 }));
            }}
            scroll={{
              x: '100%',
              ...(taskList.length > 0 ? { y: 'calc(100vh - 380px)' } : {}),
            }}
            className="task-table"
            locale={{
              emptyText: (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={error ? '请重试加载任务列表' : emptyDescription} />
              ),
            }}
          />
        </div>
        {pagination.total > 0 ? (
          <div className="task-pagination">
            <CustomPagination
              total={pagination.total}
              current={pagination.current}
              pageSize={pagination.pageSize}
              onChange={(current, pageSize) => {
                setPagination((previous) => ({ ...previous, current, pageSize }));
              }}
            />
          </div>
        ) : null}
      </div>
    </div>
  );
};

export default FileTaskList;
