import { ReloadOutlined } from "@ant-design/icons";
import { Alert, Button, Empty, Select, Spin } from "antd";
import { motion } from "framer-motion";
import {
  BarChart3,
  Clock3,
  Database,
  Target,
} from "lucide-react";
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { fetchChartData, fetchSummaryData } from "./api";
import BarChart from "./BarChart";
import ChartRenderBoundary from "./ChartRenderBoundary";
import "./index.less";
import LineChart from "./LineChart";
import { toMegabytes } from "@/pages/common/utils/throughput";
import { ChartData, SummaryData, TaskType, TimeRange } from "./types";
import { calculateSuccessRate, taskTypeOptions, transformChartData } from "./utils";

const fadeUp = {
  hidden: { opacity: 0, y: 18 },
  visible: {
    opacity: 1,
    y: 0,
    transition: {
      duration: 0.45,
      ease: [0.22, 1, 0.36, 1] as [number, number, number, number],
    },
  },
};

const sectionStagger = {
  hidden: {},
  visible: {
    transition: {
      staggerChildren: 0.08,
      delayChildren: 0.06,
    },
  },
};

const cardStagger = {
  hidden: {},
  visible: {
    transition: {
      staggerChildren: 0.06,
    },
  },
};

const emptySummary: SummaryData = {
  totalRecords: 0,
  totalBytes: 0,
  definitionCount: 0,
  totalTasks: 0,
  successTasks: 0,
  failedTasks: 0,
  runningTasks: 0,
  stoppedTasks: 0,
  avgRecordDelay: 0,
  totalBytesUnit: "-",
  totalRecordsUnit: "-",
};

type PanelResult<T> = { status: "loading" | "error" } | { status: "ready"; data: T };
type ChartPanelData = ChartData & { recordsUnit: string; bytesUnit: string };

function useMetricsPanel<T>(load: () => Promise<T>) {
  const generation = useRef(0);
  const [snapshot, setSnapshot] = useState<{ load: typeof load; result: PanelResult<T> }>();
  const reload = useCallback(async () => {
    const request = ++generation.current;
    setSnapshot({ load, result: { status: "loading" } });
    try {
      const data = await load();
      if (request === generation.current) setSnapshot({ load, result: { status: "ready", data } });
    } catch {
      if (request === generation.current) setSnapshot({ load, result: { status: "error" } });
    }
  }, [load]);

  useEffect(() => {
    void reload();
    return () => { generation.current += 1; };
  }, [reload]);

  // A response from the previous filter should never be shown under a new time range or task type.
  const result: PanelResult<T> = snapshot?.load === load ? snapshot.result : { status: "loading" };
  return { result, reload };
}

const App: React.FC = () => {
  const [taskType, setTaskType] = useState<TaskType>("BATCH");
  const [timeRange, setTimeRange] = useState<TimeRange>("D30");
  const loadSummary = useCallback(() => fetchSummaryData(timeRange, taskType), [timeRange, taskType]);
  const loadCharts = useCallback(async (): Promise<ChartPanelData> => {
    const data = await fetchChartData(timeRange, taskType);
    return {
      recordsTrend: transformChartData(data.recordsTrend, timeRange),
      bytesTrend: transformChartData(data.bytesTrend, timeRange),
      recordsSpeedTrend: transformChartData(data.recordsSpeedTrend, timeRange),
      bytesSpeedTrend: transformChartData(data.bytesSpeedTrend, timeRange),
      // The trends are scaled by the chart endpoint, not by the summary totals.
      recordsUnit: data.recordsTrend.find((item) => item.unit)?.unit || "条",
      bytesUnit: data.bytesTrend.find((item) => item.unit)?.unit || "B",
    };
  }, [timeRange, taskType]);

  const { result: summaryResult, reload: reloadSummary } = useMetricsPanel(loadSummary);
  const { result: chartResult, reload: reloadCharts } = useMetricsPanel(loadCharts);
  const summaryReady = summaryResult.status === "ready";
  const summaryData = summaryResult.status === "ready" ? summaryResult.data : emptySummary;
  const chartData = chartResult.status === "ready" ? chartResult.data : undefined;
  const loading = summaryResult.status === "loading" || chartResult.status === "loading";
  const refreshData = () => {
    void reloadSummary();
    void reloadCharts();
  };

  const completedTasks =
    summaryData.successTasks + summaryData.failedTasks + summaryData.stoppedTasks;
  const taskStatusText = `成功 ${summaryData.successTasks || 0} 个 · 失败 ${summaryData.failedTasks || 0} 个 · 运行中 ${summaryData.runningTasks || 0} 个 · 已停止/保存点结束 ${summaryData.stoppedTasks || 0} 个`;

  const successRate = useMemo(() => {
    return calculateSuccessRate(summaryData.successTasks, completedTasks);
  }, [completedTasks, summaryData.successTasks]);

  const isStreaming = taskType === "STREAM";
  const isFileTransfer = taskType === "FILE_TRANSFER";
  const showRecordMetrics = !isFileTransfer;
  const taskCountDescription = isStreaming
    ? "统计范围内采集到指标的任务执行实例"
    : "统计范围内采集到指标或当前仍在运行的任务执行实例";
  const recordsLabel = taskType === "FILE_INGEST" ? "导入记录量" : "写入记录量";
  const bytesLabel = taskType === "FILE_INGEST" ? "导入数据量" : "写入数据量";

  const statCards = isStreaming
    ? [
        {
          title: "写入记录量",
          value: summaryData.totalRecords || 0,
          subText: `所选范围内新增写入${summaryData.totalRecordsUnit ? `（${summaryData.totalRecordsUnit}）` : ""}`,
          iconBg: "bg-[rgba(77,210,255,0.12)]",
          iconColor: "text-[#4dd2ff]",
          icon: <BarChart3 size={18} strokeWidth={2} />,
        },
        {
          title: "写入数据量",
          value: summaryData.totalBytes || 0,
          subText: `所选范围内新增写入数据${summaryData.totalBytesUnit ? `（${summaryData.totalBytesUnit}）` : ""}`,
          iconBg: "bg-[rgba(77,210,255,0.12)]",
          iconColor: "text-[#4dd2ff]",
          icon: <Database size={18} strokeWidth={2} />,
        },
        {
          title: "运行中任务",
          value: summaryData.runningTasks || 0,
          subText: `采集到 ${summaryData.totalTasks || 0} 个任务指标`,
          iconBg: "bg-[rgba(77,210,255,0.12)]",
          iconColor: "text-[#4dd2ff]",
          icon: <Clock3 size={18} strokeWidth={2} />,
        },
        {
          title: "平均记录延迟",
          value: summaryData.totalTasks ? `${summaryData.avgRecordDelay || 0} ms` : "—",
          subText: taskStatusText,
          iconBg: "bg-[rgba(77,210,255,0.12)]",
          iconColor: "text-[#4dd2ff]",
          icon: <Target size={18} strokeWidth={2} />,
        },
      ]
    : isFileTransfer
      ? [
          {
          title: "传输数据量",
          value: summaryData.totalBytes || 0,
          subText: `所选范围内传输数据${summaryData.totalBytesUnit ? `（${summaryData.totalBytesUnit}）` : ""}`,
            iconBg: "bg-[rgba(77,210,255,0.12)]",
            iconColor: "text-[#4dd2ff]",
            icon: <Database size={18} strokeWidth={2} />,
          },
          {
            title: "执行任务数",
            value: summaryData.totalTasks || 0,
            subText: taskCountDescription,
            iconBg: "bg-[rgba(77,210,255,0.12)]",
            iconColor: "text-[#4dd2ff]",
            icon: <Clock3 size={18} strokeWidth={2} />,
          },
          {
            title: "完成任务数",
            value: summaryData.successTasks || 0,
            subText: "引擎状态为已完成",
            iconBg: "bg-[rgba(77,210,255,0.12)]",
            iconColor: "text-[#4dd2ff]",
            icon: <Target size={18} strokeWidth={2} />,
          },
          {
            title: "失败任务数",
            value: summaryData.failedTasks || 0,
            subText: `运行中 ${summaryData.runningTasks || 0} 个 · 已停止/保存点结束 ${summaryData.stoppedTasks || 0} 个`,
            iconBg: "bg-[rgba(77,210,255,0.12)]",
            iconColor: "text-[#4dd2ff]",
            icon: <BarChart3 size={18} strokeWidth={2} />,
          },
        ]
      : [
    {
      title: recordsLabel,
      value: summaryData.totalRecords || 0,
      subText: `所选范围内处理记录${summaryData.totalRecordsUnit ? `（${summaryData.totalRecordsUnit}）` : ""}`,
      iconBg: "bg-[rgba(77,210,255,0.12)]",
      iconColor: "text-[#4dd2ff]",
      icon: <BarChart3 size={18} strokeWidth={2} />,
    },
    {
      title: bytesLabel,
      value: summaryData.totalBytes || 0,
      subText: `所选范围内处理数据${summaryData.totalBytesUnit ? `（${summaryData.totalBytesUnit}）` : ""}`,
      iconBg: "bg-[rgba(77,210,255,0.12)]",
      iconColor: "text-[#4dd2ff]",
      icon: <Database size={18} strokeWidth={2} />,
    },
    {
      title: "执行任务数",
      value: summaryData.totalTasks || 0,
      subText: taskCountDescription,
      iconBg: "bg-[rgba(77,210,255,0.12)]",
      iconColor: "text-[#4dd2ff]",
      icon: <Clock3 size={18} strokeWidth={2} />,
    },
    {
      title: "已结束任务成功率",
      value: successRate === null ? "—" : `${successRate}%`,
      subText: completedTasks > 0
        ? taskStatusText
        : summaryData.runningTasks > 0
          ? `尚无已结束任务，当前运行中 ${summaryData.runningTasks} 个`
          : "当前统计范围内暂无已结束任务",
      iconBg: "bg-[rgba(77,210,255,0.12)]",
      iconColor: "text-[#4dd2ff]",
      icon: <Target size={18} strokeWidth={2} />,
    },
  ];

  const summaryList = isStreaming
    ? [
        {
          title: "任务采集",
          content: `统计范围内采集到 ${summaryData.totalTasks || 0} 个实时任务执行实例的指标。`,
        },
        {
          title: "写入规模",
          content: `所选范围内新增写入 ${summaryData.totalRecords || 0} ${summaryData.totalRecordsUnit || "条"}，共 ${summaryData.totalBytes || 0} ${summaryData.totalBytesUnit || "B"}。`,
        },
        {
          title: "运行状态",
          content: `${summaryData.successTasks || 0} 个任务已完成，${summaryData.runningTasks || 0} 个任务运行中，${summaryData.failedTasks || 0} 个任务异常，${summaryData.stoppedTasks || 0} 个已停止或保存点结束，平均记录延迟 ${summaryData.avgRecordDelay || 0} ms。`,
        },
      ]
    : isFileTransfer
      ? [
          {
            title: "任务执行",
            content: `统计范围内有指标记录或当前仍在运行的共 ${summaryData.totalTasks || 0} 个文件同步任务执行实例。`,
          },
          {
          title: "传输规模",
          content: `所选范围内传输 ${summaryData.totalBytes || 0} ${summaryData.totalBytesUnit || "B"} 数据。`,
          },
          {
            title: "任务状态",
            content: `${summaryData.successTasks || 0} 个任务已完成，${summaryData.failedTasks || 0} 个任务失败，${summaryData.runningTasks || 0} 个任务运行中，${summaryData.stoppedTasks || 0} 个已停止或保存点结束。`,
          },
        ]
      : [
    {
      title: "整体概览",
      content: `所选时间范围内，有指标记录或当前仍在运行的共 ${summaryData.totalTasks || 0} 个任务执行实例。`,
    },
    {
      title: "数据规模",
      content: `所选范围内处理 ${summaryData.totalBytes || 0} ${
        summaryData.totalBytesUnit || "-"
      } 数据。`,
    },
    {
      title: "执行稳定性",
      content: completedTasks > 0
        ? `已结束任务成功率为 ${successRate}%，${taskStatusText}。`
        : summaryData.runningTasks > 0
          ? `当前统计范围内暂无已结束任务，${summaryData.runningTasks} 个任务运行中。`
          : "当前统计范围内暂无已结束任务。",
    },
  ];

  const overviewTitle = isStreaming
    ? "实时写入趋势"
    : isFileTransfer
      ? "文件传输趋势"
      : taskType === "FILE_INGEST"
        ? "文件导入趋势"
        : "数据引接趋势";
  const performanceTitle = isFileTransfer ? "传输效率" : "处理效率";
  const recordsTrendTitle = isStreaming ? "写入记录量趋势" : taskType === "FILE_INGEST" ? "导入记录量趋势" : "同步记录量趋势";
  const bytesTrendTitle = isStreaming ? "写入数据量趋势" : isFileTransfer ? "传输数据量趋势" : taskType === "FILE_INGEST" ? "导入数据量趋势" : "同步数据量趋势";
  const recordsSpeedTitle = isStreaming ? "记录写入速率" : "记录处理速率";
  const bytesSpeedTitle = isStreaming ? "数据写入速率" : isFileTransfer ? "文件传输速率" : "数据处理速率";

  return (
    <div className="metrics-page">
      <main className="metrics-page__main">
        <div className="metrics-page__inner">
          <div className="mx-auto max-w-[1600px]">
            <motion.div
              initial="hidden"
              animate="visible"
              variants={sectionStagger}
            >
              <motion.div
                variants={fadeUp}
                className="mb-6 flex flex-wrap items-center justify-end gap-3"
              >
                <Select
                  value={taskType}
                  onChange={(value) => setTaskType(value as TaskType)}
                  aria-label="任务类型"
                  style={{ width: 180 }}
                  options={taskTypeOptions}
                />
                <Select
                  value={timeRange}
                  onChange={(value) => setTimeRange(value)}
                  aria-label="统计时间范围"
                  style={{ width: 140 }}
                  options={[
                    { label: "近 1 小时", value: "H1" },
                    { label: "近 6 小时", value: "H6" },
                    { label: "近 12 小时", value: "H12" },
                    { label: "近 24 小时", value: "H24" },
                    { label: "近 7 天", value: "D7" },
                    { label: "近 30 天", value: "D30" },
                  ]}
                />
                <Button
                  icon={<ReloadOutlined />}
                  loading={loading}
                  onClick={refreshData}
                  aria-label="刷新任务概览"
                >
                  刷新
                </Button>
              </motion.div>

                <motion.section variants={fadeUp} className="mb-8" aria-label="汇总指标">
                  {summaryResult.status === "loading" && <PanelLoading label="正在加载汇总指标" />}
                  {summaryResult.status === "error" && (
                    <PanelError
                      message="汇总指标加载失败"
                      description="请重试汇总指标。趋势数据可独立查看。"
                      retryLabel="重试加载汇总指标"
                      onRetry={() => void reloadSummary()}
                    />
                  )}
                  {summaryReady && (
                  <motion.div
                    variants={cardStagger}
                    initial="hidden"
                    animate="visible"
                    className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4"
                  >
                    {statCards.map((item) => (
                      <motion.div
                        key={item.title}
                        variants={fadeUp}
                        whileHover={{ y: -4, transition: { duration: 0.18 } }}
                        className="metrics-stat-card"
                      >
                        <div className="metrics-stat-card__content p-4 pt-6 md:p-6 md:pt-6">
                          <div className="mb-3 flex items-center justify-between">
                            <div className={`rounded-xl p-2 ${item.iconBg}`}>
                              <div className={item.iconColor}>{item.icon}</div>
                            </div>
                          </div>
                          <p className="metrics-stat-card__value text-2xl font-bold">
                            {item.value}
                          </p>
                          <p className="metrics-stat-card__title mt-1 text-sm">
                            {item.title}
                          </p>
                          <p className="metrics-stat-card__title mt-1 text-sm">
                            {item.subText}
                          </p>
                        </div>
                      </motion.div>
                    ))}
                  </motion.div>
                  )}
                </motion.section>

                <motion.section variants={fadeUp} className="mb-8" aria-label={overviewTitle}>
                  <div className="mb-3 flex items-center justify-between">
                    <h2 className="text-lg font-semibold text-[color:var(--st-color-text-primary)]">
                      {overviewTitle}
                    </h2>
                  </div>
                  {chartData ? (
                  <div className={`grid grid-cols-1 gap-6 ${showRecordMetrics ? "xl:grid-cols-2" : "xl:grid-cols-1"}`}>
                    {showRecordMetrics ? (
                      <motion.div variants={fadeUp} className="min-w-0">
                        <ChartCard title={recordsTrendTitle} values={chartData.recordsTrend.data}>
                          <BarChart
                            data={chartData.recordsTrend.data}
                            xAxisData={chartData.recordsTrend.xAxis}
                            title={recordsTrendTitle}
                            unit={chartData.recordsUnit}
                          />
                        </ChartCard>
                      </motion.div>
                    ) : null}

                    <motion.div variants={fadeUp} className="min-w-0">
                      <ChartCard title={bytesTrendTitle} values={chartData.bytesTrend.data}>
                        <BarChart
                          data={chartData.bytesTrend.data}
                          xAxisData={chartData.bytesTrend.xAxis}
                          title={bytesTrendTitle}
                          unit={chartData.bytesUnit}
                        />
                      </ChartCard>
                    </motion.div>
                  </div>
                  ) : (
                    <div className="metrics-chart-card p-4">
                      {chartResult.status === "loading" ? (
                        <PanelLoading label="正在加载趋势和效率数据" />
                      ) : (
                        <PanelError
                          message="趋势和效率数据加载失败"
                          description="请重试图表数据。汇总指标可独立查看。"
                          retryLabel="重试加载图表数据"
                          onRetry={() => void reloadCharts()}
                        />
                      )}
                    </div>
                  )}
                </motion.section>

                {chartData && (
                <motion.section variants={fadeUp} className="mb-8">
                  <div className="mb-3 flex items-center justify-between">
                    <h2 className="text-lg font-semibold text-[color:var(--st-color-text-primary)]">
                      {performanceTitle}
                    </h2>
                  </div>
                  <div className={`grid grid-cols-1 gap-6 ${showRecordMetrics ? "xl:grid-cols-2" : "xl:grid-cols-1"}`}>
                    {showRecordMetrics ? (
                      <motion.div variants={fadeUp}>
                        <ChartCard title={recordsSpeedTitle} values={chartData.recordsSpeedTrend.data}>
                          <LineChart
                            data={chartData.recordsSpeedTrend.data}
                            xAxisData={chartData.recordsSpeedTrend.xAxis}
                            title={recordsSpeedTitle}
                            unit="records/s"
                          />
                        </ChartCard>
                      </motion.div>
                    ) : null}

                    <motion.div variants={fadeUp}>
                      <ChartCard title={bytesSpeedTitle} values={chartData.bytesSpeedTrend.data}>
                        <LineChart
                          data={chartData.bytesSpeedTrend.data.map(toMegabytes)}
                          xAxisData={chartData.bytesSpeedTrend.xAxis}
                          title={bytesSpeedTitle}
                          unit="MB/s"
                        />
                      </ChartCard>
                    </motion.div>
                  </div>
                </motion.section>
                )}

                {summaryReady && (
                <motion.section variants={fadeUp}>
                  <div className="mb-3 flex items-center justify-between">
                    <h2 className="text-lg font-semibold text-[color:var(--st-color-text-primary)]">
                      摘要分析
                    </h2>
                  </div>

                  <motion.div
                    variants={cardStagger}
                    initial="hidden"
                    animate="visible"
                    className="grid grid-cols-1 gap-4 xl:grid-cols-3"
                  >
                    {summaryList.map((item) => (
                      <motion.div
                        key={item.title}
                        variants={fadeUp}
                        whileHover={{ y: -4, transition: { duration: 0.18 } }}
                        className="metrics-summary-card"
                      >
                        <div className="metrics-summary-card__content p-4 md:p-6">
                          <h3 className="metrics-summary-card__title text-base font-semibold">
                            {item.title}
                          </h3>
                          <p className="metrics-summary-card__description mt-3 text-sm leading-6">
                            {item.content}
                          </p>
                        </div>
                      </motion.div>
                    ))}
                  </motion.div>
                </motion.section>
                )}
            </motion.div>
          </div>
        </div>
      </main>
    </div>
  );
};

const PanelLoading = ({ label }: { label: string }) => (
  <div className="metrics-panel-loading" role="status">
    <Spin size="small" />
    <span>{label}</span>
  </div>
);

const PanelError = ({
  message, description, retryLabel, onRetry,
}: { message: string; description: string; retryLabel: string; onRetry: () => void }) => (
  <Alert
    type="error"
    showIcon
    message={message}
    description={description}
    action={<Button size="small" onClick={onRetry} aria-label={retryLabel}>重试</Button>}
  />
);

const ChartCard: React.FC<{
  title: string;
  values: (number | null)[];
  children: React.ReactNode;
}> = ({ title, values, children }) => {
  return (
    <motion.div
      whileHover={{ y: -2, transition: { duration: 0.18 } }}
      className="metrics-chart-card"
    >
      <div className="metrics-chart-card__header flex flex-col space-y-1.5 p-4 md:p-6">
        <h3 className="metrics-chart-card__title text-base font-semibold tracking-tight md:text-xl">
          {title}
        </h3>
      </div>
      <div className="metrics-chart-card__body p-4 pt-0 md:p-6 md:pt-0">
        {values.some((value) => value != null) ? (
          <ChartRenderBoundary title={title}>{children}</ChartRenderBoundary>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前统计范围暂无数据" />
        )}
      </div>
    </motion.div>
  );
};

export default App;
