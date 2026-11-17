import type { SummaryData, ChartDataItem } from '@/pages/metrics/types';

// Shared unit conversions keep API normalization separate from dashboard rendering.
export const integerFormat = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 });
export const numberFormat = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 });
const RECORD_FACTORS: Record<string, number> = { '100M': 100_000_000, '万': 10_000 };
const BYTE_FACTORS: Record<string, number> = {
  B: 1,
  KB: 2 ** 10,
  MB: 2 ** 20,
  GB: 2 ** 30,
  TB: 2 ** 40,
};

export const asNumber = (value: number | string | null | undefined) => {
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
};

const recordFactorForUnit = (unit: unknown) => {
  const normalized = String(unit || '').trim();
  if (normalized.startsWith('100M')) return RECORD_FACTORS['100M'];
  if (normalized.includes('万')) return RECORD_FACTORS['万'];
  return 1;
};

const byteFactorForUnit = (unit: unknown) => BYTE_FACTORS[String(unit || 'B').trim().toUpperCase()] || 1;

export const toRecords = (summary: SummaryData) =>
  asNumber(summary.totalRecords) * recordFactorForUnit(summary.totalRecordsUnit);

export const toBytes = (summary: SummaryData) =>
  asNumber(summary.totalBytes) * byteFactorForUnit(summary.totalBytesUnit);

export const chartRecordsToRaw = (point: ChartDataItem) =>
  asNumber(point.value) * recordFactorForUnit(point.unit);

export const chartBytesToRaw = (point: ChartDataItem) =>
  asNumber(point.value) * byteFactorForUnit(point.unit);

export const formatRecords = (value: number) => {
  if (value >= 100_000_000) return { value: numberFormat.format(value / 100_000_000), unit: '亿条' };
  if (value >= 10_000) return { value: numberFormat.format(value / 10_000), unit: '万条' };
  return { value: integerFormat.format(value), unit: '条' };
};

export const formatBytes = (value: number) => {
  const units = ['字节', 'KB', 'MB', 'GB', 'TB'];
  let unitIndex = 0;
  let scaledValue = Math.max(0, value);
  while (scaledValue >= 1024 && unitIndex < units.length - 1) {
    scaledValue /= 1024;
    unitIndex += 1;
  }
  return { value: numberFormat.format(scaledValue), unit: units[unitIndex] };
};

export const formatTime = (date: Date) =>
  new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date);

