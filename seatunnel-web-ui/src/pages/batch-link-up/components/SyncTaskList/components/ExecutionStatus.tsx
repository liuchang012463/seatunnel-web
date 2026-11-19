import React from "react";
import { useIntl } from "@umijs/max";
import {
  getThroughputMegabytesPerSecond,
  isTerminalThroughputStatus,
} from "@/pages/common/utils/throughput";

interface ExecutionStatusProps {
  record: any;
}

/**
 * 执行概况内联指标组（DESIGN.md §5.3）：去掉逐行 label 冒号，等宽数字。
 *
 * 指标按固定四行排布（运行与耗时 · 读写速度 · 行数与 QPS · 大小），不依赖容器宽度自动折行：
 * 自动折行会随数据长短产生 2~4 行，同一列表里每行高度不一致。
 */
const ExecutionStatus: React.FC<ExecutionStatusProps> = ({ record }) => {
  const intl = useIntl();
  const isManual = record?.runMode === "MANUAL";
  const status = record?.jobStatus || record?.lastJobStatus || record?.latestMetrics?.jobStatus;
  const terminal = isTerminalThroughputStatus(status);
  const startTime = new Date(String(record?.lastStartTime || record?.startTime || record?.lastSubmitTime || record?.submitTime || "").replace(" ", "T")).getTime();
  const endTime = new Date(String(record?.lastEndTime || record?.endTime || "").replace(" ", "T")).getTime();
  // runMode describes the last execution, not the configured schedule mode.
  const runModeLabel = isManual ? "手动" : record?.runMode === "SCHEDULED" ? "自动" : "—";
  const runtimeMillis = Number.isFinite(startTime) && Number.isFinite(endTime) && endTime > startTime
    ? endTime - startTime
    : 0;
  const toMegabytesPerSecond = (bps: any, bytes: any) => {
    const value = getThroughputMegabytesPerSecond(bps, bytes, runtimeMillis, terminal);
    return value === undefined ? "—" : `${value.toFixed(2)} MB/s`;
  };
  const hasMetrics = record?.metricsAvailable !== false && record?.latestMetrics?.metricsAvailable !== false;
  const readRowCount = hasMetrics ? record?.latestMetrics?.readRowCount ?? record?.readRowCount ?? record?.amount ?? 0 : undefined;
  const qps = hasMetrics ? record?.latestMetrics?.readQps ?? record?.readQps ?? record?.qps ?? 0 : undefined;
  const readBytes = hasMetrics ? record?.latestMetrics?.readBytes ?? record?.readBytes : undefined;
  const writeBytes = hasMetrics ? record?.latestMetrics?.writeBytes ?? record?.writeBytes : undefined;
  const readBps = hasMetrics ? record?.latestMetrics?.readBps ?? record?.readBps : undefined;
  const writeBps = hasMetrics ? record?.latestMetrics?.writeBps ?? record?.writeBps : undefined;

  return (
    <div className="task-metric-group">
      <div className="task-metric-group__line">
        <span
          className={`task-metric-group__badge ${
            isManual ? "is-manual" : "is-auto"
          }`}
        >
          {runModeLabel}
        </span>
        <span className="task-metric">
          <span className="task-metric__label">
            {intl.formatMessage({
              id: "pages.job.execution.time",
              defaultMessage: "耗时",
            })}
          </span>
          <span className="task-metric__value">
            {record?.duration || "-"}
            {intl.formatMessage({
              id: "pages.job.execution.unit.seconds",
              defaultMessage: "s",
            })}
          </span>
        </span>
      </div>
      <div className="task-metric-group__line">
        <span className="task-metric">
          <span className="task-metric__label">读/写速度</span>
          <span className="task-metric__value">
            R {toMegabytesPerSecond(readBps, readBytes)} / W {toMegabytesPerSecond(writeBps, writeBytes)}
          </span>
        </span>
      </div>
      <div className="task-metric-group__line">
        <span className="task-metric">
          <span className="task-metric__label">
            {intl.formatMessage({
              id: "pages.job.execution.amount",
              defaultMessage: "行数",
            })}
          </span>
          <span className="task-metric__value">
            {readRowCount ?? "—"}
          </span>
        </span>
        <span className="task-metric">
          <span className="task-metric__label">QPS</span>
          <span className="task-metric__value">
            {qps ?? "—"}
            {intl.formatMessage({
              id: "pages.job.execution.unit.rowsPerSecond",
              defaultMessage: "行/秒",
            })}
          </span>
        </span>
      </div>
      <div className="task-metric-group__line">
        <span className="task-metric">
          <span className="task-metric__label">
            {intl.formatMessage({
              id: "pages.job.execution.size",
              defaultMessage: "大小",
            })}
          </span>
          <span className="task-metric__value">{hasMetrics ? record?.syncSize || "-" : "—"}</span>
        </span>
      </div>
    </div>
  );
};

export default ExecutionStatus;
