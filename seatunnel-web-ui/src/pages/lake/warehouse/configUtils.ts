import type { LakeWarehouseConfig } from '@/services/lake';

export const INITIAL_DORIS_PASSWORD_MESSAGE = '首次配置请手工输入 Doris 密码';

export const isInitialDorisPasswordMissing = (
  config: Pick<LakeWarehouseConfig, 'passwordConfigured'> | undefined,
  password?: unknown,
) => !config?.passwordConfigured && !String(password ?? '').trim();
