import { isFieldVisible } from './formUtils';

describe('isFieldVisible', () => {
  it('shows an unrestricted field', () => {
    expect(isFieldVisible({ visibleWhen: '' }, {})).toBe(true);
  });

  it('matches a dependent field case-insensitively', () => {
    const field = { visibleWhen: 'authenticationType=BASIC' };

    expect(isFieldVisible(field, { authenticationType: 'BASIC' })).toBe(true);
    expect(isFieldVisible(field, { authenticationType: 'NONE' })).toBe(false);
  });

  it('supports multiple accepted values', () => {
    const field = { visibleWhen: 'authenticationType=BASIC|API_KEY' };

    expect(isFieldVisible(field, { authenticationType: 'API_KEY' })).toBe(true);
    expect(isFieldVisible(field, { authenticationType: 'BEARER' })).toBe(false);
  });
});
