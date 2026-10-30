import type {
  DataExplorationAsset,
  DataExplorationAssetProperty,
  ExplorationAssetSampleStatus,
  ExplorationAssetSchemaStatus,
  ExplorationAssetType,
} from '@/pages/data-source/types';
import { formatDataSize } from '@/pages/data-source/metricFormat';

export const ASSET_TYPE_LABELS: Record<ExplorationAssetType, string> = {
  TOPIC: '主题',
  SEARCH_INDEX: '索引',
  CONTAINER: '容器',
  DIRECTORY: '目录',
  FILE: '文件',
  API_COLLECTION: '接口分组',
  API_ENDPOINT: '接口',
};

export const ASSET_TYPE_EYEBROWS: Record<ExplorationAssetType, string> = {
  TOPIC: 'TOPIC RESOURCE',
  SEARCH_INDEX: 'SEARCH INDEX',
  CONTAINER: 'CONTAINER',
  DIRECTORY: 'DIRECTORY',
  FILE: 'FILE',
  API_COLLECTION: 'API COLLECTION',
  API_ENDPOINT: 'API ENDPOINT',
};

export function normalizeAssetType(value?: string): ExplorationAssetType | undefined {
  const normalized = String(value || '').trim().toUpperCase().replace(/[_-]/g, '');
  const entries = Object.entries(ASSET_TYPE_LABELS) as Array<[ExplorationAssetType, string]>;
  return entries.find(([key]) => key.replace(/_/g, '') === normalized)?.[0];
}

export function assetTypeLabel(value?: string): string {
  const type = normalizeAssetType(value);
  return type ? ASSET_TYPE_LABELS[type] : '资源';
}

export function assetTypeEyebrow(value?: string): string {
  const type = normalizeAssetType(value);
  return type ? ASSET_TYPE_EYEBROWS[type] : 'METADATA';
}

export function assetDisplayName(asset?: DataExplorationAsset | null): string {
  return asset?.displayName || asset?.name || asset?.fullyQualifiedName || '未命名元数据';
}

export type AssetTabKey = 'schema' | 'sample' | 'metrics' | 'request' | 'response';

export function tabsForAsset(value?: string): Array<{ key: AssetTabKey; label: string }> {
  switch (normalizeAssetType(value)) {
    case 'TOPIC':
    case 'SEARCH_INDEX':
    case 'CONTAINER':
    case 'FILE':
      return [
        { key: 'schema', label: '结构' },
        { key: 'sample', label: '样本数据' },
        { key: 'metrics', label: '指标' },
      ];
    case 'API_ENDPOINT':
      return [
        { key: 'request', label: '请求结构' },
        { key: 'response', label: '响应结构' },
      ];
    default:
      return [];
  }
}

export function firstTabKey(value?: string): AssetTabKey | undefined {
  const tabs = tabsForAsset(value);
  // The structure is what the metadata scan produced; the sample is optional and
  // often empty, so the workspace opens on the structure.
  return tabs.find((tab) => tab.key === 'schema')?.key ?? tabs[0]?.key;
}

export interface AssetStateCopy {
  title: string;
  description: string;
}

export function schemaStatusCopy(status?: ExplorationAssetSchemaStatus): AssetStateCopy {
  switch (status) {
    case 'NEEDS_DATA_MODEL':
      return {
        title: '尚未识别为结构化容器',
        description: 'OpenMetadata 需要结构化容器清单才能生成列结构。请先配置清单并重新扫描。',
      };
    case 'NOT_APPLICABLE':
      return {
        title: '该文件不提供结构',
        description: 'OpenMetadata 只对 CSV/TSV 文件抽取列结构。',
      };
    case 'NOT_SUPPORTED':
      return {
        title: '该类元数据不提供结构',
        description: '目录与接口分组在 OpenMetadata 中没有列结构。',
      };
    case 'NO_SCHEMA':
      return {
        title: '尚未抽取到结构',
        description: '元数据扫描完成后会生成结构；如果刚完成扫描，请稍后刷新。',
      };
    default:
      return {
        title: '暂无结构',
        description: '当前元数据没有可展示的结构。',
      };
  }
}

export function sampleStatusCopy(status?: ExplorationAssetSampleStatus): AssetStateCopy {
  switch (status) {
    case 'AVAILABLE':
      return {
        title: '样本已采集',
        description: '样本为最近一次采集结果，可在“样本数据”页签查看。',
      };
    case 'DISABLED':
      return {
        title: '样本采集未开启',
        description: '样本数据需要显式开启采集；开启后重新扫描，OpenMetadata 才会读取真实内容。',
      };
    case 'NOT_COLLECTED':
      return {
        title: '尚未采集到样本',
        description: '样本采集已开启，但当前元数据还没有样本；重新扫描后生成。',
      };
    case 'NEEDS_DATA_MODEL':
      return {
        title: '需要结构化容器清单',
        description: '只有结构化容器才能采集样本。请先配置容器清单并重新扫描。',
      };
    case 'NOT_APPLICABLE':
      return {
        title: '该文件不提供样本',
        description: 'OpenMetadata 只对 CSV/TSV 文件采集样本。',
      };
    case 'NOT_SUPPORTED':
      return {
        title: '该类元数据不提供样本',
        description: '目录与接口在 OpenMetadata 2.0.4 中没有样本数据。',
      };
    case 'PERMISSION_DENIED':
      return {
        title: '没有查看样本的权限',
        description: 'OpenMetadata 服务账号缺少 VIEW_SAMPLE_DATA 权限，请联系管理员。',
      };
    case 'ERROR':
      return {
        title: '样本读取失败',
        description: 'OpenMetadata 返回了错误；结构仍可查看，请稍后重试。',
      };
    default:
      return {
        title: '暂无样本',
        description: '当前元数据没有可展示的样本数据。',
      };
  }
}

export function formatPropertyValue(property: DataExplorationAssetProperty): string {
  const value = property.value;
  if (value === undefined || value === null || value === '') {
    return '—';
  }
  if (Array.isArray(value)) {
    return value.length > 0 ? value.join('、') : '—';
  }
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  switch (property.displayType) {
    case 'SIZE': {
      const bytes = Number(value);
      return bytes < 0 ? '不限' : formatDataSize(bytes);
    }
    case 'NUMBER':
      return Number(value).toLocaleString('zh-CN');
    case 'DURATION':
      return formatDuration(Number(value));
    default:
      return String(value);
  }
}

/** Format JSON and the Python literal representation used by some sample collectors. */
export function formatSampleMessage(message: string): { text: string; parsed: boolean } {
  const parsed = parseJsonLike(message);
  return parsed.ok
    ? { text: JSON.stringify(parsed.value, null, 2), parsed: true }
    : { text: message, parsed: false };
}

/** Formats a complex property while leaving non-JSON code and text untouched. */
export function formatJsonLikeProperty(value: unknown): string {
  if (typeof value !== 'string') {
    try {
      return JSON.stringify(value, null, 2) ?? String(value);
    } catch {
      return String(value);
    }
  }
  const parsed = parseJsonLike(value);
  return parsed.ok ? JSON.stringify(parsed.value, null, 2) : value;
}

function parseJsonLike(value: string): { ok: boolean; value?: unknown } {
  if (value.length > 1_000_000) return { ok: false };
  try {
    return { ok: true, value: JSON.parse(value) };
  } catch {
    try {
      return { ok: true, value: parsePythonLiteral(value) };
    } catch {
      return { ok: false };
    }
  }
}

/** A bounded parser for Python's plain dict/list/tuple/scalar repr; it never evaluates code. */
function parsePythonLiteral(source: string): unknown {
  let index = 0;
  let nodes = 0;

  const skipWhitespace = () => {
    while (index < source.length && /\s/.test(source[index])) index += 1;
  };

  const parseString = (): string => {
    const quote = source[index];
    index += 1;
    let result = '';
    while (index < source.length) {
      const char = source[index++];
      if (char === quote) return result;
      if (char !== '\\') {
        result += char;
        continue;
      }
      if (index >= source.length) throw new Error('Incomplete escape');
      const escaped = source[index++];
      const basicEscapes: Record<string, string> = {
        '\\': '\\',
        "'": "'",
        '"': '"',
        a: '\u0007',
        b: '\b',
        f: '\f',
        n: '\n',
        r: '\r',
        t: '\t',
        v: '\u000b',
      };
      if (Object.prototype.hasOwnProperty.call(basicEscapes, escaped)) {
        result += basicEscapes[escaped];
      } else if (escaped === 'x' || escaped === 'u' || escaped === 'U') {
        const digits = escaped === 'x' ? 2 : escaped === 'u' ? 4 : 8;
        const hex = source.slice(index, index + digits);
        if (hex.length !== digits || !/^[\da-f]+$/i.test(hex)) throw new Error('Invalid unicode escape');
        const codePoint = Number.parseInt(hex, 16);
        if (codePoint > 0x10ffff) throw new Error('Invalid unicode code point');
        result += String.fromCodePoint(codePoint);
        index += digits;
      } else if (/^[0-7]$/.test(escaped)) {
        const octal = `${escaped}${source.slice(index).match(/^[0-7]{0,2}/)?.[0] || ''}`;
        result += String.fromCharCode(Number.parseInt(octal, 8));
        index += octal.length - 1;
      } else {
        result += `\\${escaped}`;
      }
    }
    throw new Error('Unterminated string');
  };

  const parseValue = (depth: number): unknown => {
    skipWhitespace();
    nodes += 1;
    if (depth > 64 || nodes > 20_000) throw new Error('Literal is too complex');
    const char = source[index];
    if (char === "'" || char === '"') return parseString();
    if (char === '[' || char === '(') {
      const close = char === '[' ? ']' : ')';
      index += 1;
      const values: unknown[] = [];
      skipWhitespace();
      if (source[index] === close) {
        index += 1;
        return values;
      }
      while (index < source.length) {
        values.push(parseValue(depth + 1));
        skipWhitespace();
        if (source[index] === close) {
          index += 1;
          return values;
        }
        if (source[index] !== ',') throw new Error('Expected a comma');
        index += 1;
        skipWhitespace();
        if (source[index] === close) {
          index += 1;
          return values;
        }
      }
      throw new Error('Unterminated sequence');
    }
    if (char === '{') {
      index += 1;
      const result: Record<string, unknown> = Object.create(null);
      skipWhitespace();
      if (source[index] === '}') {
        index += 1;
        return result;
      }
      while (index < source.length) {
        const key = parseValue(depth + 1);
        skipWhitespace();
        if (source[index] !== ':') throw new Error('Expected a mapping key');
        index += 1;
        result[String(key)] = parseValue(depth + 1);
        skipWhitespace();
        if (source[index] === '}') {
          index += 1;
          return result;
        }
        if (source[index] !== ',') throw new Error('Expected a comma');
        index += 1;
        skipWhitespace();
        if (source[index] === '}') {
          index += 1;
          return result;
        }
      }
      throw new Error('Unterminated mapping');
    }
    const token = source.slice(index);
    const literal = /^(True|False|None)\b/.exec(token);
    if (literal) {
      index += literal[0].length;
      if (literal[0] === 'True') return true;
      if (literal[0] === 'False') return false;
      return null;
    }
    const number = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?/.exec(token);
    if (number) {
      index += number[0].length;
      const parsed = Number(number[0]);
      if (!Number.isFinite(parsed)) throw new Error('Invalid number');
      return parsed;
    }
    throw new Error('Unsupported literal');
  };

  const parsed = parseValue(0);
  skipWhitespace();
  if (index !== source.length) throw new Error('Unexpected trailing content');
  return parsed;
}

function formatDuration(milliseconds: number): string {
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return '不限';
  const seconds = Math.round(milliseconds / 1000);
  if (seconds >= 86400) return `${Math.round(seconds / 86400)} 天`;
  if (seconds >= 3600) return `${Math.round(seconds / 3600)} 小时`;
  if (seconds >= 60) return `${Math.round(seconds / 60)} 分钟`;
  return `${seconds} 秒`;
}

export function decodeHtmlEntities(value?: string): string {
  if (!value) return '';
  return value
    .replace(/&#(x?)([0-9a-f]+);/gi, (_, hex, code) => {
      const point = parseInt(code, hex ? 16 : 10);
      return Number.isFinite(point) ? String.fromCodePoint(point) : _;
    })
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'");
}

export function formatAssetTime(timestamp?: number | null): string {
  if (!timestamp) return '—';
  const date = new Date(timestamp);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN');
}

export function formatAssetBytes(value?: number | null): string {
  if (value === undefined || value === null || Number.isNaN(Number(value))) return '—';
  return formatDataSize(Number(value));
}
