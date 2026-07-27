import HttpUtils from '@/utils/HttpUtils';
import { isPrototypeMode } from '@/prototype/mode';
import type { ApiResponse } from '@/utils/request';
import {
  appendMockLifecycleExecution,
  filterLifecyclePolicies,
  readMockLifecycleExecutions,
  readMockLifecyclePolicies,
  writeMockLifecyclePolicies,
} from './mockData';
import type {
  LifecycleExecutionRecord,
  LifecyclePolicyPage,
  LifecyclePolicyQuery,
  LifecyclePolicyRecord,
} from './types';

const API_PREFIX = '/api/v1/lake-lifecycle';

const paginate = (
  records: LifecyclePolicyRecord[],
  query: LifecyclePolicyQuery,
): LifecyclePolicyPage => {
  const filtered = filterLifecyclePolicies(records, {
    keyword: query.keyword,
    target: query.target,
    status: query.status,
  });
  const pageNo = query.pageNo ?? 1;
  const pageSize = query.pageSize ?? 10;
  const start = (pageNo - 1) * pageSize;
  return {
    bizData: filtered.slice(start, start + pageSize),
    pagination: { pageNo, pageSize, total: filtered.length },
  };
};

export const fetchLifecyclePolicies = async (
  query: LifecyclePolicyQuery = {},
): Promise<ApiResponse<LifecyclePolicyPage>> => {
  if (isPrototypeMode) {
    return {
      code: 0,
      msg: 'prototype success',
      data: paginate(readMockLifecyclePolicies(), query),
    };
  }
  return HttpUtils.get<LifecyclePolicyPage>(`${API_PREFIX}/policies`, {
    params: query as unknown as Record<string, unknown>,
  } as unknown as RequestInit);
};

export const createLifecyclePolicy = async (
  payload: Omit<
    LifecyclePolicyRecord,
    'id' | 'updatedAt' | 'lastRunAt' | 'nextRunAt' | 'status' | 'executionStats'
  > & { status?: LifecyclePolicyRecord['status'] },
): Promise<ApiResponse<LifecyclePolicyRecord>> => {
  if (isPrototypeMode) {
    const data = readMockLifecyclePolicies();
    const id = `lc-${Date.now()}`;
    const next: LifecyclePolicyRecord = {
      ...payload,
      id,
      status: payload.status ?? 'DRAFT',
      lastRunAt: '-',
      nextRunAt: '待调度',
      updatedAt: '2026-07-27 12:00',
      executionStats: { executed: 0, purged: 0, archived: 0 },
    };
    writeMockLifecyclePolicies([next, ...data]);
    return { code: 0, msg: 'prototype success', data: next };
  }
  return HttpUtils.post<LifecyclePolicyRecord>(`${API_PREFIX}/policies`, payload);
};

export const toggleLifecycleStatus = async (
  id: string,
  nextStatus: LifecyclePolicyRecord['status'],
): Promise<ApiResponse<boolean>> => {
  if (isPrototypeMode) {
    const data = readMockLifecyclePolicies();
    const next = data.map((record) =>
      record.id === id
        ? { ...record, status: nextStatus, updatedAt: '2026-07-27 12:00' }
        : record,
    );
    writeMockLifecyclePolicies(next);
    return { code: 0, msg: 'prototype success', data: true };
  }
  return HttpUtils.post<boolean>(`${API_PREFIX}/policies/${id}/status`, {
    status: nextStatus,
  });
};

export const executeLifecycleNow = async (
  id: string,
): Promise<ApiResponse<LifecycleExecutionRecord>> => {
  if (isPrototypeMode) {
    const policies = readMockLifecyclePolicies();
    const policy = policies.find((p) => p.id === id);
    if (!policy) {
      return { code: 404, msg: 'prototype policy not found', data: undefined as unknown as LifecycleExecutionRecord };
    }
    const record: LifecycleExecutionRecord = {
      id: `lc-exec-${Date.now()}`,
      policyId: id,
      policyName: policy.name,
      startAt: '2026-07-27 12:00',
      endAt: '2026-07-27 12:01',
      result: 'SUCCESS',
      processedRows: 12000,
      message: '立即执行成功，清理/归档 12000 行',
    };
    appendMockLifecycleExecution(record);
    return { code: 0, msg: 'prototype success', data: record };
  }
  return HttpUtils.post<LifecycleExecutionRecord>(`${API_PREFIX}/policies/${id}/execute`);
};

export const fetchLifecycleExecutions = async (
  policyId?: string,
): Promise<ApiResponse<LifecycleExecutionRecord[]>> => {
  if (isPrototypeMode) {
    const all = readMockLifecycleExecutions();
    return {
      code: 0,
      msg: 'prototype success',
      data: policyId ? all.filter((item) => item.policyId === policyId) : all,
    };
  }
  return HttpUtils.post<LifecycleExecutionRecord[]>(`${API_PREFIX}/executions`, policyId ? { policyId } : {});
};
