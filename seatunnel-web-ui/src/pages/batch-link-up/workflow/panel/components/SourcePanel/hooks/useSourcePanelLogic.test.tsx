import { act, renderHook, waitFor } from '@testing-library/react';
import { useSourcePanelLogic } from './useSourcePanelLogic';

jest.mock('@/pages/data-source/service', () => ({
  fetchDataSourceOptions: jest.fn(),
  dataSourceCatalogApi: {
    listDatabases: jest.fn(),
    listTablesByDatabase: jest.fn(),
  },
}));

const { dataSourceCatalogApi, fetchDataSourceOptions } = jest.requireMock('@/pages/data-source/service');

it('clears the SQL table and ignores old table responses when the database changes', async () => {
  let resolveOldTables: (value: unknown) => void = () => {};
  const oldTables = new Promise((resolve) => { resolveOldTables = resolve; });
  fetchDataSourceOptions.mockResolvedValue({ data: [{ value: '1', label: 'MySQL', dbType: 'MYSQL' }] });
  dataSourceCatalogApi.listDatabases.mockResolvedValue({ data: [
    { value: 'old_db' }, { value: 'new_db' },
  ] });
  dataSourceCatalogApi.listTablesByDatabase.mockImplementation((_id: string, database: string) => (
    database === 'old_db' ? oldTables : Promise.resolve({ data: [{ value: 'new_table' }] })
  ));

  const node = (database: string) => ({
    id: 'source',
    data: { title: 'MySQL', dbType: 'MYSQL', config: { dataSourceId: '1', database } },
  });
  const onNodeDataChange = jest.fn();
  const scheduleConfig = {};
  const { result, rerender } = renderHook(
    ({ database }) => useSourcePanelLogic({
      selectedNode: node(database), onNodeDataChange, scheduleConfig, qualityDetailRef: undefined,
    }),
    { initialProps: { database: 'old_db' } },
  );

  await waitFor(() => expect(dataSourceCatalogApi.listTablesByDatabase).toHaveBeenCalledWith('1', 'old_db', undefined));
  act(() => {
    result.current.setSelectedSqlTable('old_table');
    result.current.handleDatabaseChange('new_db');
  });
  expect(result.current.selectedSqlTable).toBeUndefined();

  rerender({ database: 'new_db' });
  await waitFor(() => expect(result.current.tableOptions.map((option) => option.value)).toEqual(['new_table']));
  await act(async () => { resolveOldTables({ data: [{ value: 'old_table' }] }); });
  expect(result.current.tableOptions.map((option) => option.value)).toEqual(['new_table']);
});
