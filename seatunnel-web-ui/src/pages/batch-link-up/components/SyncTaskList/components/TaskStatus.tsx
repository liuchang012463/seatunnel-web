import { message, Popover } from "antd";
import type { MouseEvent } from "react";
import { useEffect, useRef, useState } from "react";
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
  PAUSED: { tone: "warning", label: "已暂停" },
  DOING_SAVEPOINT: { tone: "warning", label: "保存点中" },
  CANCELED: { tone: "neutral", label: "已取消" },
  CANCELLED: { tone: "neutral", label: "已取消" },
  INITIALIZING: { tone: "neutral", label: "初始化中" },
  CREATED: { tone: "neutral", label: "已创建" },
  PENDING: { tone: "neutral", label: "等待中" },
  SCHEDULED: { tone: "neutral", label: "已调度" },
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

const TaskStatus = ({ status, errorMessage }: TaskStatusProps) => {
  const config = getStatusConfig(status);
  const [logOpen, setLogOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const timerRef = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      if (timerRef.current) {
        window.clearTimeout(timerRef.current);
      }
    };
  }, []);

  const handleCopy = async (e?: MouseEvent) => {
    e?.stopPropagation();

    if (!errorMessage) return;

    try {
      await navigator.clipboard.writeText(errorMessage);
      setCopied(true);

      if (timerRef.current) {
        window.clearTimeout(timerRef.current);
      }

      timerRef.current = window.setTimeout(() => {
        setCopied(false);
      }, 1800);
    } catch (err) {
      message.error(String(err));
    }
  };

  const content = <StatusChip tone={config.tone} label={config.label} />;

  if (String(status || "").toUpperCase() === "FAILED" && errorMessage) {
    const lines = errorMessage.split("\n");

    return (
      <Popover
        placement="right"
        trigger="hover"
        open={logOpen}
        onOpenChange={setLogOpen}
        title={null}
        styles={{
          body: {
            padding: 0,
            overflow: "hidden",
            boxShadow: "none",
          },
        }}
        content={
          <div className="sync-task-error-popover">
            <div className="sync-task-error-popover__header">
              <span>失败原因</span>

              <button
                type="button"
                aria-label="复制失败原因"
                onClick={handleCopy}
                className={`sync-task-error-popover__copy${copied ? " is-copied" : ""}`}
              >
                {copied ? "已复制" : "复制"}
              </button>
            </div>

            <div className="sync-task-error-popover__body">
              {lines.map((line, index) => (
                <div key={index} className="sync-task-error-popover__line">
                  <span className="sync-task-error-popover__line-number">
                    {index + 1}
                  </span>

                  <span className="sync-task-error-popover__line-text">
                    {line || " "}
                  </span>
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
          <button
            type="button"
            className="sync-task-log-link"
            aria-label={`查看${config.label}任务日志`}
            onClick={(e) => {
              e.stopPropagation();
              setLogOpen(true);
            }}
          >
            日志
          </button>
        </span>
      </Popover>
    );
  }

  return content;
};

export default TaskStatus;
