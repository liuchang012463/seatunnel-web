import { resolveFileSyncBackPath } from './backPath';

describe('文件同步配置页返回路径', () => {
  it('文件同步列表进入的本地文件任务回到文件同步列表', () => {
    expect(
      resolveFileSyncBackPath(
        '/sync/file-link-up/12/config/file-sync',
        '?scene=edit&from=file-transfer',
      ),
    ).toBe('/sync/file-transfer');
  });

  it('文件引接列表进入的任务回到文件引接列表', () => {
    expect(
      resolveFileSyncBackPath('/sync/file-link-up/12/config/file-sync', '?scene=edit'),
    ).toBe('/sync/file-link-up');
  });

  it('批量数据引接进入的任务回到批量数据引接列表', () => {
    expect(
      resolveFileSyncBackPath('/sync/batch-link-up/12/config/file-sync', '?scene=edit'),
    ).toBe('/sync/batch-link-up');
  });

  it('iframe 布局下的路径同样能判断来源列表', () => {
    expect(
      resolveFileSyncBackPath(
        '/iframe/sync/file-link-up/12/config/file-sync',
        '?scene=edit&from=file-transfer',
      ),
    ).toBe('/sync/file-transfer');

    expect(
      resolveFileSyncBackPath('/iframe/sync/file-link-up/12/config/file-sync', '?scene=edit'),
    ).toBe('/sync/file-link-up');
  });
});
