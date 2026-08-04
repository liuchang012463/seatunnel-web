import { message, Popover } from "antd";
import React, { useEffect, useRef, useState } from "react";

interface TaskStatusProps {
  status?: string;
  errorMessage?: string;
}

const statusConfig: Record<string, { color: string; label: string }> = {
  FINISHED: { color: "#16a34a", label: "已完成" },
  SUCCESS: { color: "#16a34a", label: "已完成" },
  RUNNING: { color: "#1677ff", label: "运行中" },
  FAILED: { color: "#ef4444", label: "失败" },
  FAILING: { color: "#ef4444", label: "失败中" },
  CANCELED: { color: "#64748b", label: "已取消" },
  CANCELLED: { color: "#64748b", label: "已取消" },
  PAUSED: { color: "#f59e0b", label: "已暂停" },
  INITIALIZING: { color: "#64748b", label: "初始化中" },
  CREATED: { color: "#64748b", label: "已创建" },
  PENDING: { color: "#64748b", label: "等待中" },
  SCHEDULED: { color: "#64748b", label: "已调度" },
  DOING_SAVEPOINT: { color: "#f59e0b", label: "保存点中" },
  CANCELING: { color: "#64748b", label: "取消中" },
};

const getStatusConfig = (status?: string) => {
  const normalizedStatus = String(status || "").toUpperCase();

  return (
    statusConfig[normalizedStatus] || {
      color: "#64748b",
      label: status ? "未识别" : "未开始",
    }
  );
};

const TaskStatus: React.FC<TaskStatusProps> = ({ status, errorMessage }) => {
  const config = getStatusConfig(status);
  const [copied, setCopied] = useState(false);
  const timerRef = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      if (timerRef.current) {
        window.clearTimeout(timerRef.current);
      }
    };
  }, []);

  const handleCopy = async (event: React.MouseEvent) => {
    event.stopPropagation();

    if (!errorMessage) return;

    try {
      await navigator.clipboard.writeText(errorMessage);
      setCopied(true);

      if (timerRef.current) {
        window.clearTimeout(timerRef.current);
      }

      timerRef.current = window.setTimeout(() => setCopied(false), 1800);
    } catch {
      message.error("复制失败，请手动复制");
    }
  };

  const content = (
    <span
      className="stream-link-status-text"
      style={{ color: config.color }}
      title={config.label}
      aria-label={config.label}
    >
      {config.label}
    </span>
  );

  if (String(status || "").toUpperCase() !== "FAILED" || !errorMessage) {
    return content;
  }

  const lines = errorMessage.split("\n");

  return (
    <Popover
      placement="right"
      trigger="hover"
      title={null}
      overlayInnerStyle={{
        padding: 0,
        borderRadius: 10,
        overflow: "hidden",
        boxShadow: "0 14px 36px rgba(15, 23, 42, 0.16)",
      }}
      content={
        <div className="stream-link-error-popover">
          <div className="stream-link-error-popover__header">
            <span>任务错误详情</span>
            <button type="button" onClick={handleCopy}>
              {copied ? "已复制" : "复制"}
            </button>
          </div>
          <div className="stream-link-error-popover__body">
            {lines.map((line, index) => (
              <div key={index} className="stream-link-error-popover__line">
                <span>{index + 1}</span>
                <code>{line || " "}</code>
              </div>
            ))}
          </div>
        </div>
      }
    >
      <span className="cursor-pointer">{content}</span>
    </Popover>
  );
};

export default TaskStatus;
