import { CloseOutlined, FileSearchOutlined, ReloadOutlined } from "@ant-design/icons";
import { Empty, Input, Spin, Tooltip } from "antd";
import React, { FC, ReactNode, useCallback, useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";

import { jobLogApi, type JobLogMode } from "@/services/jobLog";

type RunLogInstanceType = "BATCH" | "STREAMING" | string;
type DrawerHeight = number | `${number}px` | `${number}vh`;

interface RunLogDrawerProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  subtitle?: string;
  footer?: ReactNode;
  children?: ReactNode;
  instanceId?: string | number;
  jobMode?: RunLogInstanceType;
  leftOffset?: number;
  defaultHeight?: DrawerHeight;
  minHeight?: DrawerHeight;
  maxHeight?: DrawerHeight;
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
  leftOffset = 64,
  defaultHeight = "70vh",
  minHeight = "40vh",
  maxHeight = "90vh",
}) => {
  const toPxHeight = useCallback((value: DrawerHeight, fallback: number) => {
    if (typeof value === "number") {
      return value;
    }

    if (typeof window === "undefined") {
      return fallback;
    }

    const text = String(value).trim();
    if (text.endsWith("vh")) {
      const num = Number.parseFloat(text);
      return Number.isFinite(num) ? (window.innerHeight * num) / 100 : fallback;
    }
    if (text.endsWith("px")) {
      const num = Number.parseFloat(text);
      return Number.isFinite(num) ? num : fallback;
    }
    return fallback;
  }, []);

  const getSafePanelHeight = useCallback(
    (height: number) => {
      const minPanelHeight = toPxHeight(minHeight, 260);
      const maxPanelHeight = toPxHeight(maxHeight, 880);
      return Math.max(minPanelHeight, Math.min(maxPanelHeight, height));
    },
    [maxHeight, minHeight, toPxHeight],
  );

  const [panelHeight, setPanelHeight] = useState(() =>
    getSafePanelHeight(toPxHeight(defaultHeight, 560)),
  );
  const [isDragging, setIsDragging] = useState(false);
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

    setPanelHeight(getSafePanelHeight(toPxHeight(defaultHeight, 560)));
    setSearchKeyword("");
    void loadLogs(true);

    const timer = window.setInterval(() => {
      void loadLogs(false);
    }, 3000);

    return () => window.clearInterval(timer);
  }, [defaultHeight, getSafePanelHeight, loadLogs, open, toPxHeight]);

  useEffect(() => {
    if (!open || typeof window === "undefined") {
      return;
    }

    const handleResize = () => setPanelHeight((current) => getSafePanelHeight(current));
    window.addEventListener("resize", handleResize);
    return () => window.removeEventListener("resize", handleResize);
  }, [getSafePanelHeight, open]);

  const logLines = useMemo(() => logContent.split(/\r?\n/), [logContent]);
  const normalizedKeyword = searchKeyword.trim().toLowerCase();
  const matchedLineCount = normalizedKeyword
    ? logLines.filter((line) => line.toLowerCase().includes(normalizedKeyword)).length
    : 0;

  const handleMouseDown = (event: React.MouseEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    setIsDragging(true);

    const startY = event.clientY;
    const startHeight = panelHeight;
    const originalUserSelect = document.body.style.userSelect;
    const originalCursor = document.body.style.cursor;
    document.body.style.userSelect = "none";
    document.body.style.cursor = "row-resize";

    const handleMouseMove = (moveEvent: MouseEvent) => {
      setPanelHeight(getSafePanelHeight(startHeight + startY - moveEvent.clientY));
    };
    const handleMouseUp = () => {
      setIsDragging(false);
      document.body.style.userSelect = originalUserSelect;
      document.body.style.cursor = originalCursor;
      document.removeEventListener("mousemove", handleMouseMove);
      document.removeEventListener("mouseup", handleMouseUp);
    };

    document.addEventListener("mousemove", handleMouseMove);
    document.addEventListener("mouseup", handleMouseUp);
  };

  if (!open || typeof document === "undefined") {
    return null;
  }

  const panelStyle = { height: `${panelHeight}px` } as React.CSSProperties;
  const wrapperStyle = { left: leftOffset } as React.CSSProperties;

  return createPortal(
    <div className="run-log-drawer fixed bottom-0 right-0 top-0 z-[9999] pointer-events-none" style={wrapperStyle}>
      <div className="absolute bottom-3 left-5 right-5 flex flex-col pointer-events-none">
        <div className="flex h-5 cursor-row-resize items-center justify-center pointer-events-auto" onMouseDown={handleMouseDown}>
          <div
            className={[
              "h-1 w-14 rounded-full transition-all duration-200",
              isDragging
                ? "bg-[#315efb] shadow-[0_8px_18px_rgba(49,94,251,0.2)]"
                : "bg-slate-300 hover:bg-slate-400",
            ].join(" ")}
          />
        </div>

        <section
          style={panelStyle}
          className="run-log-drawer__panel pointer-events-auto flex flex-col overflow-hidden rounded-[18px] border border-slate-200/90 bg-white shadow-[0_10px_30px_rgba(15,23,42,0.10)]"
        >
          <header className="run-log-drawer__header flex h-[54px] items-center justify-between border-b border-slate-100 bg-white px-5">
            <div className="flex min-w-0 items-center gap-3">
              <Tooltip title="当前任务实例的实时原始日志">
                <div className="run-log-drawer__icon flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-[#eef3ff] text-[#315efb]">
                  <FileSearchOutlined className="text-[15px]" />
                </div>
              </Tooltip>
              <div className="min-w-0">
                <div className="run-log-drawer__title truncate text-[15px] font-semibold text-slate-900">{title}</div>
                <div className="truncate text-xs text-slate-600">{subtitle} · 实时刷新</div>
              </div>
            </div>

            <div className="flex items-center gap-1.5">
              <button
                type="button"
                onClick={() => void loadLogs(true)}
                disabled={loading}
                className={[
                  "run-log-drawer__refresh-button inline-flex h-8 items-center gap-1.5 rounded-md px-2.5 text-xs font-medium",
                  "border-0 bg-transparent text-[var(--st-color-text-secondary)]",
                  "transition-colors duration-200 hover:bg-[var(--st-color-hover)] hover:text-[var(--st-color-accent)]",
                  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--st-color-focus)]",
                  loading
                    ? "cursor-not-allowed opacity-60"
                    : "active:scale-[0.98]",
                ].join(" ")}
              >
                <ReloadOutlined className={loading ? "animate-spin" : ""} />
                刷新
              </button>
              <button
                type="button"
                onClick={onClose}
                className="run-log-drawer__close-button flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 transition-all duration-200 hover:bg-slate-100 hover:text-slate-700"
                aria-label="关闭运行日志"
              >
                <CloseOutlined className="text-xs" />
              </button>
            </div>
          </header>

          <main className="run-log-drawer__body min-h-0 flex-1 bg-[#fafbfc] p-4">
            {children ? (
              children
            ) : (
              <div className="flex h-full min-h-0 flex-col gap-3 rounded-2xl border border-slate-200 bg-white p-4">
                <div className="flex flex-wrap items-center gap-3">
                  <Input
                    allowClear
                    value={searchKeyword}
                    onChange={(event) => setSearchKeyword(event.target.value)}
                    placeholder="在当前完整日志中检索 timeout、ERROR、连接..."
                    className="min-w-[240px] flex-1"
                  />
                  <span className="whitespace-nowrap text-xs text-slate-400">
                    {normalizedKeyword ? `命中 ${matchedLineCount} 行` : `共 ${logLines.length} 行`}
                  </span>
                </div>

                {loading && !logContent ? (
                  <div className="run-log-drawer__state-card flex min-h-[180px] flex-1 items-center justify-center rounded-2xl border border-dashed border-slate-200 bg-white text-center">
                    <Spin size="small" />
                    <span className="ml-2 text-xs text-slate-400">正在加载日志...</span>
                  </div>
                ) : errorText ? (
                  <div className="run-log-drawer__state-card flex min-h-[180px] flex-1 flex-col items-center justify-center rounded-2xl border border-dashed border-red-100 bg-white text-center">
                    <div className="text-sm font-medium text-red-500">获取日志失败</div>
                    <div className="mt-1 max-w-[520px] text-xs leading-5 text-slate-500">{errorText}</div>
                  </div>
                ) : (
                  <div className="min-h-0 flex-1 overflow-auto rounded-2xl border border-slate-800/90 bg-slate-950 p-4 font-mono text-xs leading-5 text-slate-100">
                    {logContent ? (
                      logLines.map((line, index) => {
                        const matched = Boolean(normalizedKeyword && line.toLowerCase().includes(normalizedKeyword));
                        return (
                          <div key={index} className={matched ? "rounded bg-cyan-950/80 text-cyan-100" : ""}>
                            {line || " "}
                          </div>
                        );
                      })
                    ) : (
                      <div className="run-log-drawer__state-card flex h-full min-h-[180px] items-center justify-center rounded-2xl border border-dashed border-slate-200 bg-white text-center">
                        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前实例暂无日志" />
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}
          </main>
          {footer ? (
            <footer className="run-log-drawer__footer flex items-center justify-end gap-2 border-t border-slate-100 bg-white px-5 py-3">
              {footer}
            </footer>
          ) : null}
        </section>
      </div>
    </div>,
    document.body,
  );
};

export default React.memo(RunLogDrawer);
