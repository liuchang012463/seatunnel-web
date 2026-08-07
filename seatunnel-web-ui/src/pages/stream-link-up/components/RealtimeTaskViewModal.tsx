import HttpUtils from "@/utils/HttpUtils";
import { Empty, Modal, Select, Spin, message } from "antd";
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
const rangeSelectClassName = [
  "w-[132px]",
  "[&_.ant-select-arrow]:!text-[#b7d9e2]",
  "[&_.ant-select-selection-item]:!text-[#dff7ff]",
  "[&_.ant-select-selector]:!border-[#1a829a]",
  "[&_.ant-select-selector]:!bg-[#064b5f]",
].join(" ");
const refreshButtonClassName = [
  "hidden items-center gap-2 border border-[#1a829a] bg-[#064b5f] px-3 py-1.5",
  "text-xs text-[#bdeaf5] transition hover:bg-[#0a5b70] hover:text-white md:flex",
].join(" ");

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
  console.log(record);
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
        const response = await HttpUtils.get(
          `/api/v1/job/streaming-instance/${instanceId}/metrics-dashboard?range=${range}`
        );

        const data =
          response?.data ||
          (response?.data as StreamingInstanceMetricsDashboardVO);
        console.log(data);
        setDashboard(data);
      } catch (error) {
        console.error("Fetch streaming metrics dashboard failed", error);
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
    return {
      jobName: instance.jobName || "实时任务 (Streaming Job)",
      jobInstanceId: instance.id || current.jobInstanceId || "-",
      engineJobId: instance.engineJobId || current.engineJobId || "-",
      clientId: instance.clientId || current.clientId || "-",
      status: current.jobStatus || instance.jobStatus || "UNKNOWN",
      startTime: formatDateTime(instance.startTime),
      lastCollectTime: formatDateTime(current.lastCollectTime),

      readQps: toNumber(current.readQps),
      writeQps: toNumber(current.writeQps),
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
          color: "#2563eb",
          data: trends.map((item) => toNumber(item.readQps)),
        },
        {
          name: "写入 QPS (Write QPS)",
          color: "#7c3aed",
          data: trends.map((item) => toNumber(item.writeQps)),
        },
      ],
      formatter: formatShortNumber,
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
          color: "#16a34a",
          data: trends.map((item) => toNumber(item.readRowCount)),
        },
        {
          name: "写入行数 (Write Rows)",
          color: "#2563eb",
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
      title={null}
      destroyOnClose
      maskClosable={false}
      centered={false}
      closeIcon={null}
      onCancel={handleClose}
      width="98vw"
      style={{ top: 16 }}
      styles={{
        mask: {
          background: "rgba(0, 22, 31, 0.82)",
        },
        content: {
          padding: 0,
          overflow: "hidden",
          borderRadius: 18,
          background: "#002f3f",
          boxShadow: "none",
          border: "1px solid #0d6a80",
        },
        body: {
          padding: 0,
        },
      }}
    >
      <div className="h-[calc(100vh-45px)] overflow-hidden bg-[#002f3f] text-[#dff7ff]">
        <div className="flex h-full flex-col">
          <header className="flex shrink-0 items-center justify-between border-b border-[#0d6a80] bg-[#003a4d] px-5 py-4">
            <div className="flex min-w-0 items-center gap-4">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-[#1a829a] bg-[#064b5f] text-[#4bd8ff]">
                <Gauge className="h-5 w-5" />
              </div>

              <div className="min-w-0">
                <div className="flex items-center gap-3">
                  <h1 className="m-0 truncate text-lg font-bold text-white">
                    {viewData.jobName}
                  </h1>

                  <TaskStatus status={viewData.status} />
                </div>

                <div className="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-[#a9cbd4]">
                  <span>实例 (Instance)：{viewData.jobInstanceId}</span>
                  <span>引擎 (Engine)：{viewData.engineJobId}</span>
                  <span>模式 (Mode)：{viewData.syncMode}</span>
                  <span>开始时间 (Start)：{viewData.startTime}</span>
                </div>
              </div>
            </div>

            <div
              className="flex shrink-0 items-center gap-3"
              style={{ marginRight: "3rem" }}
            >
              <Select
                // size="small"
                value={range}
                onChange={setRange}
                className={rangeSelectClassName}
                options={[
                  { label: "最近 15 分钟 (Last 15 min)", value: "15m" },
                  { label: "最近 1 小时 (Last 1 hour)", value: "1h" },
                  { label: "最近 6 小时 (Last 6 hours)", value: "6h" },
                  { label: "最近 24 小时 (Last 24 hours)", value: "24h" },
                ]}
              />

              <button
                type="button"
                onClick={() => fetchDashboard(false)}
                className={refreshButtonClassName}
                style={{ borderRadius: "16px", height: 33 }}
              >
                <RefreshCw
                  className={["h-4 w-4", loading ? "animate-spin" : ""].join(
                    " "
                  )}
                />
                <span>5 秒刷新 (5s refresh)</span>
              </button>

              <button
                type="button"
                aria-label="Close"
                className="ant-modal-close"
                style={{ top: "22px", color: "#b7d9e2" }}
                onClick={handleClose}
              >
                <span className="ant-modal-close-x" aria-label="关闭">
                  <span
                    role="img"
                    aria-label="close"
                    className="anticon anticon-close ant-modal-close-icon"
                  >
                    <svg
                      fill-rule="evenodd"
                      viewBox="64 64 896 896"
                      focusable="false"
                      data-icon="close"
                      width="1em"
                      height="1em"
                      fill="currentColor"
                      aria-hidden="true"
                    >
                      <path d="M799.86 166.31c.02 0 .04.02.08.06l57.69 57.7c.04.03.05.05.06.08a.12.12 0 010 .06c0 .03-.02.05-.06.09L569.93 512l287.7 287.7c.04.04.05.06.06.09a.12.12 0 010 .07c0 .02-.02.04-.06.08l-57.7 57.69c-.03.04-.05.05-.07.06a.12.12 0 01-.07 0c-.03 0-.05-.02-.09-.06L512 569.93l-287.7 287.7c-.04.04-.06.05-.09.06a.12.12 0 01-.07 0c-.02 0-.04-.02-.08-.06l-57.69-57.7c-.04-.03-.05-.05-.06-.07a.12.12 0 010-.07c0-.03.02-.05.06-.09L454.07 512l-287.7-287.7c-.04-.04-.05-.06-.06-.09a.12.12 0 010-.07c0-.02.02-.04.06-.08l57.7-57.69c.03-.04.05-.05.07-.06a.12.12 0 01.07 0c.03 0 .05.02.09.06L512 454.07l287.7-287.7c.04-.04.06-.05.09-.06a.12.12 0 01.07 0z"></path>
                    </svg>
                  </span>
                </span>
              </button>
            </div>
          </header>

          <main className="flex-1 overflow-auto p-5">
            <Spin spinning={loading && !dashboard}>
              {!instanceId ? (
                <div className="flex h-[520px] items-center justify-center rounded-xl border border-[#0d6a80] bg-[#003a4d]">
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
                    accent="blue"
                  />

                  <LightStatPanel
                    className="col-span-12 md:col-span-6 xl:col-span-3"
                    title="写入行数 (Write Rows)"
                    value={viewData.writeRows}
                    unit="行 (rows)"
                    icon={<Zap className="h-4 w-4" />}
                    accent="purple"
                  />

                  <LightStatPanel
                    className="col-span-12 md:col-span-6 xl:col-span-3"
                    title="读取 QPS (Read QPS)"
                    value={viewData.readQps}
                    unit="行/秒 (rows/s)"
                    icon={<Gauge className="h-4 w-4" />}
                    accent="orange"
                  />

                  <LightStatPanel
                    className="col-span-12 md:col-span-6 xl:col-span-3"
                    title="写入 QPS (Write QPS)"
                    value={viewData.writeQps}
                    unit="行/秒 (rows/s)"
                    icon={<Clock3 className="h-4 w-4" />}
                    accent="green"
                  />

                  <LightPanel
                    className="col-span-12 xl:col-span-8"
                    title="吞吐量 (Throughput)"
                    description="读取 QPS / 写入 QPS (Read QPS / Write QPS)"
                  >
                    <ChartOrEmpty hasData={trends.length > 0}>
                      <ReactECharts
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
                        label="客户端 ID (Client ID)"
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
                    <div className="grid gap-3 text-sm">
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
        </div>
      </div>
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
    <section
      className={[
        "rounded-xl border border-[#0d6a80] bg-[#003a4d] ",
        className,
      ].join(" ")}
    >
      <div className="border-b border-[#0d6a80] px-4 py-3">
        <div className="flex items-center justify-between gap-3">
          <div>
            <h2 className="m-0 text-sm font-bold text-white">{title}</h2>
            {description ? (
              <div className="mt-1 text-xs text-[#b7d9e2]">{description}</div>
            ) : null}
          </div>
        </div>
      </div>

      <div className="p-4">{children}</div>
    </section>
  );
};

const LightStatPanel: React.FC<{
  title: string;
  value: React.ReactNode;
  unit?: string;
  icon: React.ReactNode;
  accent: "blue" | "purple" | "orange" | "green";
  className?: string;
}> = ({ title, value, unit, icon, accent, className = "" }) => {
  const accentMap = {
    blue: "text-blue-600 bg-blue-50 border-blue-100",
    purple: "text-violet-600 bg-violet-50 border-violet-100",
    orange: "text-orange-600 bg-orange-50 border-orange-100",
    green: "text-emerald-600 bg-emerald-50 border-emerald-100",
  };

  const num = typeof value === "number" ? value : Number(value);
  const canCountUp = !Number.isNaN(num);

  return (
    <section
      className={[
        "rounded-xl border border-[#0d6a80] bg-[#003a4d] p-4 ",
        className,
      ].join(" ")}
    >
      <div className="mb-6 flex items-center justify-between">
        <div className="text-sm font-semibold text-[#c3e7ef]">{title}</div>
        <div
          className={[
            "flex h-8 w-8 items-center justify-center rounded-lg border",
            accentMap[accent],
          ].join(" ")}
        >
          {icon}
        </div>
      </div>

      <div className="flex items-end gap-2">
        <div className="text-4xl font-bold tracking-tight text-white">
          {canCountUp ? (
            <CountUp
              key={`${title}-${num}`}
              end={num}
              duration={0.8}
              separator=","
              decimals={num % 1 === 0 ? 0 : 2}
            />
          ) : (
            value || "-"
          )}
        </div>

        {unit ? (
          <div className="mb-1 text-sm font-semibold text-[#c3e7ef]">
            {unit}
          </div>
        ) : null}
      </div>
    </section>
  );
};

const MiniInfo: React.FC<{
  label: string;
  value: React.ReactNode;
}> = ({ label, value }) => {
  return (
    <div className="rounded-lg border border-[#1a829a] bg-[#033747] p-3">
      <div className="text-xs text-[#b7d9e2]">{label}</div>
      <div className="mt-2 truncate text-lg font-bold text-white">
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
    <div className="flex items-center justify-between gap-4 rounded-lg border border-[#1a829a] bg-[#033747] px-3 py-2">
      <span className="text-[#b7d9e2]">{label}</span>
      <span className="truncate text-right font-semibold text-white">
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
  if (!rows || rows.length === 0) {
    return (
      <div className="flex h-[220px] items-center justify-center">
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="暂无表级指标 (Table Metrics)"
        />
      </div>
    );
  }

  return (
    <div className="overflow-hidden rounded-lg border border-[#0d6a80]">
      <table className="w-full text-left text-sm">
        <thead className="bg-[#033747] text-xs text-[#b7d9e2]">
          <tr>
            <th className="px-4 py-3 font-semibold">表 (Table)</th>
            <th className="px-4 py-3 text-right font-semibold">
              读取行数 (Read Rows)
            </th>
            <th className="px-4 py-3 text-right font-semibold">
              写入行数 (Write Rows)
            </th>
            <th className="px-4 py-3 text-right font-semibold">
              行差 (Row Diff)
            </th>
            <th className="px-4 py-3 text-right font-semibold">
              读取 QPS (Read QPS)
            </th>
            <th className="px-4 py-3 text-right font-semibold">
              写入 QPS (Write QPS)
            </th>
            <th className="px-4 py-3 text-right font-semibold">
              状态 (Status)
            </th>
          </tr>
        </thead>

        <tbody className="divide-y divide-[#0d6a80]">
          {rows.map((item, index) => {
            const tableName = buildTableName(item);
            const rowDiff = calcRowDiff(item);

            return (
              <tr
                key={`${tableName}-${item.pipelineId || index}`}
                className="bg-[#003a4d] text-[#c3e7ef] hover:bg-[#064b5f]"
              >
                <td className="max-w-[220px] truncate px-4 py-3 font-semibold text-white">
                  {tableName}
                </td>
                <td className="px-4 py-3 text-right">
                  {formatNumber(item.readRowCount)}
                </td>
                <td className="px-4 py-3 text-right">
                  {formatNumber(item.writeRowCount)}
                </td>
                <td className="px-4 py-3 text-right">
                  <span className="rounded-md bg-orange-500/15 px-2 py-1 text-xs font-semibold text-orange-300">
                    {formatNumber(rowDiff)}
                  </span>
                </td>
                <td className="px-4 py-3 text-right">
                  {formatNumber(item.readQps)}
                </td>
                <td className="px-4 py-3 text-right">
                  {formatNumber(item.writeQps)}
                </td>
                <td className="px-4 py-3 text-right">
                  <span className="rounded-md bg-white/5 px-2 py-1 text-xs font-semibold text-[#b7d9e2]">
                    {item.status || "-"}
                  </span>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
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
      backgroundColor: "rgba(15, 23, 42, 0.92)",
      borderWidth: 0,
      textStyle: {
        color: "#fff",
      },
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
        color: "#8fb5c0",
        formatter: formatShortNumber,
      },
      axisLine: {
        show: false,
      },
      axisTick: {
        show: false,
      },
      splitLine: {
        lineStyle: {
          color: "rgba(125, 190, 205, 0.34)",
        },
      },
    },
    yAxis: {
      type: "category",
      data: yAxis,
      axisLabel: {
        color: "#8fb5c0",
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
          color: "#f97316",
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
}: {
  unit: string;
  xAxis: string[];
  series: Array<{
    name: string;
    color: string;
    data: number[];
  }>;
  formatter: (value: number) => string;
}) => {
  return {
    backgroundColor: "transparent",
    color: series.map((item) => item.color),
    grid: {
      left: 48,
      right: 24,
      top: 48,
      bottom: 34,
    },
    tooltip: {
      trigger: "axis",
      backgroundColor: "rgba(15, 23, 42, 0.92)",
      borderWidth: 0,
      textStyle: {
        color: "#fff",
      },
    },
    legend: {
      top: 6,
      right: 12,
      itemWidth: 12,
      itemHeight: 8,
      textStyle: {
        color: "#b7d9e2",
      },
    },
    xAxis: {
      type: "category",
      boundaryGap: false,
      data: xAxis,
      axisLine: {
        lineStyle: {
          color: "rgba(125, 190, 205, 0.4)",
        },
      },
      axisTick: {
        show: false,
      },
      axisLabel: {
        color: "#8fb5c0",
      },
    },
    yAxis: {
      type: "value",
      name: unit,
      nameTextStyle: {
        color: "#8fb5c0",
        padding: [0, 0, 0, -28],
      },
      axisLabel: {
        color: "#8fb5c0",
        formatter,
      },
      splitLine: {
        lineStyle: {
          color: "rgba(125, 190, 205, 0.28)",
        },
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
