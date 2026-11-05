type SkipHeaderRowNumber = number | string | null | undefined;

/** Existing Excel jobs with a missing/zero setting continue to read every row. */
export const isExcelHeaderEnabled = (skipHeaderRowNumber: SkipHeaderRowNumber): boolean =>
  Number(skipHeaderRowNumber ?? 0) > 0;

/** Apply the header default only in response to a new Excel format/file selection. */
export const defaultExcelHeaderSkipOnSelection = (
  skipHeaderRowNumber: SkipHeaderRowNumber,
): number => {
  const configuredRows = Number(skipHeaderRowNumber ?? 0);
  return Number.isFinite(configuredRows) && configuredRows > 0 ? configuredRows : 1;
};
