import {
  PauseOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SearchOutlined,
} from "@ant-design/icons";
import { Button, Empty, Input, Spin, Tag, Tooltip, message } from "antd";
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";

import {
  jobLogApi,
  type JobLogAnalysisResult,
  type JobLogFaultDiagnosisResult,
  type JobLogMode,
  type JobLogReplayResult,
  type JobLogReplayStep,
  type JobLogStructuredRecord,
} from "@/services/jobLog";

interface TaskLogObservabilityProps {
  instanceItem: any;
  jobMode: JobLogMode;
  view: "operation" | "snapshot" | "execution" | "timeline" | "replay" | "diagnosis";
}

const getResponseData = (response: any) => response?.data ?? response;

const getLogItemContent = (item: any) => {
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
        return content ? (header ? `${header}\n\n${content}` : content) : JSON.stringify(item, null, 2);
      })
      .filter(Boolean)
      .join("\n\n");
  }

  return String(getLogItemContent(data) || JSON.stringify(data, null, 2));
};

const formatTime = (value?: string, elapsedMs?: number) => {
  if (value) {
    return value;
  }
  if (elapsedMs !== undefined && elapsedMs !== null) {
    return `+${elapsedMs} ms`;
  }
  return "-";
};

const statusColor = (status?: string) => {
  if (status === "失败") {
    return "red";
  }
  if (status === "警告") {
    return "orange";
  }
  if (status === "完成") {
    return "green";
  }
  return "blue";
};

const StructuredRecordTable: React.FC<{ rows: JobLogStructuredRecord[] }> = ({ rows }) => {
  if (!rows?.length) {
    return <div className="py-6 text-center text-xs text-slate-400">暂无规则命中记录</div>;
  }

  return (
    <div className="overflow-auto rounded-lg border border-slate-200">
      <table className="w-full min-w-[760px] text-left text-xs">
        <thead className="bg-slate-50 text-slate-500">
          <tr>
            <th className="whitespace-nowrap px-3 py-2 font-semibold">时间</th>
            <th className="whitespace-nowrap px-3 py-2 font-semibold">操作</th>
            <th className="whitespace-nowrap px-3 py-2 font-semibold">目标</th>
            <th className="whitespace-nowrap px-3 py-2 font-semibold">状态</th>
            <th className="whitespace-nowrap px-3 py-2 font-semibold">来源</th>
            <th className="px-3 py-2 font-semibold">规则说明</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {rows.map((row) => (
            <tr key={`${row.category}-${row.sequence}`} className="bg-white text-slate-600 hover:bg-slate-50">
              <td className="whitespace-nowrap px-3 py-2 align-top">{formatTime(row.timestamp, row.elapsedMs)}</td>
              <td className="whitespace-nowrap px-3 py-2 align-top font-semibold text-slate-800">{row.operation}</td>
              <td className="max-w-[180px] truncate px-3 py-2 align-top">{row.target || "-"}</td>
              <td className="whitespace-nowrap px-3 py-2 align-top">
                <Tag color={statusColor(row.status)} className="!mr-0">{row.status}</Tag>
              </td>
              <td className="whitespace-nowrap px-3 py-2 align-top text-slate-400">{row.source}</td>
              <td className="min-w-[260px] px-3 py-2 align-top leading-5">{row.detail}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

const ReplayStepView: React.FC<{ step?: JobLogReplayStep; sectionTitle?: string }> = ({ step, sectionTitle }) => {
  if (!step) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="点击加载命名阶段后开始回放" />;
  }

  return (
    <div className="rounded-xl border border-slate-200 bg-slate-950 p-4 text-slate-100">
      <div className="mb-3 flex flex-wrap items-center gap-2 text-xs text-slate-400">
        <Tag color="blue">{sectionTitle || step.title}</Tag>
        <Tag color={step.status === "失败" ? "red" : "green"}>{step.status}</Tag>
        <span>{step.operation}</span>
        <span>{step.target !== "-" ? `目标：${step.target}` : ""}</span>
        <span>{formatTime(step.timestamp, step.elapsedMs)}</span>
      </div>
      <div className="text-sm leading-6 text-slate-100">{step.detail}</div>
    </div>
  );
};

const TaskLogObservability: React.FC<TaskLogObservabilityProps> = ({ instanceItem, jobMode, view }) => {
  const instanceId = instanceItem?.id;
  const isFailed = String(instanceItem?.jobStatus || "").toUpperCase() === "FAILED";
  const canDiagnose = Boolean(instanceId) && isFailed;
  const diagnosisDisabledReason = !isFailed
    ? `当前实例状态为 ${instanceItem?.jobStatus || "未知"}，仅 FAILED 状态允许故障定位。`
    : !instanceId
      ? "当前任务暂无运行实例，无法进行故障定位。"
      : "";
  const [logContent, setLogContent] = useState("");
  const [loading, setLoading] = useState(false);
  const [errorText, setErrorText] = useState("");
  const [searchKeyword, setSearchKeyword] = useState("");
  const [analysisLoading, setAnalysisLoading] = useState(false);
  const [analysisResult, setAnalysisResult] = useState<JobLogAnalysisResult | null>(null);
  const [replayLoading, setReplayLoading] = useState(false);
  const [replayResult, setReplayResult] = useState<JobLogReplayResult | null>(null);
  const [replayCursor, setReplayCursor] = useState(0);
  const [replayPlaying, setReplayPlaying] = useState(false);
  const [diagnosisLoading, setDiagnosisLoading] = useState(false);
  const [diagnosisStatus, setDiagnosisStatus] = useState("");
  const [diagnosisOutput, setDiagnosisOutput] = useState("");
  const [diagnosisResult, setDiagnosisResult] = useState<JobLogFaultDiagnosisResult | null>(null);
  const diagnosisAbortRef = useRef<AbortController | null>(null);

  const loadLog = useCallback(async () => {
    if (!instanceId) {
      setLogContent("");
      setErrorText("当前任务暂无运行实例，无法查看日志");
      return;
    }

    try {
      setLoading(true);
      setErrorText("");
      const response = await jobLogApi.content(instanceId, jobMode);
      if (response?.code !== 0) {
        setErrorText(response?.msg || "获取日志失败");
        return;
      }
      setLogContent(formatLogContent(response));
    } catch (error: any) {
      setErrorText(error?.message || "获取日志失败");
    } finally {
      setLoading(false);
    }
  }, [instanceId, jobMode]);

  useEffect(() => {
    setSearchKeyword("");
    setAnalysisResult(null);
    setReplayResult(null);
    setReplayCursor(0);
    setReplayPlaying(false);
    setDiagnosisStatus("");
    setDiagnosisOutput("");
    setDiagnosisResult(null);
    diagnosisAbortRef.current?.abort();
    if (view === "execution") {
      void loadLog();
    }
  }, [loadLog, view]);

  const loadAnalysis = useCallback(async () => {
    if (!instanceId) return;
    try {
      setAnalysisLoading(true);
      const response = await jobLogApi.analysis(instanceId, jobMode);
      if (response?.code !== 0) {
        message.error(response?.msg || "解析日志失败");
        return;
      }
      setAnalysisResult(getResponseData(response));
    } catch (error: any) {
      message.error(error?.message || "解析日志失败");
    } finally {
      setAnalysisLoading(false);
    }
  }, [instanceId, jobMode]);

  const loadReplay = useCallback(async () => {
    if (!instanceId) return;
    try {
      setReplayLoading(true);
      const response = await jobLogApi.replay(instanceId, jobMode);
      if (response?.code !== 0) {
        message.error(response?.msg || "加载操作回放失败");
        return;
      }
      setReplayResult(getResponseData(response));
      setReplayCursor(0);
      setReplayPlaying(false);
    } catch (error: any) {
      message.error(error?.message || "加载操作回放失败");
    } finally {
      setReplayLoading(false);
    }
  }, [instanceId, jobMode]);

  const replayPositions = useMemo(
    () => replayResult?.sections.flatMap((section, sectionIndex) =>
      section.steps.map((step, stepIndex) => ({ step, section, sectionIndex, stepIndex }))) || [],
    [replayResult],
  );
  const currentReplay = replayPositions[replayCursor];

  useEffect(() => {
    if (!replayPlaying || replayPositions.length === 0) {
      return;
    }
    const timer = window.setInterval(() => {
      setReplayCursor((current) => Math.min(current + 1, replayPositions.length - 1));
    }, 800);
    return () => window.clearInterval(timer);
  }, [replayPlaying, replayPositions.length]);

  useEffect(() => {
    if (replayPlaying && replayCursor >= replayPositions.length - 1) {
      setReplayPlaying(false);
    }
  }, [replayCursor, replayPlaying, replayPositions.length]);

  const startDiagnosis = useCallback(async () => {
    if (!canDiagnose) {
      message.info("只有日志状态为 FAILED 的任务才可以进行故障定位");
      return;
    }

    diagnosisAbortRef.current?.abort();
    const controller = new AbortController();
    diagnosisAbortRef.current = controller;
    setDiagnosisLoading(true);
    setDiagnosisStatus("正在连接故障定位服务...");
    setDiagnosisOutput("");
    setDiagnosisResult(null);

    try {
      for await (const event of jobLogApi.diagnosisStream(instanceId, jobMode, controller.signal)) {
        if (event.type === "status") {
          setDiagnosisStatus(event.content || "正在分析...");
        } else if (event.type === "delta") {
          setDiagnosisOutput((current) => current + (event.content || ""));
        } else if (event.type === "result" && event.result) {
          setDiagnosisResult(event.result);
        } else if (event.type === "done") {
          setDiagnosisStatus("故障定位完成");
        }
      }
    } catch (error: any) {
      if (error?.name !== "AbortError") {
        setDiagnosisStatus("");
        message.error(error?.message || "故障定位失败");
      }
    } finally {
      setDiagnosisLoading(false);
    }
  }, [canDiagnose, instanceId, jobMode]);

  useEffect(() => () => diagnosisAbortRef.current?.abort(), []);

  const logLines = useMemo(() => logContent.split(/\r?\n/), [logContent]);
  const normalizedKeyword = searchKeyword.trim().toLowerCase();
  const matchedLineCount = normalizedKeyword
    ? logLines.filter((line) => line.toLowerCase().includes(normalizedKeyword)).length
    : 0;

  if (!instanceItem?.jobStatus) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请先从左侧选择任务实例" />;
  }

  const renderAnalysisPanel = (
    title: string,
    description: string,
    rows: JobLogStructuredRecord[],
  ) => (
    <section className="rounded-xl border border-slate-200 bg-white p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="text-sm font-semibold text-slate-800">{title}</div>
          <div className="mt-1 text-xs text-slate-400">{description}</div>
        </div>
        <Button type="primary" size="small" loading={analysisLoading} onClick={() => void loadAnalysis()}>
          {analysisResult ? "重新解析" : "解析日志"}
        </Button>
      </div>
      {analysisResult ? (
        <>
          <div className="mb-3 flex flex-wrap gap-2 text-xs">
            <Tag color="blue">总记录 {analysisResult.totalLines}</Tag>
            <Tag color="red">错误 {analysisResult.errorCount}</Tag>
            <Tag color="orange">警告 {analysisResult.warningCount}</Tag>
            <Tag>规则版本 v2</Tag>
          </div>
          <div className="mb-2 flex items-center gap-2 text-sm font-semibold text-slate-700">
            <span>{title}</span><Tag className="!mr-0">{rows.length}</Tag>
          </div>
          <StructuredRecordTable rows={rows} />
        </>
      ) : (
        <div className="py-8 text-center text-xs text-slate-400">点击“解析日志”生成{title}</div>
      )}
    </section>
  );

  const renderExecutionPanel = () => (
    <div className="space-y-4">
      <section className="rounded-xl border border-slate-200 bg-white p-4">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <div>
            <div className="text-sm font-semibold text-slate-800">原始执行日志</div>
            <div className="mt-1 text-xs text-slate-400">完整日志 · 当前实例 #{instanceId}</div>
          </div>
          <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={() => void loadLog()}>
            刷新
          </Button>
        </div>
        <Input
          allowClear
          prefix={<SearchOutlined className="text-slate-400" />}
          value={searchKeyword}
          onChange={(event) => setSearchKeyword(event.target.value)}
          placeholder="在当前完整日志中检索 timeout、ERROR、连接..."
          suffix={normalizedKeyword ? <span className="text-xs text-slate-400">命中 {matchedLineCount} 行</span> : null}
        />
        <div className="mt-3 max-h-[360px] overflow-auto rounded-xl bg-slate-950 p-3 font-mono text-xs leading-5 text-slate-100">
          {loading && !logContent ? (
            <div className="flex min-h-[180px] items-center justify-center"><Spin size="small" /></div>
          ) : errorText ? (
            <div className="py-12 text-center text-red-300">{errorText}</div>
          ) : logContent ? (
            logLines.map((line, index) => {
              const matched = normalizedKeyword && line.toLowerCase().includes(normalizedKeyword);
              return <div key={index} className={matched ? "rounded bg-cyan-950/80 text-cyan-100" : ""}>{line || " "}</div>;
            })
          ) : (
            <div className="py-12 text-center text-slate-500">当前实例暂无日志</div>
          )}
        </div>
      </section>
      {renderAnalysisPanel("执行流程日志", "按执行阶段整理任务运行过程和状态变化。", analysisResult?.executionFlow || [])}
    </div>
  );

  const renderReplayPanel = () => (
    <section className="rounded-xl border border-slate-200 bg-white p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="text-sm font-semibold text-slate-800">操作可视化回放</div>
          <div className="mt-1 text-xs text-slate-400">按连续日志阶段命名分段，再回放每个阶段的结构化操作</div>
        </div>
        <Button size="small" loading={replayLoading} onClick={() => void loadReplay()}>
          {replayResult ? "重新加载回放" : "加载回放"}
        </Button>
      </div>
      {replayResult ? (
        <div className="grid gap-4 lg:grid-cols-[220px_minmax(0,1fr)]">
          <div className="space-y-2">
            {replayResult.sections.map((section, index) => {
              const firstPosition = replayPositions.findIndex((position) => position.sectionIndex === index);
              const active = currentReplay?.sectionIndex === index;
              return (
                <button
                  type="button"
                  key={section.id}
                  onClick={() => { setReplayPlaying(false); setReplayCursor(Math.max(firstPosition, 0)); }}
                  className={["w-full rounded-lg border px-3 py-2 text-left transition", active ? "border-blue-400 bg-blue-50" : "border-slate-200 bg-white hover:bg-slate-50"].join(" ")}
                >
                  <div className="flex items-center justify-between gap-2 text-sm font-semibold text-slate-700">
                    <span>{index + 1}. {section.title}</span><Tag className="!mr-0">{section.steps.length}</Tag>
                  </div>
                  <div className="mt-1 text-[11px] text-slate-400">{formatTime(section.startTime)} → {formatTime(section.endTime)}</div>
                </button>
              );
            })}
          </div>
          <div className="min-w-0">
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <Button size="small" type="primary" icon={replayPlaying ? <PauseOutlined /> : <PlayCircleOutlined />} onClick={() => setReplayPlaying((current) => !current)}>
                {replayPlaying ? "暂停" : "播放"}
              </Button>
              <Button size="small" onClick={() => { setReplayPlaying(false); setReplayCursor((current) => Math.max(0, current - 1)); }}>上一步</Button>
              <Button size="small" onClick={() => { setReplayPlaying(false); setReplayCursor((current) => Math.min(replayPositions.length - 1, current + 1)); }}>下一步</Button>
              <span className="text-xs text-slate-400">第 {replayCursor + 1} / {replayPositions.length} 步</span>
            </div>
            <input type="range" min={0} max={Math.max(0, replayPositions.length - 1)} value={replayCursor} onChange={(event) => { setReplayPlaying(false); setReplayCursor(Number(event.target.value)); }} className="mb-3 w-full accent-[#315efb]" aria-label="操作回放进度" />
            <ReplayStepView step={currentReplay?.step} sectionTitle={currentReplay?.section.title} />
          </div>
        </div>
      ) : (
        <div className="py-8 text-center text-xs text-slate-400">点击“加载回放”生成命名阶段</div>
      )}
    </section>
  );

  const renderDiagnosisPanel = () => (
    <section className="rounded-xl border border-slate-200 bg-white p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="text-sm font-semibold text-slate-800">AI故障定位</div>
          <div className="mt-1 text-xs text-slate-400">仅对 FAILED 任务调用故障定位服务，其他状态保留 Tab 但不可执行。</div>
        </div>
        <Tooltip title={canDiagnose ? "分析当前 FAILED 实例" : diagnosisDisabledReason}>
          <span>
            <Button type="primary" danger disabled={!canDiagnose || diagnosisLoading} loading={diagnosisLoading} onClick={() => void startDiagnosis()}>
              {diagnosisLoading ? "分析中..." : "开始故障定位"}
            </Button>
          </span>
        </Tooltip>
      </div>
      {!canDiagnose ? (
        <div className="rounded-lg border border-slate-100 bg-slate-50 p-3 text-xs text-slate-500">{diagnosisDisabledReason}</div>
      ) : null}
      {diagnosisStatus || diagnosisOutput ? (
        <div className="mt-3 rounded-xl border border-slate-200 bg-slate-950 p-4 text-sm leading-6 text-slate-100">
          <div className="mb-2 flex items-center gap-2 text-xs text-cyan-300"><span className="h-2 w-2 rounded-full bg-cyan-400" />{diagnosisStatus || "正在输出..."}</div>
          <div className="whitespace-pre-wrap">{diagnosisOutput || "等待模型输出..."}</div>
        </div>
      ) : null}
      {diagnosisResult ? (
        <div className="mt-3 space-y-3">
          <div className="flex flex-wrap items-center gap-2 border-b border-slate-100 pb-3">
            <Tag color="purple">归因：{diagnosisResult.faultTypeLabel || diagnosisResult.faultType}</Tag>
            <Tag color="blue">类型：{diagnosisResult.faultType}</Tag>
            <Tag>置信度 {Math.round((diagnosisResult.confidence || 0) * 100)}%</Tag>
            <Tag>{diagnosisResult.aiUsed ? "Spring AI" : "规则兜底"}</Tag>
          </div>
          <div className="rounded-xl border border-red-100 bg-red-50/60 p-4">
            <div className="text-sm font-semibold text-slate-800">错误原因</div>
            <div className="mt-2 text-sm leading-6 text-slate-600">{diagnosisResult.rootCause || "暂无明确原因"}</div>
            <div className="mt-2 text-xs text-slate-400">影响阶段：{diagnosisResult.affectedStage || "未明确"}</div>
          </div>
          <div className="grid gap-3 lg:grid-cols-2">
            <div className="rounded-xl border border-slate-100 bg-slate-50 p-3">
              <div className="mb-2 text-sm font-semibold text-slate-700">证据</div>
              <div className="max-h-48 space-y-1 overflow-auto text-xs leading-5 text-slate-600">{(diagnosisResult.evidence || []).map((item, index) => <div key={index}>{item}</div>)}</div>
            </div>
            <div className="rounded-xl border border-slate-100 bg-slate-50 p-3">
              <div className="mb-2 text-sm font-semibold text-slate-700">建议动作</div>
              <div className="space-y-1 text-xs leading-5 text-slate-600">{(diagnosisResult.recommendedActions || []).map((item, index) => <div key={index}>{index + 1}. {item}</div>)}</div>
            </div>
          </div>
        </div>
      ) : null}
    </section>
  );

  const content =
    view === "operation"
      ? renderAnalysisPanel("操作行为记录", "按时间、操作、目标和状态展示任务行为规则命中记录。", analysisResult?.operationRecords || [])
      : view === "snapshot"
        ? renderAnalysisPanel("数据读取快照", "展示日志中识别出的数据读取、写入和快照信息。", analysisResult?.dataSnapshots || [])
        : view === "execution"
          ? renderExecutionPanel()
          : view === "timeline"
            ? renderAnalysisPanel("操作时序记录", "按时间顺序还原任务各阶段的操作先后关系。", analysisResult?.timeline || [])
            : view === "replay"
              ? renderReplayPanel()
              : renderDiagnosisPanel();

  return <div className="space-y-4">{content}</div>;
};

export default TaskLogObservability;
