import {
  handlePrototypeRequest,
  resetPrototypeRequests,
} from '../mockTransport';

describe('prototype request adapter', () => {
  beforeEach(() => {
    window.localStorage.clear();
    resetPrototypeRequests();
  });

  it('returns paged list data without using fetch', async () => {
    const fetchSpy = jest.spyOn(window, 'fetch');
    const response = await handlePrototypeRequest({
      url: '/api/v1/job/batch-definition/page',
      method: 'POST',
      body: { pageNo: 1, pageSize: 10 },
    });

    expect(response.code).toBe(0);
    expect(response.data.bizData).toHaveLength(2);
    expect(response.data.pagination.total).toBe(2);
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });

  it('supports create, update state, delete and reset', async () => {
    await handlePrototypeRequest({
      url: '/api/v1/job/batch-definition',
      method: 'POST',
      body: { id: '3001', jobName: '新增原型任务' },
    });
    let page = await handlePrototypeRequest({
      url: '/api/v1/job/batch-definition/page',
      method: 'POST',
    });
    expect(page.data.bizData[0].name).toBe('新增原型任务');

    await handlePrototypeRequest({
      url: '/api/v1/job/batch-definition/3001/online',
      method: 'PUT',
    });
    page = await handlePrototypeRequest({
      url: '/api/v1/job/batch-definition/page',
      method: 'POST',
    });
    expect(
      page.data.bizData.find(({ id }: { id: string }) => id === '3001').status,
    ).toBe('RUNNING');

    await handlePrototypeRequest({
      url: '/api/v1/job/batch-definition/3001',
      method: 'DELETE',
    });
    page = await handlePrototypeRequest({
      url: '/api/v1/job/batch-definition/page',
      method: 'POST',
    });
    expect(
      page.data.bizData.some(({ id }: { id: string }) => id === '3001'),
    ).toBe(false);

    resetPrototypeRequests();
    page = await handlePrototypeRequest({
      url: '/api/v1/job/batch-definition/page',
      method: 'POST',
    });
    expect(page.data.bizData).toHaveLength(2);
  });
});
