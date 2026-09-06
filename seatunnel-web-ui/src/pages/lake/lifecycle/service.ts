import {
  fetchPhysicalInventory,
  fetchPhysicalSources,
  fetchLifecyclePolicies,
  createLifecyclePolicy,
  updateLifecyclePolicy,
  disableLifecyclePolicy,
  validateLifecycle,
  applyLifecycle,
  previewRetention,
  updateRetention,
  fetchLifecycleDetail as fetchLakeLifecycleDetail,
} from '@/services/lake';
import type { LakeApiResponse } from '@/services/lake';
import type { LifecycleValidationView, RetentionPreviewView } from './types';

/** The shared lake client owns the public policy/apply APIs. */
export {
  fetchPhysicalInventory,
  fetchPhysicalSources,
  fetchLifecyclePolicies,
  createLifecyclePolicy,
  updateLifecyclePolicy,
  disableLifecyclePolicy,
  validateLifecycle,
  applyLifecycle,
  previewRetention,
  updateRetention,
};

export const fetchLifecycleDetail = (mappingId: number): Promise<LakeApiResponse<LifecycleValidationView>> =>
  fetchLakeLifecycleDetail(mappingId) as Promise<LakeApiResponse<LifecycleValidationView>>;

export const fetchRetentionPreview = (
  mappingId: number,
  policyId: number,
): Promise<LakeApiResponse<RetentionPreviewView>> => previewRetention(mappingId, { policyId }) as Promise<LakeApiResponse<RetentionPreviewView>>;
