import React, { useState } from 'react';
import { act, render, screen, waitFor } from '@testing-library/react';
import CommonClientLinkSection from './index';

jest.mock('@/pages/client/api', () => ({ seatunnelClientApi: { option: jest.fn().mockResolvedValue({ data: [] }) } }));
jest.mock('@/pages/client/components/AddClientModal', () => () => null);
jest.mock('@/pages/data-source/components/AddOrEditDataSourceModal', () => ({
  __esModule: true, default: jest.requireActual('react').forwardRef(() => null),
}));
jest.mock('@/pages/data-source/service', () => ({ fetchDataSourceOptions: jest.fn() }));
jest.mock('@/pages/batch-link-up/DataSourceSelect', () => ({
  generateDataSourceOptions: () => [{ label: 'MySQL', value: 'MYSQL' }],
  generateSourceDataSourceOptions: () => [{ label: 'MySQL', value: 'MYSQL' }],
}));

test('keeps both prefilled endpoint ids while their options load asynchronously', async () => {
  let finishSource: (value: any) => void = () => {};
  let finishTarget: (value: any) => void = () => {};
  jest.requireMock('@/pages/data-source/service').fetchDataSourceOptions.mockImplementation(
    (_type: string, usage: string) => new Promise((resolve) => {
      if (usage === 'SOURCE') finishSource = resolve;
      else finishTarget = resolve;
    }),
  );
  const noop = () => {};
  const Wrapper = () => {
    const [source, setSource] = useState<string | undefined>('2');
    const [target, setTarget] = useState<string | undefined>('4');
    return React.createElement(React.Fragment, null,
      React.createElement('output', { 'data-testid': 'selection' }, `${source}/${target}`),
      React.createElement(CommonClientLinkSection, {
        activeStep: 'client', sourceType: { dbType: 'MYSQL' }, targetType: { dbType: 'MYSQL' },
        sourceLabel: '来源', targetLabel: '去向', clientId: undefined, setClientId: noop,
        handleSourceChange: noop, handleTargetChange: noop,
        sourceDataSourceId: source, targetDataSourceId: target,
        setSourceDataSourceId: setSource, setTargetDataSourceId: setTarget,
        sourceTestStatus: 'idle', targetTestStatus: 'idle', setSourceTestStatus: noop, setTargetTestStatus: noop,
      }),
    );
  };
  render(React.createElement(Wrapper));
  expect(screen.getByTestId('selection').textContent).toBe('2/4');
  await act(async () => {
    finishSource({ data: [{ value: 1, label: '第一个来源' }, { value: 2, label: '入湖来源' }] });
    finishTarget({ data: [{ value: 3, label: '第一个去向' }, { value: 4, label: '入湖去向' }] });
  });
  await waitFor(() => expect(screen.getByText('入湖来源', { selector: '.ant-select-selection-item' })).toBeTruthy());
  expect(screen.getByText('入湖去向', { selector: '.ant-select-selection-item' })).toBeTruthy();
  expect(screen.getByTestId('selection').textContent).toBe('2/4');
});
