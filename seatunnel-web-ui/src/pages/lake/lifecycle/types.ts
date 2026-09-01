import type {
  LakeLifecyclePolicy,
  LakeLifecycleValidation,
  LakeManagementLevel,
  LakePhysicalDataSource,
  LakeResourceStatus,
} from '@/services/lake';

export interface LifecyclePolicyFormValues {
  policyName: string;
  granularity: 'DAY' | 'MONTH' | 'YEAR';
  retentionCount: number;
  description?: string;
  status: 'DRAFT' | 'ACTIVE';
}

export interface LifecycleMappingSnapshot {
  id?: number;
  databaseName?: string;
  targetTableName?: string;
  managementLevel?: LakeManagementLevel;
  resourceStatus?: LakeResourceStatus;
  generation?: number;
  lockVersion?: number;
  targetContract?: {
    partition?: { enabled?: boolean; column?: string; granularity?: string };
  };
  targetConsistencyStatus?: string;
  actualTableExists?: boolean;
  lastReconcileAt?: string;
}

export interface LifecycleBindingSnapshot {
  id?: number;
  policyId?: number;
  policyVersion?: number;
  partitionColumn?: string;
  granularity?: 'DAY' | 'MONTH' | 'YEAR';
  retentionCount?: number;
  actualRetentionCount?: number;
  status?: 'PENDING' | 'ACTIVE' | 'ERROR' | 'DISABLED';
  lastObservedAt?: string;
  errorCode?: string;
}

export interface PartitionSummary {
  total?: number;
  historical?: number;
  current?: number;
  future?: number;
  unknown?: number;
  historicalNames?: string[];
  observedAt?: string;
}

export interface LifecycleValidationView extends LakeLifecycleValidation {
  valid?: boolean;
  code?: string;
  reasonCode?: string;
  reasons?: string[];
  mappingId?: number;
  policyId?: number;
  mappingSnapshot?: LifecycleMappingSnapshot;
  policySnapshot?: LakeLifecyclePolicy;
  partitionColumn?: string;
  granularity?: 'DAY' | 'MONTH' | 'YEAR';
  desiredRetentionCount?: number;
  actualRetentionCount?: number;
  structuralMatch?: boolean;
  partitionSummary?: PartitionSummary;
  existingBinding?: LifecycleBindingSnapshot;
  existingBindingPolicyDiff?: boolean;
  observedAt?: string;
}

export interface RetentionPreviewView {
  valid?: boolean;
  code?: string;
  reasons?: string[];
  mappingId?: number;
  policyId?: number;
  mappingSnapshot?: LifecycleMappingSnapshot;
  requestedPolicySnapshot?: LakeLifecyclePolicy;
  existingBinding?: LifecycleBindingSnapshot;
  currentDesiredRetentionCount?: number;
  currentActualRetentionCount?: number;
  requestedRetentionCount?: number;
  historicalPartitionCount?: number;
  impactedHistoricalPartitionNames?: string[];
  impactedHistoricalPartitionCount?: number;
  requiresConfirmation?: boolean;
  confirmationToken?: string;
  partitionSummary?: PartitionSummary;
  observedAt?: string;
}

export interface LifecycleTableCandidate {
  mappingId: number;
  targetTableName: string;
  sourceDataSourceName: string;
  databaseName: string;
  managementLevel?: LakeManagementLevel;
  resourceStatus?: LakeResourceStatus;
  eligible: boolean;
  reason?: string;
  partitionColumn?: string;
  granularity?: string;
}

export interface PhysicalSourceWithInventory extends LakePhysicalDataSource {
  inventory?: {
    databaseName?: string;
    registeredTables?: Array<{
      mappingId?: number;
      targetTableName?: string;
      managementLevel?: LakeManagementLevel;
      resourceStatus?: LakeResourceStatus;
    }>;
  };
}
