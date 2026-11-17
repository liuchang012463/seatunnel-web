import { act, render, waitFor } from '@testing-library/react';
import { Form } from 'antd';
import React from 'react';
import { CatalogTablePicker } from './detail';

jest.mock('@umijs/max', () => ({
  history: { push: jest.fn() },
  useParams: () => ({}),
}));
jest.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children }: any) => children,
}));
jest.mock('./CatalogScopePicker', () => () => null);
jest.mock('@/services/lake', () => ({
  fetchCatalogQueryDatabases: jest.fn(),
  fetchCatalogQueryTables: jest.fn(),
  fetchCatalogQueryColumns: jest.fn(),
}));
const api = jest.requireMock('@/services/lake');

beforeEach(() => {
  jest.clearAllMocks();
  api.fetchCatalogQueryDatabases.mockResolvedValue({
    code: 0,
    data: ['public'],
  });
  api.fetchCatalogQueryTables.mockResolvedValue({ code: 0, data: ['events'] });
  api.fetchCatalogQueryColumns.mockResolvedValue({
    code: 0,
    data: [{ name: 'id' }, { name: 'token', selectable: false }],
  });
});

const setup = () => {
  let form: any;
  const Harness = ({ refreshKey = 0 }) => {
    const [current] = Form.useForm();
    form = current;
    return React.createElement(
      Form,
      { form: current },
      React.createElement(CatalogTablePicker, {
        form: current,
        catalogId: 7,
        refreshKey,
      }),
    );
  };
  const view = render(React.createElement(Harness));
  return {
    ...view,
    getForm: () => form!,
    refresh: () => view.rerender(React.createElement(Harness, { refreshKey: 1 })),
  };
};

test('唯一库表自动选中，默认字段排除不可查询内容', async () => {
  const view = setup();
  await waitFor(() => expect(view.getForm().getFieldValue('columns')).toEqual(['id']));
  expect(view.getForm().getFieldValue('database')).toBe('public');
  expect(view.getForm().getFieldValue('table')).toBe('events');
});

test('快速换表时，旧表的迟到字段不会覆盖当前选择', async () => {
  api.fetchCatalogQueryTables.mockResolvedValue({
    code: 0,
    data: ['old_table', 'new_table'],
  });
  let finishOld: (value: unknown) => void = () => {};
  api.fetchCatalogQueryColumns.mockImplementation((_id: number, _db: string, table: string) =>
    table === 'old_table'
      ? new Promise((resolve) => {
          finishOld = resolve;
        })
      : Promise.resolve({ code: 0, data: [{ name: 'new_id' }] }),
  );
  const view = setup();
  await waitFor(() => expect(api.fetchCatalogQueryTables).toHaveBeenCalledWith(7, 'public'));
  act(() => view.getForm().setFieldValue('table', 'old_table'));
  await waitFor(() => expect(api.fetchCatalogQueryColumns).toHaveBeenCalledWith(7, 'public', 'old_table'));
  act(() => view.getForm().setFieldValue('table', 'new_table'));
  await waitFor(() => expect(view.getForm().getFieldValue('columns')).toEqual(['new_id']));
  await act(async () => {
    finishOld({ code: 0, data: [{ name: 'old_id' }] });
  });
  expect(view.getForm().getFieldValue('table')).toBe('new_table');
  expect(view.getForm().getFieldValue('columns')).toEqual(['new_id']);
});

test('刷新库表后保留仍然有效的表和自定义字段', async () => {
  api.fetchCatalogQueryColumns.mockResolvedValue({
    code: 0,
    data: [{ name: 'id' }, { name: 'amount' }],
  });
  const view = setup();
  await waitFor(() => expect(view.getForm().getFieldValue('columns')).toEqual(['id', 'amount']));
  act(() => view.getForm().setFieldValue('columns', ['amount']));
  view.refresh();
  await waitFor(() => expect(api.fetchCatalogQueryColumns).toHaveBeenCalledTimes(2));
  await waitFor(() => expect(view.getForm().getFieldValue('columns')).toEqual(['amount']));
  expect(view.getForm().getFieldValue('table')).toBe('events');
});
