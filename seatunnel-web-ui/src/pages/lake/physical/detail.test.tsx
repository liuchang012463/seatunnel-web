import React from 'react';
import { act, render, screen } from '@testing-library/react';
import Detail from './detail';

let mockRouteSourceId = '1';

jest.mock('@umijs/max', () => ({
  history: { push: jest.fn() },
  useParams: () => ({ sourceDataSourceId: mockRouteSourceId }),
}));
jest.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children, title }: { children?: import('react').ReactNode; title?: string }) =>
    require('react').createElement('div', undefined, title, children),
  ProTable: () => null,
}));
jest.mock('../useMasterDataNames', () => ({ useMasterDataNames: () => ({ unitNameByCode: () => '', systemNameByCode: () => '' }) }));
jest.mock('./service', () => ({ fetchPhysicalSource: jest.fn(), fetchPhysicalInventory: jest.fn() }));
jest.mock('@/services/lake', () => ({ fetchLakeOperations: jest.fn() }));

const api = jest.requireMock('./service');
const source = (id: number, name: string) => ({
  sourceDataSourceId: id,
  sourceDataSourceName: name,
  odsDatabaseBindingId: id * 10,
  odsDatabase: { id: id * 10, sourceDataSourceId: id, databaseName: `ods_${id}`, resourceStatus: 'READY' },
});

beforeEach(() => {
  api.fetchPhysicalSource.mockReset();
  api.fetchPhysicalInventory.mockReset();
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
  expect(screen.getByText('ods_2')).toBeTruthy();
  await act(async () => finishOldRequest({ code: 0, data: source(1, 'Kingbase') }));

  expect(screen.queryByText('Kingbase')).toBeNull();
  expect(screen.getByText('ods_2')).toBeTruthy();
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
