export const isWebUploadSource = (sourceType?: any) =>
  String(sourceType?.dbType || '').toUpperCase() === 'WEB_UPLOAD'
  || sourceType?.sourceManaged === true;

export const normalizeOfflineMode = (sourceType: any, mode?: string) =>
  isWebUploadSource(sourceType) && mode !== 'GUIDE_SINGLE'
    ? 'GUIDE_SINGLE'
    : mode || 'GUIDE_SINGLE';

export const canUseOfflineMode = (sourceType: any, mode: string) =>
  !isWebUploadSource(sourceType)
  || !['GUIDE_SINGLE_INCREMENTAL', 'GUIDE_MULTI'].includes(mode);
