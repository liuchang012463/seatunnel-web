import { buildOutputSchema, getSchemaFields } from '@/pages/batch-link-up/workflow/panel/components/SourcePanel/localFile';
import type { OfflineDatapackCsvSelection } from '../import/api';

export interface DatapackIdentity {
  packageResourceId: string;
  packageName: string;
  packageFingerprint: string;
}

export interface DatapackSelection extends DatapackIdentity {
  files: OfflineDatapackCsvSelection[];
}

export const isDatapackSource = (config: Record<string, any>) =>
  config.fileFormatType === 'csv' && (
    Boolean(config.datapack?.packageResourceId)
    // Older tasks store only the extracted resource in the server-generated directory.
    || [config.fileResource?.path, config.path, config.objectKey]
      .some((value) => /(?:^|\/)offline-datapack-[a-f0-9-]{36}\//i.test(String(value || '')))
  );

export const datapackSourceConfig = (selection: DatapackSelection) => {
  const { files: [csv], ...identity } = selection;
  const { fileResource, fileName, schema: fields, nullValue } = csv;
  const schema = { fields };
  return {
    sourceMode: 'FILE_RESOURCE',
    dbType: 'MINIO',
    connectorType: 'S3File',
    pluginName: 'S3File',
    readMode: 'file',
    syncType: 'FULL',
    fileFormatType: 'csv',
    formatLocked: true,
    fileResourceId: fileResource.id,
    fileResource,
    path: fileResource.path || fileResource.objectKey,
    objectKey: fileResource.objectKey || fileResource.path,
    encoding: 'UTF-8',
    fieldDelimiter: ',',
    csvUseHeaderLine: true,
    skipHeader: true,
    skipHeaderRowNumber: 0,
    quoteChar: '"',
    escapeChar: '',
    rowDelimiter: undefined,
    dateFormat: undefined,
    // 3.0.0 detects the fractional timestamp precision emitted by the exporter.
    datetimeFormat: undefined,
    timeFormat: undefined,
    nullFormat: nullValue,
    schema,
    outputSchema: buildOutputSchema(schema),
    schemaStatus: 'success',
    schemaError: '',
    datapack: { ...identity, fileName },
  };
};

export const changedDatapackFields = (previous: unknown, next: unknown) => {
  const before = getSchemaFields(previous);
  const after = getSchemaFields(next);
  return [...new Set([...Object.keys(before), ...Object.keys(after)])]
    .filter((name) => before[name] !== after[name]);
};

export const datapackMappingIssue = (workflow: { nodes: any[]; edges: any[] }) => {
  const source = workflow.nodes.find((node) => node?.data?.config?.datapack);
  if (!source) return undefined;
  const config = source.data.config;
  if (config.datapackReviewFields?.length) {
    return '数据包字段结构已变化，请在来源节点确认字段后检查映射';
  }
  const fields = getSchemaFields(config.schema);
  const downstream = workflow.edges.filter((edge) => edge.source === source.id).map((edge) => edge.target);
  const missing = workflow.nodes
    .filter((node) => downstream.includes(node.id) && String(node.data?.componentType).toUpperCase() === 'FIELDMAPPER')
    .flatMap((node) => (node.data.config?.mappings || [])
      .filter((mapping: any) => mapping.enabled !== false && !Object.hasOwn(fields, mapping.sourceField))
      .map((mapping: any) => mapping.sourceField));
  return missing.length ? `请修正数据包中已不存在的映射字段 ${[...new Set(missing)].join('、')}` : undefined;
};
