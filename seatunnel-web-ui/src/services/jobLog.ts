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
};
