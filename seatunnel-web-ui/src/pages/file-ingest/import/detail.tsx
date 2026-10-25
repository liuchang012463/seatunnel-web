import { ArrowLeftOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { history, useParams } from '@umijs/max';
import { App, Badge, Button, Descriptions, Space, Spin, Tag } from 'antd';
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

const OfflineImportDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const { message } = App.useApp();
  const [task, setTask] = useState<OfflineImportTask | null>(null);
  const [loading, setLoading] = useState(true);
  const [executing, setExecuting] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const logRef = useRef<HTMLPreElement>(null);

  const load = async () => {
    try {
      const response = await offlineImportTaskApi.get(id!);
      if (response?.code !== 0) {
        throw new Error(response?.message || '查询任务失败');
      }
      setTask((response?.data || null) as OfflineImportTask | null);
    } catch (error: any) {
      message.error(error?.message || '查询任务失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (id) void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  useEffect(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
    if (task?.status === 'RUNNING') {
      pollRef.current = setInterval(() => void load(), 2000);
    }
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [task?.status]);

  useEffect(() => {
    if (logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight;
    }
  }, [task?.executionLog]);

  const execute = async () => {
    setExecuting(true);
    try {
      const response = await offlineImportTaskApi.execute(id!);
      if (response?.code !== 0) {
        throw new Error(response?.message || '执行失败');
      }
      message.success('任务已开始执行');
      void load();
    } catch (error: any) {
      message.error(error?.message || '执行失败');
    } finally {
      setExecuting(false);
    }
  };

  if (loading) {
    return (
      <div className="flex min-h-[320px] items-center justify-center">
        <Spin />
      </div>
    );
  }

  if (!task) {
    return (
      <div className="flex min-h-[320px] flex-col items-center justify-center gap-3">
        <span className="text-[16px] font-medium">未找到任务</span>
        <Button type="primary" onClick={() => history.push('/sync/file-ingest?tab=offline-import')}>
          返回任务列表
        </Button>
      </div>
    );
  }

  const isSql = task.importType.endsWith('_SQL');
  const statusMeta = STATUS_META[task.status] || { text: task.status, badge: 'default' as const };

  return (
    <div className="min-h-screen bg-white">
      <div className="mx-auto max-w-[1200px] px-6 pb-10 pt-6">
        <div className="mb-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={() => history.push('/sync/file-ingest?tab=offline-import')}
            />
            <h1 className="m-0 text-[20px] font-semibold">{task.name}</h1>
            <Tag>{OFFLINE_IMPORT_TYPE_LABELS[task.importType] || task.importType}</Tag>
            <Badge status={statusMeta.badge} text={statusMeta.text} />
          </div>
          <Button
            type="primary"
            icon={<PlayCircleOutlined />}
            loading={executing || task.status === 'RUNNING'}
            disabled={task.status === 'RUNNING'}
            onClick={() => void execute()}
          >
            {task.status === 'PENDING' ? '执行导入' : '重新执行'}
          </Button>
        </div>

        {task.errorMessage && (
          <div className="mb-4 rounded-xl border border-[#fecdca] bg-[#fffaeb] px-4 py-3 text-[13px] text-[#b54708]">
            {task.errorMessage}
          </div>
        )}

        <div className="rounded-[24px] border border-[#EAECF0] p-6 shadow-[0_1px_2px_rgba(16,24,40,0.04)]">
          <Descriptions column={2} size="small">
            <Descriptions.Item label="导入文件">{task.fileName}</Descriptions.Item>
            <Descriptions.Item label="目标数据源">
              {task.targetDatasourceName || '-'}
              {task.targetSchema ? ` / ${task.targetSchema}` : ''}
            </Descriptions.Item>
            <Descriptions.Item label="任务描述">{task.description || '-'}</Descriptions.Item>
            <Descriptions.Item label="创建时间">{task.createTime || '-'}</Descriptions.Item>
            {isSql && (
              <Descriptions.Item label="语句进度">
                <Space size={16}>
                  <span>已执行 {task.executedStatements ?? 0} / {task.totalStatements ?? 0}</span>
                  {!!task.failedStatements && <span className="text-[#d92d20]">失败 {task.failedStatements}</span>}
                </Space>
              </Descriptions.Item>
            )}
            <Descriptions.Item label="最近执行">
              {(task.startTime || task.endTime)
                ? `${task.startTime || '-'} ~ ${task.endTime || '-'}`
                : '尚未执行'}
            </Descriptions.Item>
            {task.options && (
              <Descriptions.Item label="导入选项">
                {isSql
                  ? `编码 ${task.options.encoding || 'UTF-8'}，出错${
                      task.options.stopOnError === false ? '继续执行' : '停止'
                    }`
                  : `${task.options.sourceSchema ? `源 Schema ${task.options.sourceSchema}，` : ''}${
                      task.options.ignoreErrors === false ? '不忽略' : '忽略'
                    }已存在对象错误`}
              </Descriptions.Item>
            )}
          </Descriptions>
        </div>

        <div className="mt-6 rounded-[24px] border border-[#EAECF0] p-6 shadow-[0_1px_2px_rgba(16,24,40,0.04)]">
          <div className="mb-3 text-[14px] font-medium">执行日志</div>
          <pre
            ref={logRef}
            className="m-0 max-h-[480px] overflow-auto rounded-xl bg-[#0c2233] p-4 text-[12px] leading-[20px] text-[#d7f2ff]"
          >
            {task.executionLog || '暂无执行日志'}
          </pre>
        </div>
      </div>
    </div>
  );
};

export default OfflineImportDetailPage;
