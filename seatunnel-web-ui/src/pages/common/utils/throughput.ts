const TERMINAL_JOB_STATUSES = new Set([
  "FINISHED",
  "SUCCESS",
  "FAILED",
  "CANCELED",
  "CANCELLED",
  "STOPPED",
  "SAVEPOINT_DONE",
  "UNKNOWABLE",
]);

export const isTerminalThroughputStatus = (status: unknown) =>
  TERMINAL_JOB_STATUSES.has(String(status || "").toUpperCase());

/** Returns decimal MB/s, preferring a terminal byte total over the Engine's live rate. */
export const getThroughputMegabytesPerSecond = (
  bytesPerSecond: unknown,
  totalBytes: unknown,
  runtimeMillis: number,
  terminal: boolean,
): number | undefined => {
  const byteCount = totalBytes === undefined || totalBytes === null
    ? Number.NaN
    : Number(totalBytes);
  const averageBps = terminal
    && runtimeMillis > 0
    && Number.isFinite(byteCount)
    && byteCount > 0
    ? (byteCount * 1000) / runtimeMillis
    : undefined;
  const currentBps = bytesPerSecond === undefined || bytesPerSecond === null
    ? Number.NaN
    : Number(bytesPerSecond);
  const value = averageBps ?? currentBps;

  return Number.isFinite(value) ? value / 1_000_000 : undefined;
};
