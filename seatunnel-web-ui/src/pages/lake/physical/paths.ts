export const physicalResourcesPath = '/lake/resources';

export const physicalSourcePath = (sourceId: string | number) =>
  `${physicalResourcesPath}/${encodeURIComponent(sourceId)}`;

export const physicalTableCreatePath = (sourceId: string | number, bindingId: string | number) =>
  `${physicalResourcesPath}/table/create?${new URLSearchParams({
    sourceDataSourceId: String(sourceId),
    odsDatabaseBindingId: String(bindingId),
  })}`;
