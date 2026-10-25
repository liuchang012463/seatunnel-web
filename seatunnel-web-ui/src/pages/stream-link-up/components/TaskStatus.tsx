import { message, Popover } from "antd";
import React, { useEffect, useRef, useState } from "react";
import StatusChip from "@/components/StatusChip";
import { getJobStatusView } from "@/pages/common/utils/jobStatus";

interface TaskStatusProps {
  status?: string;
  errorMessage?: string;
  showErrorDetailsLink?: boolean;
}

const getStatusConfig = (status?: string) => {
  const view = getJobStatusView(status, "stream");

  return {
    tone: view.tone,
    label: view.label.defaultMessage,
    errorDetail: view.errorDetail,
  };
};

const TaskStatus: React.FC<TaskStatusProps> = ({
  status,
  errorMessage,
  showErrorDetailsLink = true,
}) => {
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

  // 只有真正的失败态才展示错误详情，已取消等中性状态不展示。
  if (!config.errorDetail || !errorMessage) {
    return content;
  }

  const lines = errorMessage.split("\n");

  return (
    <Popover
      placement="right"
      trigger="hover"
      title={null}
      styles={{
        body: {
          padding: 0,
          borderRadius: 10,
          overflow: "hidden",
          boxShadow: "0 14px 36px rgba(15, 23, 42, 0.16)",
        },
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
        className={`inline-flex items-center gap-1.5${
          showErrorDetailsLink ? " cursor-pointer" : ""
        }`}
        onClick={(e) => e.stopPropagation()}
      >
        {content}
        {showErrorDetailsLink ? (
          <a
            style={{
              fontSize: 12,
              lineHeight: "20px",
              color: "var(--st-color-accent)",
            }}
            role="button"
            tabIndex={0}
          >
            日志
          </a>
        ) : null}
      </span>
    </Popover>
  );
};

export default TaskStatus;
