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

const BYTES_PER_MEGABYTE = 1_000_000;
// Round at display time so rate calculations keep their original precision.
const metricFormat = new Intl.NumberFormat(undefined, {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
  roundingMode: "ceil",
});

export const formatMetric = (value: unknown, unit = "", missing = "—") => {
  const number = typeof value === "number" || (typeof value === "string" && value.trim())
    ? Number(value)
    : Number.NaN;
  // A sum such as 0.1 + 0.2 must stay on the 0.3 boundary.
  return Number.isFinite(number)
    ? `${metricFormat.format(Number(number.toPrecision(15)))}${unit ? ` ${unit}` : ""}`
    : missing;
};

export const toMegabytes = (bytes: number) => bytes / BYTES_PER_MEGABYTE;

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

  return Number.isFinite(value) ? toMegabytes(value) : undefined;
};
