import HttpUtils from '@/utils/HttpUtils';
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

/** Cache-only table detail is not yet exported by the shared client. */
export const fetchLifecycleDetail = (mappingId: number): Promise<LakeApiResponse<LifecycleValidationView>> =>
  HttpUtils.get(`/api/v1/lake/lifecycle/tables/${encodeURIComponent(String(mappingId))}`);

export const fetchRetentionPreview = (
  mappingId: number,
  policyId: number,
): Promise<LakeApiResponse<RetentionPreviewView>> => previewRetention(mappingId, { policyId }) as Promise<LakeApiResponse<RetentionPreviewView>>;
