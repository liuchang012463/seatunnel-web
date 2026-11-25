import { fireEvent, render, screen } from '@testing-library/react';
import React from 'react';
import CustomPagination from './CustomPagination';

describe('CustomPagination', () => {
  it('keeps the total and disabled page controls when there are no results', () => {
    const onChange = jest.fn();
    const { container } = render(<CustomPagination total={0} current={1} pageSize={10} onChange={onChange} />);

    expect(screen.getByText('共 0 条')).toBeTruthy();
    expect((screen.getByRole('combobox') as HTMLInputElement).disabled).toBe(true);
    const next = container.querySelector<HTMLButtonElement>('.ant-pagination-next button')!;
    expect(next.disabled).toBe(true);
    fireEvent.click(next);
    expect(onChange).not.toHaveBeenCalled();
  });

  it('changes pages through the callback without a document navigation link', () => {
    const onChange = jest.fn();
    render(<CustomPagination total={25} current={1} pageSize={10} onChange={onChange} />);

    const secondPage = screen.getByText('2');
    expect(secondPage.hasAttribute('href')).toBe(false);

    fireEvent.click(secondPage);
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith(2, 10);
  });
});
