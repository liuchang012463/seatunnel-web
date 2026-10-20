import { Link } from '@umijs/max';
import { Spin } from 'antd';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Activity,
  Archive,
  ArrowLeft,
  Box,
  Cable,
  CheckCircle2,
  ChevronDown,
  CircleAlert,
  CircleDot,
  Clock3,
  Cpu,
  Database,
  Eye,
  FileText,
  Filter,
  Gauge,
  Layers3,
  List,
  Network,
  Radio,
  RefreshCw,
  Search,
  ShieldCheck,
} from 'lucide-react';
import {
  fetchDataInventoryOverview,
} from '@/pages/data-source/service';
import type {
  DataInventoryDistributionItem,
  DataInventoryProfileCoverage,
  DataInventorySummary,
} from '@/pages/data-source/types';
import { fetchChartData, fetchSummaryData } from '@/pages/metrics/api';
import type { ApiChartData, ChartDataItem, SummaryData, TaskType, TimeRange } from '@/pages/metrics/types';
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
};

type TaskTypeChart = {
  key: TaskType;
  charts: ApiChartData;
};

type TrendPoint = {
  date: string;
  records: number;
  bytes: number;
};

const TASK_TYPES: TaskTypeMeta[] = [
  { key: 'BATCH', label: '批量引接', detail: '批量任务', tone: 'cyan', icon: <Database size={17} /> },
  { key: 'STREAM', label: '实时引接', detail: '流式任务', tone: 'green', icon: <Activity size={17} /> },
  { key: 'FILE_INGEST', label: '文件导入', detail: '离线导入', tone: 'amber', icon: <FileText size={17} /> },
  { key: 'FILE_TRANSFER', label: '文件同步', detail: '文件传输', tone: 'blue', icon: <Archive size={17} /> },
];

const TIME_RANGES: { value: TimeRange; label: string; shortLabel: string }[] = [
  { value: 'H1', label: '近 1 小时', shortLabel: '1H' },
  { value: 'H6', label: '近 6 小时', shortLabel: '6H' },
  { value: 'H12', label: '近 12 小时', shortLabel: '12H' },
  { value: 'H24', label: '近 24 小时', shortLabel: '24H' },
  { value: 'D7', label: '近 7 天', shortLabel: '一周' },
];

const SOURCE_TONES = ['cyan', 'green', 'amber', 'blue', 'pink', 'violet'];
const integerFormat = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 });
const numberFormat = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 });
const RECORD_FACTORS: Record<string, number> = { '100M': 100_000_000, '万': 10_000 };
const BYTE_FACTORS: Record<string, number> = {
  B: 1,
  KB: 2 ** 10,
  MB: 2 ** 20,
  GB: 2 ** 30,
  TB: 2 ** 40,
};

const asNumber = (value: number | string | null | undefined) => {
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
};

const recordFactorForUnit = (unit: unknown) => {
  const normalized = String(unit || '').trim();
  if (normalized.startsWith('100M')) return RECORD_FACTORS['100M'];
  if (normalized.includes('万')) return RECORD_FACTORS['万'];
  return 1;
};

const byteFactorForUnit = (unit: unknown) => BYTE_FACTORS[String(unit || 'B').trim().toUpperCase()] || 1;

const toRecords = (summary: SummaryData) =>
  asNumber(summary.totalRecords) * recordFactorForUnit(summary.totalRecordsUnit);

const toBytes = (summary: SummaryData) =>
  asNumber(summary.totalBytes) * byteFactorForUnit(summary.totalBytesUnit);

const chartRecordsToRaw = (point: ChartDataItem) =>
  asNumber(point.value) * recordFactorForUnit(point.unit);

const chartBytesToRaw = (point: ChartDataItem) =>
  asNumber(point.value) * byteFactorForUnit(point.unit);

const formatRecords = (value: number) => {
  if (value >= 100_000_000) return { value: numberFormat.format(value / 100_000_000), unit: '亿条' };
  if (value >= 10_000) return { value: numberFormat.format(value / 10_000), unit: '万条' };
  return { value: integerFormat.format(value), unit: '条' };
};

const formatBytes = (value: number) => {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let unitIndex = 0;
  let scaledValue = Math.max(0, value);
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

const sourceIcon = (name: string): ReactNode => {
  const type = name.toUpperCase();
  if (/SFTP|FTP|S3|MINIO|HDFS|OSS/.test(type)) return <FileText size={16} strokeWidth={1.8} />;
  if (/KAFKA|RABBIT|MQ/.test(type)) return <Radio size={16} strokeWidth={1.8} />;
  if (/HTTP|REST|API/.test(type)) return <Cable size={16} strokeWidth={1.8} />;
  if (/ELASTIC|REDIS|MONGO|DORIS/.test(type)) return <Box size={16} strokeWidth={1.8} />;
  return <Database size={16} strokeWidth={1.8} />;
};

const getTrendPath = (values: number[], maxValue: number) => {
  if (values.length === 0) return '';
  return values
    .map((value, index) => {
      const x = values.length === 1 ? 320 : 16 + (index / (values.length - 1)) * 608;
      const y = 198 - (value / maxValue) * 164;
      return (index === 0 ? 'M ' : 'L ') + x.toFixed(1) + ' ' + y.toFixed(1);
    })
    .join(' ');
};

const IngestionSituationPage: React.FC = () => {
  const [timeRange, setTimeRange] = useState<TimeRange>('H24');
  const [selectedTaskType, setSelectedTaskType] = useState<TaskType | null>(null);
  const [hoveredTaskType, setHoveredTaskType] = useState<TaskType | null>(null);
  const [taskMetrics, setTaskMetrics] = useState<TaskTypeMetric[] | null>(null);
  const [chartMetrics, setChartMetrics] = useState<TaskTypeChart[]>([]);
  const [inventorySummary, setInventorySummary] = useState<DataInventorySummary | null>(null);
  const [inventoryLoading, setInventoryLoading] = useState(true);
  const [profileCoverage, setProfileCoverage] = useState<DataInventoryProfileCoverage | null>(null);
  const [sourceTypes, setSourceTypes] = useState<DataInventoryDistributionItem[]>([]);
  const [businessSystems, setBusinessSystems] = useState<DataInventoryDistributionItem[]>([]);
  const [updatedAt, setUpdatedAt] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [inventoryError, setInventoryError] = useState('');
  const [trendError, setTrendError] = useState('');
  const [activeSourceTab, setActiveSourceTab] = useState<'overview' | 'breakdown'>('overview');
  const [activeSourceVariant, setActiveSourceVariant] = useState('');
  const [activeSystemTab, setActiveSystemTab] = useState<'systems' | 'modes'>('systems');
  const requestId = useRef(0);

  const loadDashboard = useCallback(async () => {
    const currentRequestId = ++requestId.current;
    setLoading(true);
    setInventoryLoading(true);
    const isCurrentRequest = () => currentRequestId === requestId.current;

    let summarySucceeded = false;
    let chartSucceeded = false;

    const summaryRequest = Promise.all(TASK_TYPES.map((taskType) => fetchSummaryData(timeRange, taskType.key)))
      .then((summaries) => {
        if (!isCurrentRequest()) return;
        if (summaries.some((summary) => !summary)) {
          setTaskMetrics(null);
          setError('任务概览指标暂不可用，请稍后重试。');
          return;
        }
        setTaskMetrics(
          TASK_TYPES.map((taskType, index) => ({
            ...taskType,
            summary: summaries[index],
          })),
        );
        summarySucceeded = true;
        setError('');
      })
      .catch(() => {
        if (!isCurrentRequest()) return;
        setError('任务概览指标暂不可用，请稍后重试。');
      });

    const inventoryRequest = fetchDataInventoryOverview()
      .then((response) => {
        if (!isCurrentRequest()) return;
        if (response.code === 0 && response.data) {
          setInventorySummary(response.data.summary || null);
          setSourceTypes(response.data.sourceTypes || []);
          setBusinessSystems(response.data.businessSystems || []);
          setProfileCoverage(response.data.coverage || null);
          setInventoryError('');
        } else {
          setInventorySummary(null);
          setSourceTypes([]);
          setBusinessSystems([]);
          setProfileCoverage(null);
          setInventoryError('资源目录指标暂不可用');
        }
        setInventoryLoading(false);
      })
      .catch(() => {
        if (!isCurrentRequest()) return;
        setInventorySummary(null);
        setSourceTypes([]);
        setBusinessSystems([]);
        setProfileCoverage(null);
        setInventoryError('资源目录指标暂不可用');
        setInventoryLoading(false);
      });

    const chartRequest = Promise.all(
        TASK_TYPES.map(async (taskType) => ({
          key: taskType.key,
          charts: await fetchChartData(timeRange, taskType.key),
        })),
      )
      .then((charts) => {
        if (!isCurrentRequest()) return;
        setChartMetrics(charts);
        chartSucceeded = true;
        setTrendError('');
      })
      .catch(() => {
        if (!isCurrentRequest()) return;
        setChartMetrics([]);
        setTrendError('趋势数据暂不可用');
      });

    const primaryRequests = Promise.all([summaryRequest, chartRequest]).then(() => {
      if (!isCurrentRequest()) return;
      // Both branches catch their own errors, so Promise.all always resolves. Only claim a
      // fresh timestamp when both payloads actually arrived.
      setUpdatedAt(summarySucceeded && chartSucceeded ? formatTime(new Date()) : '');
      setLoading(false);
    });
    await Promise.all([primaryRequests, inventoryRequest]);
  }, [timeRange]);

  useEffect(() => {
    void loadDashboard();
    return () => {
      requestId.current += 1;
    };
  }, [loadDashboard]);

  const visibleMetrics = useMemo(
    () => taskMetrics?.filter((item) => !selectedTaskType || item.key === selectedTaskType) || [],
    [selectedTaskType, taskMetrics],
  );

  const visibleCharts = useMemo(
    () => chartMetrics.filter((item) => !selectedTaskType || item.key === selectedTaskType),
    [chartMetrics, selectedTaskType],
  );

  const totals = useMemo(
    () =>
      visibleMetrics.reduce(
        (result, metric) => {
          result.tasks += asNumber(metric.summary.totalTasks);
          result.records += toRecords(metric.summary);
          result.bytes += toBytes(metric.summary);
          result.success += asNumber(metric.summary.successTasks);
          result.failed += asNumber(metric.summary.failedTasks);
          result.running += asNumber(metric.summary.runningTasks);
          result.stopped += asNumber(metric.summary.stoppedTasks);
          return result;
        },
        { tasks: 0, records: 0, bytes: 0, success: 0, failed: 0, running: 0, stopped: 0 },
      ),
    [visibleMetrics],
  );

  const trendPoints = useMemo(() => {
    const pointsByDate = new Map<string, TrendPoint>();
    visibleCharts.forEach(({ charts }) => {
      (charts.recordsTrend || []).forEach((point) => {
        const date = String(point.date || '');
        if (!date) return;
        const trendPoint = pointsByDate.get(date) || { date, records: 0, bytes: 0 };
        trendPoint.records += chartRecordsToRaw(point);
        pointsByDate.set(date, trendPoint);
      });
      (charts.bytesTrend || []).forEach((point) => {
        const date = String(point.date || '');
        if (!date) return;
        const trendPoint = pointsByDate.get(date) || { date, records: 0, bytes: 0 };
        trendPoint.bytes += chartBytesToRaw(point);
        pointsByDate.set(date, trendPoint);
      });
    });
    return Array.from(pointsByDate.values()).sort((left, right) => left.date.localeCompare(right.date));
  }, [visibleCharts]);

  const cumulativeBytes = useMemo(() => {
    let total = 0;
    return trendPoints.map((point) => {
      total += point.bytes;
      return total;
    });
  }, [trendPoints]);

  const completedTasks = totals.success + totals.failed + totals.stopped;
  const successRate = completedTasks > 0 ? (totals.success / completedTasks) * 100 : null;
  const recordsDisplay = formatRecords(totals.records);
  const bytesDisplay = formatBytes(totals.bytes);
  const maxTypeTasks = Math.max(1, ...visibleMetrics.map((item) => asNumber(item.summary.totalTasks)));
  const otherTasks = Math.max(totals.tasks - totals.success - totals.failed - totals.running - totals.stopped, 0);
  const selectedLabel = selectedTaskType
    ? TASK_TYPES.find((item) => item.key === selectedTaskType)?.label || '任务'
    : '全部任务类型';
  const maxTrendValue = Math.max(1, ...cumulativeBytes);
  const cumulativePath = getTrendPath(cumulativeBytes, maxTrendValue);
  const addedPath = getTrendPath(
    trendPoints.map((point) => point.bytes),
    maxTrendValue,
  );
  const trendAreaPath =
    cumulativePath && trendPoints.length > 0
      ? cumulativePath +
        ' L ' +
        (trendPoints.length === 1 ? '320' : '624') +
        ' 198 L ' +
        (trendPoints.length === 1 ? '320' : '16') +
        ' 198 Z'
      : '';
  const trendLastY =
    cumulativeBytes.length > 0
      ? 198 - (cumulativeBytes[cumulativeBytes.length - 1] / maxTrendValue) * 164
      : 198;
  const tickCount = Math.min(7, trendPoints.length);
  const trendLabels =
    tickCount <= 1
      ? trendPoints.map((point) => (timeRange === 'D7' ? point.date.slice(5, 10) : point.date.slice(11, 16)))
      : Array.from({ length: tickCount }, (_, index) => {
          const pointIndex = Math.round((index / (tickCount - 1)) * (trendPoints.length - 1));
          const date = trendPoints[pointIndex].date;
          return timeRange === 'D7' ? date.slice(5, 10) : date.slice(11, 16);
        });
  const trendScaleLabels = [1, 0.75, 0.5, 0.25, 0].map((share) =>
    formatBytes(maxTrendValue * share),
  );

  const sourceRows = useMemo(
    () => [...sourceTypes].sort((left, right) => right.count - left.count),
    [sourceTypes],
  );
  const sourceTotal = inventorySummary?.dataSourceCount ?? 0;
  const categoryTotal = sourceRows.reduce((sum, source) => sum + source.count, 0);
  const activeSource =
    sourceRows.find((source) => source.key === activeSourceVariant) || sourceRows[0];
  const activeSourceShare =
    categoryTotal > 0 && activeSource ? (activeSource.count / categoryTotal) * 100 : 0;
  const activeSourceIndex = Math.max(
    sourceRows.findIndex((source) => source.key === activeSource?.key),
    0,
  );

  const businessSystemCount = inventorySummary?.businessSystemCount;
  const streamMetric = taskMetrics?.find((item) => item.key === 'STREAM');
  const streamInScope = !selectedTaskType || selectedTaskType === 'STREAM';
  const averageDelay =
    streamInScope && asNumber(streamMetric?.summary.totalTasks) > 0
      ? asNumber(streamMetric?.summary.avgRecordDelay)
      : null;

  const latestRecordsSpeed = visibleCharts
    .map(({ charts }) => {
      const points = charts.recordsSpeedTrend || [];
      if (points.length === 0) return null;
      const latest = [...points].sort((left, right) => left.date.localeCompare(right.date))[points.length - 1];
      return asNumber(latest.value);
    })
    .filter((value): value is number => value !== null);
  const currentRecordsSpeed =
    latestRecordsSpeed.length > 0
      ? latestRecordsSpeed.reduce((sum, value) => sum + value, 0)
      : null;

  /**
   * The headline figures are the accumulated assets the system runs on: they answer
   * "how much is registered", not "how much moved in the selected window". The task
   * flow of the window stays in the task statistics panel on the right.
   */
  const kpis = [
    {
      label: '已登记数据源',
      value: inventorySummary ? integerFormat.format(inventorySummary.dataSourceCount) : '—',
      unit: inventorySummary ? '个' : '',
      tone: 'cyan',
    },
    {
      label: '数据库',
      value: inventorySummary ? integerFormat.format(inventorySummary.databaseCount) : '—',
      unit: inventorySummary ? '个' : '',
      tone: 'green',
    },
    {
      label: 'Schema',
      value: inventorySummary ? integerFormat.format(inventorySummary.schemaCount) : '—',
      unit: inventorySummary ? '个' : '',
      tone: 'violet',
    },
    {
      label: '数据表',
      value: inventorySummary ? integerFormat.format(inventorySummary.tableCount) : '—',
      unit: inventorySummary ? '张' : '',
      tone: 'blue',
    },
    {
      label: '字段',
      value: inventorySummary ? integerFormat.format(inventorySummary.columnCount) : '—',
      unit: inventorySummary ? '个' : '',
      tone: 'pink',
    },
    {
      label: '已探查表',
      value: inventorySummary ? integerFormat.format(inventorySummary.profiledTableCount) : '—',
      unit: inventorySummary ? '张' : '',
      tone: 'amber',
    },
    {
      label: '已采集行数',
      value: inventorySummary ? integerFormat.format(inventorySummary.knownRowCount) : '—',
      unit: inventorySummary ? '行' : '',
      tone: 'teal',
    },
    {
      label: '接入业务系统',
      value: businessSystemCount === undefined ? '—' : integerFormat.format(businessSystemCount),
      unit: businessSystemCount === undefined ? '' : '个',
      tone: 'orange',
    },
  ];

  const statusItems = [
    { label: '成功', value: totals.success, tone: 'green' },
    { label: '运行中', value: totals.running, tone: 'cyan' },
    { label: '失败', value: totals.failed, tone: 'red' },
    { label: '已停止', value: totals.stopped, tone: 'violet' },
    ...(otherTasks > 0 ? [{ label: '其他', value: otherTasks, tone: 'amber' }] : []),
  ];
  const maxStatus = Math.max(1, ...statusItems.map((item) => item.value));

  const resourceItems = [
    {
      label: '已登记数据源',
      detail: '连接器',
      value: inventorySummary?.dataSourceCount,
      tone: 'cyan',
      y: 96,
      icon: <Database size={16} strokeWidth={1.8} />,
    },
    {
      label: '数据库',
      detail: '资源目录',
      value: inventorySummary?.databaseCount,
      tone: 'green',
      y: 178,
      icon: <Layers3 size={16} strokeWidth={1.8} />,
    },
    {
      label: 'Schema',
      detail: '元数据',
      value: inventorySummary?.schemaCount,
      tone: 'violet',
      y: 260,
      icon: <Network size={16} strokeWidth={1.8} />,
    },
    {
      label: '数据表',
      detail: '元数据',
      value: inventorySummary?.tableCount,
      tone: 'blue',
      y: 342,
      icon: <Archive size={16} strokeWidth={1.8} />,
    },
    {
      label: '字段',
      detail: '元数据',
      value: inventorySummary?.columnCount,
      tone: 'pink',
      y: 424,
      icon: <List size={16} strokeWidth={1.8} />,
    },
    {
      label: '已探查表',
      detail: '已采集行数 ' + integerFormat.format(inventorySummary?.knownRowCount || 0),
      value: inventorySummary?.profiledTableCount,
      tone: 'amber',
      y: 506,
      icon: <Search size={16} strokeWidth={1.8} />,
    },
  ];

  const rangeLabel = TIME_RANGES.find((range) => range.value === timeRange)?.label || '所选范围';
  const taskFilterOptions = [
    { key: null as TaskType | null, label: '全部' },
    ...TASK_TYPES.map((item) => ({ key: item.key, label: item.label.replace('引接', '') })),
  ];

  return (
    <div className="bi-dashboard" aria-busy={loading}>
      <div className="ambient ambient-one" />
      <div className="ambient ambient-two" />

      <main className="dashboard-grid">
        <aside className="rail left-rail">
          <section className={'panel source-panel' + (activeSourceTab === 'breakdown' ? ' is-breakdown' : '')}>
            <div className="panel-heading">
              <h2>数据源接入情况</h2>
              <Link className="dashboard-exit" to="/metrics" title="返回任务概览" aria-label="退出大屏并打开任务概览">
                <ArrowLeft size={15} />
              </Link>
            </div>
            <div className="segmented-tabs" role="tablist" aria-label="数据源视图">
              <button
                id="source-overview-tab"
                type="button"
                className={activeSourceTab === 'overview' ? 'active' : ''}
                role="tab"
                aria-controls="source-overview-panel"
                aria-selected={activeSourceTab === 'overview'}
                onClick={() => setActiveSourceTab('overview')}
              >
                接入总览
              </button>
              <button
                id="source-breakdown-tab"
                type="button"
                className={activeSourceTab === 'breakdown' ? 'active' : ''}
                role="tab"
                aria-controls="source-breakdown-panel"
                aria-selected={activeSourceTab === 'breakdown'}
                onClick={() => setActiveSourceTab('breakdown')}
              >
                分类统计
              </button>
            </div>

            {activeSourceTab === 'overview' ? (
              <div id="source-overview-panel" className="source-overview" role="tabpanel" aria-labelledby="source-overview-tab">
                <div className="source-total-row">
                  <div>
                    <span className="data-label">已登记数据源</span>
                    <strong className="source-total">
                      {inventorySummary ? integerFormat.format(sourceTotal) : '—'}
                      <span> 个</span>
                    </strong>
                  </div>
                  <div className="source-total-trend">
                    <b>{sourceRows.length}</b>
                    <small>种连接器类型</small>
                  </div>
                </div>
                <div className="source-grid">
                  {sourceRows.slice(0, 6).map((source, index) => (
                    <button
                      key={source.key}
                      type="button"
                      className={'source-tile ' + SOURCE_TONES[index % SOURCE_TONES.length]}
                      onClick={() => {
                        setActiveSourceVariant(source.key);
                        setActiveSourceTab('breakdown');
                      }}
                    >
                      <span className="tile-icon">{sourceIcon(source.name || source.key)}</span>
                      <span className="tile-copy">
                        <b>{source.name || source.key}</b>
                        <small>已登记连接器</small>
                      </span>
                      <span className="tile-count">
                        {integerFormat.format(source.count)}
                        <em> 个</em>
                      </span>
                    </button>
                  ))}
                  {sourceRows.length === 0 && (
                    <div className="source-empty">
                      {inventoryLoading ? '资源目录加载中…' : inventoryError || '暂无数据源类型'}
                    </div>
                  )}
                </div>
              </div>
            ) : (
              <div id="source-breakdown-panel" className="source-breakdown" role="tabpanel" aria-labelledby="source-breakdown-tab">
                <div className="breakdown-switcher" role="group" aria-label="数据源分类">
                  {sourceRows.map((source, index) => (
                    <button
                      key={source.key}
                      type="button"
                      className={
                        'variant-chip ' +
                        SOURCE_TONES[index % SOURCE_TONES.length] +
                        ((activeSource?.key === source.key) ? ' active' : '')
                      }
                      aria-pressed={activeSource?.key === source.key}
                      onClick={() => setActiveSourceVariant(source.key)}
                    >
                      {source.name || source.key}
                    </button>
                  ))}
                </div>
                <div className="breakdown-head">
                  <div
                    className="donut-chart"
                    style={{
                      '--donut-value': activeSourceShare + '%',
                      '--donut-color': 'var(--' + SOURCE_TONES[activeSourceIndex % SOURCE_TONES.length] + ')',
                    } as React.CSSProperties}
                  >
                    <div>
                      <b>{numberFormat.format(activeSourceShare)}%</b>
                      <span>数据源占比</span>
                    </div>
                  </div>
                  <div>
                    <span className="data-label">{activeSource?.name || activeSource?.key || '连接器'}</span>
                    <strong className="breakdown-total">
                      {activeSource ? integerFormat.format(activeSource.count) : '—'}
                      <small> 个</small>
                    </strong>
                    <p>来自数据源登记统计</p>
                  </div>
                </div>
                <div className="breakdown-list">
                  {sourceRows.slice(0, 8).map((source, index) => (
                    <div className="breakdown-row" key={source.key}>
                      <div className="breakdown-row-meta">
                        <span><i className={'legend-dot ' + SOURCE_TONES[index % SOURCE_TONES.length]} />{source.name || source.key}</span>
                        <b>{integerFormat.format(source.count)} 个</b>
                      </div>
                      <div className="progress-track">
                        <span
                          className={SOURCE_TONES[index % SOURCE_TONES.length]}
                          style={{ width: (categoryTotal > 0 ? (source.count / categoryTotal) * 100 : 0) + '%' }}
                        />
                      </div>
                      <small>占已登记数据源 {numberFormat.format(categoryTotal > 0 ? (source.count / categoryTotal) * 100 : 0)}%</small>
                    </div>
                  ))}
                  {sourceRows.length === 0 && <span className="source-empty">{inventoryError || '暂无分类数据'}</span>}
                </div>
              </div>
            )}
            <div className="panel-foot">
              <span><span className="status-dot online" />数据资产</span>
              <span>{inventorySummary ? integerFormat.format(inventorySummary.databaseCount) + ' 个数据库' : '等待更新'}</span>
              <span className="foot-update">{updatedAt ? '更新 ' + updatedAt : ''}</span>
            </div>
          </section>

          <section className="panel systems-panel">
            <div className="panel-heading compact">
              <h2>引接的系统情况</h2>
            </div>
            <div className="underlined-tabs" role="tablist" aria-label="系统视图">
              <button
                id="system-list-tab"
                type="button"
                className={activeSystemTab === 'systems' ? 'active' : ''}
                role="tab"
                aria-controls="system-list-panel"
                aria-selected={activeSystemTab === 'systems'}
                onClick={() => setActiveSystemTab('systems')}
              >
                业务系统
              </button>
              <button
                id="system-mode-tab"
                type="button"
                className={activeSystemTab === 'modes' ? 'active' : ''}
                role="tab"
                aria-controls="system-mode-panel"
                aria-selected={activeSystemTab === 'modes'}
                onClick={() => setActiveSystemTab('modes')}
              >
                任务类型
              </button>
            </div>

            {activeSystemTab === 'systems' ? (
              <div id="system-list-panel" className="system-view" role="tabpanel" aria-labelledby="system-list-tab">
                <div className="system-summary">
                  <div><span>业务系统</span><b>{inventorySummary ? integerFormat.format(inventorySummary.businessSystemCount) : '—'}</b></div>
                  <div><span>数据源</span><b>{inventorySummary ? integerFormat.format(inventorySummary.dataSourceCount) : '—'}</b></div>
                  <div><span>数据表</span><b>{inventorySummary ? integerFormat.format(inventorySummary.tableCount) : '—'}</b></div>
                </div>
                <div className="system-list">
                  {businessSystems.slice(0, 4).map((system, index) => (
                    <Link
                      to="/data-source"
                      key={system.key}
                      className={'system-row ' + SOURCE_TONES[index % SOURCE_TONES.length]}
                      title="打开数据源管理"
                    >
                      <span className="system-status" />
                      <span className="system-copy">
                        <b>{system.name || system.key}</b>
                        <small>关联数据源</small>
                      </span>
                      <span className="system-volume">
                        <b>{integerFormat.format(system.count)}</b>
                        <small>{sourceTotal > 0 ? numberFormat.format((system.count / sourceTotal) * 100) : 0}%</small>
                      </span>
                    </Link>
                  ))}
                  {businessSystems.length === 0 && <div className="system-empty">{inventoryError || '暂无业务系统数据'}</div>}
                </div>
              </div>
            ) : (
              <div id="system-mode-panel" className="mode-view" role="tabpanel" aria-labelledby="system-mode-tab">
                <div className="mode-total">
                  <span className="data-label">{rangeLabel}任务执行</span>
                  <strong>{taskMetrics ? integerFormat.format(totals.tasks) : '—'}<span> 项</span></strong>
                  <span className="success-tag"><CheckCircle2 size={13} />运行中 {taskMetrics ? integerFormat.format(totals.running) : '—'} 项</span>
                </div>
                <div className="mode-list">
                  {TASK_TYPES.map((taskType, index) => {
                    const metric = taskMetrics?.find((item) => item.key === taskType.key);
                    const count = asNumber(metric?.summary.totalTasks);
                    return (
                      <button
                        key={taskType.key}
                        type="button"
                        className="mode-row"
                        onClick={() => setSelectedTaskType(selectedTaskType === taskType.key ? null : taskType.key)}
                      >
                        <div>
                          <span className={'legend-dot ' + SOURCE_TONES[index % SOURCE_TONES.length]} />
                          <b>{taskType.label}</b>
                          <small>{taskMetrics ? integerFormat.format(count) + ' 项' : '—'}</small>
                        </div>
                        <div className="progress-track">
                          <span
                            className={SOURCE_TONES[index % SOURCE_TONES.length]}
                            style={{ width: (taskMetrics ? (count / maxTypeTasks) * 100 : 0) + '%' }}
                          />
                        </div>
                        <strong>{metric ? integerFormat.format(metric.summary.runningTasks) : '—'}</strong>
                      </button>
                    );
                  })}
                </div>
                <div className="mode-callout">
                  <Gauge size={16} strokeWidth={1.8} />
                  <span>任务概览指标按任务类型与统计时间汇总。</span>
                </div>
              </div>
            )}
          </section>

          <section className="panel trend-panel">
            <div className="panel-heading compact">
              <h2>数据量与新增趋势</h2>
            </div>
            <div className="trend-toolbar">
              <div>
                <span className="data-label">{rangeLabel}数据量</span>
                <strong>{taskMetrics ? bytesDisplay.value + ' ' + bytesDisplay.unit : '—'}</strong>
                <span className="trend-records">
                  {taskMetrics ? recordsDisplay.value + ' ' + recordsDisplay.unit : '等待指标加载'}
                </span>
              </div>
              <div className="range-tabs" role="group" aria-label="趋势时间范围">
                {TIME_RANGES.map((range) => (
                  <button
                    key={range.value}
                    type="button"
                    className={timeRange === range.value ? 'active' : ''}
                    aria-pressed={timeRange === range.value}
                    aria-label={range.shortLabel + '，' + range.label}
                    onClick={() => setTimeRange(range.value)}
                  >
                    {range.shortLabel}
                  </button>
                ))}
              </div>
            </div>
            <div className="trend-legend">
              <span><i className="legend-line volume" />累计数据量</span>
              <span><i className="legend-line added" />区间新增量</span>
            </div>
            <div className="trend-chart-wrap">
              <svg className="trend-chart" viewBox="0 0 640 220" preserveAspectRatio="none" aria-label="引接数据量趋势图" role="img">
                <defs>
                  <linearGradient id="trend-fill" x1="0" x2="0" y1="0" y2="1">
                    <stop offset="0" stopColor="#4DD2FF" stopOpacity=".28" />
                    <stop offset="1" stopColor="#4DD2FF" stopOpacity="0" />
                  </linearGradient>
                </defs>
                {[34, 76, 118, 160, 198].map((line) => (
                  <line key={line} x1="16" y1={line} x2="624" y2={line} className="chart-grid-line" />
                ))}
                {trendPoints.length > 0 && (
                  <>
                    <path d={trendAreaPath} fill="url(#trend-fill)" />
                    <path d={cumulativePath} className="chart-line volume-line" />
                    <path d={addedPath} className="chart-line added-line" />
                    <circle cx={trendPoints.length === 1 ? '320' : '624'} cy={trendLastY} r="4.5" className="chart-last-dot" />
                  </>
                )}
              </svg>
              <div className="chart-y-labels">
                {trendScaleLabels.map((label, index) => (
                  <span key={index}>{label.value} {label.unit}</span>
                ))}
              </div>
              <div className="chart-x-labels">
                {trendLabels.map((label, index) => <span key={index}>{label}</span>)}
              </div>
              {trendPoints.length === 0 && (
                <div className="trend-empty">{trendError || '当前统计范围暂无数据量趋势'}</div>
              )}
            </div>
          </section>
        </aside>

        <section className="center-stage">
          <div className="metrics-grid" aria-label="引接指标汇总">
            {kpis.map((metric) => (
              <article key={metric.label} className={'metric-card ' + metric.tone}>
                <span className="metric-name">{metric.label}</span>
                <span className="metric-value">
                  {metric.value}
                  <small>{metric.unit}</small>
                </span>
              </article>
            ))}
          </div>

          <section className="panel topology-panel">
            <div className="topology-heading">
              <div className="panel-heading compact">
                <h2>采集引接链路拓扑</h2>
              </div>
              <div className="topology-controls">
                <span className="control-caption"><Filter size={13} />任务类型</span>
                <div className="filter-tabs" role="group" aria-label="任务类型筛选">
                  {taskFilterOptions.map((filter) => (
                    <button
                      key={filter.key || 'ALL'}
                      type="button"
                      className={selectedTaskType === filter.key ? 'active' : ''}
                      aria-pressed={selectedTaskType === filter.key}
                      onClick={() => setSelectedTaskType(filter.key)}
                    >
                      {filter.label}
                    </button>
                  ))}
                </div>
                <button
                  type="button"
                  className="dashboard-refresh"
                  onClick={() => void loadDashboard()}
                  aria-label="刷新引接态势"
                  title="刷新"
                  disabled={loading}
                >
                  <RefreshCw size={14} className={loading ? 'is-spinning' : ''} />
                </button>
              </div>
            </div>
            <div className="topology-legend">
              <span><i className="legend-dot cyan" />数据源类型</span>
              <span><i className="legend-dot online" />任务指标</span>
              <span><i className="legend-dot purple" />数据资产</span>
              <span className="legend-help"><Eye size={13} />选中任务查看汇总指标</span>
              <span className="topology-count">{sourceRows.length} 种数据源 · {visibleMetrics.length} 类任务</span>
            </div>

            <div className="topology-stage">
              <svg className="topology-svg" viewBox="0 0 1000 560" preserveAspectRatio="none" aria-hidden="true">
                <defs>
                  <pattern id="topology-grid" width="40" height="40" patternUnits="userSpaceOnUse">
                    <path d="M 40 0 L 0 0 0 40" fill="none" stroke="#2187A8" strokeOpacity=".13" strokeWidth="1" />
                  </pattern>
                  <filter id="topology-glow" x="-50%" y="-50%" width="200%" height="200%">
                    <feGaussianBlur stdDeviation="4" result="blur" />
                    <feMerge><feMergeNode in="blur" /><feMergeNode in="SourceGraphic" /></feMerge>
                  </filter>
                  <marker id="arrow-online" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
                    <path d="M 0 0 L 10 5 L 0 10 z" fill="#4DD2FF" />
                  </marker>
                  <marker id="arrow-offline" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
                    <path d="M 0 0 L 10 5 L 0 10 z" fill="#D9B621" />
                  </marker>
                </defs>
                <rect width="1000" height="560" fill="url(#topology-grid)" />
                <line x1="220" y1="34" x2="220" y2="526" className="topology-divider" />
                <line x1="562" y1="34" x2="562" y2="526" className="topology-divider" />
                <line x1="755" y1="34" x2="755" y2="526" className="topology-divider" />
                <text x="35" y="33" className="topology-column-label">数据源类型</text>
                <text x="320" y="33" className="topology-column-label">任务类型</text>
                <text x="602" y="33" className="topology-column-label">SeaTunnel Engine</text>
                <text x="812" y="33" className="topology-column-label">数据资产</text>
                {sourceRows.slice(0, 6).map((source, index) => {
                  const y = 98 + index * 82;
                  return (
                    <g className="edge-group online" key={'source-edge-' + source.key}>
                      <path d={'M 148 ' + y + ' C 250 ' + y + ', 385 278, 555 278'} className="edge-hit" />
                      <path d={'M 148 ' + y + ' C 250 ' + y + ', 385 278, 555 278'} className="edge-line online" markerEnd="url(#arrow-online)" />
                      <path d={'M 148 ' + y + ' C 250 ' + y + ', 385 278, 555 278'} className="edge-flow online" />
                    </g>
                  );
                })}
                {visibleMetrics.map((metric) => {
                  const taskIndex = TASK_TYPES.findIndex((item) => item.key === metric.key);
                  const y = 154 + taskIndex * 104;
                  const selected = selectedTaskType === metric.key;
                  return (
                    <g className={'edge-group online' + (selected ? ' hovered' : '')} key={'task-edge-' + metric.key}>
                      <path d={'M 510 ' + y + ' C 544 ' + y + ', 545 278, 568 278'} className="edge-hit" />
                      <path
                        d={'M 510 ' + y + ' C 544 ' + y + ', 545 278, 568 278'}
                        className="edge-line online"
                        style={{ stroke: 'var(--' + metric.tone + ')' }}
                        markerEnd="url(#arrow-online)"
                        filter={selected ? 'url(#topology-glow)' : undefined}
                      />
                      <path
                        d={'M 510 ' + y + ' C 544 ' + y + ', 545 278, 568 278'}
                        className="edge-flow online"
                        style={{ stroke: 'var(--' + metric.tone + ')' }}
                      />
                    </g>
                  );
                })}
                {resourceItems.map((resource) => (
                  <g className="edge-group online" key={'resource-edge-' + resource.label}>
                    <path d={'M 684 278 C 750 278, 784 ' + resource.y + ', 846 ' + resource.y} className="edge-hit" />
                    <path d={'M 684 278 C 750 278, 784 ' + resource.y + ', 846 ' + resource.y} className="edge-line online" markerEnd="url(#arrow-online)" />
                    <path d={'M 684 278 C 750 278, 784 ' + resource.y + ', 846 ' + resource.y} className="edge-flow online" />
                  </g>
                ))}
                <circle cx="625" cy="278" r="82" className="agent-halo" />
                <circle cx="625" cy="278" r="59" className="agent-ring" />
              </svg>

              {sourceRows.slice(0, 6).map((source, index) => {
                const y = 98 + index * 82;
                const tone = SOURCE_TONES[index % SOURCE_TONES.length];
                return (
                  <button
                    key={source.key}
                    type="button"
                    className={'topology-node source-node ' + tone}
                    style={{ left: '11.8%', top: (y / 5.6) + '%' }}
                    onClick={() => {
                      setActiveSourceVariant(source.key);
                      setActiveSourceTab('breakdown');
                    }}
                    aria-label={source.name + ' 数据源 ' + source.count + ' 个'}
                  >
                    <span className="node-icon">{sourceIcon(source.name || source.key)}</span>
                    <span className="node-copy">
                      <b>{source.name || source.key}</b>
                      <small>数据源连接器</small>
                    </span>
                    <span className="node-state">{integerFormat.format(source.count)} 个</span>
                  </button>
                );
              })}

              {visibleMetrics.map((metric) => {
                const taskIndex = TASK_TYPES.findIndex((item) => item.key === metric.key);
                const y = 154 + taskIndex * 104;
                const selected = hoveredTaskType === metric.key || selectedTaskType === metric.key;
                const running = asNumber(metric.summary.runningTasks);
                return (
                  <button
                    key={metric.key}
                    type="button"
                    className={'topology-node task-node ' + metric.tone + (selected ? ' selected' : '')}
                    aria-pressed={selectedTaskType === metric.key}
                    style={{ left: '43%', top: (y / 5.6) + '%' }}
                    onMouseEnter={() => setHoveredTaskType(metric.key)}
                    onMouseLeave={() => setHoveredTaskType(null)}
                    onFocus={() => setHoveredTaskType(metric.key)}
                    onBlur={() => setHoveredTaskType(null)}
                    onClick={() => setSelectedTaskType(selectedTaskType === metric.key ? null : metric.key)}
                  >
                    <span className={'task-status ' + (running > 0 ? 'online' : 'muted')} />
                    <span className="task-copy">
                      <b>{metric.label}</b>
                      <small>{integerFormat.format(metric.summary.totalTasks)} 项 · {integerFormat.format(running)} 项运行中</small>
                    </span>
                    <ChevronDown size={14} strokeWidth={1.8} className="task-chevron" />
                  </button>
                );
              })}

              <button
                type="button"
                className={'agent-node' + (hoveredTaskType || selectedTaskType ? ' active' : '')}
                onClick={() => setSelectedTaskType(null)}
                title="清除任务类型筛选"
              >
                <span className="agent-icon"><Cpu size={22} strokeWidth={1.55} /></span>
                <span className="agent-name">SeaTunnel</span>
                <span className="agent-meta">引接任务引擎</span>
              </button>

              {resourceItems.map((resource) => (
                <div
                  key={resource.label}
                  className={'topology-node resource-node ' + resource.tone}
                  style={{ left: '87.8%', top: (resource.y / 5.6) + '%' }}
                >
                  <span className="resource-icon">{resource.icon}</span>
                  <span className="resource-copy">
                    <b>{resource.label}</b>
                    <small>
                      {resource.value === undefined
                        ? '等待目录数据'
                        : integerFormat.format(resource.value) + ' 项 · ' + resource.detail}
                    </small>
                  </span>
                </div>
              ))}

              {hoveredTaskType && taskMetrics && (() => {
                const metric = taskMetrics.find((item) => item.key === hoveredTaskType);
                if (!metric) return null;
                const metricRecords = formatRecords(toRecords(metric.summary));
                const metricBytes = formatBytes(toBytes(metric.summary));
                return (
                  <div className={'topology-detail ' + metric.tone}>
                    <div className="detail-topline">
                      <span><span className="status-dot online" />任务统计 · {rangeLabel}</span>
                      <button
                        type="button"
                        title="筛选此任务类型"
                        aria-label="筛选此任务类型"
                        onClick={() => setSelectedTaskType(metric.key)}
                      >
                        <CircleDot size={14} />
                      </button>
                    </div>
                    <div className="detail-title"><strong>{metric.label}</strong><span>{metric.detail}</span></div>
                    <p className="detail-route">按已上报的任务执行指标聚合</p>
                    <div className="detail-metrics">
                      <div><span>任务数</span><b>{integerFormat.format(metric.summary.totalTasks)} 项</b></div>
                      <div><span>引接条数</span><b>{metricRecords.value} {metricRecords.unit}</b></div>
                      <div><span>数据量</span><b>{metricBytes.value} {metricBytes.unit}</b></div>
                      <div><span>运行中</span><b>{integerFormat.format(metric.summary.runningTasks)} 项</b></div>
                    </div>
                    <div className="detail-foot">
                      <span><Clock3 size={12} />{rangeLabel}</span>
                      <span>成功 <b>{integerFormat.format(metric.summary.successTasks)}</b></span>
                    </div>
                  </div>
                );
              })()}

              {taskMetrics && visibleMetrics.length === 0 && (
                <div className="topology-empty">
                  <CircleAlert size={16} />
                  <span>当前筛选没有任务指标</span>
                  <button type="button" onClick={() => setSelectedTaskType(null)}>显示全部</button>
                </div>
              )}
              <div className="topology-baseline">
                <span>SeaTunnel Web</span><i /><span>任务指标与元数据资产</span>
              </div>
              <div className="topology-edge-actions sr-only" aria-label="任务类型筛选">
                {TASK_TYPES.map((taskType) => (
                  <button key={taskType.key} type="button" onClick={() => setSelectedTaskType(taskType.key)}>
                    {taskType.label}
                  </button>
                ))}
              </div>
              {loading && !taskMetrics && (
                <div className="topology-loading"><Spin size="large" /><span>正在读取引接指标</span></div>
              )}
            </div>
            <div className="topology-baseline-note">
              <span>链路按连接器、任务类型和数据资产汇总展示，不代表单个任务的精确映射</span>
              <span>{selectedLabel} · {rangeLabel}</span>
            </div>
          </section>
        </section>

        <aside className="rail right-rail">
          <section className="panel inspect-panel">
            <div className="panel-heading compact">
              <h2>任务执行统计</h2>
              <span className="live-counter"><span className="live-dot" />{taskMetrics ? integerFormat.format(totals.tasks) + ' 项' : '加载中'}</span>
            </div>
            <div className="inspect-summary">
              <div>
                <span className="data-label">{rangeLabel}任务</span>
                <strong>{taskMetrics ? integerFormat.format(totals.tasks) : '—'}</strong>
                <small>运行中 <b>{taskMetrics ? integerFormat.format(totals.running) : '—'}</b></small>
              </div>
              <div className="quality-badge">
                <span>已结束任务成功率</span>
                <b>{successRate === null ? '—' : numberFormat.format(successRate)}<em>{successRate === null ? '' : '%'}</em></b>
                <small><ShieldCheck size={12} />{successRate === null ? '暂无已结束任务' : '按任务状态计算'}</small>
              </div>
            </div>
            <div className="inspect-metrics">
              <div>
                <span>引接条数</span>
                <b>{taskMetrics ? `${recordsDisplay.value} ${recordsDisplay.unit}` : '—'}</b>
              </div>
              <div>
                <span>引接数据量</span>
                <b>{taskMetrics ? `${bytesDisplay.value} ${bytesDisplay.unit}` : '—'}</b>
              </div>
              <div>
                <span>最新记录速率</span>
                <b>{currentRecordsSpeed === null ? '—' : `${numberFormat.format(currentRecordsSpeed)} 条/s`}</b>
              </div>
              <div>
                <span>实时平均延迟</span>
                <b>{averageDelay === null ? '—' : `${integerFormat.format(averageDelay)} ms`}</b>
              </div>
            </div>
            <div className="inspect-chart">
              {statusItems.slice(0, 4).map((item) => (
                <div className="inspect-bar-col" key={item.label}>
                  <div className="inspect-bar-value">{taskMetrics ? integerFormat.format(item.value) : '—'}</div>
                  <div className="inspect-bar-track">
                    <span
                      className={item.tone}
                      style={{ height: (taskMetrics ? (item.value / maxStatus) * 100 : 0) + '%' }}
                    />
                  </div>
                  <span>{item.label}</span>
                </div>
              ))}
            </div>
            <div className="inspect-foot">
              <span><i className="legend-dot cyan" />运行中 {taskMetrics ? integerFormat.format(totals.running) : '—'}</span>
              <span><i className="legend-dot red" />失败 {taskMetrics ? integerFormat.format(totals.failed) : '—'}</span>
              <Link to="/metrics">查看任务概览 <List size={12} /></Link>
            </div>
          </section>

          <section className="panel live-panel">
            <div className="panel-heading compact">
              <h2>引接任务类型</h2>
              <span className="live-counter"><span className="live-dot" />{visibleMetrics.length} 类</span>
            </div>
            <div className="live-task-list">
              {visibleMetrics.map((metric) => {
                const running = asNumber(metric.summary.runningTasks);
                const failed = asNumber(metric.summary.failedTasks);
                const tone = running > 0 ? 'green' : failed > 0 ? 'red' : 'muted';
                const state = running > 0 ? integerFormat.format(running) + ' 项运行中' : failed > 0 ? integerFormat.format(failed) + ' 项失败' : '暂无运行中';
                return (
                  <button
                    key={metric.key}
                    type="button"
                    className="live-task"
                    onClick={() => setSelectedTaskType(selectedTaskType === metric.key ? null : metric.key)}
                  >
                    <span className={'task-status ' + tone} />
                    <span className="live-task-name">{metric.label}</span>
                    <span className="live-task-rate">{integerFormat.format(metric.summary.totalTasks)} 项</span>
                    <b className={'live-task-state ' + tone}>{state}</b>
                  </button>
                );
              })}
              {visibleMetrics.length === 0 && <div className="live-empty">{taskMetrics ? '当前筛选没有任务' : '等待任务指标'}</div>}
            </div>
            <div className="list-scroll-hint"><span />点击任务类型可筛选大屏指标</div>
          </section>

          <section className="panel report-panel">
            <div className="panel-heading compact">
              <h2>数据资源探查情况</h2>
            </div>
            <div className="report-body">
              <div
                className="report-ring"
                style={{
                  background:
                    'conic-gradient(var(--green) 0 ' +
                    (profileCoverage?.tableCoveragePercent || 0) +
                    '%, rgba(20, 98, 123, 0.45) ' +
                    (profileCoverage?.tableCoveragePercent || 0) +
                    '% 100%)',
                }}
              >
                <div>
                  <b>{profileCoverage ? numberFormat.format(profileCoverage.tableCoveragePercent) : '—'}<em>{profileCoverage ? '%' : ''}</em></b>
                  <span>表探查覆盖</span>
                </div>
              </div>
              <div className="report-kpis">
                <div><span>已探查表</span><b>{inventorySummary ? integerFormat.format(inventorySummary.profiledTableCount) : '—'}</b></div>
                <div><span>已采集行数</span><b>{inventorySummary ? integerFormat.format(inventorySummary.knownRowCount) : '—'}</b></div>
              </div>
            </div>
            <div className="report-bars">
              <div className="report-bar-row">
                <span>表探查</span>
                <div className="progress-track">
                  <span className="green" style={{ width: (profileCoverage?.tableCoveragePercent || 0) + '%' }} />
                </div>
                <b>{profileCoverage ? numberFormat.format(profileCoverage.tableCoveragePercent) : '—'}{profileCoverage ? '%' : ''}</b>
              </div>
              <div className="report-bar-row">
                <span>库探查</span>
                <div className="progress-track">
                  <span
                    className="cyan"
                    style={{
                      width:
                        profileCoverage && profileCoverage.databaseCount > 0
                          ? (profileCoverage.profiledDatabaseCount / profileCoverage.databaseCount) * 100 + '%'
                          : '0%',
                    }}
                  />
                </div>
                <b>
                  {profileCoverage && profileCoverage.databaseCount > 0
                    ? numberFormat.format((profileCoverage.profiledDatabaseCount / profileCoverage.databaseCount) * 100)
                    : '—'}
                  {profileCoverage && profileCoverage.databaseCount > 0 ? '%' : ''}
                </b>
              </div>
            </div>
            <div className="report-note">
              <CircleAlert size={13} />
              <span>
                {profileCoverage
                  ? integerFormat.format(profileCoverage.knownRowCount) + ' 行 · ' + formatBytes(profileCoverage.knownSizeInByte).value + ' ' + formatBytes(profileCoverage.knownSizeInByte).unit
                  : inventoryError || '等待资源目录指标'}
              </span>
              <Link to="/data-source">查看 <ChevronDown size={12} /></Link>
            </div>
          </section>
        </aside>
      </main>

      {error && (
        <div className="dashboard-error" role="alert">
          <span>{error}</span>
          <button type="button" onClick={() => void loadDashboard()}>重试</button>
        </div>
      )}
      {inventoryError && inventorySummary && (
        <div className="dashboard-note" role="status">{inventoryError}</div>
      )}
      <div className="sr-only" aria-live="polite">
        {updatedAt ? '指标已于 ' + updatedAt + ' 更新' : ''}
      </div>
    </div>
  );
};

export default IngestionSituationPage;
