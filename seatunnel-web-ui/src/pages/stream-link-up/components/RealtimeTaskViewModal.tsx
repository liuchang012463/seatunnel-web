import HttpUtils from "@/utils/HttpUtils";
import {
  formatMetric,
  getThroughputMegabytesPerSecond,
  isTerminalThroughputStatus,
  toMegabytes,
} from "@/pages/common/utils/throughput";
import { Button, Card, Empty, Modal, Select, Spin, Table, Tag, message } from "antd";
import ReactECharts from "echarts-for-react";
import { Activity, Clock3, Gauge, RefreshCw, Zap } from "lucide-react";
import React, {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from "react";
import CountUp from "react-countup";
import TaskStatus from "./TaskStatus";
import { ST_CHART_ACCENT, ST_CHART_ACCENT_STRONG } from "@/utils/echarts-theme-st";

type RealtimeGrafanaLightModalRef = {
  onOpen: (visible: boolean, record?: Partial<JobInstanceVO>) => void;
};

type RealtimeGrafanaLightModalProps = {
  onClose?: () => void;
};

type JobInstanceVO = {
  id?: number | string;
  jobDefinitionId?: number | string;
  clientId?: number | string;
  runMode?: string;
  jobStatus?: string;
  triggerSource?: string;
  instanceId?: number;
  retryCount?: number;
  engineJobId?: number | string;
  runtimeConfig?: string;
  logPath?: string;
  errorMessage?: string;
  submitTime?: string;
  startTime?: string;
  endTime?: string;
  createTime?: string;
  updateTime?: string;

  jobName?: string;
  jobDesc?: string;
  definitionMode?: string;
  jobType?: string;
  definitionClientId?: number | string;
  parallelism?: number;
  jobVersion?: number;
  definitionStatus?: string;
  sourceType?: string;
  sinkType?: string;
  sourceTable?: string;
  sinkTable?: string;
};

type StreamingJobMetricsCurrentVO = {
  jobInstanceId?: number | string;
  jobDefinitionId?: number | string;
  engineJobId?: number | string;
  clientId?: number | string;
  jobStatus?: string;

  readRowCount?: number;
  writeRowCount?: number;
  readQps?: number;
  writeQps?: number;
  readBytes?: number;
  writeBytes?: number;
  readBps?: number;
  writeBps?: number;
  intermediateQueueSize?: number;
  lagCount?: number;
  recordDelay?: number;
  pipelineCount?: number;
  tableCount?: number;

  lastCollectTimeMs?: number;
  lastCollectTime?: string;
};

type StreamingJobMetricsPointVO = {
  collectTimeMs?: number;
  collectTime?: string;
  pipelineId?: number;

  readRowCount?: number;
  writeRowCount?: number;
  readQps?: number;
  writeQps?: number;
  readBytes?: number;
  writeBytes?: number;
  readBps?: number;
  writeBps?: number;
  intermediateQueueSize?: number;
  lagCount?: number;
  recordDelay?: number;
};

type JobTableMetricsVO = {
  id?: number | string;
  jobInstanceId?: number | string;
  jobDefinitionId?: number | string;
  pipelineId?: number;
  sourceTable?: string;
  sinkTable?: string;

  readRowCount?: number;
  writeRowCount?: number;
  readQps?: number;
  writeQps?: number;
  readBytes?: number;
  writeBytes?: number;
  readBps?: number;
  writeBps?: number;

  /**
   * 后端派生字段：
   * rowDiff = readRowCount - writeRowCount
   */
  rowDiff?: number;

  status?: string;
  errorMsg?: string;
  createTime?: string;
  updateTime?: string;
};

type StreamingInstanceMetricsDashboardVO = {
  instance?: JobInstanceVO;
  current?: StreamingJobMetricsCurrentVO;
  trends?: StreamingJobMetricsPointVO[];
  tableMetrics?: JobTableMetricsVO[];
  topRowDiffTables?: JobTableMetricsVO[];

  /**
   * 兼容你如果后端暂时还没改字段名，仍然返回 topLagTables 的情况。
   */
  topLagTables?: JobTableMetricsVO[];
};

type ApiResult<T> = {
  code?: number;
  msg?: string;
  data?: T;
  success?: boolean;
};

const REFRESH_INTERVAL = 5000;
const RealtimeGrafanaLightModal = forwardRef<
  RealtimeGrafanaLightModalRef,
  RealtimeGrafanaLightModalProps
>(({ onClose }, ref) => {
  const [open, setOpen] = useState(false);
  const [range, setRange] = useState("15m");
  const [record, setRecord] = useState<Partial<JobInstanceVO> | null>(null);
  const [dashboard, setDashboard] =
    useState<StreamingInstanceMetricsDashboardVO | null>(null);
  const [loading, setLoading] = useState(false);
  const timerRef = useRef<number | null>(null);

  const instanceId = useMemo(() => {
    return record?.instanceId;
  }, [record?.instanceId]);

  const fetchDashboard = useCallback(
    async (silent = false) => {
      if (!instanceId) {
        return;
      }

      if (!silent) {
        setLoading(true);
      }

      try {
        const response = await HttpUtils.get<StreamingInstanceMetricsDashboardVO>(
          `/api/v1/job/streaming-instance/${instanceId}/metrics-dashboard?range=${range}`
        );

        setDashboard(response?.data ?? null);
      } catch (error) {
        console.error("Fetch streaming metrics dashboard failed", { instanceId, range }, error);
        if (!silent) {
          message.error("获取实时监控数据失败");
        }
      } finally {
        if (!silent) {
          setLoading(false);
        }
      }
    },
    [instanceId, range]
  );

  const onOpen = (visible: boolean, item?: Partial<JobInstanceVO>) => {
    setOpen(visible);
    setRecord(item || null);

    if (!visible) {
      setDashboard(null);
    }
  };

  const handleClose = () => {
    setOpen(false);
    setRecord(null);
    setDashboard(null);

    if (timerRef.current) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }

    onClose?.();
  };

  useImperativeHandle(ref, () => ({
    onOpen,
  }));

  useEffect(() => {
    if (!open || !instanceId) {
      return;
    }

    fetchDashboard(false);

    if (timerRef.current) {
      window.clearInterval(timerRef.current);
    }

    timerRef.current = window.setInterval(() => {
      fetchDashboard(true);
    }, REFRESH_INTERVAL);

    return () => {
      if (timerRef.current) {
        window.clearInterval(timerRef.current);
        timerRef.current = null;
      }
    };
  }, [open, instanceId, range, fetchDashboard]);

  const instance = dashboard?.instance || record || {};
  const current = dashboard?.current || {};
  const trends = dashboard?.trends || [];
  const tableMetrics = dashboard?.tableMetrics || [];
  const topRowDiffTables =
    dashboard?.topRowDiffTables || dashboard?.topLagTables || [];

  const viewData = useMemo(() => {
    const status = current.jobStatus || instance.jobStatus || "UNKNOWN";
    const terminal = isTerminalThroughputStatus(status);
    const runtimeMillis =
      Date.parse(instance.endTime || "") - Date.parse(instance.startTime || instance.submitTime || "");

    return {
      jobName: instance.jobName || "实时任务 (Streaming Job)",
      jobInstanceId: instance.id || current.jobInstanceId || "-",
      engineJobId: instance.engineJobId || current.engineJobId || "-",
      clientId: instance.clientId || current.clientId || "-",
      status,
      startTime: formatDateTime(instance.startTime),
      lastCollectTime: formatDateTime(current.lastCollectTime),

      readQps: formatMetric(current.readQps),
      writeQps: formatMetric(current.writeQps),
      readMegabytes: current.readBytes == null ? undefined : toMegabytes(current.readBytes),
      writeMegabytes: current.writeBytes == null ? undefined : toMegabytes(current.writeBytes),
      readMBps: getThroughputMegabytesPerSecond(current.readBps, current.readBytes, runtimeMillis, terminal),
      writeMBps: getThroughputMegabytesPerSecond(current.writeBps, current.writeBytes, runtimeMillis, terminal),
      terminal,
      lagCount: toNumber(current.lagCount),
      queueSize: toNumber(current.intermediateQueueSize),
      recordDelay: toNumber(current.recordDelay),

      readRows: toNumber(current.readRowCount),
      writeRows: toNumber(current.writeRowCount),
      tableCount: toNumber(current.tableCount || tableMetrics.length),
      pipelineCount: toNumber(current.pipelineCount),

      source: instance.sourceTable || instance.sourceType || "-",
      sink: instance.sinkTable || instance.sinkType || "-",
      syncMode: instance.definitionMode || instance.jobType || "STREAMING",
      runningDuration: calcRunningDuration(
        instance.startTime,
        instance.endTime
      ),
    };
  }, [instance, current, tableMetrics.length]);

  const throughputOption = useMemo(() => {
    const xAxis = trends.map((item) =>
      formatChartTime(item.collectTime, item.collectTimeMs)
    );

    return buildLightLineOption({
      unit: "行/秒 (rows/s)",
      xAxis,
      series: [
        {
          name: "读取 QPS (Read QPS)",
          color: ST_CHART_ACCENT,
          data: trends.map((item) => toNumber(item.readQps)),
        },
        {
          name: "写入 QPS (Write QPS)",
          color: ST_CHART_ACCENT_STRONG,
          dashed: true,
          data: trends.map((item) => toNumber(item.writeQps)),
        },
      ],
      formatter: (value) => formatMetric(value),
      isRate: true,
    });
  }, [trends]);

  const rowsOption = useMemo(() => {
    const xAxis = trends.map((item) =>
      formatChartTime(item.collectTime, item.collectTimeMs)
    );

    return buildLightLineOption({
      unit: "行 (rows)",
      xAxis,
      series: [
        {
          name: "读取行数 (Read Rows)",
          color: ST_CHART_ACCENT,
          data: trends.map((item) => toNumber(item.readRowCount)),
        },
        {
          name: "写入行数 (Write Rows)",
          color: ST_CHART_ACCENT_STRONG,
          dashed: true,
          data: trends.map((item) => toNumber(item.writeRowCount)),
        },
      ],
      formatter: formatShortNumber,
    });
  }, [trends]);

  const rowDiffBarOption = useMemo(() => {
    const rows = topRowDiffTables.length > 0 ? topRowDiffTables : tableMetrics;

    const finalRows = rows
      .map((item) => ({
        ...item,
        rowDiff: calcRowDiff(item),
      }))
      .sort((a, b) => toNumber(b.rowDiff) - toNumber(a.rowDiff))
      .slice(0, 5);

    return buildRowDiffBarOption(finalRows);
  }, [topRowDiffTables, tableMetrics]);

  return (
    <Modal
      open={open}
      footer={null}
      title={
        <header className="flex shrink-0 items-center justify-between border-b border-[var(--st-color-border)] bg-[var(--st-color-header)] py-4 pl-5 pr-14">
          <div className="flex min-w-0 items-center gap-4">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-[var(--st-color-line-strong)] bg-[var(--st-color-bg-hover)] text-[var(--st-color-accent)]">
              <Gauge className="h-5 w-5" />
            </div>

            <div className="min-w-0">
              <div className="flex items-center gap-3">
                <h1 className="m-0 truncate text-lg font-bold text-[var(--st-color-text-primary)]">
                  {viewData.jobName}
                </h1>

                <TaskStatus status={viewData.status} />
              </div>

              <div className="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-base text-[var(--st-color-text-secondary)]">
                <span>实例 (Instance)：{viewData.jobInstanceId}</span>
                <span>引擎 (Engine)：{viewData.engineJobId}</span>
                <span>模式 (Mode)：{viewData.syncMode}</span>
                <span>开始时间 (Start)：{viewData.startTime}</span>
              </div>
            </div>
          </div>

          <div className="flex shrink-0 items-center gap-3">
            <Select
              value={range}
              onChange={setRange}
              className="w-[224px]"
              aria-label="统计时间范围"
              options={[
                { label: "最近 15 分钟 (Last 15 min)", value: "15m" },
                { label: "最近 1 小时 (Last 1 hour)", value: "1h" },
                { label: "最近 6 小时 (Last 6 hours)", value: "6h" },
                { label: "最近 24 小时 (Last 24 hours)", value: "24h" },
              ]}
            />

            <Button
              icon={<RefreshCw size={16} />}
              onClick={() => fetchDashboard(false)}
              loading={loading}
            >
              5 秒刷新
            </Button>
          </div>
        </header>
      }
      destroyOnHidden
      maskClosable={false}
      centered={false}
      onCancel={handleClose}
      width="98vw"
      style={{ top: 16 }}
      styles={{
        header: { flexShrink: 0, marginBottom: 0, padding: 0 },
        content: {
          padding: 0,
          maxHeight: "calc(100vh - 32px)",
          display: "flex",
          flexDirection: "column",
          overflow: "hidden",
        },
        body: {
          padding: 0,
          minHeight: 0,
          overflow: "auto",
        },
      }}
    >
      <main className="p-5">
        <Spin spinning={loading && !dashboard}>
          {!instanceId ? (
            <div className="flex h-[520px] items-center justify-center rounded-xl border border-[var(--st-color-border)] bg-[var(--st-color-bg-elevated)]">
              <Empty description="缺少 instanceId，无法加载实时监控数据" />
            </div>
          ) : (
            <div className="grid grid-cols-12 gap-4">
              <LightStatPanel
                className="col-span-12 md:col-span-6 xl:col-span-3"
                title="读取行数 (Read Rows)"
                value={viewData.readRows}
                unit="行 (rows)"
                icon={<Activity className="h-4 w-4" />}
                secondaryLabel="累计读取数据量"
                secondaryValue={formatMetric(viewData.readMegabytes, "MB")}
              />

              <LightStatPanel
                className="col-span-12 md:col-span-6 xl:col-span-3"
                title="写入行数 (Write Rows)"
                value={viewData.writeRows}
                unit="行 (rows)"
                icon={<Zap className="h-4 w-4" />}
                secondaryLabel="累计写入数据量"
                secondaryValue={formatMetric(viewData.writeMegabytes, "MB")}
              />

              <LightStatPanel
                className="col-span-12 md:col-span-6 xl:col-span-3"
                title="读取 QPS (Read QPS)"
                value={viewData.readQps}
                unit="行/秒 (rows/s)"
                icon={<Gauge className="h-4 w-4" />}
                secondaryLabel={viewData.terminal ? "平均读取吞吐" : "读取吞吐"}
                secondaryValue={formatMetric(viewData.readMBps, "MB/s")}
              />

              <LightStatPanel
                className="col-span-12 md:col-span-6 xl:col-span-3"
                title="写入 QPS (Write QPS)"
                value={viewData.writeQps}
                unit="行/秒 (rows/s)"
                icon={<Clock3 className="h-4 w-4" />}
                secondaryLabel={viewData.terminal ? "平均写入吞吐" : "写入吞吐"}
                secondaryValue={formatMetric(viewData.writeMBps, "MB/s")}
              />

              <LightPanel
                className="col-span-12 xl:col-span-8"
                title="吞吐量 (Throughput)"
                description="读取 QPS / 写入 QPS (Read QPS / Write QPS)"
              >
                <ChartOrEmpty hasData={trends.length > 0}>
                  <ReactECharts
                    theme="st"
                    option={throughputOption}
                    notMerge
                    lazyUpdate
                    style={{ height: 330 }}
                  />
                </ChartOrEmpty>
              </LightPanel>

              <LightPanel
                className="col-span-12 xl:col-span-4"
                title="任务概览 (Job Summary)"
                description="当前运行状态 (Current runtime status)"
              >
                <div className="grid h-[330px] grid-cols-2 gap-3">
                  <MiniInfo
                    label="表数量 (Tables)"
                    value={viewData.tableCount}
                  />
                  <MiniInfo
                    label="Pipeline 数 (Pipelines)"
                    value={viewData.pipelineCount}
                  />
                  <MiniInfo
                    label="队列大小 (Queue Size)"
                    value={formatNumber(viewData.queueSize)}
                  />
                  <MiniInfo
                    label="运行时长 (Duration)"
                    value={viewData.runningDuration}
                  />
                  <MiniInfo
                    label="引接引擎 ID"
                    value={viewData.clientId}
                  />
                  <MiniInfo
                    label="最近采集时间 (Last Collect)"
                    value={viewData.lastCollectTime}
                  />
                </div>
              </LightPanel>

              <LightPanel
                className="col-span-12 xl:col-span-8"
                title="行数 (Rows)"
                description="读取行数 / 写入行数 (Read Rows / Write Rows)"
              >
                <ChartOrEmpty hasData={trends.length > 0}>
                  <ReactECharts
                    theme="st"
                    option={rowsOption}
                    notMerge
                    lazyUpdate
                    style={{ height: 310 }}
                  />
                </ChartOrEmpty>
              </LightPanel>

              <LightPanel
                className="col-span-12 xl:col-span-4"
                title="最大行差 (Top Row Diff)"
                description="按表统计读取行数 - 写入行数 (Read rows - Write rows by table)"
              >
                <ChartOrEmpty hasData={tableMetrics.length > 0}>
                  <ReactECharts
                    theme="st"
                    option={rowDiffBarOption}
                    notMerge
                    lazyUpdate
                    style={{ height: 310 }}
                  />
                </ChartOrEmpty>
              </LightPanel>

              {/* <LightPanel
                className="col-span-12 xl:col-span-5"
                title="基本信息 (Basic Info)"
                description="来源 / 去向 (Source / Sink)"
              >
                <div className="grid gap-3 text-base">
                  <BasicRow
                    label="来源 (Source)"
                    value={viewData.source}
                  />
                  <BasicRow label="去向 (Sink)" value={viewData.sink} />
                  <BasicRow
                    label="同步模式 (Sync Mode)"
                    value={viewData.syncMode}
                  />
                  <BasicRow
                    label="引擎任务 ID (Engine Job ID)"
                    value={viewData.engineJobId}
                  />
                  <BasicRow
                    label="任务状态 (Job Status)"
                    value={viewData.status}
                  />
                </div>
              </LightPanel> */}

              <LightPanel
                className="col-span-12 xl:col-span-12"
                title="表级指标 (Table Metrics)"
                description="表级读写进度 (Table level read/write progress)"
              >
                <TableMetricsView rows={tableMetrics} />
              </LightPanel>
            </div>
          )}
        </Spin>
      </main>
    </Modal>
  );
});

RealtimeGrafanaLightModal.displayName = "RealtimeGrafanaLightModal";

export default RealtimeGrafanaLightModal;

const LightPanel: React.FC<{
  title: string;
  description?: string;
  className?: string;
  children: React.ReactNode;
}> = ({ title, description, className = "", children }) => {
  return (
    <Card
      className={className}
      size="small"
      title={
        <div className="py-3">
          <h2 className="m-0">{title}</h2>
          {description && (
            <div className="mt-1 text-base font-normal text-[var(--st-color-text-secondary)]">{description}</div>
          )}
        </div>
      }
      styles={{ body: { padding: 16 } }}
    >
      {children}
    </Card>
  );
};

const LightStatPanel: React.FC<{
  title: string;
  value: number | string;
  unit?: string;
  icon: React.ReactNode;
  className?: string;
  secondaryLabel: string;
  secondaryValue: string;
}> = ({ title, value, unit, icon, className = "", secondaryLabel, secondaryValue }) => {
  return (
    <Card className={className} size="small" styles={{ body: { padding: 16 } }}>
      <div className="mb-6 flex items-center justify-between">
        <div className="text-base font-semibold text-[var(--st-color-text-secondary)]">{title}</div>
        <div className="flex h-8 w-8 items-center justify-center rounded-md border border-[var(--st-color-border)] bg-[var(--st-color-bg-control)] text-[var(--st-color-accent)]">
          {icon}
        </div>
      </div>

      <div className="flex flex-wrap items-baseline gap-x-2">
        <div className="text-4xl font-bold tracking-tight text-[var(--st-color-text-primary)]">
          {typeof value === "number" ? (
            <CountUp
              key={`${title}-${value}`}
              end={value}
              duration={0.8}
              separator=","
            />
          ) : (
            value
          )}
        </div>

        {unit ? (
          <div className="text-base font-semibold text-[var(--st-color-text-secondary)]">
            {unit}
          </div>
        ) : null}
      </div>
      <div className="mt-3 flex flex-wrap items-baseline justify-between gap-x-2 border-t border-[var(--st-color-border)] pt-2 text-base text-[var(--st-color-text-secondary)]">
        <span>{secondaryLabel}</span>
        <strong className="whitespace-nowrap text-[var(--st-color-text-primary)]">{secondaryValue}</strong>
      </div>
    </Card>
  );
};

const MiniInfo: React.FC<{
  label: string;
  value: React.ReactNode;
}> = ({ label, value }) => {
  return (
    <div className="rounded-lg border border-[var(--st-color-line-strong)] bg-[var(--st-color-bg-control)] p-3">
      <div className="text-base text-[var(--st-color-text-secondary)]">{label}</div>
      <div className="mt-2 truncate text-lg font-bold text-[var(--st-color-text-primary)]">
        {value || "-"}
      </div>
    </div>
  );
};

const BasicRow: React.FC<{
  label: string;
  value: React.ReactNode;
}> = ({ label, value }) => {
  return (
    <div className="flex items-center justify-between gap-4 rounded-lg border border-[var(--st-color-line-strong)] bg-[var(--st-color-bg-control)] px-3 py-2">
      <span className="text-[var(--st-color-text-secondary)]">{label}</span>
      <span className="truncate text-right font-semibold text-[var(--st-color-text-primary)]">
        {value || "-"}
      </span>
    </div>
  );
};

const ChartOrEmpty: React.FC<{
  hasData: boolean;
  children: React.ReactNode;
}> = ({ hasData, children }) => {
  if (!hasData) {
    return (
      <div className="flex h-[310px] items-center justify-center">
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="暂无指标数据"
        />
      </div>
    );
  }

  return <>{children}</>;
};

const TableMetricsView: React.FC<{
  rows: JobTableMetricsVO[];
}> = ({ rows }) => {
  return (
    <Table<JobTableMetricsVO>
      size="small"
      pagination={false}
      scroll={{ x: 850 }}
      dataSource={rows}
      rowKey={(item) => String(item.id ?? `${item.pipelineId}-${buildTableName(item)}`)}
      locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无表级指标" /> }}
      columns={[
        { title: "表", key: "table", width: 260, ellipsis: true, render: (_, item) => buildTableName(item) },
        { title: "读取行数", dataIndex: "readRowCount", align: "right", render: formatNumber },
        { title: "写入行数", dataIndex: "writeRowCount", align: "right", render: formatNumber },
        { title: "行差", key: "rowDiff", align: "right", render: (_, item) => formatNumber(calcRowDiff(item)) },
        { title: "读取 QPS", dataIndex: "readQps", align: "right", render: (value) => formatMetric(value) },
        { title: "写入 QPS", dataIndex: "writeQps", align: "right", render: (value) => formatMetric(value) },
        { title: "状态", dataIndex: "status", align: "right", render: (status) => status ? <Tag>{status}</Tag> : "—" },
      ]}
    />
  );
};

const buildRowDiffBarOption = (rows: JobTableMetricsVO[]) => {
  const finalRows = rows || [];
  const yAxis = finalRows.map((item) => buildTableName(item));
  const values = finalRows.map((item) => calcRowDiff(item));

  return {
    backgroundColor: "transparent",
    grid: {
      left: 96,
      right: 18,
      top: 18,
      bottom: 24,
    },
    tooltip: {
      trigger: "axis",
      textStyle: { fontSize: 14 },
      formatter: (params: any[]) => {
        const item = params?.[0];
        if (!item) {
          return "";
        }

        return `${item.name}<br/>行差 (Row Diff)：${formatNumber(item.value)}`;
      },
    },
    xAxis: {
      type: "value",
      axisLabel: {
        formatter: formatShortNumber,
      },
      axisLine: {
        show: false,
      },
      axisTick: {
        show: false,
      },
    },
    yAxis: {
      type: "category",
      data: yAxis,
      axisLabel: {
        width: 82,
        overflow: "truncate",
      },
      axisLine: {
        show: false,
      },
      axisTick: {
        show: false,
      },
    },
    series: [
      {
        name: "行差 (Row Diff)",
        type: "bar",
        data: values,
        barWidth: 12,
        itemStyle: {
          color: ST_CHART_ACCENT,
          borderRadius: [0, 8, 8, 0],
        },
      },
    ],
  };
};

const buildLightLineOption = ({
  unit,
  xAxis,
  series,
  formatter,
  isRate = false,
}: {
  unit: string;
  xAxis: string[];
  series: Array<{
    name: string;
    color: string;
    dashed?: boolean;
    data: number[];
  }>;
  formatter: (value: number) => string;
  isRate?: boolean;
}) => {
  return {
    backgroundColor: "transparent",
    color: series.map((item) => item.color),
    grid: {
      left: 48,
      right: 24,
      top: 48,
      bottom: 34,
      containLabel: true,
    },
    tooltip: {
      trigger: "axis",
      textStyle: { fontSize: 14 },
      valueFormatter: isRate ? (value: unknown) => formatMetric(value, unit) : undefined,
    },
    legend: {
      top: 6,
      right: 12,
      itemWidth: 12,
      itemHeight: 8,
      textStyle: {
        color: "#c4d1de",
        fontSize: 14,
      },
    },
    xAxis: {
      type: "category",
      boundaryGap: false,
      data: xAxis,
      axisTick: {
        show: false,
      },
    },
    yAxis: {
      type: "value",
      minInterval: isRate ? 0.1 : undefined,
      name: unit,
      nameTextStyle: {
        padding: [0, 0, 0, -28],
      },
      axisLabel: {
        formatter,
      },
    },
    series: series.map((item) => ({
      name: item.name,
      type: "line",
      smooth: true,
      showSymbol: false,
      data: item.data,
      lineStyle: {
        width: 2,
        color: item.color,
        type: item.dashed ? "dashed" : "solid",
      },
      areaStyle: {
        color: item.color,
        opacity: 0.08,
      },
    })),
  };
};

const buildTableName = (item: JobTableMetricsVO) => {
  if (item.sourceTable && item.sinkTable) {
    return `${item.sourceTable} → ${item.sinkTable}`;
  }

  return item.sourceTable || item.sinkTable || "-";
};

const calcRowDiff = (item: JobTableMetricsVO) => {
  if (item.rowDiff !== undefined && item.rowDiff !== null) {
    return Math.max(toNumber(item.rowDiff), 0);
  }

  const read = toNumber(item.readRowCount);
  const write = toNumber(item.writeRowCount);

  return Math.max(read - write, 0);
};

const toNumber = (value?: number | string | null) => {
  if (value === undefined || value === null || value === "") {
    return 0;
  }

  const num = Number(value);
  if (Number.isNaN(num)) {
    return 0;
  }

  return num;
};

const formatNumber = (value?: number | string | null) => {
  if (value === undefined || value === null || value === "") {
    return "-";
  }

  const num = Number(value);
  if (Number.isNaN(num)) {
    return value;
  }

  return num.toLocaleString();
};

const formatShortNumber = (value: number) => {
  if (value >= 100000000) {
    return `${Math.round((value / 100000000) * 10) / 10}B`;
  }

  if (value >= 1000000) {
    return `${Math.round((value / 1000000) * 10) / 10}M`;
  }

  if (value >= 1000) {
    return `${Math.round((value / 1000) * 10) / 10}K`;
  }

  return `${value}`;
};

const formatDateTime = (value?: string | number | Date | null) => {
  if (!value) {
    return "-";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return String(value);
  }

  const yyyy = date.getFullYear();
  const mm = `${date.getMonth() + 1}`.padStart(2, "0");
  const dd = `${date.getDate()}`.padStart(2, "0");
  const hh = `${date.getHours()}`.padStart(2, "0");
  const mi = `${date.getMinutes()}`.padStart(2, "0");
  const ss = `${date.getSeconds()}`.padStart(2, "0");

  return `${yyyy}-${mm}-${dd} ${hh}:${mi}:${ss}`;
};

const formatChartTime = (
  value?: string | number | Date,
  timestamp?: number
) => {
  const raw = value || timestamp;

  if (!raw) {
    return "";
  }

  const date = new Date(raw);

  if (Number.isNaN(date.getTime())) {
    return String(raw);
  }

  const hh = `${date.getHours()}`.padStart(2, "0");
  const mi = `${date.getMinutes()}`.padStart(2, "0");

  return `${hh}:${mi}`;
};

const calcRunningDuration = (
  startTime?: string | number | Date,
  endTime?: string | number | Date
) => {
  if (!startTime) {
    return "-";
  }

  const start = new Date(startTime).getTime();
  const end = endTime ? new Date(endTime).getTime() : Date.now();

  if (Number.isNaN(start) || Number.isNaN(end) || end < start) {
    return "-";
  }

  const totalSeconds = Math.floor((end - start) / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  return `${`${hours}`.padStart(2, "0")}:${`${minutes}`.padStart(
    2,
    "0"
  )}:${`${seconds}`.padStart(2, "0")}`;
};
