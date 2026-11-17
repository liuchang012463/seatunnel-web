import type { LakeWarehouseConfig } from '@/services/lake';

export interface DorisConnectionValues extends Record<string, unknown> {
  fenodes?: string;
  queryPort?: string | number;
  user?: string;
  password?: string;
  driverLocation?: string;
  other?: Array<{ key?: string; value?: string }>;
}

/** Lake configuration connects to the cluster; physical ingestion supplies business databases. */
export const buildWarehouseJdbcUrl = (values: DorisConnectionValues) => {
  const firstNode = String(values.fenodes || '').split(',')[0].trim()
    .replace(/^https?:\/\//i, '').replace(/\/.*$/, '');
  const host = firstNode.replace(/:\d+$/, '') || '127.0.0.1';
  const queryPort = String(values.queryPort || '9030').trim();
  const params = new URLSearchParams();
  values.other?.forEach((item) => {
    const key = String(item?.key || '').trim();
    if (key) params.set(key, String(item?.value || '').trim());
  });
  const query = params.toString();
  return `jdbc:mysql://${host}:${queryPort}/${query ? `?${query}` : ''}`;
};

export const INITIAL_DORIS_PASSWORD_MESSAGE = '首次配置请手工输入 Doris 密码';

export const isInitialDorisPasswordMissing = (
  config: Pick<LakeWarehouseConfig, 'passwordConfigured'> | undefined,
  password?: unknown,
) => !config?.passwordConfigured && !String(password ?? '').trim();
