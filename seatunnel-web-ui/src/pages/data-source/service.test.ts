import HttpUtils from '@/utils/HttpUtils';
import {
  checkDataSourceUsage,
  fetchBusinessSystemOptions,
  fetchDataSourceAll,
  fetchDataSourceUnitOptions,
  unwrapMasterDataList,
} from './service';

jest.mock('@/utils/HttpUtils', () => ({
  __esModule: true,
  default: {
    get: jest.fn(),
    post: jest.fn(),
  },
}));

describe('data source service', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('loads all data sources with GET', async () => {
    const response = { code: 0, data: [] };
    (HttpUtils.get as jest.Mock).mockResolvedValue(response);

    await expect(fetchDataSourceAll()).resolves.toBe(response);

    expect(HttpUtils.get).toHaveBeenCalledWith('/api/v1/data-source/all');
    expect(HttpUtils.post).not.toHaveBeenCalled();
  });

  it('checks data source usage before delete', async () => {
    const response = { code: 0, data: true };
    (HttpUtils.get as jest.Mock).mockResolvedValue(response);

    await expect(checkDataSourceUsage('42')).resolves.toBe(response);

    expect(HttpUtils.get).toHaveBeenCalledWith('/api/v1/data-source/42/usage');
  });

  it('loads active data-source units from the master-data options endpoint', async () => {
    const response = {
      code: 0,
      data: [{ id: 1, unitName: '市局' }],
    };
    (HttpUtils.get as jest.Mock).mockResolvedValue(response);

    await expect(fetchDataSourceUnitOptions()).resolves.toBe(response);

    expect(HttpUtils.get).toHaveBeenCalledWith('/api/v1/data-source-units/active');
    expect(unwrapMasterDataList(response)).toEqual(response.data);
  });

  it('loads business systems scoped to the selected unit', async () => {
    const response = {
      code: 0,
      data: {
        bizData: [{ id: 2, unitId: 1, systemName: '统一门户' }],
        pagination: { pageNo: 1, pageSize: 20, total: 1 },
      },
    };
    (HttpUtils.get as jest.Mock).mockResolvedValue(response);

    await expect(fetchBusinessSystemOptions(1)).resolves.toBe(response);

    expect(HttpUtils.get).toHaveBeenCalledWith('/api/v1/business-systems/active?unitId=1');
    expect(unwrapMasterDataList(response)).toEqual(response.data.bizData);
  });
});
