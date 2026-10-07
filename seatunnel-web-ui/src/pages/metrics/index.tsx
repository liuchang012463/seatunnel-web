import { ReloadOutlined } from "@ant-design/icons";
import { Alert, Button, Select, Spin } from "antd";
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
import "./index.less";
import LineChart from "./LineChart";
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

const App: React.FC = () => {
  const [chartData, setChartData] = useState<ChartData>({
    recordsTrend: { data: [], xAxis: [] },
    bytesTrend: { data: [], xAxis: [] },
    recordsSpeedTrend: { data: [], xAxis: [] },
    bytesSpeedTrend: { data: [], xAxis: [] },
  });

  const [loading, setLoading] = useState(false);
  const [pageReady, setPageReady] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const [summaryData, setSummaryData] = useState<SummaryData>({
    totalRecords: 0,
    totalBytes: 0,
    totalTasks: 0,
    successTasks: 0,
    failedTasks: 0,
    runningTasks: 0,
    stoppedTasks: 0,
    avgRecordDelay: 0,
    totalBytesUnit: "-",
    totalRecordsUnit: "-",
  });

  const [taskType, setTaskType] = useState<TaskType>("BATCH");
  const [timeRange, setTimeRange] = useState<TimeRange>("D30");
  const requestGeneration = useRef(0);

  const refreshData = useCallback(async () => {
    const generation = ++requestGeneration.current;
    try {
      setLoading(true);
      setPageReady(false);
      setErrorMessage(null);

      const [summary, apiData] = await Promise.all([
        fetchSummaryData(timeRange, taskType),
        fetchChartData(timeRange, taskType),
      ]);
      const processedData: ChartData = {
        recordsTrend: transformChartData(apiData.recordsTrend as any, timeRange),
        bytesTrend: transformChartData(apiData.bytesTrend as any, timeRange),
        recordsSpeedTrend: transformChartData(
          apiData.recordsSpeedTrend as any,
          timeRange
        ),
        bytesSpeedTrend: transformChartData(
          apiData.bytesSpeedTrend as any,
          timeRange
        ),
      };

      if (generation !== requestGeneration.current) return;
      setSummaryData(summary);
      setChartData(processedData);
      setPageReady(true);
    } catch (error: unknown) {
      if (generation === requestGeneration.current) {
        setErrorMessage("任务概览暂不可用，请检查连接后重试");
      }
    } finally {
      if (generation === requestGeneration.current) {
        setLoading(false);
      }
    }
  }, [timeRange, taskType]);

  useEffect(() => {
    refreshData();
    return () => {
      requestGeneration.current += 1;
    };
  }, [refreshData]);

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
    <div
      style={{
        height: "calc(100vh - 56px)",
        backgroundColor: "var(--st-color-bg-page)",
        overflow: "hidden",
      }}
    >
      <main className="h-full overflow-auto px-4 py-4 md:px-6 md:py-5">
        <div className="min-h-full bg-transparent p-0">
          <div className="mx-auto max-w-[1600px]">
            {errorMessage ? (
              <Alert
                className="mb-4"
                type="error"
                showIcon
                message="任务概览加载失败"
                description={errorMessage}
                action={
                  <Button
                    size="small"
                    onClick={() => void refreshData()}
                    aria-label="重试加载任务概览"
                  >
                    重试
                  </Button>
                }
              />
            ) : null}
            <motion.div
              initial="hidden"
              animate={pageReady ? "visible" : "hidden"}
              variants={sectionStagger}
            >
              <motion.div
                variants={fadeUp}
                className="mb-6 flex gap-4 lg:flex-row lg:items-center lg:justify-between"
              >
                <div>
                  <h1 className="flex items-center gap-2 text-2xl font-bold md:text-3xl">
                    <svg
                      xmlns="http://www.w3.org/2000/svg"
                      width="24"
                      height="24"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth={2}
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      className="h-7 w-7"
                      style={{ color: "var(--st-color-accent)" }}
                    >
                      <path d="M3 3v16a2 2 0 0 0 2 2h16" />
                      <path d="M18 17V9" />
                      <path d="M13 17V5" />
                      <path d="M8 17v-3" />
                    </svg>
                    任务洞察
                  </h1>
                </div>

                <div className="flex flex-wrap items-center gap-3">
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
                    onClick={() => void refreshData()}
                    aria-label="刷新任务概览"
                  >
                    刷新
                  </Button>
                </div>
              </motion.div>

              <Spin spinning={loading}>
                <motion.section variants={fadeUp} className="mb-8">
                  <motion.div
                    variants={cardStagger}
                    initial="hidden"
                    animate={pageReady ? "visible" : "hidden"}
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
                          <p className="metrics-stat-card__title mt-1 text-xs">
                            {item.title}
                          </p>
                          <p className="metrics-stat-card__title mt-1 text-xs">
                            {item.subText}
                          </p>
                        </div>
                      </motion.div>
                    ))}
                  </motion.div>
                </motion.section>

                <motion.section variants={fadeUp} className="mb-8">
                  <div className="mb-3 flex items-center justify-between">
                    <h2 className="text-lg font-semibold text-[color:var(--st-color-text-primary)]">
                      {overviewTitle}
                    </h2>
                  </div>
                  <div className={`grid grid-cols-1 gap-6 ${showRecordMetrics ? "xl:grid-cols-2" : "xl:grid-cols-1"}`}>
                    {showRecordMetrics ? (
                      <motion.div variants={fadeUp} className="min-w-0">
                        <ChartCard title={recordsTrendTitle}>
                          <BarChart
                            data={chartData.recordsTrend.data}
                            xAxisData={chartData.recordsTrend.xAxis}
                            title={recordsTrendTitle}
                            unit={String(summaryData.totalRecordsUnit || "条")}
                            loading={loading}
                          />
                        </ChartCard>
                      </motion.div>
                    ) : null}

                    <motion.div variants={fadeUp} className="min-w-0">
                      <ChartCard title={bytesTrendTitle}>
                        <BarChart
                          data={chartData.bytesTrend.data}
                          xAxisData={chartData.bytesTrend.xAxis}
                          title={bytesTrendTitle}
                          unit={String(summaryData.totalBytesUnit || "B")}
                          loading={loading}
                        />
                      </ChartCard>
                    </motion.div>
                  </div>
                </motion.section>

                <motion.section variants={fadeUp} className="mb-8">
                  <div className="mb-3 flex items-center justify-between">
                    <h2 className="text-lg font-semibold text-[color:var(--st-color-text-primary)]">
                      {performanceTitle}
                    </h2>
                  </div>
                  <div className={`grid grid-cols-1 gap-6 ${showRecordMetrics ? "xl:grid-cols-2" : "xl:grid-cols-1"}`}>
                    {showRecordMetrics ? (
                      <motion.div variants={fadeUp}>
                        <ChartCard title={recordsSpeedTitle}>
                          <LineChart
                            data={chartData.recordsSpeedTrend.data}
                            xAxisData={chartData.recordsSpeedTrend.xAxis}
                            title={recordsSpeedTitle}
                            unit="records/s"
                            loading={loading}
                          />
                        </ChartCard>
                      </motion.div>
                    ) : null}

                    <motion.div variants={fadeUp}>
                      <ChartCard title={bytesSpeedTitle}>
                        <LineChart
                          data={chartData.bytesSpeedTrend.data}
                          xAxisData={chartData.bytesSpeedTrend.xAxis}
                          title={bytesSpeedTitle}
                          unit="B/s"
                          loading={loading}
                        />
                      </ChartCard>
                    </motion.div>
                  </div>
                </motion.section>

                <motion.section variants={fadeUp}>
                  <div className="mb-3 flex items-center justify-between">
                    <h2 className="text-lg font-semibold text-[color:var(--st-color-text-primary)]">
                      摘要分析
                    </h2>
                  </div>

                  <motion.div
                    variants={cardStagger}
                    initial="hidden"
                    animate={pageReady ? "visible" : "hidden"}
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
              </Spin>
            </motion.div>
          </div>
        </div>
      </main>
    </div>
  );
};

const ChartCard: React.FC<{
  title: string;
  children: React.ReactNode;
}> = ({ title, children }) => {
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
        {children}
      </div>
    </motion.div>
  );
};

export default App;
