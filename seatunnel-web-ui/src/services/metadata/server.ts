import HttpUtils, { type RequestOptions } from '@/utils/HttpUtils';

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
  baseUrl?: string;
  defaultDomainFqn?: string;
  botDomainFqns?: string[];
  availableDomainFqns?: string[];
  botDomainLookupAvailable?: boolean;
  domainListAvailable?: boolean;
  tokenConfigured?: boolean;
  connectTimeoutMs?: number;
  readTimeoutMs?: number;
  expectedServerVersion?: string;
  expectedIngestionPatch?: string;
  configVersion?: number;
  connStatus?: string;
  lastError?: string;
  configured?: boolean;
  health?: MetadataIntegrationHealth;
}

export type OpenMetadataServerPayload = {
  baseUrl?: string;
  token?: string;
  defaultDomainFqn?: string;
  connectTimeoutMs?: number;
  readTimeoutMs?: number;
  rebuildBindings?: boolean;
};

const SERVER = '/api/v1/metadata/server';

export async function fetchOpenMetadataServer(): Promise<MetadataApiResponse<OpenMetadataServerConfig>> {
  return HttpUtils.get(SERVER);
}

export async function saveOpenMetadataServer(
  payload: OpenMetadataServerPayload,
  options?: RequestOptions,
): Promise<MetadataApiResponse<OpenMetadataServerConfig>> {
  return HttpUtils.put(SERVER, payload, options);
}

export async function testOpenMetadataServer(
  payload: OpenMetadataServerPayload,
): Promise<MetadataApiResponse<OpenMetadataServerConfig>> {
  return HttpUtils.post(`${SERVER}/connect-test`, payload);
}
