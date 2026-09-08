import HttpUtils from '@/utils/HttpUtils';

export interface MetadataApiResponse<T> {
  code: number;
  msg?: string;
  message?: string;
  data?: T;
}

export interface MetadataIntegrationHealth {
  openMetadata?: string;
  orchestrator?: string;
  version?: string;
  expectedVersion?: string;
  ingestionVersion?: string;
  expectedVersionLine?: string;
  versionCompatible?: boolean;
}

export interface OpenMetadataServerConfig {
  enabled?: boolean;
  baseUrl?: string;
  tokenConfigured?: boolean;
  connectTimeoutMs?: number;
  readTimeoutMs?: number;
  expectedServerVersion?: string;
  expectedIngestionPatch?: string;
  kingbaseTunnelHost?: string;
  kingbaseTunnelPort?: number;
  configVersion?: number;
  connStatus?: string;
  lastError?: string;
  configured?: boolean;
  health?: MetadataIntegrationHealth;
}

export type OpenMetadataServerPayload = {
  enabled?: boolean;
  baseUrl?: string;
  token?: string;
  connectTimeoutMs?: number;
  readTimeoutMs?: number;
  kingbaseTunnelHost?: string;
  kingbaseTunnelPort?: number;
};

const SERVER = '/api/v1/metadata/server';

export async function fetchOpenMetadataServer(): Promise<MetadataApiResponse<OpenMetadataServerConfig>> {
  return HttpUtils.get(SERVER);
}

export async function saveOpenMetadataServer(
  payload: OpenMetadataServerPayload,
): Promise<MetadataApiResponse<OpenMetadataServerConfig>> {
  return HttpUtils.put(SERVER, payload);
}

export async function testOpenMetadataServer(
  payload: OpenMetadataServerPayload,
): Promise<MetadataApiResponse<OpenMetadataServerConfig>> {
  return HttpUtils.post(`${SERVER}/connect-test`, payload);
}
