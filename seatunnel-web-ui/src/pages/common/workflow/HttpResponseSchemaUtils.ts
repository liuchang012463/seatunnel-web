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

  const text = value.trim();
  if (/^\d{4}[-/]\d{1,2}[-/]\d{1,2}$/.test(text)) return 'date';
  if (/^\d{1,2}:\d{2}(?::\d{2})?(?:\.\d+)?$/.test(text)) return 'time';
  // Accept common API timestamp shapes, including ISO-8601 with a timezone.
  if (/^\d{4}[-/]\d{1,2}[-/]\d{1,2}[T ]\d{1,2}:\d{2}(?::\d{2})?(?:[.,]\d+)?(?:Z|[+-]\d{2}:?\d{2})?$/.test(text)) {
    return 'timestamp';
  }
  return 'string';
}

export function buildHttpSchemaFieldCandidates(
  root: unknown,
  contentField: string,
): HttpSchemaFieldCandidate[] {
  const records = resolveHttpContentRecords(root, contentField);
  // Scan every record for field names so fields that only appear in later
  // records are not missing from the schema editor. Keep the first non-null
  // value as the sample used for type inference.
  const fieldNames: string[] = [];
  const samples = new Map<string, unknown>();
  records.forEach((record) => {
    Object.entries(record).forEach(([name, value]) => {
      if (!samples.has(name)) {
        fieldNames.push(name);
        samples.set(name, undefined);
      }
      if (samples.get(name) === undefined && value !== null && value !== undefined) {
        samples.set(name, value);
      }
    });
  });

  return fieldNames.map((name) => {
    const sample = samples.get(name);
    return { name, sample, inferredType: inferType(sample) };
  });
}

export function mergeHttpSchemaFieldTypes(
  candidates: HttpSchemaFieldCandidate[],
  current: Record<string, string>,
): Record<string, string> {
  return candidates.reduce<Record<string, string>>((fields, candidate) => {
    // Keep an explicit user choice; otherwise prefill the recommended type
    // inferred from the response sample.
    fields[candidate.name] = String(current[candidate.name] || candidate.inferredType || '');
    return fields;
  }, {});
}

export function formatHttpSchemaSample(value: unknown): string {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
