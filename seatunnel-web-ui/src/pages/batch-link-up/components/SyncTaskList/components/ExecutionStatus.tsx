import { useIntl } from "@umijs/max";

interface ExecutionStatusProps {
  record: any;
}

const ExecutionStatus: React.FC<ExecutionStatusProps> = ({ record }) => {
  const intl = useIntl();
  const isManual = record?.runMode === "MANUAL";

  return (
    <>
      {/* Execution */}
      <div style={{ display: "flex", alignItems: "center" }}>
        <span
          style={{
            fontWeight: 700,
            fontSize: 19,
            marginRight: 8,
            color: "#1890ff",
          }}
        >
          ·
        </span>

        <span style={{ marginRight: 16, fontWeight: 700, color: "#333" }}>
          {intl.formatMessage({
            id: "pages.job.execution.runMode",
            defaultMessage: "运行模式：",
          })}{" "}
        </span>

        <div
          style={{
            minWidth: 42,
            padding: "2px 8px",
            color: isManual ? "#ffd591" : "#b7eb8f",
            fontSize: 12,
            fontWeight: 700,
            lineHeight: "18px",
            textAlign: "center",
            background: isManual
              ? "rgba(250, 140, 22, 0.16)"
              : "rgba(82, 196, 26, 0.14)",
            border: `1px solid ${
              isManual ? "rgba(250, 140, 22, 0.34)" : "rgba(82, 196, 26, 0.32)"
            }`,
            borderRadius: 999,
          }}
        >
          {isManual ? "手动" : "自动"}
        </div>
      </div>

      {/* Time */}
      <div style={{ display: "flex", alignItems: "center" }}>
        <span style={{ fontWeight: 700, fontSize: 19, marginRight: 8 }}>·</span>
        <span style={{ marginRight: 42, fontWeight: 700 }}>
          {intl.formatMessage({
            id: "pages.job.execution.time",
            defaultMessage: "耗时：",
          })}{" "}
        </span>
        <span style={{ color: "gray" }}>
          {record?.duration || "-"}{" "}
          {intl.formatMessage({
            id: "pages.job.execution.unit.seconds",
            defaultMessage: "秒",
          })}
        </span>
      </div>

      {/* Amount */}
      <div style={{ display: "flex", alignItems: "center" }}>
        <span style={{ fontWeight: 700, fontSize: 19, marginRight: 8 }}>·</span>
        <span style={{ marginRight: 30, fontWeight: 700 }}>
          {intl.formatMessage({
            id: "pages.job.execution.amount",
            defaultMessage: "数据量：",
          })}{" "}
        </span>
        <span style={{ color: "gray" }}>
          {record?.readRowCount ?? 0}{" "}
          {intl.formatMessage({
            id: "pages.job.execution.unit.rows",
            defaultMessage: "行",
          })}
        </span>
      </div>

      {/* QPS */}
      <div style={{ display: "flex", alignItems: "center" }}>
        <span style={{ fontWeight: 700, fontSize: 19, marginRight: 8 }}>·</span>
        <span style={{ marginRight: 43, fontWeight: 700 }}>
          {intl.formatMessage({
            id: "pages.job.execution.qps",
            defaultMessage: "Qps：",
          })}{" "}
        </span>
        <span style={{ color: "gray" }}>
          {record?.qps ?? 0}{" "}
          {intl.formatMessage({
            id: "pages.job.execution.unit.rowsPerSecond",
            defaultMessage: "行/秒",
          })}
        </span>
      </div>

      {/* Size */}
      <div style={{ display: "flex", alignItems: "center" }}>
        <span style={{ fontWeight: 700, fontSize: 19, marginRight: 8 }}>·</span>
        <span style={{ marginRight: 43, fontWeight: 700 }}>
          {intl.formatMessage({
            id: "pages.job.execution.size",
            defaultMessage: "大小：",
          })}{" "}
        </span>
        <span style={{ color: "gray" }}>{record?.syncSize || "-"}</span>
      </div>
    </>
  );
};

export default ExecutionStatus;
