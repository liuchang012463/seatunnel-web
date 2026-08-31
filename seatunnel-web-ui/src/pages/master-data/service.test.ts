import HttpUtils from '@/utils/HttpUtils';
import {
  createBusinessSystem,
  createDataSourceUnit,
  deleteBusinessSystem,
  deleteDataSourceUnit,
  fetchActiveBusinessSystems,
  fetchActiveDataSourceUnits,
  fetchBusinessSystemPage,
  fetchDataSourceUnitPage,
  normalizePageData,
  toUnitOptions,
  updateBusinessSystem,
  updateDataSourceUnit,
} from './service';

jest.mock('@/utils/HttpUtils', () => ({
  __esModule: true,
  default: {
    get: jest.fn(),
    post: jest.fn(),
    put: jest.fn(),
    delete: jest.fn(),
  },
}));

describe('master-data service', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('uses the paginated unit endpoint', async () => {
    const response = { code: 0, data: { bizData: [], pagination: { pageNo: 1, pageSize: 10, total: 0 } } };
    (HttpUtils.post as jest.Mock).mockResolvedValue(response);

    await expect(fetchDataSourceUnitPage({ pageNo: 1, pageSize: 10, unitName: '市局' })).resolves.toBe(response);
    expect(HttpUtils.post).toHaveBeenCalledWith('/api/v1/data-source-units/page', {
      pageNo: 1,
      pageSize: 10,
      unitName: '市局',
    });
  });

  it('uses the paginated business-system endpoint with unitId', async () => {
    const response = { code: 0, data: { bizData: [], pagination: { pageNo: 1, pageSize: 10, total: 0 } } };
    (HttpUtils.post as jest.Mock).mockResolvedValue(response);

    await expect(
      fetchBusinessSystemPage({ pageNo: 1, pageSize: 10, unitId: 7, systemName: '门户' }),
    ).resolves.toBe(response);
    expect(HttpUtils.post).toHaveBeenCalledWith('/api/v1/business-systems/page', {
      pageNo: 1,
      pageSize: 10,
      unitId: 7,
      systemName: '门户',
    });
  });

  it('supports active options scoped by unit', async () => {
    const unitResponse = { code: 0, data: [] };
    const systemResponse = { code: 0, data: [] };
    (HttpUtils.get as jest.Mock)
      .mockResolvedValueOnce(unitResponse)
      .mockResolvedValueOnce(systemResponse);

    await expect(fetchActiveDataSourceUnits()).resolves.toBe(unitResponse);
    await expect(fetchActiveBusinessSystems(7)).resolves.toBe(systemResponse);

    expect(HttpUtils.get).toHaveBeenNthCalledWith(1, '/api/v1/data-source-units/active');
    expect(HttpUtils.get).toHaveBeenNthCalledWith(2, '/api/v1/business-systems/active?unitId=7');
  });

  it('maps all CRUD operations to the existing master-data endpoints', async () => {
    const response = { code: 0, data: true };
    (HttpUtils.post as jest.Mock).mockResolvedValue(response);
    (HttpUtils.put as jest.Mock).mockResolvedValue(response);
    (HttpUtils.delete as jest.Mock).mockResolvedValue(response);

    const unitPayload = { unitCode: 'A', unitName: '单位', status: 1 as const };
    const systemPayload = { unitId: 1, systemCode: 'S', systemName: '系统', status: 1 as const };
    await createDataSourceUnit(unitPayload);
    await updateDataSourceUnit(1, unitPayload);
    await deleteDataSourceUnit(1);
    await createBusinessSystem(systemPayload);
    await updateBusinessSystem(2, systemPayload);
    await deleteBusinessSystem(2);

    expect(HttpUtils.post).toHaveBeenNthCalledWith(1, '/api/v1/data-source-units', unitPayload);
    expect(HttpUtils.put).toHaveBeenNthCalledWith(1, '/api/v1/data-source-units/1', unitPayload);
    expect(HttpUtils.delete).toHaveBeenNthCalledWith(1, '/api/v1/data-source-units/1');
    expect(HttpUtils.post).toHaveBeenNthCalledWith(2, '/api/v1/business-systems', systemPayload);
    expect(HttpUtils.put).toHaveBeenNthCalledWith(2, '/api/v1/business-systems/2', systemPayload);
    expect(HttpUtils.delete).toHaveBeenNthCalledWith(2, '/api/v1/business-systems/2');
  });

  it('normalizes both current and legacy pagination shapes', () => {
    expect(
      normalizePageData({
        code: 0,
        data: { bizData: [{ id: 1 }], pagination: { pageNo: 2, pageSize: 20, total: 3 } },
      }),
    ).toEqual({
      records: [{ id: 1 }],
      pagination: { pageNo: 2, pageSize: 20, total: 3 },
    });

    expect(
      normalizePageData({ code: 0, data: { records: [{ id: 2 }], total: 1 } }),
    ).toEqual({
      records: [{ id: 2 }],
      pagination: { pageNo: 1, pageSize: 10, total: 1 },
    });
  });

  it('uses the unit name as the option label while retaining the generated code separately', async () => {
    expect(toUnitOptions([{ id: 1, unitCode: 'UNIT_001', unitName: '测试单位', status: 1 }])).toEqual([
      { id: 1, label: '测试单位', unitCode: 'UNIT_001', unitName: '测试单位' },
    ]);
  });
});
