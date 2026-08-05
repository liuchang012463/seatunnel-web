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

export interface JobLogAnalysisResult {
  instanceId: number;
  jobMode: JobLogMode;
  totalLines: number;
  errorCount: number;
  warningCount: number;
  operationRecords: JobLogEntry[];
  dataSnapshots: JobLogEntry[];
  executionFlow: JobLogEntry[];
  errors: JobLogEntry[];
  timeline: JobLogEntry[];
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

};
