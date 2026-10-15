import { message, Popover } from "antd";
import type { MouseEvent } from "react";
import { useEffect, useRef, useState } from "react";
import StatusChip, { type StatusChipTone } from "@/components/StatusChip";
import {
  normalizeBatchJobStatus,
  rawBatchJobStatusLabel,
} from "@/pages/batch-link-up/utils/normalizeJobStatus";

interface TaskStatusProps {
  status?: string;
  errorMessage?: string;
}

const statusConfig: Record<string, { tone: StatusChipTone; label: string }> = {
  FINISHED: { tone: "success", label: "成功" },
  RUNNING: { tone: "processing", label: "运行中" },
  FAILED: { tone: "error", label: "失败" },
};

const getStatusConfig = (status?: string) => {
  const rawLabel = rawBatchJobStatusLabel(status);

  if (rawLabel) {
    return {
      tone: (rawLabel === "提交结果待确认"
        ? "warning"
        : "neutral") as StatusChipTone,
      label: rawLabel,
    };
  }

  const normalizedStatus = normalizeBatchJobStatus(status);

  if (normalizedStatus === "UNSTARTED") {
    return {
      tone: "neutral" as StatusChipTone,
      label: "未开始",
    };
  }

  return statusConfig[normalizedStatus];
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

  const isUnknownSubmission = config.label === "提交结果待确认";

  if ((config.label === "失败" || isUnknownSubmission) && errorMessage) {
    const lines = errorMessage.split("\n");
    const errorTitle = isUnknownSubmission ? "提交结果待确认" : "失败原因";

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
              <span>{errorTitle}</span>

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
