import { CopyOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { Button, DatePicker, Empty, Form, Input, Select, Table, Tooltip, message } from 'antd';
import type { TablePaginationConfig } from 'antd';
import moment from 'moment';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { fileIngestTaskApi, fileTransferTaskApi } from './api';
import type { FileTaskType } from './types';
import type { TaskSortField, TaskSortOrder } from '@/pages/common/components/TaskSortControls';
import ActionColumn from '@/pages/batch-link-up/components/SyncTaskList/components/ActionColumn';
import DataSourceSyncPlan from '@/pages/batch-link-up/components/SyncTaskList/components/DataSourceSyncPlan';
import ExecutionStatus from '@/pages/batch-link-up/components/SyncTaskList/components/ExecutionStatus';
import ScheduleInfo from '@/pages/batch-link-up/components/SyncTaskList/components/ScheduleInfo';
import TaskStatus from '@/pages/batch-link-up/components/SyncTaskList/components/TaskStatus';
import CustomPagination from '@/pages/batch-link-up/CustomPagination';
import { withTimeout } from '@/utils/withTimeout';
import '@/pages/batch-link-up/components/SyncTaskList/index.less';

const { RangePicker } = DatePicker;

interface SearchValues {
  jobName?: string;
  id?: string;
  status?: string;
  sourceType?: string;
  createTime?: moment.Moment[];
}

interface FileTaskListProps {
  taskType: FileTaskType;
  mode: 'GUIDE_SINGLE' | 'FILE_SYNC';
  goDetail: (id: string, item?: any) => void;
  emptyDescription: string;
  fileMode?: boolean;
}

const sourceOptions = [
  { label: '文件资源库', value: 'FILE_RESOURCE' },
  { label: 'FTP', value: 'FTP' },
  { label: 'SFTP', value: 'SFTP' },
  { label: 'S3', value: 'S3' },
  { label: 'MinIO', value: 'MINIO' },
];

const statusOptions = [
  { label: '运行中', value: 'RUNNING' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '失败', value: 'FAILED' },
];

const FileTaskSearchForm: React.FC<{
  fileMode: boolean;
  initialValues: SearchValues;
  onSearch: (values: SearchValues) => void;
  onReset: () => void;
}> = ({ fileMode, initialValues, onSearch, onReset }) => {
  const [form] = Form.useForm<SearchValues>();

  useEffect(() => {
    form.setFieldsValue(initialValues);
  }, [form, initialValues]);

  return (
    <div className="rounded-2xl border border-slate-100 bg-white px-4 py-3 shadow-sm">
      <Form
        form={form}
        layout="inline"
        initialValues={initialValues}
        onFinish={(values) => onSearch(values)}
        className="flex flex-wrap gap-2"
      >
        <Form.Item name="jobName" className="mb-0">
          <Input
            allowClear
            prefix={<SearchOutlined className="text-slate-400" />}
            placeholder="任务名称"
            className="w-[190px] rounded-full"
          />
        </Form.Item>
        <Form.Item name="id" className="mb-0">
          <Input allowClear placeholder="任务定义 ID" className="w-[170px] rounded-full" />
        </Form.Item>
        <Form.Item name="status" className="mb-0">
          <Select allowClear options={statusOptions} placeholder="运行状态" className="w-[130px]" />
        </Form.Item>
        {fileMode ? (
          <Form.Item name="sourceType" className="mb-0">
            <Select allowClear options={sourceOptions} placeholder="来源类型" className="w-[145px]" />
          </Form.Item>
        ) : null}
        <Form.Item name="createTime" className="mb-0">
          <RangePicker className="rounded-full" placeholder={['创建开始', '创建结束']} />
        </Form.Item>
        <Form.Item className="mb-0">
          <div className="flex gap-2">
            <Button type="primary" htmlType="submit" className="rounded-full px-4">
              查询
            </Button>
            <Button
              htmlType="button"
              className="rounded-full"
              onClick={() => {
                form.resetFields();
                onReset();
              }}
            >
              重置
            </Button>
          </div>
        </Form.Item>
      </Form>
    </div>
  );
};

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
  const [taskList, setTaskList] = useState<any[]>([]);
  const [searchParams, setSearchParams] = useState<SearchValues>({});
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
      setTaskList([]);
      setPagination((previous) => ({ ...previous, total: 0 }));
      setError(getErrorMessage(fetchError, '查询任务列表失败，请稍后重试'));
    } finally {
      setLoading(false);
    }
  }, [mode, pagination.current, pagination.pageSize, searchParams, sort.field, sort.order, taskApi, taskType]);

  useEffect(() => {
    void fetchTaskList();
  }, [fetchTaskList]);

  const handleSearch = (values: SearchValues) => {
    setSearchParams(values);
    setPagination((previous) => ({ ...previous, current: 1 }));
  };

  const handleReset = () => {
    setSearchParams({});
    setSort({ field: 'createTime', order: 'desc' });
    setPagination((previous) => ({ ...previous, current: 1 }));
  };

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
        title: '任务名称',
        dataIndex: 'jobName',
        width: 220,
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
        title: '状态',
        dataIndex: 'lastJobStatus',
        width: 120,
        render: (_value: unknown, record: any) => (
          <div className="sync-task-status-cell flex w-full justify-center">
            <TaskStatus status={record?.lastJobStatus} errorMessage={record?.lastErrorMessage} />
          </div>
        ),
      },
      {
        title: '引接计划',
        key: 'syncPlan',
        width: 260,
        render: (_value: unknown, record: any) => <DataSourceSyncPlan record={record} />,
      },
      {
        title: '执行概况',
        key: 'execution',
        width: 210,
        render: (_value: unknown, record: any) => <ExecutionStatus record={record} />,
      },
      {
        title: '调度',
        key: 'schedule',
        width: 220,
        render: (_value: unknown, record: any) => <ScheduleInfo record={record} />,
      },
      {
        title: '创建时间',
        dataIndex: 'createTime',
        width: 170,
        sorter: true,
        sortOrder: sort.field === 'createTime' ? (sort.order === 'asc' ? 'ascend' : 'descend') : undefined,
        render: (value: string) => <span className="sync-task-time">{value || '-'}</span>,
      },
      {
        title: '操作',
        key: 'action',
        width: 260,
        render: (_value: unknown, record: any) => (
          <ActionColumn record={record} cbk={() => void fetchTaskList()} goDetail={goDetail} />
        ),
      },
    ],
    [fetchTaskList, goDetail, sort.field, sort.order],
  );

  return (
    <div className="batch-link-up-page sync-task-list">
      <div className="space-y-4">
        <FileTaskSearchForm
          fileMode={fileMode}
          initialValues={searchParams}
          onSearch={handleSearch}
          onReset={handleReset}
        />
        {error ? (
          <div className="flex items-center justify-between rounded-xl border border-rose-100 bg-rose-50 px-4 py-3 text-sm text-rose-700">
            <span>{error}</span>
            <Button size="small" icon={<ReloadOutlined />} onClick={() => void fetchTaskList()}>
              重试
            </Button>
          </div>
        ) : null}
        <Table
          rowKey="id"
          columns={columns as any}
          dataSource={taskList}
          loading={loading}
          pagination={false}
          onChange={(_tablePagination: TablePaginationConfig, _filters, sorter) => {
            const active = Array.isArray(sorter) ? sorter[0] : sorter;
            if (!active?.order) return;
            setSort({
              field: active.field === 'jobName' ? 'name' : 'createTime',
              order: active.order === 'ascend' ? 'asc' : 'desc',
            });
            setPagination((previous) => ({ ...previous, current: 1 }));
          }}
          scroll={{ x: 'max-content', y: 'calc(100vh - 380px)' }}
          className="task-table"
          locale={{
            emptyText: (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={error ? '请重试加载任务列表' : emptyDescription} />
            ),
          }}
        />
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
