/** Format byte sizes with adaptive KB / MB / GB / TB units. */
export function formatDataSize(bytes?: number | null): string {
  if (bytes === undefined || bytes === null || Number.isNaN(Number(bytes))) {
    return '-';
  }
  const value = Number(bytes);
  if (value < 0) {
    return '-';
  }
  const kb = 1024;
  const mb = kb * 1024;
  const gb = mb * 1024;
  const tb = gb * 1024;
  if (value < kb) {
    return `${value} B`;
  }
  if (value < mb) {
    return `${(value / kb).toFixed(1)} KB`;
  }
  if (value < gb) {
    return `${(value / mb).toFixed(1)} MB`;
  }
  if (value < tb) {
    return `${(value / gb).toFixed(1)} GB`;
  }
  return `${(value / tb).toFixed(1)} TB`;
}
