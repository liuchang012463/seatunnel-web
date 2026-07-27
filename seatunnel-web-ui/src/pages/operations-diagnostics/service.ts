import HttpUtils from '@/utils/HttpUtils';
import { isPrototypeMode } from '@/prototype/mode';
import type { ApiResponse } from '@/utils/request';
import {
  filterFaults,
  readMockFaultEvidence,
  readMockFaults,
  writeMockFaults,
} from './mockData';
import type {
  FaultEvidence,
  FaultPage,
  FaultQuery,
  FaultRecord,
} from './types';

const API_PREFIX = '/api/v1/operations-diagnostics';

const paginate = (records: FaultRecord[], query: FaultQuery): FaultPage => {
  const filtered = filterFaults(records, {
    keyword: query.keyword,
    severity: query.severity,
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

export const fetchFaults = async (
  query: FaultQuery = {},
): Promise<ApiResponse<FaultPage>> => {
  if (isPrototypeMode) {
    return {
      code: 0,
      msg: 'prototype success',
      data: paginate(readMockFaults(), query),
    };
  }
  return HttpUtils.post<FaultPage>(`${API_PREFIX}/page`, query);
};

export const fetchFaultEvidence = async (
  id: string,
): Promise<ApiResponse<FaultEvidence | undefined>> => {
  if (isPrototypeMode) {
    const evidence = readMockFaultEvidence(id);
    return {
      code: evidence ? 0 : 404,
      msg: evidence ? 'prototype success' : 'prototype not found',
      data: evidence,
    };
  }
  return HttpUtils.get<FaultEvidence>(`${API_PREFIX}/${id}/evidence`);
};

export const retryFault = async (
  id: string,
): Promise<ApiResponse<FaultRecord>> => {
  if (isPrototypeMode) {
    const data = readMockFaults();
    const target = data.find((r) => r.id === id);
    if (!target) {
      return {
        code: 404,
        msg: 'prototype fault not found',
        data: undefined as unknown as FaultRecord,
      };
    }
    const next: FaultRecord[] = data.map((record): FaultRecord =>
      record.id === id
        ? {
            ...record,
            status: 'INVESTIGATING',
            lastSeen: '2026-07-27 12:00',
          }
        : record,
    );
    writeMockFaults(next);
    return {
      code: 0,
      msg: 'prototype success',
      data: { ...target, status: 'INVESTIGATING', lastSeen: '2026-07-27 12:00' },
    };
  }
  return HttpUtils.post<FaultRecord>(`${API_PREFIX}/${id}/retry`);
};
