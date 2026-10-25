import { PlayCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { App, Badge, Button, Input, Popconfirm, Select, Space, Table, Tag } from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  OFFLINE_IMPORT_TYPE_LABELS,
  offlineImportTaskApi,
  type OfflineImportTask,
} from './api';

const STATUS_META: Record<string, { text: string; badge: 'default' | 'processing' | 'success' | 'error' }> = {
  PENDING: { text: '待执行', badge: 'default' },
  RUNNING: { text: '执行中', badge: 'processing' },
  SUCCESS: { text: '成功', badge: 'success' },
  FAILED: { text: '失败', badge: 'error' },
};

const TYPE_TAG_COLOR: Record<string, string> = {
  KINGBASE_DMP: 'geekblue',
  DAMENG_DMP: 'volcano',
  KINGBASE_SQL: 'cyan',
  DAMENG_SQL: 'green',
};

const formatTime = (value?: string) => (value ? value.replace('T', ' ').slice(0, 19) : '-');

const OfflineImportTaskList: React.FC = () => {
  const { message } = App.useApp();
  const [loading, setLoading] = useState(false);
  const [tasks, setTasks] = useState<OfflineImportTask[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNo, setPageNo] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [name, setName] = useState('');
  const [status, setStatus] = useState<string>();
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const load = async (silent = false) => {
    if (!silent) setLoading(true);
    try {
      const response = await offlineImportTaskApi.page({ pageNo, pageSize, name, status });
      const data = (response?.data as any)?.bizData || [];
      setTasks(data);
      setTotal(Number((response?.data as any)?.pagination?.total ?? data.length ?? 0));
    } catch (error: any) {
      if (!silent) message.error(error?.message || '查询任务失败');
    } finally {
      if (!silent) setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pageNo, pageSize, name, status]);

  // Refresh quietly while any task is running so status/progress stay current.
  useEffect(() => {
    const hasRunning = tasks.some((task) => task.status === 'RUNNING');
    if (!hasRunning) {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
      return;
    }
    if (!pollRef.current) {
      pollRef.current = setInterval(() => void load(true), 3000);
    }
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tasks]);

  useEffect(
    () => () => {
      if (pollRef.current) clearInterval(pollRef.current);
    },
    [],
  );

  const execute = async (task: OfflineImportTask) => {
    try {
      const response = await offlineImportTaskApi.execute(task.id);
      if (response?.code !== 0) {
        throw new Error(response?.msg || '执行失败');
      }
      message.success(`任务「${task.name}」已开始执行`);
      void load(true);
    } catch (error: any) {
      message.error(error?.message || '执行失败');
    }
  };

  const remove = async (task: OfflineImportTask) => {
    try {
      const response = await offlineImportTaskApi.remove(task.id);
      if (response?.code !== 0) {
        throw new Error(response?.msg || '删除失败');
      }
      message.success('任务已删除');
      void load();
    } catch (error: any) {
      message.error(error?.message || '删除失败');
    }
  };

  const columns = [
    {
      title: '任务名称',
      dataIndex: 'name',
      key: 'name',
      ellipsis: true,
      render: (value: string, record: OfflineImportTask) => (
        <a onClick={() => history.push(`/sync/file-ingest/import/detail/${record.id}`)}>{value}</a>
      ),
    },
    {
      title: '导入类型',
      dataIndex: 'importType',
      key: 'importType',
      width: 150,
      render: (value: string) => (
        <Tag color={TYPE_TAG_COLOR[value] || 'default'}>
          {OFFLINE_IMPORT_TYPE_LABELS[value as keyof typeof OFFLINE_IMPORT_TYPE_LABELS] || value}
        </Tag>
      ),
    },
    { title: '导入文件', dataIndex: 'fileName', key: 'fileName', ellipsis: true },
    {
      title: '目标数据源',
      key: 'target',
      width: 200,
      ellipsis: true,
      render: (_: unknown, record: OfflineImportTask) =>
        `${record.targetDatasourceName || '-'}${record.targetSchema ? ` / ${record.targetSchema}` : ''}`,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (value: string) => (
        <Badge
          status={STATUS_META[value]?.badge || 'default'}
          text={STATUS_META[value]?.text || value}
        />
      ),
    },
    {
      title: '进度',
      key: 'progress',
      width: 120,
      render: (_: unknown, record: OfflineImportTask) =>
        record.importType.endsWith('_SQL')
          ? `${record.executedStatements ?? 0}/${record.totalStatements ?? 0}${
              record.failedStatements ? `（失败 ${record.failedStatements}）` : ''
            }`
          : '-',
    },
    {
      title: '最近执行',
      key: 'time',
      width: 190,
      render: (_: unknown, record: OfflineImportTask) => (
        <span className="text-[12px]">
          {record.startTime ? `${formatTime(record.startTime)} ~ ${formatTime(record.endTime)}` : '-'}
        </span>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 200,
      render: (_: unknown, record: OfflineImportTask) => (
        <Space size={4}>
          <Button
            type="link"
            size="small"
            icon={<PlayCircleOutlined />}
            disabled={record.status === 'RUNNING'}
            onClick={() => void execute(record)}
          >
            执行
          </Button>
          <Button
            type="link"
            size="small"
            onClick={() => history.push(`/sync/file-ingest/import/detail/${record.id}`)}
          >
            详情
          </Button>
          <Popconfirm
            title="确认删除该导入任务？"
            disabled={record.status === 'RUNNING'}
            onConfirm={() => void remove(record)}
          >
            <Button type="link" size="small" danger disabled={record.status === 'RUNNING'}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div className="p-4">
      <div className="mb-3 flex items-center gap-3">
        <Input.Search
          placeholder="按任务名称搜索"
          allowClear
          style={{ width: 260 }}
          onSearch={(value) => {
            setPageNo(1);
            setName(value);
          }}
        />
        <Select
          placeholder="状态"
          allowClear
          style={{ width: 140 }}
          options={Object.entries(STATUS_META).map(([value, meta]) => ({ value, label: meta.text }))}
          onChange={(value) => {
            setPageNo(1);
            setStatus(value);
          }}
        />
        <Button icon={<ReloadOutlined />} onClick={() => void load()} />
      </div>

      <Table
        rowKey="id"
        size="middle"
        loading={loading}
        columns={columns}
        dataSource={tasks}
        pagination={{
          current: pageNo,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (count) => `共 ${count} 条`,
          onChange: (page, size) => {
            setPageNo(page);
            setPageSize(size);
          },
        }}
      />
    </div>
  );
};

export default OfflineImportTaskList;
