import { FileSearchOutlined, ReloadOutlined } from "@ant-design/icons";
import { Empty, Input, Modal, Spin, Tooltip } from "antd";
import React, { FC, ReactNode, useCallback, useEffect, useMemo, useState } from "react";

import { jobLogApi, type JobLogMode } from "@/services/jobLog";

import "../../../sumary.less";

type RunLogInstanceType = "BATCH" | "STREAMING" | string;

interface RunLogDrawerProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  subtitle?: string;
  footer?: ReactNode;
  children?: ReactNode;
  instanceId?: string | number;
  jobMode?: RunLogInstanceType;
  /** @deprecated 已改为与「查看历史」同款 Modal，保留以免调用方报错 */
  leftOffset?: number;
  /** @deprecated 已改为与「查看历史」同款 Modal，保留以免调用方报错 */
  defaultHeight?: number | string;
  /** @deprecated 已改为与「查看历史」同款 Modal，保留以免调用方报错 */
  minHeight?: number | string;
  /** @deprecated 已改为与「查看历史」同款 Modal，保留以免调用方报错 */
  maxHeight?: number | string;
}

const getResponseData = (response: any) => response?.data ?? response;

const getLogItemContent = (item: any) => {
  if (!item) {
    return "";
  }

  if (typeof item === "string") {
    return item;
  }

  return item?.content || item?.logContent || item?.log || item?.message || item?.data || "";
};

const formatLogContent = (value: any): string => {
  const data = getResponseData(value);

  if (!data) {
    return "";
  }

  if (typeof data === "string") {
    return data;
  }

  if (Array.isArray(data?.logs)) {
    return formatLogContent(data.logs);
  }

  if (Array.isArray(data)) {
    return data
      .map((item) => {
        if (typeof item === "string") {
          return item;
        }

        const header = [
          item?.node ? `# Node: ${item.node}` : "",
          item?.logName ? `# File: ${item.logName}` : "",
        ]
          .filter(Boolean)
          .join("\n");
        const content = getLogItemContent(item);

        if (content) {
          return header ? `${header}\n\n${content}` : content;
        }

        return JSON.stringify(item, null, 2);
      })
      .filter(Boolean)
      .join("\n\n");
  }

  return String(getLogItemContent(data) || JSON.stringify(data, null, 2));
};

const RunLogDrawer: FC<RunLogDrawerProps> = ({
  open,
  onClose,
  title = "运行日志",
  subtitle = "查看任务运行输出",
  footer,
  children,
  instanceId,
  jobMode = "BATCH",
}) => {
  const [loading, setLoading] = useState(false);
  const [logContent, setLogContent] = useState("");
  const [errorText, setErrorText] = useState("");
  const [searchKeyword, setSearchKeyword] = useState("");

  const loadLogs = useCallback(
    async (showLoading = true) => {
      if (!open) {
        return;
      }

      if (!instanceId) {
        setLogContent("");
        setErrorText("当前任务暂无运行实例，无法查看日志");
        return;
      }

      if (showLoading) {
        setLoading(true);
      }

      try {
        const response = await jobLogApi.content(instanceId, jobMode as JobLogMode);
        if (response?.code !== 0) {
          setErrorText(response?.msg || "获取日志失败");
          return;
        }

        setLogContent(formatLogContent(response));
        setErrorText("");
      } catch (error: any) {
        setErrorText(error?.message || "获取日志失败");
      } finally {
        if (showLoading) {
          setLoading(false);
        }
      }
    },
    [instanceId, jobMode, open],
  );

  useEffect(() => {
    if (!open) {
      return;
    }

    setSearchKeyword("");
    void loadLogs(true);

    // 打开期间定时拉取，保持「实时刷新」
    const timer = window.setInterval(() => {
      void loadLogs(false);
    }, 3000);

    return () => window.clearInterval(timer);
  }, [loadLogs, open]);

  const logLines = useMemo(() => logContent.split(/\r?\n/), [logContent]);
  const normalizedKeyword = searchKeyword.trim().toLowerCase();
  const matchedLineCount = normalizedKeyword
    ? logLines.filter((line) => line.toLowerCase().includes(normalizedKeyword)).length
    : 0;

  const modalTitle = (
    <div className="flex min-w-0 items-center justify-between gap-3 pr-8">
      <div className="flex min-w-0 items-center gap-3">
        <Tooltip title="当前任务实例的实时原始日志">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-[color:var(--st-color-bg-control)] text-[color:var(--st-color-accent)]">
            <FileSearchOutlined className="text-[15px]" />
          </div>
        </Tooltip>
        <div className="min-w-0">
          <div className="truncate text-[15px] font-semibold text-[color:var(--st-color-text-primary)]">
            {title}
          </div>
          <div className="truncate text-sm text-[color:var(--st-color-text-secondary)]">
            {subtitle} · 实时刷新
          </div>
        </div>
      </div>
      <button
        type="button"
        onClick={() => void loadLogs(true)}
        disabled={loading}
        className={[
          "inline-flex h-8 shrink-0 items-center gap-1.5 rounded-md px-2.5 text-sm font-medium",
          "border-0 bg-transparent text-[var(--st-color-text-secondary)]",
          "transition-colors duration-200 hover:bg-[var(--st-color-hover)] hover:text-[var(--st-color-accent)]",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--st-color-focus)]",
          loading ? "cursor-not-allowed opacity-60" : "active:scale-[0.98]",
        ].join(" ")}
      >
        <ReloadOutlined className={loading ? "animate-spin" : ""} />
        刷新
      </button>
    </div>
  );

  return (
    <Modal
      title={modalTitle}
      open={open}
      onCancel={onClose}
      styles={{
        mask: { background: "var(--st-color-modal-mask)" },
        content: {
          display: "flex",
          flexDirection: "column",
          flex: "1 1 0%",
          minHeight: 0,
        },
        header: {
          marginBottom: 0,
          padding: "16px 20px",
          borderBottom: "1px solid var(--st-color-border)",
        },
        body: {
          display: "flex",
          flexDirection: "column",
          flex: "1 1 auto",
          minHeight: 0,
          overflow: "hidden",
          padding: 16,
        },
        footer: footer
          ? {
              marginTop: 0,
              padding: "12px 20px",
              borderTop: "1px solid var(--st-color-border)",
            }
          : undefined,
      }}
      destroyOnHidden
      className="custom-modal task-history-modal"
      maskClosable={false}
      style={{ top: 10, height: "calc(100vh - 20px)" }}
      width="96vw"
      footer={footer || null}
    >
      <div className="flex min-h-0 flex-1 flex-col">
        {children ? (
          children
        ) : (
          <div className="flex h-full min-h-0 flex-col gap-3">
            <div className="flex shrink-0 flex-wrap items-center gap-3">
              <Input
                allowClear
                value={searchKeyword}
                onChange={(event) => setSearchKeyword(event.target.value)}
                placeholder="在当前完整日志中检索 timeout、ERROR、连接..."
                className="min-w-[240px] flex-1"
              />
              <span className="whitespace-nowrap text-sm text-[color:var(--st-color-text-secondary)]">
                {normalizedKeyword ? `命中 ${matchedLineCount} 行` : `共 ${logLines.length} 行`}
              </span>
            </div>

            {loading && !logContent ? (
              <div className="flex min-h-0 flex-1 items-center justify-center rounded-lg border border-dashed border-[color:var(--st-color-border)] bg-[color:var(--st-color-bg-control)] text-center">
                <Spin size="small" />
                <span className="ml-2 text-sm text-[color:var(--st-color-text-secondary)]">
                  正在加载日志...
                </span>
              </div>
            ) : errorText ? (
              <div className="flex min-h-0 flex-1 flex-col items-center justify-center rounded-lg border border-dashed border-red-400/40 bg-[color:var(--st-color-bg-control)] text-center">
                <div className="text-sm font-medium text-red-500">获取日志失败</div>
                <div className="mt-1 max-w-[520px] text-sm leading-5 text-[color:var(--st-color-text-secondary)]">
                  {errorText}
                </div>
              </div>
            ) : (
              <div className="min-h-0 flex-1 overflow-auto rounded-lg border border-[color:var(--st-color-border)] bg-[#0b1220] p-4 font-mono text-sm leading-5 text-slate-100">
                {logContent ? (
                  logLines.map((line, index) => {
                    const matched = Boolean(
                      normalizedKeyword && line.toLowerCase().includes(normalizedKeyword),
                    );
                    const isError = /\bERROR\b/i.test(line);
                    return (
                      <div
                        key={index}
                        className={[
                          matched ? "rounded bg-cyan-950/80 text-cyan-100" : "",
                          isError && !matched ? "text-red-400" : "",
                        ]
                          .filter(Boolean)
                          .join(" ")}
                      >
                        {line || " "}
                      </div>
                    );
                  })
                ) : (
                  <div className="flex h-full min-h-[180px] items-center justify-center text-center">
                    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前实例暂无日志" />
                  </div>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </Modal>
  );
};

export default React.memo(RunLogDrawer);
