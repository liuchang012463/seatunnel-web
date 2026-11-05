const compareIntegerDigits = (left: string, right: string) => {
  if (left.length !== right.length) return left.length < right.length ? -1 : 1;
  return left === right ? 0 : left < right ? -1 : 1;
};

const integerParts = (value: string) => {
  const negative = value.startsWith('-');
  const digits = value.replace(/^[+-]/, '').replace(/^0+(?=\d)/, '');
  return { negative: negative && digits !== '0', digits };
};

const isWithinIntegerRange = (value: string, positiveMax: string, negativeMagnitudeMax: string) => {
  const { negative, digits } = integerParts(value);
  return compareIntegerDigits(digits, negative ? negativeMagnitudeMax : positiveMax) <= 0;
};

/**
 * Excel preview is capped at 20 rows, so unseen cells may still contain text.
 * Keep auto-recognized Excel columns as strings to avoid runtime parse failures.
 */
export const inferTypeFromSamples = (values: unknown[], format?: string): string => {
  if (format === 'excel') return 'string';

  const samples = values
    .filter((value) => value !== null && value !== undefined && String(value).trim() !== '')
    .map((value) => String(value).trim());
  if (!samples.length) return 'string';

  if (samples.every((value) => /^[+-]?\d+$/.test(value))) {
    if (samples.every((value) => isWithinIntegerRange(value, '2147483647', '2147483648'))) {
      return 'int';
    }
    if (samples.every((value) => isWithinIntegerRange(value, '9223372036854775807', '9223372036854775808'))) {
      return 'long';
    }
    return 'string';
  }

  // 小数列允许混入整数值（如 1.5 与 2），只要存在小数点即建议 double。
  if (samples.every((value) => /^[+-]?\d+(\.\d+)?$/.test(value)) && samples.some((value) => value.includes('.'))) {
    return 'double';
  }
  return 'string';
};
