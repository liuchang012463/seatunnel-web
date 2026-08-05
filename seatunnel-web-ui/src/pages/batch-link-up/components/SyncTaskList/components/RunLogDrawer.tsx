import { jobLogApi, type JobLogMode } from "@/services/jobLog";
import {
  CloseOutlined, EditOutlined,
  FileSearchOutlined,
  PauseOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
} from "@ant-design/icons";
import { Button, Empty, Input, Spin, Tag, Tooltip } from "antd";
import React, { FC, ReactNode } from "react";
import { memo, useCallback, useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";

type RunLogInstanceType = "BATCH" | "STREAMING" | string;

type DrawerHeight = number | `${number}px` | `${number}vh`;

interface RunLogDrawerProps {
  open: boolean;
  onClose: () => void;

  title?: string;
  subtitle?: string;
  footer?: ReactNode;
  children?: ReactNode;

  /**
   * 任务实例 ID。
   * 离线任务：t_seatunnel_web_job_instance.id
   * 实时任务：t_seatunnel_web_streaming_job_instance.id
   */
  instanceId?: string | number;

  /**
   * 实例类型。
   * BATCH: 离线任务实例
   * STREAMING: 实时任务实例
   */
  jobMode?: RunLogInstanceType;

  /**
   * 左侧菜单宽度。
   * 不想遮住左侧菜单时传 64。
   */
  leftOffset?: number;

  /**
   * 支持 number / px / vh。
   * 例如：
   * defaultHeight={560}
   * defaultHeight="560px"
   * defaultHeight="70vh"
   */
  defaultHeight?: DrawerHeight;
  minHeight?: DrawerHeight;
  maxHeight?: DrawerHeight;
}

const getResponseData = (response: any) => {
  return response?.data ?? response;
};

const getLogItemContent = (item: any) => {
  if (!item) {
    return "";
  }

  if (typeof item === "string") {
    return item;
  }

  return (
    item?.content ||
    item?.logContent ||
    item?.log ||
    item?.message ||
    item?.data ||
    ""
  );
};

const formatLogContent = (value: any) => {
  const data = getResponseData(value);

  if (!data) {
    return "";
  }

  if (typeof data === "string") {
    return data;
  }

  /**
   * 兼容后端返回：
   * {
   *   logs: [...]
   * }
   */
  if (Array.isArray(data?.logs)) {
    return formatLogContent(data.logs);
  }

  /**
   * 兼容 SeaTunnel Engine 返回：
   * [
   *   {
   *     node: "localhost:8080",
   *     logName: "job-xxx.log",
   *     logLink: "http://xxx/logs/job-xxx.log",
   *     content: "..."
   *   }
   * ]
   */
  if (Array.isArray(data)) {
    return data
      .map((item) => {
        if (typeof item === "string") {
          return item;
        }

        const header = [
          item?.node ? `# Node: ${item.node}` : "",
          item?.logName ? `# File: ${item.logName}` : "",
          item?.logLink ? `# Link: ${item.logLink}` : "",
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

  const content = getLogItemContent(data);

  if (content) {
    return String(content);
  }

  return JSON.stringify(data, null, 2);
};

const RunLogDrawer: FC<RunLogDrawerProps> = ({
  open,
  onClose,
  title = "运行日志",
  instanceId,
  jobMode = "BATCH",
  subtitle = "查看任务运行输出",
  footer,
  children,
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
    [maxHeight, minHeight, toPxHeight]
  );

  const [panelHeight, setPanelHeight] = useState(() =>
    getSafePanelHeight(toPxHeight(defaultHeight, 560))
  );
  const [isDragging, setIsDragging] = useState(false);

  const [loading, setLoading] = useState(false);
  const [logContent, setLogContent] = useState("");
  const [errorText, setErrorText] = useState("");
  const [searchKeyword, setSearchKeyword] = useState("");
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchResult, setSearchResult] = useState<any>(null);
  const [analysisLoading, setAnalysisLoading] = useState(false);
  const [analysisResult, setAnalysisResult] = useState<any>(null);
  const [diagnosisLoading, setDiagnosisLoading] = useState(false);
  const [diagnosisResult, setDiagnosisResult] = useState<any>(null);
  const [replayLoading, setReplayLoading] = useState(false);
  const [replayResult, setReplayResult] = useState<any>(null);
  const [replayIndex, setReplayIndex] = useState(0);
  const [replayPlaying, setReplayPlaying] = useState(false);
  const [activeView, setActiveView] = useState<
    "raw" | "search" | "analysis" | "replay" | "diagnosis"
  >("raw");

  const panelStyle = useMemo(
    () =>
      ({
        height: `${panelHeight}px`,
      } as React.CSSProperties),
    [panelHeight]
  );

  const wrapperStyle = useMemo(
    () =>
      ({
        left: leftOffset,
      } as React.CSSProperties),
    [leftOffset]
  );

  const loadLogs = useCallback(async () => {
    if (!open) {
      return;
    }

    if (!instanceId) {
      setLogContent("");
      setErrorText("当前任务暂无运行实例，无法查看日志");
      return;
    }

    try {
      setLoading(true);
      setErrorText("");
      setLogContent("");

      const response = await jobLogApi.content(
        instanceId,
        jobMode as JobLogMode,
      );

      if (response?.code !== 0) {
        const msg = response?.msg || response?.message || "获取日志失败";
        setErrorText(msg);
        return;
      }

      const content = formatLogContent(response?.data);

      setLogContent(content || "");
    } catch (error: any) {
      const msg = error?.message || "获取日志失败";
      setErrorText(msg);
    } finally {
      setLoading(false);
    }
  }, [open, instanceId, jobMode]);

  const searchLogs = useCallback(async () => {
    if (!instanceId) {
      return;
    }

    try {
      setSearchLoading(true);
      const response = await jobLogApi.search(instanceId, jobMode as JobLogMode, {
        keyword: searchKeyword,
        page: 1,
        pageSize: 200,
      });
      if (response?.code !== 0) {
        setErrorText(response?.msg || response?.message || "检索日志失败");
        return;
      }
      setSearchResult(getResponseData(response));
      setActiveView("search");
    } catch (error: any) {
      setErrorText(error?.message || "检索日志失败");
    } finally {
      setSearchLoading(false);
    }
  }, [instanceId, jobMode, searchKeyword]);

  const analyzeLogs = useCallback(async () => {
    if (!instanceId) {
      return;
    }

    try {
      setAnalysisLoading(true);
      const response = await jobLogApi.analysis(
        instanceId,
        jobMode as JobLogMode,
      );
      if (response?.code !== 0) {
        setErrorText(response?.msg || response?.message || "解析日志失败");
        return;
      }
      setAnalysisResult(getResponseData(response));
      setActiveView("analysis");
    } catch (error: any) {
      setErrorText(error?.message || "解析日志失败");
    } finally {
      setAnalysisLoading(false);
    }
  }, [instanceId, jobMode]);

  const loadReplay = useCallback(async () => {
    if (!instanceId) {
      return;
    }

    try {
      setReplayLoading(true);
      const response = await jobLogApi.replay(
        instanceId,
        jobMode as JobLogMode,
      );
      if (response?.code !== 0) {
        setErrorText(response?.msg || response?.message || "加载日志回放失败");
        return;
      }
      setReplayResult(getResponseData(response));
      setReplayIndex(0);
      setReplayPlaying(false);
      setActiveView("replay");
    } catch (error: any) {
      setErrorText(error?.message || "加载日志回放失败");
    } finally {
      setReplayLoading(false);
    }
  }, [instanceId, jobMode]);

  const diagnoseLogs = useCallback(async () => {
    if (!instanceId) {
      return;
    }

    try {
      setDiagnosisLoading(true);
      const response = await jobLogApi.diagnosis(
        instanceId,
        jobMode as JobLogMode,
      );
      if (response?.code !== 0) {
        setErrorText(response?.msg || response?.message || "故障定位失败");
        return;
      }
      setDiagnosisResult(getResponseData(response));
      setActiveView("diagnosis");
    } catch (error: any) {
      setErrorText(error?.message || "故障定位失败");
    } finally {
      setDiagnosisLoading(false);
    }
  }, [instanceId, jobMode]);

  useEffect(() => {
    if (!open) {
      return;
    }

    setPanelHeight(getSafePanelHeight(toPxHeight(defaultHeight, 560)));
  }, [open, defaultHeight, getSafePanelHeight, toPxHeight]);

  useEffect(() => {
    if (!open) {
      return;
    }

    void loadLogs();
  }, [open, loadLogs]);

  useEffect(() => {
    const steps = replayResult?.steps;
    if (!replayPlaying || !steps?.length || typeof window === "undefined") {
      return;
    }

    const timer = window.setInterval(() => {
      setReplayIndex((current) => Math.min(current + 1, steps.length - 1));
    }, 800);

    return () => window.clearInterval(timer);
  }, [replayPlaying, replayResult]);

  useEffect(() => {
    const steps = replayResult?.steps;
    if (replayPlaying && steps?.length && replayIndex >= steps.length - 1) {
      setReplayPlaying(false);
    }
  }, [replayIndex, replayPlaying, replayResult]);

  useEffect(() => {
    if (!open || typeof window === "undefined") {
      return;
    }

    const handleResize = () => {
      setPanelHeight((prev) => getSafePanelHeight(prev));
    };

    window.addEventListener("resize", handleResize);

    return () => {
      window.removeEventListener("resize", handleResize);
    };
  }, [open, getSafePanelHeight]);

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
      const deltaY = startY - moveEvent.clientY;
      const nextHeight = getSafePanelHeight(startHeight + deltaY);

      setPanelHeight(nextHeight);
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

  const replaySteps = replayResult?.steps ?? [];
  const currentReplayStep = replaySteps[replayIndex];

  return createPortal(
    <div
      className="run-log-drawer fixed bottom-0 right-0 top-0 z-[9999] pointer-events-none"
      style={wrapperStyle}
    >
      <div className="absolute bottom-3 left-5 right-5 flex flex-col pointer-events-none">
        <div
          className="flex h-5 cursor-row-resize items-center justify-center pointer-events-auto"
          onMouseDown={handleMouseDown}
        >
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
          className={[
            "run-log-drawer__panel pointer-events-auto flex flex-col overflow-hidden rounded-[18px]",
            "border border-slate-200/90 bg-white",
            "shadow-[0_10px_30px_rgba(15,23,42,0.10)]",
          ].join(" ")}
        >
          <header className="run-log-drawer__header flex h-[54px] items-center justify-between border-b border-slate-100 bg-white px-5">
            <div className="flex min-w-0 items-center gap-3">
              <Tooltip title="需要在log4j2.properties中开启rootLogger.appenderRef.file.ref = routingAppender">
                <div className="run-log-drawer__icon flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-[#eef3ff] text-[#315efb]">
                    <FileSearchOutlined className="text-[15px]" />
                </div>
              </Tooltip>

              <div className="min-w-0">
                <div className="run-log-drawer__title truncate text-[15px] font-semibold text-slate-900">
                  {title}
                </div>
                <div className="run-log-drawer__subtitle truncate text-xs text-slate-400">
                  {subtitle}
                </div>
              </div>
            </div>

            <div className="flex items-center gap-1.5">
              <Button
                type="text"
                size="small"
                onClick={() => setActiveView("raw")}
                className={activeView === "raw" ? "!text-[#315efb]" : "!text-slate-500"}
              >
                原始
              </Button>
              <Button
                type="text"
                size="small"
                onClick={() => setActiveView("search")}
                className={activeView === "search" ? "!text-[#315efb]" : "!text-slate-500"}
              >
                检索
              </Button>
              <Button
                type="text"
                loading={analysisLoading}
                onClick={() => void analyzeLogs()}
                className={activeView === "analysis" ? "!text-[#315efb]" : "!text-slate-500"}
              >
                分析
              </Button>
              <Button
                type="text"
                loading={diagnosisLoading}
                onClick={() => void diagnoseLogs()}
                className={activeView === "diagnosis" ? "!text-[#315efb]" : "!text-slate-500"}
              >
                定位
              </Button>
              <Button
                type="text"
                loading={replayLoading}
                onClick={() => void loadReplay()}
                className={activeView === "replay" ? "!text-[#315efb]" : "!text-slate-500"}
              >
                回放
              </Button>
              <button
                type="button"
                onClick={() => void loadLogs()}
                disabled={loading}
                className={[
                  "run-log-drawer__refresh-button flex h-8 items-center gap-1.5 rounded-lg px-2.5 text-xs",
                  "text-slate-500 transition-all duration-200",
                  "hover:bg-slate-100 hover:text-slate-700",
                  loading ? "cursor-not-allowed opacity-60" : "",
                ].join(" ")}
              >
                <ReloadOutlined className={loading ? "animate-spin" : ""} />
                刷新
              </button>

              <button
                type="button"
                onClick={onClose}
                className={[
                  "run-log-drawer__close-button flex h-8 w-8 items-center justify-center rounded-lg",
                  "text-slate-400 transition-all duration-200",
                  "hover:bg-slate-100 hover:text-slate-700",
                ].join(" ")}
                aria-label="关闭运行日志"
              >
                <CloseOutlined className="text-xs" />
              </button>
            </div>
          </header>

          <main className="run-log-drawer__body min-h-0 flex-1 bg-[#fafbfc] p-4">
            {children ? (
              children
            ) : loading ? (
              <div className="run-log-drawer__state-card flex h-full min-h-[180px] items-center justify-center rounded-2xl border border-dashed border-slate-200 bg-white text-center">
                <Spin size="small" />
                <span className="ml-2 text-xs text-slate-400">
                  正在加载日志...
                </span>
              </div>
            ) : errorText ? (
              <div className="run-log-drawer__state-card flex h-full min-h-[180px] flex-col items-center justify-center rounded-2xl border border-dashed border-red-100 bg-white text-center">
                <div className="text-sm font-medium text-red-500">
                  获取日志失败
                </div>
                <div className="mt-1 max-w-[520px] text-xs leading-5 text-slate-400">
                  {errorText}
                </div>
              </div>
            ) : activeView === "search" ? (
              <div className="flex h-full min-h-0 flex-col gap-3 rounded-2xl border border-slate-200 bg-white p-4">
                <div className="flex items-center gap-2">
                  <Input.Search
                    value={searchKeyword}
                    allowClear
                    placeholder="检索关键字，例如 timeout、ERROR、连接"
                    enterButton="检索"
                    loading={searchLoading}
                    onChange={(event) => setSearchKeyword(event.target.value)}
                    onSearch={() => void searchLogs()}
                  />
                  {searchResult ? <Tag color="blue">命中 {searchResult.total ?? 0} 行</Tag> : null}
                </div>
                {searchResult?.entries?.length ? (
                  <div className="min-h-0 flex-1 overflow-auto rounded-xl bg-slate-950 p-3 font-mono text-xs leading-5 text-slate-100">
                    {searchResult.entries.map((entry: any) => (
                      <div key={`${entry.lineNumber}-${entry.sequence}`} className="mb-1 border-b border-white/5 pb-1 last:border-0">
                        <span className="mr-2 text-slate-500">L{entry.lineNumber}</span>
                        <span className="mr-2 text-cyan-300">{entry.level}</span>
                        <span className="mr-2 text-violet-300">{entry.source}</span>
                        {entry.message}
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="flex flex-1 items-center justify-center">
                    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="输入关键字检索完整日志" />
                  </div>
                )}
              </div>
            ) : activeView === "analysis" ? (
              <div className="flex h-full min-h-0 flex-col gap-3 overflow-auto rounded-2xl border border-slate-200 bg-white p-4">
                {analysisResult ? (
                  <>
                    <div className="flex flex-wrap items-center gap-2 border-b border-slate-100 pb-3 text-xs text-slate-500">
                      <Tag color="blue">总行数 {analysisResult.totalLines ?? 0}</Tag>
                      <Tag color="red">错误 {analysisResult.errorCount ?? 0}</Tag>
                      <Tag color="orange">警告 {analysisResult.warningCount ?? 0}</Tag>
                      <span>规则版本 v1：按来源、级别和关键词提取结构化记录</span>
                    </div>
                    <div className="grid gap-3 lg:grid-cols-2">
                      {[
                        ["操作行为记录", analysisResult.operationRecords],
                        ["数据读取快照", analysisResult.dataSnapshots],
                        ["执行流程日志", analysisResult.executionFlow],
                        ["错误记录", analysisResult.errors],
                        ["操作时序记录", analysisResult.timeline],
                      ].map(([label, entries]: [string, any[]]) => (
                        <section key={label} className="rounded-xl border border-slate-100 bg-slate-50 p-3">
                          <div className="mb-2 flex items-center justify-between text-sm font-medium text-slate-700">
                            <span>{label}</span>
                            <Tag>{entries?.length ?? 0}</Tag>
                          </div>
                          {entries?.length ? (
                            <div className="max-h-40 overflow-auto space-y-1 text-xs leading-5 text-slate-600">
                              {entries.map((entry) => (
                                <div key={`${label}-${entry.sequence}`} className="border-b border-slate-200/70 pb-1 last:border-0">
                                  <span className="mr-2 text-slate-400">L{entry.lineNumber}</span>
                                  <span className="mr-2 text-violet-500">{entry.source}</span>
                                  {entry.message}
                                </div>
                              ))}
                            </div>
                          ) : (
                            <div className="text-xs text-slate-400">暂无记录</div>
                          )}
                        </section>
                      ))}
                    </div>
                  </>
                ) : (
                  <div className="flex flex-1 items-center justify-center">
                    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="点击分析提取结构化日志记录" />
                  </div>
                )}
              </div>
            ) : activeView === "diagnosis" ? (
              <div className="flex h-full min-h-0 flex-col gap-3 overflow-auto rounded-2xl border border-slate-200 bg-white p-4">
                {diagnosisResult ? (
                  <>
                    <div className="flex flex-wrap items-center gap-2 border-b border-slate-100 pb-3">
                      <Tag color={diagnosisResult.faultType === "SYSTEM_COMPONENT" ? "purple" : "red"}>
                        {diagnosisResult.faultTypeLabel || "未明确"}
                      </Tag>
                      <Tag color="blue">
                        置信度 {Math.round((diagnosisResult.confidence ?? 0) * 100)}%
                      </Tag>
                      <Tag>{diagnosisResult.aiUsed ? "Spring AI" : "规则兜底"}</Tag>
                    </div>
                    <section className="rounded-xl border border-red-100 bg-red-50/50 p-4">
                      <div className="text-sm font-medium text-slate-800">错误原因</div>
                      <div className="mt-2 text-sm leading-6 text-slate-600">
                        {diagnosisResult.rootCause || "暂无明确原因"}
                      </div>
                      <div className="mt-2 text-xs text-slate-400">
                        影响阶段：{diagnosisResult.affectedStage || "未明确"}
                      </div>
                    </section>
                    <div className="grid gap-3 lg:grid-cols-2">
                      <section className="rounded-xl border border-slate-100 bg-slate-50 p-3">
                        <div className="mb-2 text-sm font-medium text-slate-700">证据</div>
                        <div className="max-h-48 overflow-auto space-y-1 text-xs leading-5 text-slate-600">
                          {(diagnosisResult.evidence ?? []).map((item: string, index: number) => (
                            <div key={`evidence-${index}`}>{item}</div>
                          ))}
                        </div>
                      </section>
                      <section className="rounded-xl border border-slate-100 bg-slate-50 p-3">
                        <div className="mb-2 text-sm font-medium text-slate-700">建议动作</div>
                        <div className="space-y-1 text-xs leading-5 text-slate-600">
                          {(diagnosisResult.recommendedActions ?? []).map((item: string, index: number) => (
                            <div key={`action-${index}`}>{index + 1}. {item}</div>
                          ))}
                        </div>
                      </section>
                    </div>
                    {diagnosisResult.uncertainties?.length ? (
                      <div className="rounded-xl border border-orange-100 bg-orange-50 p-3 text-xs leading-5 text-orange-700">
                        不确定性：{diagnosisResult.uncertainties.join("；")}
                      </div>
                    ) : null}
                  </>
                ) : (
                  <div className="flex flex-1 items-center justify-center">
                    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="点击定位分析错误日志" />
                  </div>
                )}
              </div>
            ) : activeView === "replay" ? (
              <div className="flex h-full min-h-0 flex-col gap-4 rounded-2xl border border-slate-200 bg-white p-4">
                {currentReplayStep ? (
                  <>
                    <div className="flex flex-wrap items-center gap-2 border-b border-slate-100 pb-3">
                      <Button
                        size="small"
                        type="primary"
                        icon={replayPlaying ? <PauseOutlined /> : <PlayCircleOutlined />}
                        onClick={() => setReplayPlaying((playing) => !playing)}
                      >
                        {replayPlaying ? "暂停" : "播放"}
                      </Button>
                      <Button size="small" onClick={() => setReplayIndex((index) => Math.max(0, index - 1))}>
                        上一步
                      </Button>
                      <Button size="small" onClick={() => setReplayIndex((index) => Math.min(replaySteps.length - 1, index + 1))}>
                        下一步
                      </Button>
                      <span className="text-xs text-slate-400">
                        第 {replayIndex + 1} / {replaySteps.length} 步
                      </span>
                    </div>
                    <input
                      type="range"
                      min={0}
                      max={Math.max(0, replaySteps.length - 1)}
                      value={replayIndex}
                      onChange={(event) => {
                        setReplayPlaying(false);
                        setReplayIndex(Number(event.target.value));
                      }}
                      className="w-full accent-[#315efb]"
                      aria-label="操作回放进度"
                    />
                    <div className="min-h-0 flex-1 overflow-auto rounded-xl bg-slate-950 p-4 text-slate-100">
                      <div className="mb-3 flex flex-wrap items-center gap-2 text-xs text-slate-400">
                        <Tag color="blue">{currentReplayStep.title}</Tag>
                        <Tag color={currentReplayStep.status === "ERROR" ? "red" : "green"}>
                          {currentReplayStep.status}
                        </Tag>
                        <span>{currentReplayStep.source}</span>
                        <span>{currentReplayStep.timestamp || `L${currentReplayStep.lineNumber}`}</span>
                      </div>
                      <div className="whitespace-pre-wrap font-mono text-sm leading-6">
                        {currentReplayStep.detail || currentReplayStep.raw}
                      </div>
                    </div>
                  </>
                ) : (
                  <div className="flex flex-1 items-center justify-center">
                    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="点击回放加载完整操作时序" />
                  </div>
                )}
              </div>
            ) : logContent ? (
              <pre
                className={[
                  "h-full min-h-0 overflow-auto rounded-2xl border border-slate-800/90",
                  "bg-slate-950 p-4 text-xs leading-5 text-slate-100",
                  "font-mono",
                ].join(" ")}
              >
                {logContent}
              </pre>
            ) : (
              <div className="run-log-drawer__state-card flex h-full min-h-[180px] flex-col items-center justify-center rounded-2xl border border-dashed border-slate-200 bg-white text-center">
                <div className="text-sm font-medium text-slate-600">
                  暂无日志
                </div>
                <div className="mt-1 text-xs text-slate-400">
                  当前任务实例暂未返回运行日志
                </div>
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
    document.body
  );
};

export default memo(RunLogDrawer);
