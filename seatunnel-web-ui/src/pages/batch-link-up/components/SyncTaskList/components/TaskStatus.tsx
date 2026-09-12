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
        overlayInnerStyle={{
          padding: 0,
          borderRadius: 14,
          overflow: "hidden",
          boxShadow: "0 18px 45px rgba(15, 23, 42, 0.18)",
        }}
        content={
          <div className="w-[520px] overflow-hidden rounded-[14px] border border-white/10 bg-[#0f172a] font-mono text-[13px] leading-[1.6]">
            <div className="flex h-10 items-center justify-between border-b border-white/10 bg-white/[0.03] px-3">
              <div className="flex items-center gap-1.5">
                <span className="h-2.5 w-2.5 rounded-full bg-[#ff5f57]" />
                <span className="h-2.5 w-2.5 rounded-full bg-[#ffbd2e]" />
                <span className="h-2.5 w-2.5 rounded-full bg-[#28c840]" />
              </div>

              <button
                type="button"
                onClick={handleCopy}
                className={[
                  "inline-flex items-center justify-center rounded-full border px-2.5 py-1 text-xs transition-colors",
                  copied
                    ? "border-[#86efac]/80 bg-[#f0fdf4] text-[#16a34a]"
                    : "border-white/10 bg-white/5 text-slate-300 hover:bg-white/10 hover:text-white",
                ].join(" ")}
              >
                {copied ? "COPIED" : "COPY"}
              </button>
            </div>

            <div className="max-h-[240px] min-h-[120px] overflow-auto px-3 py-3">
              {lines.map((line, index) => (
                <div key={index} className="flex items-start">
                  <span className="w-9 shrink-0 select-none pr-3 text-right text-[color:var(--st-color-text-muted)]">
                    {index + 1}
                  </span>

                  <span className="flex-1 whitespace-pre-wrap break-words text-[rgb(0,255,136)]">
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
          <a
            style={{
              fontSize: 12,
              lineHeight: '20px',
              color: 'var(--st-color-accent)',
            }}
            role="button"
            tabIndex={0}
            onClick={(e) => {
              e.stopPropagation();
              setLogOpen(true);
            }}
          >
            日志
          </a>
        </span>
      </Popover>
    );
  }

  return content;
};

export default TaskStatus;
