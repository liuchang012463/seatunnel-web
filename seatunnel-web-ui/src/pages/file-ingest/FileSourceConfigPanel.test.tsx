import HttpUtils from '@/utils/HttpUtils';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { App } from 'antd';
import React from 'react';
import FileSourceConfigPanel from './FileSourceConfigPanel';

jest.mock('@/utils/HttpUtils', () => ({ post: jest.fn() }));
jest.mock('./FileResourceSourceCard', () => () => null);

function renderPanel(fileFormatType: string, fileName?: string, config = {}) {
  const Panel = () => {
    const [sourceConfig, setSourceConfig] = React.useState<Record<string, unknown>>({
      sourceMode: 'FILE_RESOURCE',
      fileFormatType,
      formatLocked: true,
      fileResource: fileName ? { id: '42', name: fileName } : undefined,
      ...config,
    });
    return React.createElement(FileSourceConfigPanel, {
      sourceConfig,
      onChange: (patch) => setSourceConfig((previous) => ({ ...previous, ...patch })),
    });
  };
  render(React.createElement(App, null, React.createElement(Panel)));
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
  ['csv', 'sample.csv'],
  ['text', 'sample.txt'],
  ['json', 'sample.json'],
])('uses the selected GB2312 encoding to recognize and preview %s', async (format, fileName) => {
  jest.mocked(HttpUtils.post).mockResolvedValue({
    code: 0,
    msg: '',
    data: { columns: ['姓名'], rows: [{ 姓名: '张三' }] },
  });
  renderPanel(format, fileName);

  fireEvent.mouseDown(screen.getByRole('combobox', { name: '文件编码' }));
  fireEvent.click(screen.getByTitle('GB 2312（简体中文）'));
  await screen.findByText('1 个字段');

  const previewRequest = ['/api/v1/file-resources/42/preview', {
    limit: 20,
    fileFormatType: format,
    encoding: 'GB2312',
  }];
  expect(HttpUtils.post).toHaveBeenNthCalledWith(1, ...previewRequest);

  fireEvent.click(screen.getByRole('button', { name: /数据预览/ }));
  expect(HttpUtils.post).toHaveBeenNthCalledWith(2, ...previewRequest);
  const dialog = await screen.findByRole('dialog', { name: `文件预览 · ${fileName}` });
  expect(within(dialog).getByRole('cell', { name: '张三' })).toBeTruthy();
});

it('keeps saved custom encodings and manually configured fields', () => {
  renderPanel('csv', 'sample.csv', {
    encoding: 'Big5',
    schema: { fields: { 姓名: 'string' } },
  });

  expect(screen.getByText('Big5')).toBeTruthy();
  fireEvent.mouseDown(screen.getByRole('combobox', { name: '文件编码' }));
  fireEvent.click(screen.getByTitle('GB 2312（简体中文）'));

  expect(screen.getByRole('textbox', { name: '字段名 姓名' })).toBeTruthy();
  expect(HttpUtils.post).not.toHaveBeenCalled();
});

it('replaces automatically recognized CSV headers when the encoding changes', async () => {
  jest.mocked(HttpUtils.post)
    .mockResolvedValueOnce({ code: 0, msg: '', data: { columns: ['����'], rows: [] } })
    .mockResolvedValueOnce({ code: 0, msg: '', data: { columns: ['姓名'], rows: [] } });
  renderPanel('csv', 'sample.csv');

  fireEvent.click(screen.getByRole('button', { name: /识别字段/ }));
  await screen.findByRole('textbox', { name: '字段名 ����' });

  fireEvent.mouseDown(screen.getByRole('combobox', { name: '文件编码' }));
  fireEvent.click(screen.getByTitle('GB 2312（简体中文）'));

  await screen.findByRole('textbox', { name: '字段名 姓名' });
  expect(screen.queryByRole('textbox', { name: '字段名 ����' })).toBeNull();
});

it('reads Excel workbook encoding automatically even with a legacy encoding setting', async () => {
  jest.mocked(HttpUtils.post).mockResolvedValue({
    code: 0,
    msg: '',
    data: { columns: [], rows: [] },
  });
  renderPanel('excel', 'sample.xlsx', { encoding: 'GB2312' });

  expect(screen.getByRole<HTMLInputElement>('combobox', { name: '文件编码' }).disabled).toBe(true);
  expect(screen.getByText('按工作簿格式自动读取')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: /数据预览/ }));
  expect(HttpUtils.post).toHaveBeenCalledWith('/api/v1/file-resources/42/preview', {
    limit: 20,
    fileFormatType: 'excel',
  });
  await screen.findByRole('dialog', { name: '文件预览 · sample.xlsx' });
});

it.each([
  ['duckdb', 'sample.duckdb'],
  ['sqlite', 'sample.sqlite'],
])('hides structured data preview for %s database files', (format, fileName) => {
  renderPanel(format, fileName);

  expect(screen.queryByRole('button', { name: /数据预览/ })).toBeNull();
});
