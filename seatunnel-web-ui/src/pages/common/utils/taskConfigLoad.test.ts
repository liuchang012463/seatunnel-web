import { readTaskConfig } from './taskConfigLoad';

describe('saved task configuration reads', () => {
  it.each([
    [{ code: 10000, msg: '服务端异常: only offline job definition can be edited' }, 'online'],
    [{ response: { code: 11303, msg: 'only offline streaming job definition can be edited' } }, 'online'],
    [{ response: { code: 11301 }, message: '批处理任务定义不存在' }, 'missing'],
    [{ code: 11807 }, 'missing'],
    [{ status: 404 }, 'missing'],
    [{ response: { status: 403 } }, 'forbidden'],
    [{ status: 401 }, 'forbidden'],
    [{ code: 1, message: 'NOT_LOGIN' }, 'forbidden'],
    [{ code: 0, data: null }, 'unavailable'],
    [{ code: 0, data: [] }, 'unavailable'],
    [{ code: 11013, msg: 'table not found' }, 'unavailable'],
    [new Error('NetworkError'), 'unavailable'],
  ])('classifies returned and rejected responses %#', async (failure, problem) => {
    const returned = jest.fn().mockResolvedValue(failure);
    await expect(readTaskConfig('7', returned)).resolves.toEqual({ problem });
    expect(returned).toHaveBeenCalledWith('7', { skipErrorHandler: true });
    await expect(readTaskConfig('7', jest.fn().mockRejectedValue(failure))).resolves.toEqual({ problem });
  });

  it.each([['ONLINE', 'online'], ['OFFLINE', 'unavailable']])(
    'confirms a streaming query failure against the %s release state',
    async (releaseState, problem) => {
      const query = jest.fn().mockResolvedValue({ code: 0, data: { releaseState } });
      const request = jest.fn().mockRejectedValue({ code: 11303, message: '查询批处理任务定义失败' });
      await expect(readTaskConfig('7', request, query)).resolves.toEqual({ problem });
      expect(query).toHaveBeenCalledWith('7', { skipErrorHandler: true });
    },
  );

  it('only reads the summary for an ambiguous streaming failure', async () => {
    const query = jest.fn();
    const data = { basic: { jobName: '样例' } };
    await expect(readTaskConfig('7', jest.fn().mockResolvedValue({ code: 0, data }), query)).resolves.toEqual({ data });
    await expect(readTaskConfig('7', jest.fn().mockRejectedValue({ status: 403 }), query)).resolves.toEqual({ problem: 'forbidden' });
    expect(query).not.toHaveBeenCalled();
  });
});
