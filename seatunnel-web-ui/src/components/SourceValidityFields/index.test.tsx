import React, { useState } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import SourceValidityFields from './index';

jest.mock('@/pages/knowledge-management/api', () => ({ fetchTimeVariableList: jest.fn() }));
const api = jest.requireMock('@/pages/knowledge-management/api');

test('uses the existing variable name rather than its option ID in the saved filter', async () => {
  api.fetchTimeVariableList.mockResolvedValue({ code: 0, data: [{ value: '42', label: 'lastWeek', description: 'now - 7d' }] });
  const changed = jest.fn();
  function Editor() {
    const [value, setValue] = useState({ enabled: false });
    return React.createElement(SourceValidityFields, { value, fields: [{ name: 'created_at', type: 'DATETIME' }],
      onLoadFields: jest.fn(), onChange: (next) => { setValue(next as any); changed(next); } });
  }
  render(React.createElement(Editor));
  expect(api.fetchTimeVariableList).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText('按时间筛选（可选）'));
  fireEvent.click(screen.getByRole('switch'));
  fireEvent.mouseDown(screen.getByLabelText('时间字段'));
  fireEvent.click(await screen.findByText('created_at', { selector: '.ant-select-item-option-content' }));
  fireEvent.mouseDown(screen.getByLabelText('起始时间变量'));
  fireEvent.click(await screen.findByText('lastWeek', { selector: '.ant-select-item-option-content' }));
  await waitFor(() => expect(changed).toHaveBeenLastCalledWith({ enabled: true, fieldName: 'created_at', timeVariable: 'lastWeek' }));
  expect(screen.getByText('created_at >= ${var:lastWeek}')).toBeTruthy();
});
