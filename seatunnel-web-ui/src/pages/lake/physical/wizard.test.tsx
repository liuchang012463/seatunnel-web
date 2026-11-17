import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import Wizard from './wizard';

jest.mock('@umijs/max', () => ({
  history: { push: jest.fn(), replace: jest.fn() },
  useLocation: () => ({ search: '?sourceDataSourceId=1&odsDatabaseBindingId=10' }),
}));
jest.mock('@ant-design/pro-components', () => ({ PageContainer: ({ children }: any) => children }));
jest.mock('@/services/lake', () => ({ fetchApplicableLifecyclePolicies: jest.fn() }));
jest.mock('./service', () => ({
  fetchPhysicalSource: jest.fn(), fetchSourceDatabases: jest.fn(), fetchSourceSchemas: jest.fn(),
  fetchSourceTables: jest.fn(), fetchSourceTableDetail: jest.fn(), previewManagedTable: jest.fn(), createManagedTable: jest.fn(),
}));
const api = jest.requireMock('./service');
const lake = jest.requireMock('@/services/lake');

test('a first-time user can preview with defaults and return without losing the selected source', async () => {
  api.fetchPhysicalSource.mockResolvedValue({ code: 0, data: { sourceDataSourceId: 1, sourceDataSourceName: '业务源', odsDatabaseBindingId: 10, odsDatabase: { databaseName: 'ods_erp' } } });
  api.fetchSourceDatabases.mockResolvedValue({ code: 0, data: [{ name: 'app', fullyQualifiedName: 'svc.app' }] });
  api.fetchSourceSchemas.mockResolvedValue({ code: 0, data: [{ name: 'sales', fullyQualifiedName: 'svc.app.sales' }] });
  api.fetchSourceTables.mockResolvedValue({ code: 0, data: { records: [{ id: 't', name: 'orders', fullyQualifiedName: 'svc.app.sales.orders' }] } });
  api.fetchSourceTableDetail.mockResolvedValue({ code: 0, data: { columns: [{ name: 'id', dataType: 'BIGINT' }, { name: 'created_at', dataType: 'TIMESTAMP', constraint: 'NOT_NULL' }], tableConstraints: [{ constraintType: 'PRIMARY_KEY', columns: ['id'] }] } });
  lake.fetchApplicableLifecyclePolicies.mockResolvedValue({ code: 0, data: [] });
  api.previewManagedTable.mockResolvedValue({ code: 0, data: { valid: true, planFingerprint: 'plan', targetTableName: 'app_sales_orders', targetContract: { columns: [{ name: 'id' }, { name: 'created_at' }] } } });
  render(React.createElement(Wizard));
  await waitFor(() => expect(api.fetchSourceTables).toHaveBeenCalled());
  fireEvent.mouseDown(screen.getByLabelText('来源表'));
  fireEvent.click(await screen.findByText('orders', { selector: '.ant-select-item-option-content' }));
  await screen.findByDisplayValue('app_sales_orders');
  fireEvent.click(screen.getByRole('button', { name: '检查并继续' }));
  await waitFor(() => expect(api.previewManagedTable).toHaveBeenCalledWith(expect.objectContaining({
    omEntityId: 't', targetTableName: 'app_sales_orders', tableModel: 'DUPLICATE', keyColumns: ['id'],
    distribution: { type: 'RANDOM', columns: [], buckets: 'AUTO' }, partition: { enabled: false },
  })));
  await screen.findByRole('button', { name: '创建并配置全量任务' });
  fireEvent.click(screen.getByRole('button', { name: '返回修改' }));
  expect(await screen.findByDisplayValue('app_sales_orders')).toBeTruthy();
  expect(api.fetchSourceTableDetail).toHaveBeenCalledTimes(1);
});
