import { inferTypeFromSamples } from '../fileTypeInference';

describe('inferTypeFromSamples', () => {
  it('infers int only when every integer sample is inside the signed 32-bit range', () => {
    expect(inferTypeFromSamples(['-2147483648', '2147483647'])).toBe('int');
    expect(inferTypeFromSamples(['2147483648'])).toBe('long');
  });

  it('uses long for signed 64-bit values, including the lower bound', () => {
    expect(inferTypeFromSamples(['-9223372036854775808', '9223372036854775807'])).toBe('long');
  });

  it('keeps integers outside the signed 64-bit range as strings', () => {
    expect(inferTypeFromSamples(['9223372036854775808'])).toBe('string');
    expect(inferTypeFromSamples(['-9223372036854775809'])).toBe('string');
  });

  it('retains the existing decimal inference', () => {
    expect(inferTypeFromSamples(['2', '1.5'])).toBe('double');
  });

  it('keeps Excel auto-recognized columns as strings because preview samples are bounded', () => {
    expect(inferTypeFromSamples(['1', '2', '3'], 'excel')).toBe('string');
  });
});
