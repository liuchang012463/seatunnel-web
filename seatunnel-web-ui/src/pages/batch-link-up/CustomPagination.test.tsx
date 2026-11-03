import { fireEvent, render, screen } from '@testing-library/react';
import React from 'react';
import CustomPagination from './CustomPagination';

describe('CustomPagination', () => {
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
