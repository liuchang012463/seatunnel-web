import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import AssetExplorationSettingsModal from './AssetExplorationSettingsModal';

jest.mock('@/pages/data-source/service', () => ({
  fetchDataSourceMetadataStatus: jest.fn(),
  retryDataSourceMetadataSync: jest.fn(),
  triggerDataSourceScan: jest.fn(),
  updateDataSourceSampleData: jest.fn(),
  updateDataSourceStorageManifest: jest.fn(),
}));

const service = jest.requireMock('@/pages/data-source/service');

const errorStatus = {
  syncStatus: 'ERROR',
  sampleDataSupported: true,
  sampleDataEnabled: false,
  storageManifestSupported: false,
};

const readyStatus = {
  ...errorStatus,
  syncStatus: 'READY',
};

describe('AssetExplorationSettingsModal', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    service.fetchDataSourceMetadataStatus
      .mockResolvedValueOnce({ code: 0, data: errorStatus })
      .mockResolvedValue({ code: 0, data: readyStatus });
    service.retryDataSourceMetadataSync.mockResolvedValue({ code: 0, data: true });
  });

  it('keeps save-and-scan visible without changing any setting', async () => {
    render(React.createElement(AssetExplorationSettingsModal, {
      open: true,
      dataSourceId: '42',
      dataSourceName: 'http',
      dbType: 'HTTP',
      onClose: () => {},
    }));

    expect(await screen.findByRole('button', { name: '保存并扫描' })).toBeTruthy();
  });

  it('retries a failed metadata sync from the status row', async () => {
    render(React.createElement(AssetExplorationSettingsModal, {
      open: true,
      dataSourceId: '42',
      dataSourceName: 'http',
      dbType: 'HTTP',
      onClose: () => {},
    }));

    fireEvent.click(await screen.findByRole('button', { name: '重试同步' }));

    await waitFor(() => {
      expect(service.retryDataSourceMetadataSync).toHaveBeenCalledWith('42');
    });
    expect(await screen.findByText('已就绪')).toBeTruthy();
  });
});
