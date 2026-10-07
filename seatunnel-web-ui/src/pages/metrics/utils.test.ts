import { calculateSuccessRate } from './utils';

describe('task overview metrics helpers', () => {
  it('leaves the rate unavailable when no task has reached a terminal state', () => {
    expect(calculateSuccessRate(0, 0)).toBeNull();
  });

  it('calculates success using terminal task outcomes', () => {
    expect(calculateSuccessRate(2, 3)).toBe(67);
  });
});
