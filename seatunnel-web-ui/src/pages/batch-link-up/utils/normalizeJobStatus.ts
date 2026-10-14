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
