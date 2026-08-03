export type DataSourceCategoryKey =
  | 'RELATIONAL'
  | 'OLAP'
  | 'MESSAGE_QUEUE'
  | 'FILE_TRANSFER'
  | 'API'
  | 'OTHER';

export interface DataSourceRegistryItem {
  dbType: string;
  label: string;
  category: DataSourceCategoryKey;
  connectorType: string;
  pluginName?: string;
  source?: boolean;
  sink?: boolean;
  realtime?: boolean;
  taskSelector?: boolean;
  creatable?: boolean;
}

export interface DataSourceCategory {
  key: DataSourceCategoryKey;
  label: string;
  dbTypes: string[];
}

export const DATA_SOURCE_REGISTRY: DataSourceRegistryItem[] = [
  { dbType: 'JDBC', label: 'JDBC', category: 'RELATIONAL', connectorType: 'Jdbc', pluginName: 'JDBC-JDBC', source: true, sink: true, taskSelector: true },
  { dbType: 'MYSQL', label: 'MYSQL', category: 'RELATIONAL', connectorType: 'Jdbc', pluginName: 'JDBC-MYSQL', source: true, sink: true, taskSelector: true },
  { dbType: 'ORACLE', label: 'ORACLE', category: 'RELATIONAL', connectorType: 'Jdbc', pluginName: 'JDBC-ORACLE', source: true, sink: true, taskSelector: true },
  { dbType: 'POSTGRE_SQL', label: 'PostgreSQL', category: 'RELATIONAL', connectorType: 'Jdbc', pluginName: 'JDBC-POSTGRESQL', source: true, sink: true, taskSelector: true },
  { dbType: 'KINGBASE', label: '人大金仓（Kingbase）', category: 'RELATIONAL', connectorType: 'Jdbc', pluginName: 'JDBC-KINGBASE', source: true, sink: true, taskSelector: true },
  { dbType: 'DAMENG', label: '达梦（Dameng）', category: 'RELATIONAL', connectorType: 'Jdbc', pluginName: 'JDBC-DAMENG', source: true, sink: true, taskSelector: true },
  { dbType: 'DORIS', label: 'Doris', category: 'OLAP', connectorType: 'Doris', pluginName: 'DORIS', source: true, sink: true, taskSelector: true },
  { dbType: 'KAFKA', label: 'Kafka', category: 'MESSAGE_QUEUE', connectorType: 'Kafka', pluginName: 'KAFKA', source: true, sink: true, realtime: true, taskSelector: true },
  { dbType: 'FTP', label: 'FTP', category: 'FILE_TRANSFER', connectorType: 'FtpFile' },
  { dbType: 'SFTP', label: 'SFTP', category: 'FILE_TRANSFER', connectorType: 'SftpFile' },
  { dbType: 'S3', label: 'Amazon S3', category: 'FILE_TRANSFER', connectorType: 'S3File' },
  { dbType: 'MINIO', label: 'MinIO', category: 'FILE_TRANSFER', connectorType: 'S3File' },
  { dbType: 'HTTP', label: 'HTTP / API', category: 'API', connectorType: 'Http', pluginName: 'HTTP', source: true, sink: false, realtime: true, taskSelector: true },
  { dbType: 'H2', label: 'H2', category: 'OTHER', connectorType: 'Jdbc', creatable: false },
];

const CATEGORY_LABELS: Record<DataSourceCategoryKey, string> = {
  RELATIONAL: '关系型数据库',
  OLAP: 'OLAP 数据库',
  MESSAGE_QUEUE: '消息队列',
  FILE_TRANSFER: '文件传输',
  API: 'API 服务',
  OTHER: '其他',
};

export const DATA_SOURCE_CATEGORIES: DataSourceCategory[] = (
  Object.keys(CATEGORY_LABELS) as DataSourceCategoryKey[]
).map((key) => ({
  key,
  label: CATEGORY_LABELS[key],
  dbTypes: DATA_SOURCE_REGISTRY.filter((item) => item.category === key).map(
    (item) => item.dbType,
  ),
}));

export function getDataSourceCategory(dbType?: string): DataSourceCategory {
  const normalized = String(dbType || '').toUpperCase();
  const item = DATA_SOURCE_REGISTRY.find((entry) => entry.dbType === normalized);
  const key = item?.category || 'OTHER';
  return DATA_SOURCE_CATEGORIES.find((category) => category.key === key)!;
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
