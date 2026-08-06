import HttpUtils from "@/utils/HttpUtils";

export type JobLogMode = "BATCH" | "STREAMING";

export interface JobLogSearchParams {
  keyword?: string;
  level?: string;
  source?: string;
  category?: string;
  page?: number;
  pageSize?: number;
}

export interface JobLogEntry {
  sequence: number;
  lineNumber: number;
  timestamp?: string;
  level: string;
  source: string;
  category: string;
  eventType: string;
  message: string;
  raw: string;
  elapsedMs?: number;
}

export interface JobLogStructuredRecord {
  sequence: number;
  lineNumber: number;
  timestamp?: string;
  elapsedMs?: number;
  source: string;
  category: string;
  eventType: string;
  operation: string;
  target: string;
  status: string;
  detail: string;
}

export interface JobLogAnalysisResult {
  instanceId: number;
  jobMode: JobLogMode;
  totalLines: number;
  errorCount: number;
  warningCount: number;
  operationRecords: JobLogStructuredRecord[];
  dataSnapshots: JobLogStructuredRecord[];
  executionFlow: JobLogStructuredRecord[];
  errors: JobLogEntry[];
  timeline: JobLogStructuredRecord[];
}

export interface JobLogReplayStep {
  sequence: number;
  lineNumber: number;
  timestamp?: string;
  elapsedMs?: number;
  source: string;
  category: string;
  eventType: string;
  operation: string;
  target: string;
  title: string;
  status: string;
  detail: string;
  logs?: string[];
}

export interface JobLogReplaySection {
  id: string;
  title: string;
  category: string;
  startTime?: string;
  endTime?: string;
  durationMs?: number;
  steps: JobLogReplayStep[];
}

export interface JobLogReplayResult {
  instanceId: number;
  jobMode: JobLogMode;
  totalSections: number;
  totalSteps: number;
  durationMs?: number;
  sections: JobLogReplaySection[];
}

export interface JobLogFaultDiagnosisResult {
  instanceId: number;
  jobMode: JobLogMode;
  aiUsed: boolean;
  provider: string;
  faultType: string;
  faultTypeLabel: string;
  confidence: number;
  rootCause: string;
  affectedStage: string;
  evidence: string[];
  recommendedActions: string[];
  uncertainties: string[];
  generatedAt?: string;
}

export interface JobLogDiagnosisStreamEvent {
  type: "status" | "delta" | "result" | "done";
  content?: string;
  result?: JobLogFaultDiagnosisResult;
}

export const jobLogApi = {
  content: (instanceId: string | number, jobMode: JobLogMode) => {
    const segment = jobMode === "STREAMING" ? "streaming" : "batch";
    return HttpUtils.get<any>(`/api/v1/job/${segment}-instance/${instanceId}/log`);
  },

  search: (
    instanceId: string | number,
    jobMode: JobLogMode,
    params: JobLogSearchParams = {},
  ) => {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && String(value).trim() !== "") {
        query.set(key, String(value));
      }
    });
    return HttpUtils.get<any>(
      `/api/v1/job-log/${jobMode}/${instanceId}/search?${query.toString()}`,
    );
  },

  analysis: (instanceId: string | number, jobMode: JobLogMode) =>
    HttpUtils.get<any>(`/api/v1/job-log/${jobMode}/${instanceId}/analysis`),

  replay: (instanceId: string | number, jobMode: JobLogMode) =>
    HttpUtils.get<any>(`/api/v1/job-log/${jobMode}/${instanceId}/replay`),

  diagnosis: (instanceId: string | number, jobMode: JobLogMode) =>
    HttpUtils.get<any>(`/api/v1/job-log/${jobMode}/${instanceId}/diagnosis`),

  async *diagnosisStream(
    instanceId: string | number,
    jobMode: JobLogMode,
    signal?: AbortSignal,
  ): AsyncGenerator<JobLogDiagnosisStreamEvent> {
    const response = await fetch(
      `/api/v1/job-log/${jobMode}/${instanceId}/diagnosis/stream`,
      {
        method: "GET",
        headers: { Accept: "text/event-stream" },
        credentials: "omit",
        signal,
      },
    );

    if (!response.ok) {
      const message = await response.text();
      throw new Error(message || `故障定位请求失败（${response.status}）`);
    }

    if (!response.body) {
      throw new Error("服务器未返回故障定位流");
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    const parseBlock = (block: string): JobLogDiagnosisStreamEvent | null => {
      const data = block
        .split(/\r?\n/)
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trimStart())
        .join("\n");

      if (!data) {
        return null;
      }

      return JSON.parse(data) as JobLogDiagnosisStreamEvent;
    };

    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done });

      const blocks = buffer.split(/\r?\n\r?\n/);
      buffer = blocks.pop() || "";

      for (const block of blocks) {
        const event = parseBlock(block);
        if (event) {
          yield event;
        }
      }

      if (done) {
        break;
      }
    }

    if (buffer.trim()) {
      const event = parseBlock(buffer);
      if (event) {
        yield event;
      }
    }
  },

};
