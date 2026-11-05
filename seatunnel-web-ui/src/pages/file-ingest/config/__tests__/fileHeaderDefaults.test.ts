import {
  defaultExcelHeaderSkipOnSelection,
  isExcelHeaderEnabled,
} from '../fileHeaderDefaults';

describe('Excel first-row header defaults', () => {
  it('keeps old saved configurations with a zero skip count unchanged', () => {
    expect(isExcelHeaderEnabled(0)).toBe(false);
  });

  it('defaults a newly selected Excel format or file to skipping the header row', () => {
    expect(defaultExcelHeaderSkipOnSelection(undefined)).toBe(1);
    expect(defaultExcelHeaderSkipOnSelection(0)).toBe(1);
  });

  it('preserves an explicitly configured skip count when selecting Excel', () => {
    expect(defaultExcelHeaderSkipOnSelection(2)).toBe(2);
  });
});
