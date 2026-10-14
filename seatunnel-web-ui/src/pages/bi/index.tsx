import {
  ApiOutlined,
  CheckCircleOutlined,
  DatabaseOutlined,
  FileOutlined,
  SyncOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { Button, Spin } from 'antd';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { fetchSummaryData } from '@/pages/metrics/api';
import type { SummaryData, TaskType, TimeRange } from '@/pages/metrics/types';
import './index.less';

type TaskTypeMeta = {
  key: TaskType;
  label: string;
  detail: string;
  tone: string;
  icon: ReactNode;
};

type TaskTypeMetric = TaskTypeMeta & {
  summary: SummaryData;
  records: number;
  bytes: number;
};

const TASK_TYPES: TaskTypeMeta[] = [
  { key: 'BATCH', label: '批量引接', detail: '批量任务', tone: 'cyan', icon: <DatabaseOutlined /> },
  { key: 'STREAM', label: '实时引接', detail: '流式任务', tone: 'green', icon: <ThunderboltOutlined /> },
  { key: 'FILE_INGEST', label: '文件导入', detail: '离线导入', tone: 'violet', icon: <FileOutlined /> },
  { key: 'FILE_TRANSFER', label: '文件同步', detail: '文件传输', tone: 'amber', icon: <SyncOutlined /> },
];

const TIME_RANGES: { value: TimeRange; label: string }[] = [
  { value: 'H24', label: '近 24 小时' },
  { value: 'D7', label: '近 7 天' },
  { value: 'D30', label: '近 30 天' },
];

const RECORD_FACTORS: Record<string, number> = { '100M': 100_000_000, '万': 10_000 };
const BYTE_FACTORS: Record<string, number> = {
  B: 1,
  KB: 2 ** 10,
  MB: 2 ** 20,
  GB: 2 ** 30,
  TB: 2 ** 40,
};

const numberFormat = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 });
const integerFormat = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 });

const asNumber = (value: number | null | undefined) =>
  typeof value === 'number' && Number.isFinite(value) ? value : 0;

const toRecords = (summary: SummaryData) =>
  asNumber(summary.totalRecords) * (RECORD_FACTORS[summary.totalRecordsUnit] || 1);

const toBytes = (summary: SummaryData) =>
  asNumber(summary.totalBytes) * (BYTE_FACTORS[String(summary.totalBytesUnit).toUpperCase()] || 1);

const formatRecords = (value: number) => {
  if (value >= 100_000_000) return { value: numberFormat.format(value / 100_000_000), unit: '亿条' };
  if (value >= 10_000) return { value: numberFormat.format(value / 10_000), unit: '万条' };
  return { value: integerFormat.format(value), unit: '条' };
};

const formatBytes = (value: number) => {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let unitIndex = 0;
  let scaledValue = value;
  while (scaledValue >= 1024 && unitIndex < units.length - 1) {
    scaledValue /= 1024;
    unitIndex += 1;
  }
  return { value: numberFormat.format(scaledValue), unit: units[unitIndex] };
};

const formatTime = (date: Date) =>
  new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date);

const IngestionSituationPage: React.FC = () => {
  const [timeRange, setTimeRange] = useState<TimeRange>('H24');
  const [selectedTaskType, setSelectedTaskType] = useState<TaskType | null>(null);
  const [taskMetrics, setTaskMetrics] = useState<TaskTypeMetric[] | null>(null);
  const [updatedAt, setUpdatedAt] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const requestId = useRef(0);

  const loadMetrics = useCallback(async () => {
    const currentRequestId = ++requestId.current;
    setLoading(true);
    setError('');
    setTaskMetrics(null);

    try {
      const summaries = await Promise.all(
        TASK_TYPES.map(async (taskType) => ({
          taskType,
          summary: await fetchSummaryData(timeRange, taskType.key),
        })),
      );

      if (currentRequestId !== requestId.current) return;

      setTaskMetrics(
        summaries.map(({ taskType, summary }) => ({
          ...taskType,
          summary,
          records: toRecords(summary),
          bytes: toBytes(summary),
        })),
      );
      setUpdatedAt(formatTime(new Date()));
    } catch (loadError) {
      if (currentRequestId !== requestId.current) return;
      setError(loadError instanceof Error ? loadError.message : '引接指标加载失败');
    } finally {
      if (currentRequestId === requestId.current) setLoading(false);
    }
  }, [timeRange]);

  useEffect(() => {
    void loadMetrics();
    return () => {
      requestId.current += 1;
    };
  }, [loadMetrics]);

  const visibleMetrics = useMemo(
    () => taskMetrics?.filter((item) => !selectedTaskType || item.key === selectedTaskType) || [],
    [selectedTaskType, taskMetrics],
  );

  const totals = useMemo(() => {
    const result = {
      tasks: 0,
      records: 0,
      bytes: 0,
      success: 0,
      failed: 0,
      running: 0,
      stopped: 0,
    };

    visibleMetrics.forEach(({ summary, records, bytes }) => {
      result.tasks += asNumber(summary.totalTasks);
      result.records += records;
      result.bytes += bytes;
      result.success += asNumber(summary.successTasks);
      result.failed += asNumber(summary.failedTasks);
      result.running += asNumber(summary.runningTasks);
      result.stopped += asNumber(summary.stoppedTasks);
    });

    return result;
  }, [visibleMetrics]);

  const successRate = totals.tasks > 0 ? (totals.success / totals.tasks) * 100 : 0;
  const recordsDisplay = formatRecords(totals.records);
  const bytesDisplay = formatBytes(totals.bytes);
  const maxTypeTasks = Math.max(1, ...visibleMetrics.map(({ summary }) => asNumber(summary.totalTasks)));
  const knownStatusCount = totals.success + totals.failed + totals.running + totals.stopped;
  const statusTotal = Math.max(totals.tasks, knownStatusCount);
  const otherTasks = Math.max(totals.tasks - knownStatusCount, 0);
  const statusItems = [
    { label: '成功', value: totals.success, tone: 'success' },
    { label: '运行中', value: totals.running, tone: 'running' },
    { label: '失败', value: totals.failed, tone: 'failed' },
    { label: '已停止', value: totals.stopped, tone: 'stopped' },
    ...(otherTasks > 0 ? [{ label: '其他', value: otherTasks, tone: 'other' }] : []),
  ];
  const selectedLabel = selectedTaskType
    ? TASK_TYPES.find(({ key }) => key === selectedTaskType)?.label || '任务'
    : '全部任务类型';
  const successDegrees = totals.tasks > 0 ? Math.min(360, successRate * 3.6) : 0;

  return (
    <div className="bi-dashboard" aria-busy={loading}>
      <div className="bi-ambient bi-ambient-one" />
      <div className="bi-ambient bi-ambient-two" />

      <header className="bi-header">
        <div className="bi-title-group">
          <div className="bi-eyebrow"><span className="bi-live-dot" /> SEATUNNEL WEB / INGESTION MONITOR</div>
          <h1>引接态势</h1>
          <p>统一查看引接任务执行与数据处理情况</p>
        </div>
        <div className="bi-header-actions">
          <div className="bi-range-tabs" role="group" aria-label="指标统计时间范围">
            {TIME_RANGES.map((range) => (
              <button
                key={range.value}
                type="button"
                className={timeRange === range.value ? 'active' : ''}
                aria-pressed={timeRange === range.value}
                onClick={() => setTimeRange(range.value)}
              >
                {range.label}
              </button>
            ))}
          </div>
          <span className="bi-update-time">更新时间 {updatedAt || '--:--:--'}</span>
          <Button
            className="bi-refresh-button"
            icon={<SyncOutlined spin={loading} />}
            loading={loading}
            onClick={() => void loadMetrics()}
          >
            刷新
          </Button>
        </div>
      </header>

      <section className="bi-kpi-grid" aria-label="引接指标汇总">
        <article className="bi-kpi-card tone-cyan">
          <span className="bi-kpi-icon"><ApiOutlined /></span>
          <div className="bi-kpi-copy"><span>任务数量</span><strong>{taskMetrics ? integerFormat.format(totals.tasks) : '—'}</strong><small>{selectedLabel}</small></div>
          <span className="bi-kpi-unit">项</span>
        </article>
        <article className="bi-kpi-card tone-green">
          <span className="bi-kpi-icon"><DatabaseOutlined /></span>
          <div className="bi-kpi-copy"><span>引接条数</span><strong>{taskMetrics ? recordsDisplay.value : '—'}</strong><small>所选范围内处理记录</small></div>
          <span className="bi-kpi-unit">{recordsDisplay.unit}</span>
        </article>
        <article className="bi-kpi-card tone-violet">
          <span className="bi-kpi-icon"><FileOutlined /></span>
          <div className="bi-kpi-copy"><span>引接数据量</span><strong>{taskMetrics ? bytesDisplay.value : '—'}</strong><small>所选范围内处理数据</small></div>
          <span className="bi-kpi-unit">{bytesDisplay.unit}</span>
        </article>
        <article className="bi-kpi-card tone-amber">
          <span className="bi-kpi-icon"><ThunderboltOutlined /></span>
          <div className="bi-kpi-copy"><span>运行中任务</span><strong>{taskMetrics ? integerFormat.format(totals.running) : '—'}</strong><small>当前统计范围</small></div>
          <span className="bi-kpi-unit">项</span>
        </article>
      </section>

      {error && (
        <div className="bi-error" role="alert">
          <span>引接指标加载失败：{error}</span>
          <button type="button" onClick={() => void loadMetrics()}>重试</button>
        </div>
      )}

      <main className="bi-main-grid">
        <aside className="bi-panel bi-type-panel">
          <div className="bi-panel-heading">
            <div><h2>任务类型分布</h2><span>按统计范围聚合</span></div>
            <button
              type="button"
              className={`bi-all-filter ${selectedTaskType ? '' : 'active'}`}
              aria-pressed={!selectedTaskType}
              onClick={() => setSelectedTaskType(null)}
            >
              全部
            </button>
          </div>
          <div className="bi-type-list">
            {TASK_TYPES.map((taskType) => {
              const metric = taskMetrics?.find(({ key }) => key === taskType.key);
              const count = asNumber(metric?.summary.totalTasks);
              const recordMetric = formatRecords(metric?.records || 0);
              const byteMetric = formatBytes(metric?.bytes || 0);
              const selected = selectedTaskType === taskType.key;

              return (
                <button
                  key={taskType.key}
                  type="button"
                  className={`bi-type-row tone-${taskType.tone}${selected ? ' selected' : ''}`}
                  aria-pressed={selected}
                  onClick={() => setSelectedTaskType(selected ? null : taskType.key)}
                >
                  <span className="bi-type-icon">{taskType.icon}</span>
                  <span className="bi-type-content">
                    <span className="bi-type-title"><b>{taskType.label}</b><small>{taskType.detail}</small></span>
                    <span className="bi-type-count">{taskMetrics ? integerFormat.format(count) : '—'}<em> 项</em></span>
                    <span className="bi-type-progress"><i style={{ width: `${taskMetrics ? (count / maxTypeTasks) * 100 : 0}%` }} /></span>
                    <span className="bi-type-meta">{taskMetrics ? `${recordMetric.value} ${recordMetric.unit} · ${byteMetric.value} ${byteMetric.unit}` : '等待指标加载'}</span>
                  </span>
                </button>
              );
            })}
          </div>
          <div className="bi-panel-note">
            <CheckCircleOutlined />
            <span>指标按任务类型汇总，支持按时间范围筛选。</span>
          </div>
        </aside>

        <section className="bi-panel bi-flow-panel">
          <div className="bi-panel-heading bi-flow-heading">
            <div><h2>引接任务总览</h2><span>{selectedLabel} · {TIME_RANGES.find(({ value }) => value === timeRange)?.label}</span></div>
            <div className="bi-flow-legend"><i />统计示意</div>
          </div>
          <div className="bi-flow-legend-row"><span>任务类型</span><span>SeaTunnel Engine</span><span>指标汇总</span></div>
          <div className="bi-flow-stage">
            <svg className="bi-flow-svg" viewBox="0 0 1000 560" preserveAspectRatio="none" aria-hidden="true">
              <defs>
                <filter id="bi-glow" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="3" result="blur" /><feMerge><feMergeNode in="blur" /><feMergeNode in="SourceGraphic" /></feMerge></filter>
              </defs>
              <line x1="235" y1="26" x2="235" y2="534" className="bi-flow-divider" />
              <line x1="755" y1="26" x2="755" y2="534" className="bi-flow-divider" />
              {visibleMetrics.map((metric, index) => {
                const y = 100 + index * 120;
                const selected = selectedTaskType === metric.key;
                return (
                  <path
                    key={metric.key}
                    d={`M 205 ${y} C 310 ${y}, 330 280, 470 280`}
                    className={`bi-flow-edge tone-${metric.tone}${selectedTaskType && !selected ? ' is-dimmed' : ''}${metric.summary.runningTasks > 0 ? ' is-running' : ''}`}
                    filter={selected ? 'url(#bi-glow)' : undefined}
                  />
                );
              })}
              <path d="M 670 280 C 725 280, 730 280, 790 280" className="bi-flow-edge bi-flow-edge-output" />
            </svg>

            <div className="bi-flow-types">
              {visibleMetrics.map((metric) => (
                <button
                  key={metric.key}
                  type="button"
                  className={`bi-flow-type tone-${metric.tone}${selectedTaskType && selectedTaskType !== metric.key ? ' is-dimmed' : ''}`}
                  style={{
                    top: `${((100 + TASK_TYPES.findIndex(({ key }) => key === metric.key) * 120) / 560) * 100}%`,
                  }}
                  onClick={() => setSelectedTaskType(selectedTaskType === metric.key ? null : metric.key)}
                  aria-pressed={selectedTaskType === metric.key}
                >
                  <span className="bi-flow-type-icon">{metric.icon}</span>
                  <span className="bi-flow-type-copy"><b>{metric.label}</b><small>{integerFormat.format(asNumber(metric.summary.runningTasks))} 项运行中</small></span>
                  <strong>{integerFormat.format(asNumber(metric.summary.totalTasks))}</strong>
                </button>
              ))}
            </div>

            <div className="bi-engine-node">
              <span className="bi-engine-orbit" />
              <span className="bi-engine-icon"><ThunderboltOutlined /></span>
              <b>SeaTunnel</b>
              <small>引接任务引擎</small>
            </div>

            <div className="bi-output-node">
              <span className="bi-output-icon"><DatabaseOutlined /></span>
              <b>数据处理汇总</b>
              <span><small>记录</small><strong>{taskMetrics ? recordsDisplay.value : '—'} {recordsDisplay.unit}</strong></span>
              <span><small>数据量</small><strong>{taskMetrics ? bytesDisplay.value : '—'} {bytesDisplay.unit}</strong></span>
            </div>
            {loading && !taskMetrics && <div className="bi-flow-loading"><Spin size="large" /><span>正在读取引接指标</span></div>}
          </div>
          <div className="bi-flow-footer"><span>统计类型：批量、实时、文件导入、文件同步</span><span>引接条数与数据量由任务已上报指标累计</span></div>
        </section>

        <aside className="bi-panel bi-status-panel">
          <div className="bi-panel-heading">
            <div><h2>任务执行状态</h2><span>{selectedLabel} · 当前统计范围</span></div>
            <span className="bi-live-label"><i className="bi-live-dot" />指标</span>
          </div>
          <div className="bi-status-summary">
            <div
              className="bi-status-ring"
              style={{ background: `conic-gradient(var(--bi-green) 0deg ${successDegrees}deg, rgba(33, 135, 168, .28) ${successDegrees}deg 360deg)` }}
            >
              <div><strong>{taskMetrics ? `${numberFormat.format(successRate)}%` : '—'}</strong><span>成功任务占比</span></div>
            </div>
            <div className="bi-status-total"><span>任务总数</span><strong>{taskMetrics ? integerFormat.format(totals.tasks) : '—'}</strong><small>{selectedLabel}</small></div>
          </div>
          <div className="bi-status-list">
            {statusItems.map((item) => {
              const share = statusTotal > 0 ? (item.value / statusTotal) * 100 : 0;
              return (
                <div className={`bi-status-item tone-${item.tone}`} key={item.label}>
                  <div className="bi-status-meta"><span><i />{item.label}</span><b>{taskMetrics ? integerFormat.format(item.value) : '—'}</b></div>
                  <div className="bi-status-track"><i style={{ width: `${share}%` }} /></div>
                </div>
              );
            })}
          </div>
          <div className="bi-status-foot"><span><i className="bi-live-dot" />汇总自任务状态统计</span><span>{updatedAt || '等待更新'}</span></div>
        </aside>
      </main>
    </div>
  );
};

export default IngestionSituationPage;
