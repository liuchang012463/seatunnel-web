import { stripLayoutPrefix } from '@/utils/iframeLayout';

/**
 * 文件同步配置页由多个任务列表进入（批量数据引接、文件引接、文件同步），
 * 返回时必须回到用户实际来源的那个列表。
 *
 * - `from=file-transfer`：文件同步列表里的本地文件任务复用了本页面。
 * - 其余情况按路径判断：文件引接页面回到文件引接列表，否则回到批量数据引接列表。
 */
export const resolveFileSyncBackPath = (pathname: string, search?: string): string => {
  const from = new URLSearchParams(search || '').get('from');

  if (from === 'file-transfer') {
    return '/sync/file-transfer';
  }

  const path = stripLayoutPrefix(pathname);

  return path.startsWith('/sync/file-link-up') ? '/sync/file-link-up' : '/sync/batch-link-up';
};
