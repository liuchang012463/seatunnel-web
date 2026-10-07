import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from './index';
import { fetchChartData, fetchSummaryData } from './api';

type Summary = {
  totalRecords: number;
  totalBytes: number;
  totalTasks: number;
  successTasks: number;
  failedTasks: number;
  runningTasks: number;
  stoppedTasks: number;
  avgRecordDelay: number;
  totalRecordsUnit: string;
  totalBytesUnit: string;
};

jest.mock('./api', () => ({
  fetchChartData: jest.fn(),
  fetchSummaryData: jest.fn(),
}));

jest.mock('./BarChart', () => () => null);
jest.mock('./LineChart', () => () => null);

jest.mock('framer-motion', () => {
  const ReactModule = require('react');
  return {
    motion: new Proxy({}, {
      get: (_target, element) => ({
        children,
        initial: _initial,
        animate: _animate,
        variants: _variants,
        whileHover: _whileHover,
        ...props
      }: any) => ReactModule.createElement(element, props, children),
    }),
  };
});

jest.mock('antd', () => {
  const ReactModule = require('react');
  return {
    Alert: () => null,
    Button: ({ children, icon: _icon, loading, ...props }: any) =>
      ReactModule.createElement('button', { ...props, 'data-loading': String(Boolean(loading)) }, children),
    Select: ({ value, onChange, options, ...props }: any) =>
      ReactModule.createElement(
        'select',
        { ...props, value, onChange: (event: any) => onChange(event.target.value) },
        options.map((option: any) =>
          ReactModule.createElement('option', { key: option.value, value: option.value }, option.label),
        ),
      ),
    Spin: ({ children }: any) => ReactModule.createElement('div', null, children),
  };
});

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
};

const summary = (totalRecords: number): Summary => ({
  totalRecords,
  totalBytes: totalRecords,
  totalTasks: 1,
  successTasks: 0,
  failedTasks: 0,
  runningTasks: 1,
  stoppedTasks: 0,
  avgRecordDelay: 0,
  totalRecordsUnit: '条',
  totalBytesUnit: 'B',
});

const chartData = {
  recordsTrend: [],
  bytesTrend: [],
  recordsSpeedTrend: [],
  bytesSpeedTrend: [],
};

describe('metrics request ordering', () => {
  it('keeps the latest task type and time range when older requests finish later', async () => {
    const summaryRequests: Array<ReturnType<typeof deferred<Summary>>> = [];
    const chartRequests: Array<ReturnType<typeof deferred<any>>> = [];
    (fetchSummaryData as jest.Mock).mockImplementation((_range: string, _type: string) => {
      const request = deferred<Summary>();
      summaryRequests.push(request);
      return request.promise;
    });
    (fetchChartData as jest.Mock).mockImplementation((_range: string, _type: string) => {
      const request = deferred<any>();
      chartRequests.push(request);
      return request.promise;
    });

    render(React.createElement(App));
    await waitFor(() => expect(summaryRequests).toHaveLength(1));
    await act(async () => {
      summaryRequests[0].resolve(summary(42));
      chartRequests[0].resolve(chartData);
    });
    expect(await screen.findAllByText('42')).toHaveLength(2);

    fireEvent.change(screen.getByRole('combobox', { name: '任务类型' }), {
      target: { value: 'STREAM' },
    });
    await waitFor(() => expect(summaryRequests).toHaveLength(2));

    fireEvent.change(screen.getByRole('combobox', { name: '统计时间范围' }), {
      target: { value: 'H1' },
    });
    await waitFor(() => expect(summaryRequests).toHaveLength(3));

    await act(async () => {
      summaryRequests[1].resolve(summary(202));
      chartRequests[1].resolve(chartData);
    });
    expect(screen.queryByText('202')).toBeNull();
    expect(screen.getByRole('button', { name: '刷新任务概览' }).getAttribute('data-loading')).toBe('true');

    await act(async () => {
      summaryRequests[2].resolve(summary(303));
      chartRequests[2].resolve(chartData);
    });
    expect(await screen.findAllByText('303')).toHaveLength(2);

    expect(screen.getAllByText('303')).toHaveLength(2);
    expect(screen.queryByText('202')).toBeNull();
    expect(screen.getByRole('button', { name: '刷新任务概览' }).getAttribute('data-loading')).toBe('false');
  });
});
