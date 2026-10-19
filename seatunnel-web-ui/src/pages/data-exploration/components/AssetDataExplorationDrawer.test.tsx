import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import AssetDataExplorationDrawer from './AssetDataExplorationDrawer';

jest.mock('@/pages/data-source/icon/DatabaseIcons', () => ({
  __esModule: true,
  default: () => null,
}));

jest.mock('@/pages/data-source/service', () => ({
  fetchDataExplorationAssets: jest.fn(),
  fetchDataExplorationAsset: jest.fn(),
  fetchDataSourceMetadataStatus: jest.fn(),
  triggerDataSourceScan: jest.fn(),
  updateDataSourceSampleData: jest.fn(),
  updateDataSourceStorageManifest: jest.fn(),
}));

const service = jest.requireMock('@/pages/data-source/service');

const topic = {
  id: 'topic-1',
  name: 'orders.created',
  displayName: null,
  fullyQualifiedName: 'st_ds_42.orders.created',
  assetType: 'TOPIC',
  assetLabel: '主题',
  description: null,
  hasChildren: false,
  childCount: null,
  fieldCount: 1,
  tags: [],
  sizeInByte: null,
  updatedAt: null,
};

const topicDetail = {
  asset: topic,
  breadcrumbs: [],
  schema: {
    kind: 'MESSAGE',
    status: 'AVAILABLE',
    fields: [{ name: 'id', dataType: 'INT', description: null, tags: [], children: [] }],
    requestFields: [],
    responseFields: [],
  },
  sample: {
    kind: 'MESSAGES',
    status: 'AVAILABLE',
    columns: [],
    rows: [],
    messages: ['{"id":1}'],
    messageCount: 1,
  },
  properties: [
    { key: 'partitions', label: '分区数', value: 3, displayType: 'NUMBER' },
  ],
  tags: [],
  description: 'order events',
  updatedAt: null,
};

const container = {
  id: 'container-1',
  name: 'bucket',
  displayName: 'bucket',
  fullyQualifiedName: 'st_ds_42.bucket',
  assetType: 'CONTAINER',
  assetLabel: '容器',
  description: null,
  hasChildren: true,
  childCount: null,
  fieldCount: null,
  tags: [],
  sizeInByte: 0,
  updatedAt: null,
};

const containerDetail = {
  asset: container,
  breadcrumbs: [],
  schema: { kind: 'COLUMNS', status: 'NEEDS_DATA_MODEL', fields: [] },
  sample: { kind: 'ROWS', status: 'NEEDS_DATA_MODEL', columns: [], rows: [], messages: [] },
  properties: [{ key: 'prefix', label: '前缀', value: '/', displayType: 'CODE' }],
  tags: [],
  description: null,
  updatedAt: null,
};

const childContainer = {
  ...container,
  id: 'container-2',
  name: 'raw',
  displayName: 'raw',
  fullyQualifiedName: 'st_ds_42.bucket.raw',
  hasChildren: false,
};

function page(records: unknown[]) {
  return {
    breadcrumbs: [],
    parent: null,
    records,
    total: records.length,
    pageNo: 1,
    pageSize: 50,
    truncated: false,
  };
}

describe('AssetDataExplorationDrawer', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    service.fetchDataSourceMetadataStatus.mockResolvedValue({
      code: 0,
      data: {
        syncStatus: 'READY',
        sampleDataSupported: true,
        sampleDataEnabled: true,
        storageManifestSupported: false,
        scan: { status: 'SUCCESS' },
      },
    });
    service.fetchDataExplorationAssets.mockResolvedValue({
      code: 0,
      data: page([topic]),
    });
    service.fetchDataExplorationAsset.mockResolvedValue({
      code: 0,
      data: topicDetail,
    });
    service.triggerDataSourceScan.mockResolvedValue({ code: 0, data: true });
  });

  it('lists assets and shows schema and sample tabs', async () => {
    render(React.createElement(AssetDataExplorationDrawer, {
      inline: true,
      open: true,
      dataSourceId: '42',
      dataSourceName: 'kafka',
      dbType: 'KAFKA',
      onClose: () => {},
    }));

    expect(await screen.findByText('orders.created')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '查看 orders.created 详情' }));

    expect(await screen.findByRole('tab', { name: /样本数据/ })).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: /样本数据/ }));

    expect(await screen.findByText('{"id":1}')).toBeTruthy();
    expect(service.fetchDataExplorationAsset).toHaveBeenCalledWith('42', 'topic-1', 'TOPIC');
  });

  it('enters a container level and shows the breadcrumb', async () => {
    service.fetchDataExplorationAssets.mockImplementation(({ parentId }: { parentId?: string }) => (
      Promise.resolve({
        code: 0,
        data: parentId
          ? {
            ...page([childContainer]),
            breadcrumbs: [{
              id: 'container-1',
              name: 'bucket',
              assetType: 'CONTAINER',
              fullyQualifiedName: 'st_ds_42.bucket',
            }],
            parent: {
              id: 'container-1',
              name: 'bucket',
              assetType: 'CONTAINER',
              fullyQualifiedName: 'st_ds_42.bucket',
            },
          }
          : page([container]),
      })
    ));
    service.fetchDataExplorationAsset.mockResolvedValue({
      code: 0,
      data: containerDetail,
    });

    render(React.createElement(AssetDataExplorationDrawer, {
      inline: true,
      open: true,
      dataSourceId: '42',
      dataSourceName: 'minio',
      dbType: 'MINIO',
      onClose: () => {},
    }));

    expect(await screen.findByText('bucket')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '进入 bucket' }));

    expect(await screen.findByText('raw')).toBeTruthy();
    await waitFor(() => {
      expect(service.fetchDataExplorationAssets).toHaveBeenCalledWith(
        expect.objectContaining({ parentId: 'container-1', parentType: 'CONTAINER' }),
      );
    });
    expect(screen.getAllByText('bucket').length).toBeGreaterThan(0);
  });

  it('opens a deep-linked asset and loads its parent level', async () => {
    render(React.createElement(AssetDataExplorationDrawer, {
      inline: true,
      open: true,
      dataSourceId: '42',
      dataSourceName: 'kafka',
      dbType: 'KAFKA',
      initialAssetId: 'topic-1',
      initialAssetType: 'TOPIC',
      onClose: () => {},
    }));

    expect(await screen.findByRole('heading', { name: 'orders.created' })).toBeTruthy();
    expect(service.fetchDataExplorationAsset).toHaveBeenCalledWith('42', 'topic-1', 'TOPIC');
  });
});
