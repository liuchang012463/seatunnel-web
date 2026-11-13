/**
 * 数据源分类。
 * 图数据库、时序数据库尚未接入 SeaTunnel 连接器：见 PLANNED_DATA_SOURCE_CATEGORIES。
 * 接入步骤：在 DATA_SOURCE_REGISTRY 增加条目（category 用对应 key）→ 把 key 写入 CATEGORY_LABELS。
 */
export type DataSourceCategoryKey =
  | 'RELATIONAL'
  | 'OLAP'
  | 'MESSAGE_QUEUE'
  | 'FILE_TRANSFER'
  | 'API'
  | 'OTHER'
  | 'GRAPH'
  | 'TIME_SERIES';

/** 规划中分类：无 REGISTRY 条目时不出现在管理页分类页签 */
export const PLANNED_DATA_SOURCE_CATEGORIES = [
  { key: 'GRAPH' as const, label: '图数据库', note: '待接入图数据库连接器（如 Neo4j 等）' },
  { key: 'TIME_SERIES' as const, label: '时序数据库', note: '待接入时序库连接器（如 InfluxDB、TDengine 等）' },
];

export interface DataSourceRegistryItem {
  dbType: string;
  label: string;
  category: DataSourceCategoryKey;
  connectorType: string;
  pluginName?: string;
  /** Management views preserve legacy capability when omitted; task selectors keep explicit-true behavior. */
  source?: boolean;
  sink?: boolean;
  realtime?: boolean;
  taskSelector?: boolean;
  creatable?: boolean;
}

export type DataSourceUsageRole = 'SOURCE' | 'SINK';

/** Missing connector metadata preserves legacy usage; only explicit false disables it. */
export function supportsDataSourceUsage(
  item: Pick<DataSourceRegistryItem, 'source' | 'sink'> | undefined,
  usage: DataSourceUsageRole,
): boolean {
  if (!item) return false;
  return usage === 'SOURCE' ? item.source !== false : item.sink !== false;
}

export function filterDataSourceTypesByUsage<T extends DataSourceRegistryItem>(
  items: readonly T[],
  usage: DataSourceUsageRole,
): T[] {
  return items.filter((item) => supportsDataSourceUsage(item, usage));
}

export interface DataSourceCategory {
  key: DataSourceCategoryKey;
  label: string;
  dbTypes: string[];
}

/**
 * 引接任务「数据同步方式」下拉顺序跟注册表一致。
 * 常用国产库（达梦 / 金仓 / 海量）靠前，默认选中达梦。
 */
export const DATA_SOURCE_REGISTRY: DataSourceRegistryItem[] = [
  { dbType: 'DAMENG', label: '达梦（Dameng）', category: 'RELATIONAL', connectorType: 'Jdbc', pluginName: 'JDBC-DAMENG', source: true, sink: true, taskSelector: true },
  { dbType: 'KINGBASE', label: '人大金仓（Kingbase）', category: 'RELATIONAL', connectorType: 'Jdbc', pluginName: 'JDBC-KINGBASE', source: true, sink: true, taskSelector: true },
  { dbType: 'VASTBASE', label: '海量（Vastbase）', category: 'RELATIONAL', connectorType: 'Jdbc', pluginName: 'JDBC-VASTBASE', source: true, sink: true, taskSelector: true },
  { dbType: 'MYSQL', label: 'MYSQL', category: 'RELATIONAL', connectorType: 'Jdbc', pluginName: 'JDBC-MYSQL', source: true, sink: true, taskSelector: true },
  { dbType: 'POSTGRE_SQL', label: 'PostgreSQL', category: 'RELATIONAL', connectorType: 'Jdbc', pluginName: 'JDBC-POSTGRESQL', source: true, sink: true, taskSelector: true },
  { dbType: 'ORACLE', label: 'ORACLE', category: 'RELATIONAL', connectorType: 'Jdbc', pluginName: 'JDBC-ORACLE', source: true, sink: true, taskSelector: true },
  { dbType: 'ZEONEDB', label: 'ZeoneDB-D', category: 'RELATIONAL', connectorType: 'Jdbc', pluginName: 'JDBC-POSTGRESQL', source: true, sink: true, taskSelector: true },
  { dbType: 'JDBC', label: 'JDBC', category: 'RELATIONAL', connectorType: 'Jdbc', pluginName: 'JDBC-JDBC', source: true, sink: true, taskSelector: true },
  { dbType: 'DORIS', label: 'Doris', category: 'OLAP', connectorType: 'Doris', pluginName: 'DORIS', source: true, sink: true, taskSelector: true },
  { dbType: 'ELASTICSEARCH', label: 'Elasticsearch', category: 'OLAP', connectorType: 'Elasticsearch', pluginName: 'ELASTICSEARCH', source: true, sink: true, realtime: true, taskSelector: true },
  { dbType: 'KAFKA', label: 'Kafka', category: 'MESSAGE_QUEUE', connectorType: 'Kafka', pluginName: 'KAFKA', source: true, sink: true, realtime: true, taskSelector: true },
  { dbType: 'FTP', label: 'FTP', category: 'FILE_TRANSFER', connectorType: 'FtpFile' },
  { dbType: 'SFTP', label: 'SFTP', category: 'FILE_TRANSFER', connectorType: 'SftpFile' },
  { dbType: 'S3', label: 'Amazon S3', category: 'FILE_TRANSFER', connectorType: 'S3File' },
  { dbType: 'MINIO', label: 'MinIO', category: 'FILE_TRANSFER', connectorType: 'S3File' },
  { dbType: 'HTTP', label: 'HTTP / API', category: 'API', connectorType: 'Http', pluginName: 'HTTP', source: true, sink: false, realtime: true, taskSelector: true },
  { dbType: 'H2', label: 'H2', category: 'OTHER', connectorType: 'Jdbc', creatable: false },
];

/** 新建引接任务默认来源（达梦） */
export const DEFAULT_TASK_DATASOURCE_TYPE = {
  dbType: 'DAMENG',
  connectorType: 'Jdbc',
  pluginName: 'JDBC-DAMENG',
} as const;

/**
 * 引接任务「去向」仅开放人大金仓 / 海量。
 * 顺序：金仓 → 海量；新建任务默认去向为金仓。
 */
export const TASK_TARGET_DB_TYPES = ['KINGBASE', 'VASTBASE'] as const;

export const DEFAULT_TASK_TARGET_DATASOURCE_TYPE = {
  dbType: 'KINGBASE',
  connectorType: 'Jdbc',
  pluginName: 'JDBC-KINGBASE',
} as const;

/** JDBC-backed task sources that expose database-first metadata. */
export const DATABASE_SCOPE_DATA_SOURCE_TYPES = new Set([
  'JDBC',
  'MYSQL',
  'ORACLE',
  'POSTGRE_SQL',
  'ZEONEDB',
  'VASTBASE',
  'KINGBASE',
  'DAMENG',
  'DORIS',
]);

/** Sources with a schema layer below the selected database. */
export const SCHEMA_SCOPE_DATA_SOURCE_TYPES = new Set([
  'JDBC',
  'ORACLE',
  'POSTGRE_SQL',
  'ZEONEDB',
  'VASTBASE',
  'KINGBASE',
  'DAMENG',
]);

export function supportsDatabaseScope(dbType?: string): boolean {
  return DATABASE_SCOPE_DATA_SOURCE_TYPES.has(String(dbType || '').toUpperCase());
}

export function supportsSchemaScope(dbType?: string): boolean {
  return SCHEMA_SCOPE_DATA_SOURCE_TYPES.has(String(dbType || '').toUpperCase());
}

/** 仅含已接入连接器的分类；GRAPH / TIME_SERIES 待 REGISTRY 有条目后再加入 */
const CATEGORY_LABELS: Partial<Record<DataSourceCategoryKey, string>> = {
  RELATIONAL: '关系型数据库',
  OLAP: 'OLAP 数据库',
  MESSAGE_QUEUE: '消息队列',
  FILE_TRANSFER: '文件与对象存储',
  API: 'API 服务',
  OTHER: '其他',
};

/** 连接器副标题用协议说明，不暴露内部代码（DESIGN.md §4.8）。 */
const CONNECTOR_TYPE_LABELS: Record<string, string> = {
  Jdbc: 'JDBC 通用连接',
  FtpFile: 'FTP 文件同步',
  SftpFile: 'SFTP 文件同步',
  S3File: 'S3 协议对象存储',
  Kafka: 'Kafka 消息队列',
  Http: 'HTTP 接口',
  Elasticsearch: 'Elasticsearch 引擎',
  Doris: 'Doris OLAP',
};

export function getConnectorTypeLabel(connectorType?: string, fallback = '数据源连接器'): string {
  if (!connectorType) {
    return fallback;
  }
  return CONNECTOR_TYPE_LABELS[connectorType] || connectorType;
}

export const DATA_SOURCE_CATEGORIES: DataSourceCategory[] = (
  Object.keys(CATEGORY_LABELS) as DataSourceCategoryKey[]
)
  .filter((key) => CATEGORY_LABELS[key])
  .map((key) => ({
    key,
    label: CATEGORY_LABELS[key] as string,
    dbTypes: DATA_SOURCE_REGISTRY.filter((item) => item.category === key).map(
      (item) => item.dbType,
    ),
  }));

export function getDataSourceCategory(dbType?: string): DataSourceCategory {
  const normalized = String(dbType || '').toUpperCase();
  const item = DATA_SOURCE_REGISTRY.find((entry) => entry.dbType === normalized);
  const key = item?.category || 'OTHER';
  return (
    DATA_SOURCE_CATEGORIES.find((category) => category.key === key) ||
    DATA_SOURCE_CATEGORIES.find((category) => category.key === 'OTHER')!
  );
}

export function groupDataSourcesByCategory<T extends { dbType?: string }>(
  records: T[],
): Array<{ category: DataSourceCategory; records: T[] }> {
  const buckets = new Map<DataSourceCategoryKey, T[]>();
  records.forEach((record) => {
    const category = getDataSourceCategory(record.dbType);
    buckets.set(category.key, [...(buckets.get(category.key) || []), record]);
  });
  return DATA_SOURCE_CATEGORIES.map((category) => ({
    category,
    records: buckets.get(category.key) || [],
  })).filter((group) => group.records.length > 0);
}
