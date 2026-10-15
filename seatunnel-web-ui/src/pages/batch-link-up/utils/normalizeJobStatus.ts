export type BatchJobStatusCategory = "FINISHED" | "FAILED" | "RUNNING" | "UNSTARTED";

const FAILED_STATUSES = new Set([
  "FAILED",
  "UNKNOWABLE",
  "CANCELED",
  "CANCELLED",
]);

export const normalizeBatchJobStatus = (
  status?: string | null,
): BatchJobStatusCategory => {
  const normalized = String(status || "").trim().toUpperCase();

  if (!normalized) return "UNSTARTED";
  if (["FINISHED", "SUCCESS", "SAVEPOINT_DONE"].includes(normalized)) {
    return "FINISHED";
  }
  if (FAILED_STATUSES.has(normalized)) return "FAILED";

  return "RUNNING";
};

/**
 * Statuses whose meaning the coarse category cannot express. A cancelled job is not a
 * failure, and an ambiguous submission is not running, so both get their own label.
 */
const RAW_STATUS_LABELS: Record<string, string> = {
  SUBMISSION_UNKNOWN: "提交结果待确认",
  CANCELED: "已取消",
  CANCELLED: "已取消",
};

export const rawBatchJobStatusLabel = (status?: string | null) => {
  const normalized = String(status || "").trim().toUpperCase();

  return RAW_STATUS_LABELS[normalized];
};
