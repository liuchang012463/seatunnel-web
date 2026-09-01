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

export type LakeManagementLevel = 'MANAGED' | 'AUTO_CREATED' | 'UNMANAGED';
export type LakeConsistencyStatus = 'CONSISTENT' | 'DRIFT' | 'MISSING' | 'UNKNOWN' | 'UNBOUND';

export interface PhysicalDataSource {
  sourceDataSourceId: number;
  sourceDataSourceName?: string;
  businessSystemId?: number;
  unitId?: number;
  unitCode?: string;
  systemCode?: string;
  odsDatabaseBindingId?: number;
  odsDatabase?: OdsDatabase;
}

export interface LakePhysicalSummary {
  boundDataSourceCount?: number;
  odsTableCount?: number;
  pendingExceptionCount?: number;
}

export interface OdsDatabase {
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

export interface InventoryTable {
  mappingId?: number;
  sourceObjectRefId?: number;
  targetTableName: string;
  managementLevel?: LakeManagementLevel;
  resourceStatus?: LakeResourceStatus;
  sourceBound?: boolean;
  actualExists?: boolean;
}

export interface InventoryRelation {
  relationId?: number;
  jobId?: number;
  jobRuntimeType?: string;
  jobVersion?: number;
  relationStatus?: string;
  relationScope?: string;
  tableMappingId?: number;
  sourceEndpointSnapshot?: string;
  sinkEndpointSnapshot?: string;
  schemaSaveModeSnapshot?: string;
}

export interface PhysicalInventory {
  odsDatabaseBindingId: number;
  databaseName?: string;
  actualTableNames?: string[];
  registeredTables?: InventoryTable[];
  discoveredTables?: InventoryTable[];
  tableRelations?: InventoryRelation[];
  namespaceRelations?: InventoryRelation[];
}

export interface TargetType {
  base?: string;
  length?: number;
  precision?: number;
  scale?: number;
}

export interface TargetColumn {
  sourceName?: string;
  sourceOrdinal?: number;
  targetName?: string;
  targetType?: TargetType | string;
  nullable?: boolean;
  key?: boolean;
  physicalOrdinal?: number;
}

export interface TargetContract {
  tableModel?: 'DUPLICATE' | 'UNIQUE';
  columns?: TargetColumn[];
  keyColumns?: string[];
  partition?: {
    enabled?: boolean;
    column?: string;
    granularity?: string;
  };
  distribution?: {
    type?: string;
    columns?: string[];
    buckets?: string;
  };
}

export interface FieldMapping {
  sourceField?: string;
  targetField?: string;
  targetType?: string;
}

export interface ManagedTable {
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
  targetContract?: TargetContract;
  fieldMappings?: FieldMapping[];
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

export interface DeleteImpact {
  mappingId?: number;
  targetTableName?: string;
  actualTableExists?: boolean;
  lifecycleBound?: boolean;
  allowed?: boolean;
  impactHash?: string;
  relations?: Array<{
    relationId?: number;
    jobId?: number;
    jobVersion?: number;
    relationScope?: string;
    jobRuntimeType?: string;
    relationStatus?: string;
  }>;
  blockers?: string[];
}

export interface ManagedTablePreview {
  valid?: boolean;
  previewToken?: string;
  sourceDataSourceId?: number;
  omEntityId?: string;
  odsDatabaseBindingId?: number;
  targetTableName?: string;
  sourceSchemaHash?: string;
  targetContractHash?: string;
  targetContract?: TargetContract;
  fieldMappings?: FieldMapping[];
  ddl?: string;
  warnings?: string[];
  errors?: string[];
}

export interface OdsSourceColumn {
  name: string;
  dataType?: string;
  dataTypeDisplay?: string;
  dataLength?: number;
  precision?: number;
  scale?: number;
  ordinalPosition?: number;
  constraint?: string;
}

export interface OdsSourceTable {
  id: string;
  name: string;
  fullyQualifiedName: string;
  columnCount?: number;
}

export interface OdsSourceTableDetail extends OdsSourceTable {
  columns: OdsSourceColumn[];
  tableConstraints?: Array<{ constraintType?: string; columns?: string[] }>;
}

export interface LakePage<T> {
  bizData?: T[];
  pagination?: { pageNo?: number; pageSize?: number; total?: number };
}

export interface ApiResponse<T> {
  code: number;
  data: T;
  msg?: string;
  message?: string;
}
