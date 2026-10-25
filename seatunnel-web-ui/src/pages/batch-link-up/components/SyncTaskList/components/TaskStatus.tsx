import { message, Popover } from "antd";
import React, { useEffect, useRef, useState } from "react";
import type { MouseEvent } from "react";
import StatusChip from "@/components/StatusChip";
import { getJobStatusView } from "@/pages/common/utils/jobStatus";

interface TaskStatusProps {
  status?: string;
  errorMessage?: string;
}

const getStatusConfig = (status?: string) => {
  const view = getJobStatusView(status, "batch");

  return {
    tone: view.tone,
    label: view.label.defaultMessage,
    errorDetail: view.errorDetail,
  };
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

  // 只有真正的失败态才展示失败原因，已取消等中性状态不展示。
  if (config.errorDetail && errorMessage) {
    const lines = errorMessage.split("\n");
    const errorTitle = "失败原因";

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
