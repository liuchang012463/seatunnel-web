import HttpUtils from '@/utils/HttpUtils';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { App } from 'antd';
import React from 'react';
import FileSourceConfigPanel from './FileSourceConfigPanel';

jest.mock('@/utils/HttpUtils', () => ({ post: jest.fn() }));
jest.mock('./FileResourceSourceCard', () => () => null);

function renderPanel(fileFormatType: string, fileName?: string) {
  render(React.createElement(App, null, React.createElement(FileSourceConfigPanel, {
    sourceConfig: {
      sourceMode: 'FILE_RESOURCE',
      fileFormatType,
      formatLocked: true,
      fileResource: fileName ? { id: '42', name: fileName } : undefined,
    },
    onChange: jest.fn(),
  })));
}

afterEach(() => jest.clearAllMocks());

it.each([
  ['excel', 'sample.xlsx'],
  ['csv', 'sample.csv'],
  ['text', 'sample.txt'],
  ['json', 'sample.json'],
])('previews a selected %s file when the import entry presets its format', async (format, fileName) => {
  jest.mocked(HttpUtils.post).mockResolvedValue({
    code: 0,
    msg: '',
    data: { columns: ['value'], rows: [{ value: '样例数据' }] },
  });
  renderPanel(format, fileName);

  fireEvent.click(screen.getByRole('button', { name: /数据预览/ }));

  expect(HttpUtils.post).toHaveBeenCalledWith('/api/v1/file-resources/42/preview', {
    limit: 20,
    fileFormatType: format,
  });
  const dialog = await screen.findByRole('dialog', { name: `文件预览 · ${fileName}` });
  expect(within(dialog).getByRole('cell', { name: '样例数据' })).toBeTruthy();
});

it('disables data preview until a file is selected', () => {
  renderPanel('excel');

  const previewButton = screen.getByRole<HTMLButtonElement>('button', { name: /数据预览/ });
  expect(previewButton.disabled).toBe(true);
});

it.each([
  ['duckdb', 'sample.duckdb'],
  ['sqlite', 'sample.sqlite'],
])('hides structured data preview for %s database files', (format, fileName) => {
  renderPanel(format, fileName);

  expect(screen.queryByRole('button', { name: /数据预览/ })).toBeNull();
});
