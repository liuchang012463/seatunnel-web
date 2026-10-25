export type BatchJobStatusCategory = "FINISHED" | "FAILED" | "RUNNING" | "UNSTARTED";

const FAILED_STATUSES = new Set([
  "FAILED",
  // Historical rows only: the backend removed the manual submission-confirmation state.
  "SUBMISSION_UNKNOWN",
  "UNKNOWABLE",
  "CANCELED",
  "CANCELLED",
]);

export const normalizeBatchJobStatus = (
  status?: string | null,
): BatchJobStatusCategory => {
  const normalized = String(status || "").trim().toUpperCase();

  if (!normalized || ["UNSTARTED", "NOT_EXECUTED"].includes(normalized)) return "UNSTARTED";
  if (["FINISHED", "SUCCESS", "SAVEPOINT_DONE"].includes(normalized)) {
    return "FINISHED";
  }
  if (FAILED_STATUSES.has(normalized)) return "FAILED";

  return "RUNNING";
};
