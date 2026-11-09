import { DoubleRightOutlined, FileOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import Header from '@/components/Header';
import { useIntl } from '@umijs/max';
import { Alert, App, Button, Descriptions, Input, Modal, Spin, Splitter, Tabs } from 'antd';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { HistoryItem } from '@/pages/batch-link-up/type';
import HistoryFilterBar from '@/pages/batch-link-up/components/TaskHistoryPanel/components/HistoryFilterBar';
import HistoryList from '@/pages/batch-link-up/components/TaskHistoryPanel/components/HistoryList';
import HistoryPanelHeader from '@/pages/batch-link-up/components/TaskHistoryPanel/components/HistoryPanelHeader';
import HistoryStatusFilters from '@/pages/batch-link-up/components/TaskHistoryPanel/components/HistoryStatusFilters';
import type { TimeRangeType } from '@/pages/batch-link-up/components/TaskHistoryPanel/hooks/useTaskHistory';
import TaskDetailHeader from '@/pages/batch-link-up/TaskHeader';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import {
  OFFLINE_IMPORT_TYPE_LABELS,
  offlineImportTargetDbType,
  offlineImportTaskApi,
  type OfflineImportTask,
  type OfflineImportTaskRun,
} from './api';
import '@/pages/batch-link-up/components/TaskHistoryPanel/sync.less';

const formatTime = (value?: string) => (value ? String(value).replace('T', ' ').slice(0, 19) : '-');

// 将导入任务状态映射到常规运行历史使用的状态分类。
const historyJobStatus = (status: string) => {
  if (status === 'SUCCESS') return 'FINISHED';
  if (status === 'QUEUED') return 'RUNNING';
  return status;
};

interface OfflineImportLogPanelProps {
  run?: OfflineImportTaskRun;
  loading: boolean;
  onRefresh: () => void;
}

const OfflineImportLogPanel: React.FC<OfflineImportLogPanelProps> = ({ run, loading, onRefresh }) => {
  const [searchKeyword, setSearchKeyword] = useState('');
  const logContent = run?.executionLog || run?.errorMessage || '';
  const logLines = useMemo(() => logContent.split(/\r?\n/), [logContent]);
  const normalizedKeyword = searchKeyword.trim().toLowerCase();
  const matchedLineCount = normalizedKeyword
    ? logLines.filter((line) => line.toLowerCase().includes(normalizedKeyword)).length
    : 0;

  useEffect(() => setSearchKeyword(''), [run?.id]);

  return (
    <section className="rounded-xl border border-slate-200 bg-white p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="text-sm font-semibold text-slate-800">原始执行日志</div>
          <div className="mt-1 text-sm text-slate-400">完整日志 · 当前实例 #{run?.id || '-'}</div>
        </div>
        <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={onRefresh}>
          刷新
        </Button>
      </div>
      <Input
        allowClear
        prefix={<SearchOutlined className="text-slate-400" />}
        value={searchKeyword}
        onChange={(event) => setSearchKeyword(event.target.value)}
        placeholder="在当前完整日志中检索 timeout、ERROR、连接..."
        suffix={normalizedKeyword ? <span className="text-sm text-slate-400">命中 {matchedLineCount} 行</span> : null}
      />
      <div className="mt-3 max-h-[360px] overflow-auto rounded-xl bg-slate-950 p-3 font-mono text-sm leading-5 text-slate-100">
        {loading && !logContent ? (
          <div className="flex min-h-[180px] items-center justify-center"><Spin size="small" /></div>
        ) : !run ? (
          <div className="py-12 text-center text-slate-500">请先从左侧选择运行记录</div>
        ) : logContent ? (
          logLines.map((line, index) => {
            const matched = normalizedKeyword && line.toLowerCase().includes(normalizedKeyword);
            return <div key={index} className={matched ? 'rounded bg-cyan-950/80 text-cyan-100' : ''}>{line || ' '}</div>;
          })
        ) : (
          <div className="py-12 text-center text-slate-500">该次运行暂无日志</div>
        )}
      </div>
    </section>
  );
};

interface OfflineImportHistoryModalProps {
  open: boolean;
  taskId?: string;
  onClose: () => void;
}

const OfflineImportHistoryModal: React.FC<OfflineImportHistoryModalProps> = ({ open, taskId, onClose }) => {
  const { message } = App.useApp();
  const intl = useIntl();
  const [task, setTask] = useState<OfflineImportTask>();
  const [runs, setRuns] = useState<OfflineImportTaskRun[]>([]);
  const [selectedRunId, setSelectedRunId] = useState<string>();
  const [keyword, setKeyword] = useState('');
  const [timeRangeType, setTimeRangeType] = useState<TimeRangeType>('最近一天');
  const [customTimeRange, setCustomTimeRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [statusFilter, setStatusFilter] = useState('all');
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
    setSelectedRunId(undefined);
    setKeyword('');
    setTimeRangeType('最近一天');
    setCustomTimeRange(null);
    setStatusFilter('all');
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

  const timeAndKeywordFilteredRuns = useMemo(() => {
    const now = dayjs();
    const startTime = timeRangeType === '最近一天'
      ? now.subtract(1, 'day').valueOf()
      : timeRangeType === '最近三天'
        ? now.subtract(3, 'day').valueOf()
        : timeRangeType === '最近一周'
          ? now.subtract(7, 'day').valueOf()
          : customTimeRange?.[0]?.startOf('day').valueOf();
    const endTime = timeRangeType === '自定义'
      ? customTimeRange?.[1]?.endOf('day').valueOf()
      : now.valueOf();
    const normalizedKeyword = keyword.trim().toLowerCase();

    return runs.filter((run) => {
      const timestamp = new Date(run.startTime || run.endTime || 0).getTime();
      const matchesTime = (startTime === undefined || timestamp >= startTime)
        && (endTime === undefined || timestamp <= endTime);
      const matchesKeyword = !normalizedKeyword
        || String(run.taskName || task?.name || '').toLowerCase().includes(normalizedKeyword);
      return matchesTime && matchesKeyword;
    });
  }, [customTimeRange, keyword, runs, task?.name, timeRangeType]);
  const statusCountMap = useMemo(() => timeAndKeywordFilteredRuns.reduce((counts, run) => {
    const status = historyJobStatus(run.status);
    if (status === 'FINISHED' || status === 'RUNNING' || status === 'FAILED') {
      counts[status] += 1;
    }
    counts.all += 1;
    return counts;
  }, { all: 0, FINISHED: 0, RUNNING: 0, FAILED: 0 }), [timeAndKeywordFilteredRuns]);
  const filteredRuns = useMemo(() => timeAndKeywordFilteredRuns.filter((run) => (
    statusFilter === 'all' || historyJobStatus(run.status) === statusFilter
  )), [statusFilter, timeAndKeywordFilteredRuns]);
  const historyItems = useMemo<HistoryItem[]>(() => filteredRuns.map((run) => ({
    id: String(run.id),
    jobName: run.taskName || task?.name || '-',
    jobStatus: historyJobStatus(run.status),
    time: formatTime(run.startTime),
    startTime: formatTime(run.startTime),
    endTime: run.endTime ? formatTime(run.endTime) : '-',
  })), [filteredRuns, task?.name]);
  const selectedHistoryItem = historyItems.find((item) => item.id === selectedRunId) || historyItems[0];
  const selectedRun = filteredRuns.find((run) => String(run.id) === selectedRunId) || filteredRuns[0];
  const taskTypeLabel = task ? OFFLINE_IMPORT_TYPE_LABELS[task.importType] || task.importType : '-';

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
        content: { display: 'flex', flexDirection: 'column', flex: '1 1 0%', minHeight: 0 },
        body: { display: 'flex', flexDirection: 'column', flex: '1 1 auto', minHeight: 0, overflow: 'hidden' },
      }}
    >
      <div className="flex min-h-0 flex-1 flex-col p-4">
        <div className="mb-4 shrink-0 text-[18px] font-semibold">运行历史</div>
        <div className="min-h-0 flex-1">
          <Splitter style={{ height: '100%' }}>
            <Splitter.Panel defaultSize="22%" min="22%" max="70%">
              <div className="flex h-full min-h-0 flex-col border-r border-slate-200 bg-white">
                <div className="border-b border-slate-200 bg-white px-2.5 pb-2 pt-2.5">
                  <HistoryPanelHeader intl={intl} onRefresh={() => void load()} />
                  <HistoryFilterBar
                    intl={intl}
                    keyword={keyword}
                    onKeywordChange={setKeyword}
                    timeRangeType={timeRangeType}
                    onTimeRangeTypeChange={setTimeRangeType}
                    customTimeRange={customTimeRange}
                    onCustomTimeRangeChange={setCustomTimeRange}
                  />
                  <HistoryStatusFilters
                    intl={intl}
                    statusFilter={statusFilter}
                    statusCountMap={statusCountMap}
                    onStatusFilterChange={setStatusFilter}
                  />
                  {loadError ? (
                    <Alert
                      className="mt-2"
                      type="error"
                      showIcon
                      message="读取运行历史失败"
                      description={loadError}
                      action={<Button size="small" onClick={() => void load()}>重试</Button>}
                    />
                  ) : null}
                </div>
                <HistoryList
                  intl={intl}
                  loading={loading}
                  historyItems={historyItems}
                  instanceItem={selectedHistoryItem}
                  onSelect={(item) => setSelectedRunId(String(item.id))}
                />
              </div>
            </Splitter.Panel>

            <Splitter.Panel>
              <div className="flex h-full min-h-0 flex-col bg-slate-50">
                {selectedRun && task ? (
                  <>
                    <TaskDetailHeader item={{ jobName: task.name }} />
                    <div className="min-h-0 flex-1 overflow-y-auto bg-slate-50">
                      <section className="m-4 rounded bg-white p-4 shadow-[0_2px_6px_#0000000d]">
                        <div className="flex items-center justify-between">
                          <Header
                            title={(
                              <span className="text-sm text-white">
                                {intl.formatMessage({
                                  id: 'pages.job.detail.basicInfo',
                                  defaultMessage: 'Basic Info',
                                })}
                              </span>
                            )}
                          />
                          <div className="w-[150px]" />
                        </div>
                        <Descriptions column={2} labelStyle={{ color: 'rgba(128,128,128,1)' }}>
                          <Descriptions.Item label={intl.formatMessage({ id: 'pages.job.detail.jobCode', defaultMessage: 'Job Instance ID' })}>
                            <span className="pl-3 text-black">{selectedRun.id}</span>
                          </Descriptions.Item>
                          <Descriptions.Item label={intl.formatMessage({ id: 'pages.job.detail.syncPlan', defaultMessage: 'Sync Plan' })}>
                            <div className="flex items-center pl-3 text-black">
                              <FileOutlined />
                              <span className="ml-2">湖文件</span>
                              <DoubleRightOutlined className="mx-3 text-sky-500" />
                              <DatabaseIcons dbType={offlineImportTargetDbType(task.importType)} width="20" height="20" />
                              <span className="ml-2 truncate">
                                {[
                                  task.targetDatasourceName || '-',
                                  task.targetDatabase,
                                  task.targetSchema,
                                ].filter(Boolean).join(' / ')}
                              </span>
                            </div>
                          </Descriptions.Item>
                          <Descriptions.Item label={intl.formatMessage({ id: 'pages.job.detail.startTime', defaultMessage: 'Start Time' })}>
                            <span className="pl-3 text-black">{formatTime(selectedRun.startTime)}</span>
                          </Descriptions.Item>
                          <Descriptions.Item label={intl.formatMessage({ id: 'pages.job.detail.endTime', defaultMessage: 'End Time' })}>
                            <span className="pl-3 text-black">{formatTime(selectedRun.endTime)}</span>
                          </Descriptions.Item>
                          <Descriptions.Item label="任务 ID">
                            <span className="pl-3 text-black">{task.id}</span>
                          </Descriptions.Item>
                          <Descriptions.Item label="导入类型">
                            <span className="pl-3 text-black">{taskTypeLabel}</span>
                          </Descriptions.Item>
                          <Descriptions.Item span={4} label="导入文件">
                            <span className="pl-3 text-black">{task.fileName || '-'}</span>
                          </Descriptions.Item>
                          <Descriptions.Item label="执行进度">
                            <span className="pl-3 text-black">
                              {String(task.importType).endsWith('_SQL')
                                ? `${selectedRun.executedStatements ?? 0}/${selectedRun.totalStatements ?? 0}`
                                : selectedRun.status === 'SUCCESS' ? '完成' : '-'}
                            </span>
                          </Descriptions.Item>
                          <Descriptions.Item label="失败语句">
                            <span className="pl-3 text-black">{selectedRun.failedStatements ?? 0}</span>
                          </Descriptions.Item>
                        </Descriptions>
                      </section>
                      <div className="m-4 rounded-lg bg-white p-4 shadow-[0_1px_3px_rgba(15,23,42,0.04)]">
                        <Tabs
                          activeKey="logs"
                          className="flex min-h-0 flex-1 flex-col"
                          items={[{
                            key: 'logs',
                            label: '日志',
                            children: (
                              <OfflineImportLogPanel
                                run={selectedRun}
                                loading={loading}
                                onRefresh={() => void load()}
                              />
                            ),
                          }]}
                        />
                      </div>
                    </div>
                  </>
                ) : (
                  <div className="flex h-full items-center justify-center text-base text-slate-400">
                    请选择左侧的运行记录查看详情
                  </div>
                )}
              </div>
            </Splitter.Panel>
          </Splitter>
        </div>
      </div>
    </Modal>
  );
};

export default OfflineImportHistoryModal;
