import type { FormInstance } from 'antd';

export enum DataSourceOperateType {
  Create = 'CREATE',
  Edit = 'EDIT',
}

export type DataSourceLifecycleStatus = 'ENABLED' | 'DISABLED' | 'REVOKED';

export interface CommonApiResponse<T> {
  code: number;
  data: T;
  message?: string;
}

export interface PaginationInfo {
  pageNo: number;
  pageSize: number;
  total: number;
}

export type DataSourceEntityId = string | number;

export interface DataSourceUnitOption {
  id: DataSourceEntityId;
  unitCode?: string;
  unitName: string;
  status?: number;
  remark?: string;
}

export interface BusinessSystemOption {
  id: DataSourceEntityId;
  unitId: DataSourceEntityId;
  systemCode?: string;
  systemName: string;
  status?: number;
  remark?: string;
}

export interface DataSourceRecord {
  id?: string;
  name?: string;
  /** @deprecated Response compatibility for historical data source rows. */
  dataSourceUnit?: string;
  businessSystemId?: DataSourceEntityId | null;
  unitId?: DataSourceEntityId | null;
  unitCode?: string;
  unitName?: string;
  systemCode?: string;
  businessSystemName?: string;
  systemName?: string;
  dbType?: string;
  jdbcUrl?: string;
  environment?: string;
  environmentName?: string;
  connStatus?: string;
  metadataSyncStatus?: string;
  scanStatus?: string;
  scanLastRunTime?: string;
  scanLastSuccessTime?: string;
  profileStatus?: string;
  profileLastRunTime?: string;
  profileLastSuccessTime?: string;
  status?: DataSourceLifecycleStatus;
  /** System projection rows are maintained by lake warehouse configuration. */
  systemManaged?: boolean;
  systemKey?: string;
  remark?: string;
  originalJson?: string;
  createTime?: string;
  updateTime?: string;
}

export interface DataSourceMetadataRunState {
  status?: 'NEVER' | 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'UNKNOWN';
  lastRunTime?: string;
  lastSuccessTime?: string;
  lastError?: string;
}

export interface DataSourceMetadataStatus {
  syncStatus?: string;
  scan?: DataSourceMetadataRunState;
  exploration?: DataSourceMetadataRunState;
}

export interface DataSourcePageResult {
  bizData: DataSourceRecord[];
  pagination: PaginationInfo;
}

export interface DataSourcePageParams {
  pageNo: number;
  pageSize: number;
  dbType?: string;
  dbTypes?: string[];
  name?: string;
  unitId?: DataSourceEntityId;
  businessSystemId?: DataSourceEntityId;
  /** @deprecated Do not send this field for canonical data-source filtering. */
  dataSourceUnit?: string;
  status?: DataSourceLifecycleStatus;
  environment?: string;
}

export interface DataSourceFormValues {
  name: string;
  unitId?: DataSourceEntityId;
  businessSystemId?: DataSourceEntityId | null;
  environment: string;
  remark?: string;
}

export type DataSourceConnectionFormValues = Record<string, unknown>;

export interface DataSourceModalOpenPayload {
  operateType: DataSourceOperateType;
  currentRecord?: DataSourceRecord;
  onSuccess?: () => void;
  /**
   * 外部创建入口已确定 dbType 时传入。
   * 传入后弹窗会跳过数据源类型选择页。
   */
  dbType?: string;

  /**
   * 是否隐藏“上一步”按钮。
   * 从任务配置页创建来源/去向数据源时建议为 true。
   */
  hideBack?: boolean;
}

export interface DataSourceModalRef {
  open: (payload: DataSourceModalOpenPayload) => void;
  close: () => void;
}

export interface DynamicFormFieldRule {
  required?: boolean;
  pattern?: string;
  min?: number;
  max?: number;
  message: string;
}

export interface DynamicFormField {
  key: string;
  label: string;
  type: 'INPUT' | 'PASSWORD' | 'SELECT' | 'NUMBER' | 'SWITCH' | 'TEXTAREA' | 'CUSTOM_SELECT';
  placeholder?: string;
  description?: string;
  options?: Array<{ label: string; value: string | number }>;
  defaultValue?: unknown;
  visibleWhen?: string;
  rules?: DynamicFormFieldRule[];
}

export interface DynamicFormSchemaResponse {
  formFields: DynamicFormField[];
}

export interface DynamicDataSourceFormProps {
  dbType: string;
  form: FormInstance<DataSourceFormValues>;
  configForm: FormInstance;
  operateType: DataSourceOperateType;
  onManageMasterData?: () => void;
  /** 编辑模式下的初始配置数据 */
  initialConfig?: Record<string, unknown>;
  /** Render only connector fields when a page owns the base metadata. */
  hideBaseFields?: boolean;
  /** Keep an existing server-side password when the input is empty. */
  allowExistingPassword?: boolean;
}

export interface DataSourceOptionItem {
  label: string;
  value: string;
}

/**
 * Read-only catalog entries for non-JDBC sources. Kafka and Elasticsearch
 * expose these as OptionVO records while the file connectors use
 * DataSourceCatalogFileEntry below. Keep the value open because older
 * deployments sometimes return numeric identifiers.
 */
export interface DataSourceCatalogOption {
  value?: string | number;
  label?: string;
  description?: string;
  [key: string]: unknown;
}

export interface DataSourceCatalogFileEntry {
  name?: string;
  path?: string;
  type?: string;
  size?: number;
  modifiedTime?: number;
  [key: string]: unknown;
}

export interface DataSourceCatalogItem {
  onlyDiScript: boolean;
  dbType: string;
  label: string;
  type: string;
  connectorType?: string;
  disabled?: boolean;
  img?: string;
  doc?: {
    reader?: string;
    writer?: string;
  };
}

export interface DataSourceGroup {
  groupName: string;
  datasourceList: DataSourceCatalogItem[];
}

/** Read-only projections returned by the backend OpenMetadata exploration facade. */
export interface DataExplorationDatabase {
  id: string;
  name: string;
  fullyQualifiedName: string;
}

export interface DataExplorationSchema {
  id: string;
  name: string;
  fullyQualifiedName: string;
  databaseFullyQualifiedName?: string;
}

export interface DataExplorationTable {
  id: string;
  name: string;
  displayName?: string;
  fullyQualifiedName: string;
  tableType?: string;
  description?: string;
  columnCount?: number;
  profileAvailable?: boolean;
  profileTime?: number;
}

export interface DataExplorationTablePage {
  records: DataExplorationTable[];
  total: number;
  pageNo: number;
  pageSize: number;
}

export interface DataExplorationColumn {
  name: string;
  fullyQualifiedName?: string;
  dataType?: string;
  dataTypeDisplay?: string;
  dataLength?: number;
  precision?: number;
  scale?: number;
  description?: string;
  constraint?: string;
  ordinalPosition?: number;
}

export interface DataExplorationConstraint {
  constraintType?: string;
  columns?: string[];
  referredColumns?: string[];
  relationshipType?: string;
}

export interface DataExplorationTableDetail extends DataExplorationTable {
  serviceFullyQualifiedName?: string;
  databaseFullyQualifiedName?: string;
  schemaFullyQualifiedName?: string;
  retentionPeriod?: string;
  tags?: string[];
  domains?: string[];
  columns: DataExplorationColumn[];
  tableConstraints: DataExplorationConstraint[];
}

export interface DataExplorationMetadataUpdate {
  displayName?: string;
  description?: string;
  tags?: string[];
  domainId?: string;
  retentionPeriod?: string;
}

export interface DataExplorationMetadataJob {
  jobId?: string;
  status?: string;
  type?: string;
  fullyQualifiedName?: string;
  level?: string;
  totalTables?: number;
  progress?: {
    total?: number;
    completed?: number;
    failed?: number;
    skipped?: number;
    [key: string]: unknown;
  };
  result?: unknown;
  error?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface DataExplorationErColumn {
  id: string;
  name: string;
  displayName?: string;
  description?: string;
  dataType?: string;
  constraints: string[];
}

export interface DataExplorationErNode {
  id: string;
  name: string;
  displayName?: string;
  description?: string;
  fullyQualifiedName: string;
  columns: DataExplorationErColumn[];
}

export interface DataExplorationErEndpoint {
  nodeId: string;
  columns: string[];
}

export interface DataExplorationErEdge {
  id: string;
  type: 'FOREIGN_KEY' | string;
  source: DataExplorationErEndpoint;
  target: DataExplorationErEndpoint;
}

export interface DataExplorationErDiagram {
  databaseFqn: string;
  schemaFullyQualifiedName?: string;
  nodes: DataExplorationErNode[];
  edges: DataExplorationErEdge[];
}

export interface DataExplorationTableMetrics {
  rowCount?: number;
  columnCount?: number;
  sizeInByte?: number;
}

export type ExplorationQualityStatus = 'NORMAL' | 'ABNORMAL' | 'NO_RULE' | 'NO_PROFILE';

export interface DataExplorationColumnProfile {
  name: string;
  dataType?: string;
  constraint?: string;
  profileTime?: number;
  valuesCount?: number;
  validCount?: number;
  duplicateCount?: number;
  nullCount?: number;
  missingCount?: number;
  distinctCount?: number;
  uniqueCount?: number;
  nullProportion?: number;
  distinctProportion?: number;
  uniqueProportion?: number;
  min?: unknown;
  max?: unknown;
  mean?: number;
  minLength?: number;
  maxLength?: number;
  qualityStatus?: ExplorationQualityStatus;
  qualityReason?: string;
}

export interface DataExplorationProfile {
  profileTime?: number;
  table?: DataExplorationTableMetrics;
  columns: DataExplorationColumnProfile[];
}

export interface DataExplorationPreviewColumn {
  title?: string;
  dataIndex?: string;
  key?: string;
  ellipsis?: boolean;
}

export interface DataExplorationPreview {
  columns?: DataExplorationPreviewColumn[];
  data?: Array<Record<string, unknown>>;
  total?: number;
}

export interface DataInventorySummary {
  unitCount: number;
  businessSystemCount: number;
  dataSourceCount: number;
  databaseCount: number;
  schemaCount: number;
  tableCount: number;
  columnCount: number;
  profiledDatabaseCount: number;
  profiledTableCount: number;
  knownRowCount: number;
}

export interface DataInventoryFilter {
  unitId?: DataSourceEntityId;
  businessSystemId?: DataSourceEntityId;
  dataSourceId?: DataSourceEntityId;
  databaseFqn?: string;
}

export interface DataInventoryDistributionItem {
  key: string;
  name: string;
  count: number;
}

export interface DataInventoryProfileCoverage {
  databaseCount: number;
  profiledDatabaseCount: number;
  tableCount: number;
  profiledTableCount: number;
  knownRowCount: number;
  tableCoveragePercent: number;
}

export interface DataInventoryOverview {
  summary: DataInventorySummary;
  coverage: DataInventoryProfileCoverage;
  generatedAt?: number;
}

export type DataSourceTopologyNodeType =
  | 'UNIT'
  | 'BUSINESS_SYSTEM'
  | 'DATA_SOURCE'
  | 'DATABASE'
  | 'SCHEMA'
  | 'TABLE';

export interface DataSourceTopologyNode {
  id: string;
  nodeType: DataSourceTopologyNodeType;
  name: string;
  children?: DataSourceTopologyNode[];
}
