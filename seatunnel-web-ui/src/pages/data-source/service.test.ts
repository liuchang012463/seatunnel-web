import HttpUtils from '@/utils/HttpUtils';
import {
  checkDataSourceUsage,
  fetchBusinessSystemOptions,
  fetchDataSourceAll,
  fetchDataSourceUnitOptions,
  fetchDataSourceMetadataRuns,
  fetchDataExplorationDatabases,
  fetchDataExplorationSchemas,
  fetchDataExplorationTables,
  fetchDataExplorationTable,
  fetchDataExplorationProfile,
  fetchDataSourceTopologyChildren,
  fetchDataSourceTopologyTree,
  fetchDataSourceCatalogFiles,
  fetchDataSourceCatalogOptions,
  previewDataExplorationTable,
  triggerDataSourceExploration,
  triggerDataSourceScan,
  fetchDataSourceMetadataStatus,
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

  it('uses the existing data-source route for scan and exploration actions', async () => {
    const response = { code: 0, data: true };
    (HttpUtils.post as jest.Mock).mockResolvedValue(response);

    await expect(triggerDataSourceScan('42')).resolves.toBe(response);
    await expect(triggerDataSourceExploration('42', 'st_ds_42.orders')).resolves.toBe(response);

    expect(HttpUtils.post).toHaveBeenNthCalledWith(1, '/api/v1/data-source/42/scan');
    expect(HttpUtils.post).toHaveBeenNthCalledWith(2, '/api/v1/data-source/42/explore', {
      databaseFqn: 'st_ds_42.orders',
    });
  });

  it('reads the cached metadata status used by exploration feedback polling', async () => {
    const response = { code: 0, data: { exploration: { status: 'QUEUED' } } };
    (HttpUtils.get as jest.Mock).mockResolvedValue(response);

    await expect(fetchDataSourceMetadataStatus('42')).resolves.toBe(response);

    expect(HttpUtils.get).toHaveBeenCalledWith('/api/v1/data-source/42/metadata-status');
  });

  it('reads the two product-facing run histories through the existing data-source route', async () => {
    const response = { code: 0, data: [] };
    (HttpUtils.get as jest.Mock).mockResolvedValue(response);

    await expect(fetchDataSourceMetadataRuns('42', 'SCAN')).resolves.toBe(response);
    await expect(fetchDataSourceMetadataRuns('42', 'EXPLORATION')).resolves.toBe(response);

    expect(HttpUtils.get).toHaveBeenNthCalledWith(1, '/api/v1/data-source/42/runs?type=SCAN&limit=5');
    expect(HttpUtils.get).toHaveBeenNthCalledWith(2, '/api/v1/data-source/42/runs?type=EXPLORATION&limit=5');
  });

  it('keeps scan-result reads behind the SeaTunnel data-exploration facade', async () => {
    const response = { code: 0, data: [] };
    (HttpUtils.get as jest.Mock).mockResolvedValue(response);
    (HttpUtils.post as jest.Mock).mockResolvedValue(response);

    await expect(fetchDataExplorationDatabases('42')).resolves.toBe(response);
    await expect(fetchDataExplorationSchemas('42', 'st_ds_42.orders')).resolves.toBe(response);
    await expect(fetchDataExplorationTables('42', 'st_ds_42.orders', 'st_ds_42.orders.public')).resolves.toBe(response);
    await expect(fetchDataExplorationTable('42', 'table-id')).resolves.toBe(response);
    await expect(fetchDataExplorationProfile('42', 'table-id')).resolves.toBe(response);
    await expect(previewDataExplorationTable('42', 'table-id')).resolves.toBe(response);

    expect(HttpUtils.get).toHaveBeenNthCalledWith(
      1,
      '/api/v1/data-exploration/databases?dataSourceId=42',
    );
    expect(HttpUtils.get).toHaveBeenNthCalledWith(
      2,
      '/api/v1/data-exploration/schemas?dataSourceId=42&databaseFqn=st_ds_42.orders',
    );
    expect(HttpUtils.get).toHaveBeenNthCalledWith(
      3,
      '/api/v1/data-exploration/tables?dataSourceId=42&databaseFqn=st_ds_42.orders&schemaFqn=st_ds_42.orders.public&pageNo=1&pageSize=20',
    );
    expect(HttpUtils.get).toHaveBeenNthCalledWith(
      4,
      '/api/v1/data-exploration/tables/table-id?dataSourceId=42',
    );
    expect(HttpUtils.get).toHaveBeenNthCalledWith(
      5,
      '/api/v1/data-exploration/tables/table-id/profile?dataSourceId=42',
    );
    expect(HttpUtils.post).toHaveBeenCalledWith(
      '/api/v1/data-exploration/tables/table-id/preview?dataSourceId=42',
      {},
    );
  });

  it('loads topology levels through the SeaTunnel lazy topology facade', async () => {
    const response = { code: 0, data: [] };
    (HttpUtils.get as jest.Mock).mockResolvedValue(response);

    await expect(fetchDataSourceTopologyTree({ dataSourceId: 42 })).resolves.toBe(response);
    await expect(fetchDataSourceTopologyChildren('DATA_SOURCE', '42')).resolves.toBe(response);

    expect(HttpUtils.get).toHaveBeenNthCalledWith(
      1,
      '/api/v1/data-source-topology/tree?dataSourceId=42',
    );
    expect(HttpUtils.get).toHaveBeenNthCalledWith(
      2,
      '/api/v1/data-source-topology/children?nodeType=DATA_SOURCE&nodeId=42',
    );
  });

  it('loads connector assets and file prefixes through the catalog endpoints', async () => {
    const response = { code: 0, data: [] };
    (HttpUtils.get as jest.Mock).mockResolvedValue(response);

    await expect(fetchDataSourceCatalogOptions('42')).resolves.toBe(response);
    await expect(fetchDataSourceCatalogFiles('42')).resolves.toBe(response);
    await expect(fetchDataSourceCatalogFiles('42', '/bucket/orders 2026')).resolves.toBe(response);

    expect(HttpUtils.get).toHaveBeenNthCalledWith(1, '/api/v1/data-source/catalog/list/42');
    expect(HttpUtils.get).toHaveBeenNthCalledWith(2, '/api/v1/data-source/catalog/files/42');
    expect(HttpUtils.get).toHaveBeenNthCalledWith(
      3,
      '/api/v1/data-source/catalog/files/42?path=%2Fbucket%2Forders%202026',
    );
  });
});
