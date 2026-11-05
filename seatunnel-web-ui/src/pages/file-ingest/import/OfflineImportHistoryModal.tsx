import { ReloadOutlined } from '@ant-design/icons';
import { Alert, App, Badge, Button, DatePicker, Empty, Modal, Space, Splitter, Spin, Tabs, Tag } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  OFFLINE_IMPORT_TYPE_LABELS,
  offlineImportTaskApi,
  type OfflineImportTask,
  type OfflineImportTaskRun,
} from './api';

const STATUS_META: Record<string, { label: string; badge: 'default' | 'processing' | 'success' | 'error' }> = {
  RUNNING: { label: '执行中', badge: 'processing' },
  SUCCESS: { label: '成功', badge: 'success' },
  FAILED: { label: '失败', badge: 'error' },
};

const formatTime = (value?: string) => (value ? String(value).replace('T', ' ').slice(0, 19) : '尚未运行');

interface OfflineImportHistoryModalProps {
  open: boolean;
  taskId?: string;
  onClose: () => void;
}

const OfflineImportHistoryModal: React.FC<OfflineImportHistoryModalProps> = ({ open, taskId, onClose }) => {
  const { message } = App.useApp();
  const [task, setTask] = useState<OfflineImportTask>();
  const [runs, setRuns] = useState<OfflineImportTaskRun[]>([]);
  const [selectedRunId, setSelectedRunId] = useState<string>();
  const [dateRange, setDateRange] = useState<[number, number]>();
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string>();
  const requestSeqRef = useRef(0);
  const inFlightRef = useRef(false);

  const load = async (silent = false) => {
    if (!taskId || inFlightRef.current) return;
    inFlightRef.current = true;
    const requestSeq = ++requestSeqRef.current;
    if (!silent) setLoading(true);
    try {
      const [taskResponse, runResponse] = await Promise.all([
        offlineImportTaskApi.get(taskId, { skipErrorHandler: true }),
        offlineImportTaskApi.history(taskId, { skipErrorHandler: true }),
      ]);
      if (requestSeq !== requestSeqRef.current) return;
      if (taskResponse?.code !== 0) throw new Error(taskResponse?.message || taskResponse?.msg || '读取任务信息失败');
      if (runResponse?.code !== 0) throw new Error(runResponse?.message || runResponse?.msg || '读取运行历史失败');
      const nextRuns = (Array.isArray(runResponse?.data) ? runResponse.data : []) as OfflineImportTaskRun[];
      setTask(taskResponse.data as OfflineImportTask);
      setRuns(nextRuns);
      setLoadError(undefined);
      setSelectedRunId((current) => current && nextRuns.some((run) => String(run.id) === current)
        ? current
        : nextRuns[0]?.id);
    } catch (error: any) {
      if (requestSeq !== requestSeqRef.current) return;
      if (!silent) {
        const text = error?.message || '读取运行历史失败';
        setLoadError(text);
        message.error(text);
      }
    } finally {
      if (requestSeq === requestSeqRef.current) {
        inFlightRef.current = false;
        if (!silent) setLoading(false);
      }
    }
  };

  useEffect(() => {
    if (!open) return undefined;
    requestSeqRef.current += 1;
    inFlightRef.current = false;
    setTask(undefined);
    setRuns([]);
    setDateRange(undefined);
    setSelectedRunId(undefined);
    setLoadError(undefined);
    setLoading(true);
    void load();
    const poll = setInterval(() => void load(true), 3000);
    return () => {
      requestSeqRef.current += 1;
      inFlightRef.current = false;
      clearInterval(poll);
    };
    // The interval intentionally reads the current task ID while this modal is open.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, taskId]);

  const filteredRuns = useMemo(() => runs.filter((run) => {
    if (!dateRange) return true;
    const timestamp = new Date(run.startTime || run.endTime || 0).getTime();
    return timestamp >= dateRange[0] && timestamp <= dateRange[1];
  }), [dateRange, runs]);
  const selectedRun = filteredRuns.find((run) => String(run.id) === selectedRunId) || filteredRuns[0];
  const taskTypeLabel = task ? OFFLINE_IMPORT_TYPE_LABELS[task.importType] || task.importType : '-';
  const logContent = selectedRun?.executionLog || selectedRun?.errorMessage || '该次运行暂无日志';

  return (
    <Modal
      title={task?.name ? `运行历史 · ${task.name}` : '运行历史'}
      open={open}
      onCancel={onClose}
      destroyOnHidden
      maskClosable={false}
      className="custom-modal task-history-modal"
      style={{ top: 10, height: 'calc(100vh - 20px)' }}
      width="96vw"
      footer={null}
      styles={{
        mask: { background: 'var(--st-color-modal-mask)' },
        content: { display: 'flex', flexDirection: 'column', minHeight: 0, height: '100%' },
        body: { display: 'flex', flex: '1 1 auto', minHeight: 0, overflow: 'hidden' },
      }}
    >
      <div className="flex min-h-0 flex-1 flex-col p-4">
        <div className="mb-3 flex shrink-0 items-center justify-between">
          <span className="text-[18px] font-semibold">运行历史</span>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新</Button>
        </div>
        <div className="min-h-0 flex-1">
          <Splitter style={{ height: '100%' }}>
            <Splitter.Panel defaultSize="25%" min="270px" max="42%">
              <div className="flex h-full min-h-0 flex-col border-r border-slate-200 pr-3">
                <div className="mb-3 shrink-0">
                  <div className="mb-2 text-[14px] font-medium">执行记录</div>
                  <DatePicker.RangePicker
                    allowClear
                    style={{ width: '100%' }}
                    placeholder={['开始日期', '结束日期']}
                    onChange={(dates: any) => {
                      setDateRange(dates?.[0] && dates?.[1]
                        ? [dates[0].startOf('day').valueOf(), dates[1].endOf('day').valueOf()]
                        : undefined);
                    }}
                  />
                </div>
                <div className="min-h-0 flex-1 space-y-2 overflow-y-auto pr-1">
                  {loading && !runs.length ? <div className="flex justify-center py-8"><Spin /></div> : null}
                  {loadError ? <Alert type="error" showIcon message="读取运行历史失败" description={loadError} action={<Button size="small" onClick={() => void load()}>重试</Button>} /> : null}
                  {!loading && !loadError && !filteredRuns.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无运行记录" /> : null}
                  {filteredRuns.map((run) => {
                    const selected = String(run.id) === String(selectedRun?.id);
                    const statusMeta = STATUS_META[run.status] || { label: run.status, badge: 'default' as const };
                    return (
                      <button
                        type="button"
                        key={run.id}
                        onClick={() => setSelectedRunId(String(run.id))}
                        className={`w-full rounded-lg border p-3 text-left transition-colors ${selected ? 'border-sky-500 bg-sky-50' : 'border-slate-200 hover:border-slate-300'}`}
                      >
                        <div className="mb-1 flex items-center justify-between gap-2">
                          <Badge status={statusMeta.badge} text={statusMeta.label} />
                          <span className="text-[14px] text-slate-500">#{run.id}</span>
                        </div>
                        <div className="text-[14px] text-slate-600">{formatTime(run.startTime)}</div>
                        <div className="mt-1 text-[14px] text-slate-500">
                          {run.executedStatements ?? 0}/{run.totalStatements ?? 0} 条语句
                          {run.failedStatements ? ` · 失败 ${run.failedStatements}` : ''}
                        </div>
                      </button>
                    );
                  })}
                </div>
              </div>
            </Splitter.Panel>

            <Splitter.Panel>
              <div className="flex h-full min-h-0 flex-col pl-4">
                {task ? (
                  <section className="mb-3 shrink-0 rounded-lg border border-slate-200 bg-slate-50 p-3">
                    <div className="mb-2 flex items-center gap-2">
                      <span className="text-[16px] font-semibold">{task.name}</span>
                      <Tag>{taskTypeLabel}</Tag>
                      <Badge status={STATUS_META[task.status]?.badge || 'default'} text={STATUS_META[task.status]?.label || task.status} />
                    </div>
                    <div className="grid grid-cols-2 gap-x-6 gap-y-1 text-[14px] text-slate-600">
                      <span>任务 ID：{task.id}</span>
                      <span>导入文件：{task.fileName || '-'}</span>
                      <span>目标数据源：{task.targetDatasourceName || '-'}</span>
                      <span>目标 Schema：{task.targetSchema || '-'}</span>
                    </div>
                  </section>
                ) : null}
                <Tabs
                  activeKey="logs"
                  className="flex min-h-0 flex-1 flex-col"
                  items={[{
                    key: 'logs',
                    label: '日志',
                    children: (
                      <div className="flex h-full min-h-[260px] flex-col overflow-hidden rounded-lg border border-slate-200 bg-white">
                        <div className="flex shrink-0 items-center justify-between border-b border-slate-200 px-3 py-2 text-[14px]">
                          <Space size={12}>
                            <span>{selectedRun ? formatTime(selectedRun.startTime) : '暂无运行记录'}</span>
                            {selectedRun ? <Badge status={STATUS_META[selectedRun.status]?.badge || 'default'} text={STATUS_META[selectedRun.status]?.label || selectedRun.status} /> : null}
                          </Space>
                          {selectedRun?.endTime ? <span className="text-slate-500">结束：{formatTime(selectedRun.endTime)}</span> : null}
                        </div>
                        <pre className="min-h-0 flex-1 overflow-auto whitespace-pre-wrap break-words p-4 text-[14px] leading-6 text-slate-700">{logContent}</pre>
                      </div>
                    ),
                  }]}
                />
              </div>
            </Splitter.Panel>
          </Splitter>
        </div>
      </div>
    </Modal>
  );
};

export default OfflineImportHistoryModal;
