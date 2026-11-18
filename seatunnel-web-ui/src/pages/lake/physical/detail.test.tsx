import React from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import Detail from './detail';

let mockRouteSourceId = '1';

jest.mock('@umijs/max', () => ({
  history: { push: jest.fn() },
  useParams: () => ({ sourceDataSourceId: mockRouteSourceId }),
}));
jest.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children, title, extra }: {
    children?: import('react').ReactNode; title?: string; extra?: import('react').ReactNode;
  }) => require('react').createElement('div', undefined, title, extra, children),
  ProTable: () => null,
}));
jest.mock('../useMasterDataNames', () => ({ useMasterDataNames: () => ({ unitNameByCode: () => '', systemNameByCode: () => '' }) }));
jest.mock('./service', () => ({
  createOdsDatabase: jest.fn(), fetchPhysicalSource: jest.fn(), fetchPhysicalInventory: jest.fn(),
}));

const api = jest.requireMock('./service');
const { history } = jest.requireMock('@umijs/max');
const source = (id: number, name: string) => ({
  sourceDataSourceId: id,
  sourceDataSourceName: name,
  odsDatabaseBindingId: id * 10,
  odsDatabase: { id: id * 10, sourceDataSourceId: id, databaseName: `ods_${id}`, displayName: `${name}入湖库`, resourceStatus: 'READY' },
});

beforeEach(() => {
  api.createOdsDatabase.mockReset();
  api.fetchPhysicalSource.mockReset();
  api.fetchPhysicalInventory.mockReset();
  history.push.mockClear();
  mockRouteSourceId = '1';
});

test('switching to another source ignores the earlier detail response', async () => {
  let finishOldRequest!: (response: { code: number; data: ReturnType<typeof source> }) => void;
  api.fetchPhysicalSource.mockImplementation((id: string) => id === '1'
    ? new Promise((resolve) => { finishOldRequest = resolve; })
    : Promise.resolve({ code: 0, data: source(2, 'Vastbase') }));
  api.fetchPhysicalInventory.mockResolvedValue({ code: 0, data: { odsDatabaseBindingId: 20, registeredTables: [] } });

  const { rerender } = render(React.createElement(Detail));
  mockRouteSourceId = '2';
  rerender(React.createElement(Detail));
  await screen.findByText('Vastbase');
  expect(screen.getByText('Vastbase入湖库')).toBeTruthy();
  await act(async () => finishOldRequest({ code: 0, data: source(1, 'Kingbase') }));

  expect(screen.queryByText('Kingbase')).toBeNull();
  expect(screen.getByText('Vastbase入湖库')).toBeTruthy();
  expect(api.fetchPhysicalInventory).toHaveBeenCalledTimes(1);
  expect(api.fetchPhysicalInventory).toHaveBeenCalledWith(20);
});

test('an ODS binding for another source does not load its tables', async () => {
  const wrong = source(1, 'Vastbase');
  wrong.odsDatabase.sourceDataSourceId = 2;
  api.fetchPhysicalSource.mockResolvedValue({ code: 0, data: wrong });
  const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
  const error = jest.spyOn(console, 'error').mockImplementation(() => undefined);
  try {
    render(React.createElement(Detail));
    await screen.findByText('返回的入湖库与当前数据源不匹配，请重新加载。');
    expect(api.fetchPhysicalInventory).not.toHaveBeenCalled();
  } finally {
    warn.mockRestore();
    error.mockRestore();
  }
});

test('first intake asks for a single ODS name before creating the binding', async () => {
  api.fetchPhysicalSource.mockResolvedValue({
    code: 0, data: { sourceDataSourceId: 1, sourceDataSourceName: '订单源', dbType: 'MYSQL' },
  });
  api.createOdsDatabase.mockResolvedValue(source(1, '订单源').odsDatabase);
  render(React.createElement(Detail));
  fireEvent.click(await screen.findByRole('button', { name: /开始入湖/ }));

  const dialog = within(await screen.findByRole('dialog'));
  expect(dialog.getByText('设置 ODS 库名称')).toBeTruthy();
  expect(api.createOdsDatabase).not.toHaveBeenCalled();
  expect(dialog.getAllByRole('textbox')).toHaveLength(1);
  expect((dialog.getByRole('textbox') as HTMLInputElement).value).toBe('订单源');
  fireEvent.change(dialog.getByRole('textbox'), { target: { value: ' 192.168.100.95_订单 ' } });
  fireEvent.click(dialog.getByRole('button', { name: '开始入湖' }));

  await waitFor(() => expect(api.createOdsDatabase).toHaveBeenCalledWith('1', '192.168.100.95_订单'));
  expect(history.push).toHaveBeenCalledWith('/lake/resources/table/create?sourceDataSourceId=1&odsDatabaseBindingId=10');
});

test('starting again uses the previous binding name for restoration', async () => {
  api.fetchPhysicalSource.mockResolvedValue({
    code: 0, data: { sourceDataSourceId: 1, sourceDataSourceName: '订单源', dbType: 'MYSQL', previousOdsName: 'ds_1' },
  });
  api.createOdsDatabase.mockResolvedValue(source(1, '订单源').odsDatabase);
  render(React.createElement(Detail));
  fireEvent.click(await screen.findByRole('button', { name: /开始入湖/ }));

  await waitFor(() => expect(api.createOdsDatabase).toHaveBeenCalledWith('1', 'ds_1'));
  expect(screen.queryByRole('dialog')).toBeNull();
});

test('a ready warehouse shows the display name and hides unregistered tables', async () => {
  api.fetchPhysicalSource.mockResolvedValue({
    code: 0, data: { ...source(1, '订单源'), dbType: 'MYSQL' },
  });
  api.fetchPhysicalInventory.mockResolvedValue({
    code: 0, data: {
      odsDatabaseBindingId: 10,
      registeredTables: [],
      discoveredTables: [{ targetTableName: 'outside_existing_table', managementLevel: 'UNMANAGED' }],
    },
  });
  render(React.createElement(Detail));
  await screen.findByRole('button', { name: /新增入湖表/ });
  expect(screen.getByText('订单源入湖库')).toBeTruthy();
  expect(screen.queryByText('ods_1')).toBeNull();
  expect(screen.queryByRole('button', { name: /关联已有表/ })).toBeNull();
  expect(screen.queryByRole('button', { name: /刷新库状态/ })).toBeNull();

  fireEvent.click(screen.getByRole('button', { name: /诊断详情/ }));
  await screen.findByRole('button', { name: /刷新库状态/ });
  expect(screen.queryByText('outside_existing_table')).toBeNull();
  expect(screen.queryByText('发现的已有表')).toBeNull();
  expect(screen.queryByRole('tab', { name: '操作记录' })).toBeNull();
});
