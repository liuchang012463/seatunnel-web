import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import Wizard from './wizard';

jest.mock('@umijs/max', () => ({
  history: { push: jest.fn(), replace: jest.fn() },
  useLocation: () => ({ search: '?sourceDataSourceId=1&odsDatabaseBindingId=10' }),
}));
jest.mock('@ant-design/pro-components', () => ({ PageContainer: ({ children }: any) => children }));
jest.mock('@/services/lake', () => ({ fetchApplicableLifecyclePolicies: jest.fn() }));
jest.mock('../../batch-link-up/api', () => ({
  seatunnelJobDefinitionApi: { getUniqueId: jest.fn().mockResolvedValue({ code: 0, data: 202 }) },
}));
jest.mock('./service', () => ({
  fetchPhysicalSource: jest.fn(), fetchSourceDatabases: jest.fn(), fetchSourceSchemas: jest.fn(),
  fetchSourceTables: jest.fn(), fetchSourceTableDetail: jest.fn(), previewManagedTable: jest.fn(), createManagedTable: jest.fn(),
}));
const api = jest.requireMock('./service');
const lake = jest.requireMock('@/services/lake');

beforeEach(() => {
  jest.clearAllMocks();
  api.fetchPhysicalSource.mockResolvedValue({ code: 0, data: { sourceDataSourceId: 1, sourceDataSourceName: '业务源', odsDatabaseBindingId: 10, odsDatabase: { databaseName: 'ods_erp' } } });
  api.fetchSourceDatabases.mockResolvedValue({ code: 0, data: [{ name: 'app', fullyQualifiedName: 'svc.app' }] });
  api.fetchSourceSchemas.mockResolvedValue({ code: 0, data: [{ name: 'sales', fullyQualifiedName: 'svc.app.sales' }] });
  api.fetchSourceTables.mockResolvedValue({ code: 0, data: { records: [{ id: 't', name: 'orders', fullyQualifiedName: 'svc.app.sales.orders' }] } });
  lake.fetchApplicableLifecyclePolicies.mockResolvedValue({ code: 0, data: [] });
  api.previewManagedTable.mockResolvedValue({ code: 0, data: { valid: true, planFingerprint: 'plan', targetTableName: 'app_sales_orders', targetContract: { columns: [{ name: 'id' }, { name: 'created_at' }] } } });
});

async function selectSourceTable() {
  render(React.createElement(Wizard));
  await waitFor(() => expect(api.fetchSourceTables).toHaveBeenCalled());
  fireEvent.mouseDown(screen.getByLabelText('来源表'));
  fireEvent.click(await screen.findByText('orders', { selector: '.ant-select-item-option-content' }));
  await screen.findByDisplayValue('app_sales_orders');
}

test('a first-time user can keep defaults and source selection, then go directly to full task configuration', async () => {
  api.fetchSourceTableDetail.mockResolvedValue({ code: 0, data: { columns: [{ name: 'id', dataType: 'BIGINT' }, { name: 'created_at', dataType: 'TIMESTAMP', constraint: 'NOT_NULL' }], tableConstraints: [{ constraintType: 'PRIMARY_KEY', columns: ['id'] }] } });
  await selectSourceTable();
  expect(screen.getByText('按源表主键 id 排序')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '检查并继续' }));
  await waitFor(() => expect(api.previewManagedTable).toHaveBeenCalledWith(expect.objectContaining({
    omEntityId: 't', targetTableName: 'app_sales_orders', tableModel: 'DUPLICATE', keyColumns: ['id'],
    distribution: { type: 'RANDOM', columns: [], buckets: 'AUTO' }, partition: { enabled: false },
  })));
  await screen.findByRole('button', { name: '创建并配置全量任务' });
  fireEvent.click(screen.getByRole('button', { name: '返回修改' }));
  expect(await screen.findByDisplayValue('app_sales_orders')).toBeTruthy();
  expect(api.fetchSourceTableDetail).toHaveBeenCalledTimes(1);
  api.createManagedTable.mockResolvedValue({ code: 0, data: { id: 101, resourceStatus: 'READY' } });
  fireEvent.click(screen.getByRole('button', { name: '检查并继续' }));
  const createButton = await screen.findByRole('button', { name: '创建并配置全量任务' });
  await waitFor(() => expect(createButton.className).not.toContain('ant-btn-loading'));
  fireEvent.click(createButton);
  await waitFor(() => expect(api.createManagedTable).toHaveBeenCalledWith('plan'));
  await waitFor(() => expect(jest.requireMock('../../batch-link-up/api').seatunnelJobDefinitionApi.getUniqueId).toHaveBeenCalled());
  await waitFor(() => expect(jest.requireMock('@umijs/max').history.replace)
    .toHaveBeenCalledWith('/sync/batch-link-up/202/detail?lakeMappingId=101'));
});

test('an all-nullable source can be previewed without sort fields and preserves its type', async () => {
  api.fetchSourceTableDetail.mockResolvedValue({ code: 0, data: { columns: [{ name: 'id', dataType: 'INTEGER' }] } });
  await selectSourceTable();
  expect(screen.getByText('无排序字段')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '检查并继续' }));

  await waitFor(() => expect(api.previewManagedTable).toHaveBeenCalledWith(expect.objectContaining({
    tableModel: 'DUPLICATE', keyColumns: [],
    columns: [{ sourceField: 'id', targetField: 'id', targetType: 'INT', key: false }],
    distribution: { type: 'RANDOM', columns: [], buckets: 'AUTO' },
    partition: { enabled: false },
  })));
  expect(await screen.findByText('保留明细（DUPLICATE KEY） · 无排序字段')).toBeTruthy();
});

test('switching to unique never uses a sort fallback as a merge field without confirmation', async () => {
  api.fetchSourceTableDetail.mockResolvedValue({ code: 0, data: { columns: [{ name: 'id', dataType: 'BIGINT', constraint: 'NOT_NULL' }] } });
  await selectSourceTable();
  expect(screen.getByText('按 id 排序')).toBeTruthy();
  fireEvent.mouseDown(screen.getByLabelText('表模型'));
  fireEvent.click(await screen.findByText('按键合并（UNIQUE KEY）', { selector: '.ant-select-item-option-content' }));

  const keyOption = await screen.findByRole('checkbox', { name: 'id用于合并' }) as HTMLInputElement;
  expect(keyOption.checked).toBe(false);
  fireEvent.click(screen.getByRole('button', { name: '检查并继续' }));
  await screen.findByText('请选择能唯一识别一条记录的非空字段');
  expect(api.previewManagedTable).not.toHaveBeenCalled();

  fireEvent.click(keyOption);
  fireEvent.click(screen.getByRole('button', { name: '检查并继续' }));
  await waitFor(() => expect(api.previewManagedTable).toHaveBeenCalledWith(expect.objectContaining({
    tableModel: 'UNIQUE', keyColumns: ['id'],
    distribution: { type: 'HASH', columns: ['id'], buckets: 'AUTO' },
  })));
});
