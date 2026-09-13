import { useIntl } from "@umijs/max";

interface ExecutionStatusProps {
  record: any;
}

/** 执行概况内联指标组（DESIGN.md §5.3）：去掉逐行 label 冒号，等宽数字。 */
const ExecutionStatus: React.FC<ExecutionStatusProps> = ({ record }) => {
  const intl = useIntl();
  const isManual = record?.runMode === "MANUAL";

  return (
    <div className="task-metric-group">
      <span
        className={`task-metric-group__badge ${
          isManual ? "is-manual" : "is-auto"
        }`}
      >
        {isManual ? "手动" : "自动"}
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
      <span className="task-metric">
        <span className="task-metric__label">
          {intl.formatMessage({
            id: "pages.job.execution.amount",
            defaultMessage: "行数",
          })}
        </span>
        <span className="task-metric__value">
          {record?.readRowCount ?? 0}
        </span>
      </span>
      <span className="task-metric">
        <span className="task-metric__label">QPS</span>
        <span className="task-metric__value">
          {record?.qps ?? 0}
          {intl.formatMessage({
            id: "pages.job.execution.unit.rowsPerSecond",
            defaultMessage: "行/秒",
          })}
        </span>
      </span>
      <span className="task-metric">
        <span className="task-metric__label">
          {intl.formatMessage({
            id: "pages.job.execution.size",
            defaultMessage: "大小",
          })}
        </span>
        <span className="task-metric__value">{record?.syncSize || "-"}</span>
      </span>
    </div>
  );
};

export default ExecutionStatus;
