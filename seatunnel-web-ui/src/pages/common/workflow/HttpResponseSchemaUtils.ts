export type HttpContentFieldOption = {
  label: string;
  value: string;
};

export type HttpSchemaFieldCandidate = {
  name: string;
  sample: unknown;
  inferredType: string;
};

export const SEATUNNEL_HTTP_SCHEMA_TYPES = [
  'string',
  'boolean',
  'tinyint',
  'smallint',
  'int',
  'bigint',
  'float',
  'double',
  'decimal',
  'date',
  'time',
  'timestamp',
  'binary',
];

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const isObjectArray = (value: unknown): value is Array<Record<string, unknown>> =>
  Array.isArray(value) && value.some(isRecord);

const childPath = (path: string, key: string) =>
  /^[A-Za-z_][A-Za-z0-9_]*$/.test(key) ? `${path}.${key}` : `${path}['${key.replace(/'/g, "\\'")}']`;

const contentPath = (path: string) => `${path}.*`;

export function findHttpContentFields(root: unknown): HttpContentFieldOption[] {
  const result: HttpContentFieldOption[] = [];
  const seen = new Set<string>();

  const add = (value: string, label = value) => {
    if (!seen.has(value)) {
      seen.add(value);
      result.push({ value, label });
    }
  };

  const visit = (value: unknown, path: string, depth: number) => {
    if (depth > 6 || !isRecord(value)) {
      return;
    }

    Object.entries(value).forEach(([key, child]) => {
      const nextPath = childPath(path, key);
      if (isObjectArray(child)) {
        add(contentPath(nextPath), `${contentPath(nextPath)}（数组记录）`);
        child.slice(0, 5).forEach((item) => visit(item, contentPath(nextPath), depth + 1));
      } else if (isRecord(child)) {
        visit(child, nextPath, depth + 1);
      }
    });
  };

  if (Array.isArray(root) && root.some(isRecord)) {
    add('$.*', '$.*（数组记录）');
  } else if (isRecord(root)) {
    visit(root, '$', 0);
  }

  if (result.length === 0 && (isRecord(root) || Array.isArray(root))) {
    add('$', '根对象');
  }

  return result;
}

function parsePath(path: string): string[] {
  if (path === '$') {
    return [];
  }

  return path
    .replace(/^\$\.?/, '')
    .replace(/\[['"]([^'"\]]+)['"]\]/g, '.$1')
    .split('.')
    .filter(Boolean);
}

function selectPath(root: unknown, path: string): unknown {
  const values = parsePath(path).reduce<unknown[]>((current, segment) => {
    const next: unknown[] = [];
    current.forEach((value) => {
      if (segment === '*') {
        if (Array.isArray(value)) {
          next.push(...value);
        } else if (isRecord(value)) {
          next.push(...Object.values(value));
        }
        return;
      }

      if (Array.isArray(value) && /^\d+$/.test(segment)) {
        const child = value[Number(segment)];
        if (child !== undefined) next.push(child);
      } else if (isRecord(value) && Object.prototype.hasOwnProperty.call(value, segment)) {
        next.push(value[segment]);
      }
    });
    return next;
  }, [root]);
  return values.length === 1 ? values[0] : values;
}

export function resolveHttpContentRecords(root: unknown, path: string): Array<Record<string, unknown>> {
  const selected = selectPath(root, path);
  if (isRecord(selected)) return [selected];
  if (Array.isArray(selected)) return selected.filter(isRecord) as Array<Record<string, unknown>>;
  return [];
}

function inferType(value: unknown): string {
  if (typeof value === 'boolean') return 'boolean';
  if (typeof value === 'number') return Number.isInteger(value) ? 'bigint' : 'double';
  if (Array.isArray(value)) return 'string';
  if (isRecord(value)) return 'string';
  if (typeof value !== 'string') return 'string';
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return 'date';
  if (/^\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(value)) return 'time';
  if (/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}/.test(value)) return 'timestamp';
  return 'string';
}

const sampleValue = (values: unknown[]) => values.find((value) => value !== null && value !== undefined);

export function buildHttpSchemaFieldCandidates(
  root: unknown,
  contentField: string,
): HttpSchemaFieldCandidate[] {
  const records = resolveHttpContentRecords(root, contentField).slice(0, 50);
  const fieldNames = new Set<string>();
  records.forEach((record) => Object.keys(record).forEach((name) => fieldNames.add(name)));

  return Array.from(fieldNames).map((name) => {
    const sample = sampleValue(records.map((record) => record[name]));
    return { name, sample, inferredType: inferType(sample) };
  });
}

export function formatHttpSchemaSample(value: unknown): string {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
