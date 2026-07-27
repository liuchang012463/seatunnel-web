import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const csvPath = path.resolve(
  root,
  '../docs/contract-delivery/data-ingestion/traceability.csv',
);
const registryPath = path.resolve(root, 'src/prototype/registry.ts');
const outputPath = path.resolve(
  root,
  'src/prototype/generated/traceability.ts',
);

const parseCsv = (source) => {
  const rows = [];
  let row = [];
  let cell = '';
  let quoted = false;
  for (let index = 0; index < source.length; index += 1) {
    const char = source[index];
    const next = source[index + 1];
    if (char === '"' && quoted && next === '"') {
      cell += '"';
      index += 1;
    } else if (char === '"') {
      quoted = !quoted;
    } else if (char === ',' && !quoted) {
      row.push(cell);
      cell = '';
    } else if ((char === '\n' || char === '\r') && !quoted) {
      if (char === '\r' && next === '\n') index += 1;
      row.push(cell);
      if (row.some(Boolean)) rows.push(row);
      row = [];
      cell = '';
    } else {
      cell += char;
    }
  }
  if (cell || row.length) {
    row.push(cell);
    rows.push(row);
  }
  const [header, ...values] = rows;
  return values.map((columns) =>
    Object.fromEntries(header.map((key, index) => [key, columns[index] || ''])),
  );
};

const modulePages = {
  'MOD-001': ['reporting-forms', 'reporting-reports'],
  'MOD-002': ['data-source', 'data-discovery'],
  'MOD-003': ['batch-link-up', 'stream-link-up', 'lake-resources'],
  'MOD-004': ['client', 'batch-link-up', 'stream-link-up', 'knowledge'],
  'MOD-005': ['bi', 'metrics', 'alarm', 'diagnostics'],
  'MOD-006': ['links', 'topology'],
  'MOD-007': ['edge-access', 'knowledge', 'open-api'],
  'MOD-008': ['cloud-edge', 'edge-access'],
  'MOD-009': ['lake-resources', 'lifecycle', 'logical-access'],
};

const rows = parseCsv(fs.readFileSync(csvPath, 'utf8'));
const registrySource = fs.readFileSync(registryPath, 'utf8');
const registryRequirementIds = new Set(
  [...registrySource.matchAll(/['"]((?:F|P)-\d{2}(?:\.\d{2})?)['"]/g)].map(
    (match) => match[1],
  ),
);
const pageIds = new Set(
  [...registrySource.matchAll(/\bid:\s*['"]([^'"]+)['"]/g)].map(
    (match) => match[1],
  ),
);

const failures = [];
if (rows.length !== 55) {
  failures.push(`指标行数应为 55，实际为 ${rows.length}`);
}
if (pageIds.size !== 20) {
  failures.push(`页面注册表应为 20 项，实际为 ${pageIds.size}`);
}

const seen = new Set();
rows.forEach((row) => {
  if (seen.has(row.requirement_id)) {
    failures.push(`重复指标 ${row.requirement_id}`);
  }
  seen.add(row.requirement_id);
  if (!registryRequirementIds.has(row.requirement_id)) {
    failures.push(`遗漏指标 ${row.requirement_id}`);
  }
  if (!modulePages[row.technical_module]) {
    failures.push(
      `未知技术模块 ${row.technical_module}（${row.requirement_id}）`,
    );
  }
});

modulePages &&
  Object.values(modulePages)
    .flat()
    .forEach((pageId) => {
      if (!pageIds.has(pageId)) failures.push(`孤立或未知页面 ${pageId}`);
    });

if (failures.length) {
  console.error(failures.map((item) => `- ${item}`).join('\n'));
  process.exit(1);
}

const generated = rows.map((row) => ({
  id: row.requirement_id,
  parentId: row.parent_id,
  title: row.atomic_requirement,
  technicalModule: row.technical_module,
  strategy: row.strategy,
  pageIds: modulePages[row.technical_module],
}));

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(
  outputPath,
  `/* 此文件由 scripts/generate-prototype-traceability.mjs 生成，请勿手工编辑。 */\n` +
    `export const generatedTraceability = ${JSON.stringify(generated, null, 2)} as const;\n`,
  'utf8',
);
console.log(
  `traceability generated: ${rows.length} requirements, ${pageIds.size} pages -> ${path.relative(
    root,
    outputPath,
  )}`,
);
