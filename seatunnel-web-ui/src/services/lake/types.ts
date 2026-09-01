import type { ApiResponse } from '@/utils/request';

export type LakeResourceStatus =
  | 'PENDING_CREATE'
  | 'CREATING'
  | 'READY'
  | 'ERROR'
  | 'CREATE_FAILED'
  | 'MISSING'
  | 'UNKNOWN'
  | 'DELETING'
  | 'DELETED';
export type LakeConsistencyStatus = 'CONSISTENT' | 'DRIFT' | 'MISSING' | 'UNKNOWN' | 'UNBOUND';
export type LakeManagementLevel = 'MANAGED' | 'AUTO_CREATED' | 'UNMANAGED';
export type LakePolicyStatus = 'DRAFT' | 'ACTIVE' | 'DISABLED';
export type LakePartitionGranularity = 'DAY' | 'MONTH' | 'YEAR';
export type LakeCatalogScope = 'ALL' | 'DATABASE' | 'TABLE';
export type LakeJdbcAdapter = 'MYSQL' | 'POSTGRESQL' | 'ORACLE';
export type LakeRecommendationMode = 'PHYSICAL' | 'LOGICAL' | 'UNSUPPORTED';

export interface LakePagination {
  pageNo: number;
  pageSize: number;
  total: number;
}

export interface LakePage<T> {
  bizData?: T[];
  pagination?: Partial<LakePagination>;
}

export type LakeApiResponse<T> = ApiResponse<T>;

export interface LakeOdsDatabase {
  id: number;
  lakeDataSourceId?: number;
  sourceDataSourceId?: number;
  unitCode?: string;
  systemCode?: string;
  databaseName?: string;
  resourceStatus?: LakeResourceStatus;
  generation?: number;
  lockVersion?: number;
  errorCode?: string;
  errorMessage?: string;
  lastReconcileAt?: string;
  deleted?: boolean;
  createTime?: string;
  updateTime?: string;
}

export interface LakePhysicalDataSource {
  sourceDataSourceId: number;
  sourceDataSourceName?: string;
  businessSystemId?: number;
  unitId?: number;
  unitCode?: string;
  systemCode?: string;
  odsDatabaseBindingId?: number;
  odsDatabase?: LakeOdsDatabase;
}

export interface LakeInventoryTable {
  mappingId?: number;
  sourceObjectRefId?: number;
  sourceTableName?: string;
  targetTableName?: string;
  managementLevel?: LakeManagementLevel;
  resourceStatus?: LakeResourceStatus;
  sourceBound?: boolean;
  actualExists?: boolean;
  actualTableExists?: boolean;
  [key: string]: unknown;
}

export interface LakeInventoryRelation {
  relationId?: number;
  jobId?: number;
  jobRuntimeType?: string;
  jobVersion?: number;
  relationStatus?: string;
  relationScope?: string;
  tableMappingId?: number;
  [key: string]: unknown;
}

export interface LakePhysicalInventory {
  odsDatabaseBindingId: number;
  databaseName?: string;
  actualTableNames?: string[];
  registeredTables?: LakeInventoryTable[];
  discoveredTables?: LakeInventoryTable[];
  tableRelations?: LakeInventoryRelation[];
  namespaceRelations?: LakeInventoryRelation[];
}

export interface LakeTableColumn {
  sourceField?: string;
  sourceFieldName?: string;
  sourceType?: string;
  sourceNullable?: boolean;
  targetField?: string;
  targetFieldName?: string;
  targetType?: string;
  targetNullable?: boolean;
  key?: boolean;
}

export interface LakeTargetContract {
  tableModel?: 'DUPLICATE' | 'UNIQUE';
  columns?: LakeTableColumn[];
  keyColumns?: string[];
  partition?: { enabled?: boolean; column?: string; granularity?: string };
  distribution?: { type?: string; columns?: string[]; buckets?: string };
  [key: string]: unknown;
}

export interface LakeManagedTable {
  id: number;
  sourceObjectRefId?: number;
  sourceDataSourceId?: number;
  omEntityId?: string;
  omFqn?: string;
  odsDatabaseBindingId?: number;
  lakeDataSourceId?: number;
  databaseName?: string;
  targetTableName?: string;
  managementLevel?: LakeManagementLevel;
  tableModel?: 'DUPLICATE' | 'UNIQUE';
  resourceStatus?: LakeResourceStatus;
  generation?: number;
  lockVersion?: number;
  sourceSchemaHash?: string;
  targetContractHash?: string;
  targetContract?: LakeTargetContract;
  fieldMappings?: LakeTableColumn[];
  sourceConsistencyStatus?: LakeConsistencyStatus;
  targetConsistencyStatus?: LakeConsistencyStatus;
  taskConsistencyStatus?: LakeConsistencyStatus;
  actualTableExists?: boolean;
  errorCode?: string;
  errorMessage?: string;
  lastReconcileAt?: string;
  deleted?: boolean;
  createTime?: string;
  updateTime?: string;
}

export interface LakeManagedTablePreview {
  valid?: boolean;
  previewToken?: string;
  sourceDataSourceId?: number;
  omEntityId?: string;
  odsDatabaseBindingId?: number;
  targetTableName?: string;
  sourceSchemaHash?: string;
  targetContractHash?: string;
  targetContract?: LakeTargetContract;
  fieldMappings?: LakeTableColumn[];
  ddl?: string;
  warnings?: string[];
  errors?: string[];
}

export interface LakeDeleteImpact {
  mappingId?: number;
  targetTableName?: string;
  actualTableExists?: boolean;
  lifecycleBound?: boolean;
  allowed?: boolean;
  impactHash?: string;
  relations?: Array<Record<string, unknown>>;
  blockers?: string[];
}

export interface LakeLifecyclePolicy {
  id?: number;
  policyName?: string;
  version?: number;
  status?: LakePolicyStatus;
  granularity?: LakePartitionGranularity;
  retentionCount?: number;
  description?: string;
  createTime?: string;
  updateTime?: string;
}

export interface LakeLifecycleValidation {
  valid?: boolean;
  mappingId?: number;
  policyId?: number;
  status?: string;
  errorCode?: string;
  errorMessage?: string;
  warnings?: string[];
  errors?: string[];
  [key: string]: unknown;
}

export interface LakeCatalog {
  id?: number;
  lakeDataSourceId?: number;
  sourceDataSourceId?: number;
  targetCatalogName?: string;
  adapter?: string;
  scope?: LakeCatalogScope;
  desiredSpecHash?: string;
  credentialRevision?: string;
  driverChecksum?: string;
  validationStatus?: string;
  resourceStatus?: LakeResourceStatus;
  generation?: number;
  lockVersion?: number;
  errorCode?: string;
  errorMessage?: string;
  actualSnapshot?: Record<string, unknown>;
  lastObservedAt?: string;
  lastReconcileAt?: string;
  createTime?: string;
  updateTime?: string;
}

export interface LakeCapability {
  adapter?: LakeJdbcAdapter | string;
  enabled?: boolean;
  supported?: boolean;
  reasonCodes?: string[];
  disabledReasons?: string[];
}

export interface LakeRecommendation {
  mode: LakeRecommendationMode;
  recommendation?: LakeRecommendationMode;
  reason: string;
  reasonCode?: string;
  disabledReasons?: string[];
  physicalCapability: LakeCapability;
  logicalCapability: LakeCapability;
  targetScope?: LakeCatalogScope;
  adapter?: LakeJdbcAdapter;
}

export interface LakeQueryTableIdentity {
  catalog: string;
  database: string;
  table: string;
}

export interface LakeQueryColumnIdentity {
  table: LakeQueryTableIdentity;
  column: string;
}

export interface LakeReadOnlyQueryResult {
  columns: string[];
  rows: Array<Record<string, unknown>>;
  rowCount: number;
  byteCount: number;
  truncated: boolean;
  elapsedMillis: number;
  explain: boolean;
}

export interface LakeErrorPayload {
  code?: string;
  message?: string;
}

