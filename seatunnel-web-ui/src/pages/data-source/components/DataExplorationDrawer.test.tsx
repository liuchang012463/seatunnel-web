import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import DataExplorationDrawer from './DataExplorationDrawer';

jest.mock('@/pages/data-exploration/components/DataExplorationErDiagram', () => () => null);
jest.mock('@/pages/data-exploration/components/AssetDataExplorationDrawer', () => () => null);
jest.mock('../service', () => ({
  fetchDataExplorationDatabases: jest.fn(),
  fetchDataExplorationSchemas: jest.fn(),
  fetchDataExplorationTables: jest.fn(),
  fetchDataExplorationTable: jest.fn(),
  fetchDataExplorationProfile: jest.fn(),
  fetchDataInventorySummary: jest.fn(),
  previewDataExplorationTable: jest.fn(),
}));

const service = jest.requireMock('../service');

it('opens column definitions first and keeps wide sample fields readable', async () => {
  const columns = Array.from({ length: 16 }, (_, index) => ({
    name: `field_${index}`,
    dataTypeDisplay: index === 15 ? 'VARCHAR(255)' : 'INT',
  }));
  const tables = ['orders', 'customers'].map((name) => ({
    id: name, name, fullyQualifiedName: `service.db.public.${name}`, columnCount: columns.length,
  }));
  service.fetchDataExplorationDatabases.mockResolvedValue({ code: 0, data: [{ name: 'db', fullyQualifiedName: 'service.db' }] });
  service.fetchDataExplorationSchemas.mockResolvedValue({ code: 0, data: [{ name: 'public', fullyQualifiedName: 'service.db.public' }] });
  service.fetchDataExplorationTables.mockResolvedValue({ code: 0, data: { records: tables, total: 2, pageNo: 1, pageSize: 20 } });
  service.fetchDataExplorationTable.mockImplementation((_source: string, id: string) => Promise.resolve({
    code: 0, data: { ...tables.find((table) => table.id === id), columns, tableConstraints: [] },
  }));
  service.fetchDataExplorationProfile.mockResolvedValue({ code: 0, data: {} });
  service.fetchDataInventorySummary.mockResolvedValue({ code: 0, data: {} });
  service.previewDataExplorationTable.mockResolvedValue({
    code: 0,
    data: {
      columns: columns.map(({ name }) => ({ title: name, dataIndex: name, key: name })),
      data: [{ field_15: 'the last field value' }],
    },
  });

  const { container } = render(React.createElement(DataExplorationDrawer, {
    inline: true, open: true, dataSourceId: '42', onClose: () => {},
  }));
  const columnsTab = await screen.findByRole('tab', { name: /列/ });
  expect(columnsTab.getAttribute('aria-selected')).toBe('true');
  expect(service.previewDataExplorationTable).not.toHaveBeenCalled();

  fireEvent.click(screen.getByRole('tab', { name: /样本数据/ }));
  expect(await screen.findByText('the last field value')).toBeTruthy();
  expect(screen.getAllByTitle('VARCHAR(255)').length).toBeGreaterThan(0);
  const sampleTable = container.querySelector('.ant-tabs-tabpane-active .ant-table-body table') as HTMLElement;
  expect(sampleTable.style.width).toBe('2880px');

  fireEvent.click(screen.getByRole('button', { name: /customers/ }));
  await waitFor(() => expect(screen.getByRole('tab', { name: /列/ }).getAttribute('aria-selected')).toBe('true'));
  expect(service.previewDataExplorationTable).toHaveBeenCalledTimes(1);
});
