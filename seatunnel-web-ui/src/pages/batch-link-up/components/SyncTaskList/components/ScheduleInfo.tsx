import { Badge, message, Popover } from "antd";
import { useState } from "react";
import { seatunnelJobScheduleApi } from "../../../api";
import { useIntl } from "@umijs/max";

interface ExecutionStatusProps {
  record: any;
}

/**
 * 调度时间压到「MM-DD HH:mm」（DESIGN.md §4.2 时间列口径）：
 * 完整时间在 154px 的调度列里放不下一行，会被迫折成两行，行高随数据变化。
 * 完整值仍通过 title 悬浮可见。
 */
const formatCompactTime = (value?: string) => {
  if (!value) return "-";

  const normalized = String(value).replace("T", " ");
  const [date, time] = normalized.split(" ");

  if (!date || !time) return normalized;

  const [, month, day] = date.split(/[-/]/);

  return month && day ? `${month}-${day} ${time.slice(0, 5)}` : normalized;
};

/** 调度信息内联指标组（DESIGN.md §5.3）：每项固定一行，Cron 保留最近 5 次弹层。 */
const ScheduleInfo: React.FC<ExecutionStatusProps> = ({ record }) => {
  const intl = useIntl();
  const [cronExpression, setCronExpression] = useState<any[]>([]);

  const renderStatus = (status: string) => {
    if (status === "NORMAL") {
      return <span className="task-metric__value is-success">启用</span>;
    }
    return <span className="task-metric__value is-error">暂停</span>;
  };

  const executionMode =
    record?.executionMode || (record?.cronExpression ? "AUTO" : "MANUAL");

  const lastRunLabel = intl.formatMessage({
    id: "pages.job.schedule.lastRunTime",
    defaultMessage: "上次运行",
  });
  const nextRunLabel = intl.formatMessage({
    id: "pages.job.schedule.nextRunTime",
    defaultMessage: "下次运行",
  });

  const renderTime = (value?: string) => (
    <span className="task-metric__value" title={value || undefined}>
      {formatCompactTime(value)}
    </span>
  );

  if (executionMode === "MANUAL") {
    return (
      <div className="task-metric-group">
        <div className="task-metric-group__line">
          <span className="task-metric">
            <span className="task-metric__label">执行方式</span>
            <span className="task-metric__value">手动触发</span>
          </span>
        </div>
        <div className="task-metric-group__line">
          <span className="task-metric">
            <span className="task-metric__label">{lastRunLabel}</span>
            {renderTime(record?.lastScheduleTime)}
          </span>
        </div>
        <div className="task-metric-group__line">
          <span className="task-metric">
            <span className="task-metric__label">{nextRunLabel}</span>
            <span className="task-metric__value">-</span>
          </span>
        </div>
      </div>
    );
  }

  return (
    <div className="task-metric-group">
      <div className="task-metric-group__line">
        <span className="task-metric">
          <span className="task-metric__label">执行方式</span>
          <span className="task-metric__value">自动调度</span>
        </span>
      </div>
      <div className="task-metric-group__line">
        <span className="task-metric">
          <span className="task-metric__label">
            {intl.formatMessage({
              id: "pages.job.schedule.cron",
              defaultMessage: "Cron",
            })}
          </span>
          <Popover
            content={
              <div style={{ padding: "0 0 0 6px" }}>
                {cronExpression.map((item, index) => (
                  <div
                    key={index}
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: 4,
                      fontSize: 12,
                    }}
                  >
                    <Badge status="success" text={item || "-"} />
                  </div>
                ))}
              </div>
            }
            title={intl.formatMessage({
              id: "pages.job.schedule.last5RunsTitle",
              defaultMessage: "⏰ 最近 5 次运行时间",
            })}
            trigger="click"
          >
            <a
              onClick={() => {
                if (record?.cronExpression) {
                  seatunnelJobScheduleApi
                    .getLast5ExecutionTimes(record?.cronExpression)
                    .then((data) => {
                      if (data?.code === 0) {
                        setCronExpression(data?.data || []);
                      } else {
                        message.error(data?.msg);
                      }
                    });
                } else {
                  message.error(
                    intl.formatMessage({
                      id: "pages.job.message.unknownError",
                      defaultMessage: "Unknown Error",
                    }),
                  );
                }
              }}
              className="task-metric__link"
              title={record?.cronExpression || undefined}
            >
              {record?.cronExpression || "-"}
            </a>
          </Popover>
        </span>
      </div>
      <div className="task-metric-group__line">
        <span className="task-metric">
          <span className="task-metric__label">调度状态</span>
          {renderStatus(record?.scheduleStatus)}
        </span>
      </div>
      <div className="task-metric-group__line">
        <span className="task-metric">
          <span className="task-metric__label">{lastRunLabel}</span>
          {renderTime(record?.lastScheduleTime)}
        </span>
      </div>
      <div className="task-metric-group__line">
        <span className="task-metric">
          <span className="task-metric__label">{nextRunLabel}</span>
          {renderTime(record?.nextScheduleTime)}
        </span>
      </div>
    </div>
  );
};

export default ScheduleInfo;
