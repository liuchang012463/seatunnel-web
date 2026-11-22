import HttpUtils from '@/utils/HttpUtils';

export interface UnifiedAuthConfig {
  issuer?: string;
  clientId?: string;
  redirectUri?: string;
  clientSecretConfigured?: boolean;
  enabled?: boolean;
  autoProvisionGeneralUser?: boolean;
  ticketEnabled?: boolean;
  ticketValidationEndpoint?: string;
  ticketValidationMethod?: string;
  ticketLoginEntryUrl?: string;
  ticketResultIssuerPointer?: string;
  ticketResultSubjectPointer?: string;
  ticketResultEmailPointer?: string;
  ticketResultDisplayNamePointer?: string;
  configVersion?: number;
  authenticationMode?: string;
  trustedCallbackConfigured?: boolean;
  ticketIssuer?: string;
  ticketParameterName?: string;
  ticketSuccessField?: string;
  ticketSuccessValue?: string;
  ticketUsernameField?: string;
}

export interface UnifiedAuthConfigPayload {
  issuer: string;
  clientId: string;
  redirectUri: string;
  clientSecret?: string;
  enabled: boolean;
  autoProvisionGeneralUser: boolean;
  ticketEnabled: boolean;
  ticketValidationEndpoint?: string;
  ticketValidationMethod: string;
  ticketLoginEntryUrl?: string;
  ticketIssuer: string;
  ticketParameterName: string;
  ticketSuccessField: string;
  ticketSuccessValue: string;
  ticketUsernameField: string;
  ticketResultEmailPointer: string;
  ticketResultDisplayNamePointer: string;
}

export interface UnifiedAuthIdentity {
  identityId: number;
  identitySource?: string;
  issuer: string;
  subject: string;
  email?: string;
  displayName?: string;
  localUserId?: number;
  localUserName?: string;
  localUserType?: 'ADMIN_USER' | 'GENERAL_USER';
  lastObservedAt?: string;
}

export interface UnifiedAuthLocalUser {
  id: number;
  userName: string;
  email?: string;
  userType: 'ADMIN_USER' | 'GENERAL_USER';
}

export interface UnifiedAuthTicketPending {
  externalDisplayName: string;
  externalEmail?: string;
  localUserName?: string;
  confirmationToken?: string;
  cancellationToken: string;
  confirmable: boolean;
}

const API = '/api/v1/unified-auth';

export const getUnifiedAuthConfig = () =>
  HttpUtils.get<UnifiedAuthConfig>(`${API}/config`);

export const saveUnifiedAuthConfig = (payload: UnifiedAuthConfigPayload) =>
  HttpUtils.put<UnifiedAuthConfig>(`${API}/config`, payload);

export const testUnifiedAuthConnection = (payload: UnifiedAuthConfigPayload) =>
  HttpUtils.post<{ connected: boolean; message: string }>(`${API}/connect-test`, payload);

export const getUnifiedAuthMappings = () =>
  HttpUtils.get<UnifiedAuthIdentity[]>(`${API}/mappings`);

export const getUnifiedAuthLocalUsers = () =>
  HttpUtils.get<UnifiedAuthLocalUser[]>(`${API}/local-users`);

export const bindUnifiedAuthMapping = (identityId: number, localUserId: number) =>
  HttpUtils.post<void>(`${API}/mappings`, { identityId, localUserId });

export const unbindUnifiedAuthMapping = (identityId: number) =>
  HttpUtils.delete<void>(`${API}/mappings/${identityId}`);

export const getUnifiedAuthTicketPending = () =>
  HttpUtils.get<UnifiedAuthTicketPending>(`${API}/sso/pending`, { skipErrorHandler: true });

export const confirmUnifiedAuthTicketLogin = (confirmationToken: string) =>
  HttpUtils.post<void>(`${API}/sso/confirm`, { confirmationToken });

export const cancelUnifiedAuthTicketLogin = (cancellationToken: string) =>
  HttpUtils.post<void>(`${API}/sso/cancel`, { cancellationToken });
