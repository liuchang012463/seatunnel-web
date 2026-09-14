import { message, Popover } from "antd";
import React, { useEffect, useRef, useState } from "react";
import StatusChip, { type StatusChipTone } from "@/components/StatusChip";

interface TaskStatusProps {
  status?: string;
  errorMessage?: string;
}

const statusConfig: Record<string, { tone: StatusChipTone; label: string }> = {
  FINISHED: { tone: "success", label: "已完成" },
  SUCCESS: { tone: "success", label: "已完成" },
  RUNNING: { tone: "processing", label: "运行中" },
  FAILED: { tone: "error", label: "失败" },
  FAILING: { tone: "error", label: "失败中" },
  CANCELED: { tone: "neutral", label: "已取消" },
  CANCELLED: { tone: "neutral", label: "已取消" },
  PAUSED: { tone: "warning", label: "已暂停" },
  INITIALIZING: { tone: "neutral", label: "初始化中" },
  CREATED: { tone: "neutral", label: "已创建" },
  PENDING: { tone: "neutral", label: "等待中" },
  SCHEDULED: { tone: "neutral", label: "已调度" },
  DOING_SAVEPOINT: { tone: "warning", label: "保存点中" },
  CANCELING: { tone: "neutral", label: "取消中" },
};

const getStatusConfig = (status?: string) => {
  const normalizedStatus = String(status || "").toUpperCase();

  return (
    statusConfig[normalizedStatus] || {
      tone: "neutral" as StatusChipTone,
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

  const content = <StatusChip tone={config.tone} label={config.label} />;

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
      <span
        className="inline-flex cursor-pointer items-center gap-1.5"
        onClick={(e) => e.stopPropagation()}
      >
        {content}
        <a
          style={{
            fontSize: 12,
            lineHeight: '20px',
            color: 'var(--st-color-accent)',
          }}
          role="button"
          tabIndex={0}
        >
          日志
        </a>
      </span>
    </Popover>
  );
};

export default TaskStatus;
