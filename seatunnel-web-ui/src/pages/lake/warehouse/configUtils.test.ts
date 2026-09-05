import { isInitialDorisPasswordMissing } from './configUtils';

describe('isInitialDorisPasswordMissing', () => {
  it('requires a password when no Doris configuration exists', () => {
    expect(isInitialDorisPasswordMissing(undefined, '')).toBe(true);
    expect(isInitialDorisPasswordMissing({ passwordConfigured: false }, '  ')).toBe(true);
  });

  it('accepts a manually entered password for the initial configuration', () => {
    expect(isInitialDorisPasswordMissing(undefined, 'doris-secret')).toBe(false);
  });

  it('allows an empty password when the existing password is configured', () => {
    expect(isInitialDorisPasswordMissing({ passwordConfigured: true }, '')).toBe(false);
  });
});
