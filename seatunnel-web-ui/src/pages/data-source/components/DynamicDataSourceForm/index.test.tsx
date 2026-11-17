import { act, render, screen, waitFor } from '@testing-library/react';
import { Form } from 'antd';
import React from 'react';
import DynamicDataSourceForm from './index';

jest.mock('@umijs/max', () => {
  const intl = { formatMessage: ({ defaultMessage, id }: any) => defaultMessage || id };
  return { useIntl: () => intl };
});
jest.mock('@/utils/HttpUtils', () => ({ __esModule: true, default: { get: jest.fn() } }));
jest.mock('../../icon/DatabaseIcons', () => () => null);
jest.mock('../DataSourceUnitSelect', () => () => null);
const http = jest.requireMock('@/utils/HttpUtils').default;
const initialConfig = { database: 'legacy_ods', fenodes: 'fe:8030' };

beforeEach(() => {
  jest.clearAllMocks();
  http.get.mockResolvedValue({ code: 0, data: { formFields: [
    { key: 'fenodes', label: 'FE 地址', type: 'INPUT', rules: [{ required: true }] },
    { key: 'database', label: '默认数据库', type: 'INPUT', defaultValue: 'ods', rules: [{ required: true }] },
  ] } });
});

const setup = (excludedConfigFields?: string[]) => {
  let connectionForm: any;
  const Harness = () => {
    const [base] = Form.useForm();
    const [connection] = Form.useForm();
    connectionForm = connection;
    return React.createElement(DynamicDataSourceForm, {
      dbType: 'DORIS', form: base, configForm: connection, operateType: 'CREATE' as any,
      hideBaseFields: true, initialConfig, excludedConfigFields,
    });
  };
  const view = render(React.createElement(Harness));
  return { ...view, getForm: () => connectionForm! };
};

test('lake configuration excludes the database field, its old value, and its required rule', async () => {
  const view = setup(['database']);
  await screen.findByLabelText('FE 地址');
  await waitFor(() => expect(view.getForm().getFieldValue('fenodes')).toBe('fe:8030'));
  expect(screen.queryByLabelText('默认数据库')).toBeNull();
  expect(view.getForm().getFieldValue('database')).toBeUndefined();
  await expect(view.getForm().validateFields()).resolves.toEqual({ fenodes: 'fe:8030' });
});

test('ordinary Doris datasource forms retain database selection and validation', async () => {
  const view = setup();
  await screen.findByLabelText('默认数据库');
  await waitFor(() => expect(view.getForm().getFieldValue('database')).toBe('legacy_ods'));
  await act(async () => {
    view.getForm().setFieldValue('database', '');
    await expect(view.getForm().validateFields(['database'])).rejects.toMatchObject({
      errorFields: [{ name: ['database'] }],
    });
  });
});
