export const ONLINE_INGESTION_PATH = '/sync/online-ingestion';
export const ONLINE_INGESTION_LABEL = '在线数据引接';

export const onlineIngestionTabs = [
  { path: '/sync/batch-link-up', label: '批量同步', taskType: 'BATCH' },
  { path: '/sync/stream-link-up', label: '实时同步', taskType: 'STREAM' },
  { path: '/sync/file-transfer', label: '文件同步', taskType: 'FILE_TRANSFER' },
] as const;

export function findOnlineIngestionTab(pathname: string) {
  return onlineIngestionTabs.find(({ path }) => pathname === path || pathname.startsWith(`${path}/`));
}
