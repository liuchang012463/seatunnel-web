export const physicalResourcesPath = '/lake/resources';

export const physicalSourcePath = (sourceId: string | number) =>
  `${physicalResourcesPath}/${encodeURIComponent(sourceId)}`;

export const physicalTablePath = (mappingId: string | number) =>
  `${physicalResourcesPath}/table/${encodeURIComponent(mappingId)}`;

export const physicalTableCreatePath = (sourceId: string | number, bindingId: string | number) =>
  `${physicalResourcesPath}/table/create?${new URLSearchParams({
    sourceDataSourceId: String(sourceId),
    odsDatabaseBindingId: String(bindingId),
  })}`;

export function lakeTaskConfigPath(
  jobId: string | number,
  runtime: string | undefined,
  mode: string | undefined,
  releaseState: string | undefined,
  mappingId: string | number,
) {
  const streaming = runtime === 'STREAMING';
  const configPages: Record<string, string> = {
    GUIDE_SINGLE: 'single',
    GUIDE_MULTI: 'multi',
    ...(streaming ? {} : { GUIDE_SINGLE_INCREMENTAL: 'single-incremental' }),
  };
  const page = mode ? configPages[mode] : undefined;
  if (!page) return undefined;

  const batchSingle = !streaming && (page === 'single' || page === 'single-incremental');
  const params = new URLSearchParams({ scene: batchSingle && releaseState !== 'OFFLINE' ? 'view' : 'edit' });
  if (batchSingle) params.set('lakeMappingId', String(mappingId));
  return `/sync/${streaming ? 'stream' : 'batch'}-link-up/${encodeURIComponent(jobId)}/config/${page}?${params}`;
}
