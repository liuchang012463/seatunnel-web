import HttpUtils from '@/utils/HttpUtils';
import { checkDataSourceUsage, fetchDataSourceAll } from './service';

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
});
