export type HttpTimeFormatDetection =
  | { kind: 'detected'; pattern: string; sample: string }
  | { kind: 'unsupported'; reason: 'timezone' | 'epoch' | 'invalid' };

const daysInMonth = (year: number, month: number) => {
  if (month === 2) {
    const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
    return leapYear ? 29 : 28;
  }
  return [4, 6, 9, 11].includes(month) ? 30 : 31;
};

/** Detect local numeric date/time samples without guessing timezones or epochs. */
export function detectHttpTimeFormat(sample: string): HttpTimeFormatDetection {
  const value = sample.trim();
  if (!value) {
    return { kind: 'unsupported', reason: 'invalid' };
  }

  if (/^\d{10}(?:\d{3}){0,3}$/.test(value)) {
    return { kind: 'unsupported', reason: 'epoch' };
  }

  if (/(?:Z|[+-]\d{2}:?\d{2}|\b(?:UTC|GMT))$/i.test(value)) {
    return { kind: 'unsupported', reason: 'timezone' };
  }

  const match = /^(\d{4})([-/])(\d{2})\2(\d{2})(?:([ T])(\d{2}):(\d{2})(?::(\d{2})(?:([.,])(\d{1,9}))?)?)?$/.exec(value);
  if (!match) {
    return { kind: 'unsupported', reason: 'invalid' };
  }

  const [, yearText, dateSeparator, monthText, dayText, dateTimeSeparator, hourText, minuteText, secondText, fractionSeparator, fraction] = match;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  if (year < 1 || month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month)) {
    return { kind: 'unsupported', reason: 'invalid' };
  }

  let pattern = `yyyy${dateSeparator}MM${dateSeparator}dd`;
  if (dateTimeSeparator) {
    const hour = Number(hourText);
    const minute = Number(minuteText);
    const second = secondText === undefined ? undefined : Number(secondText);
    if (hour > 23 || minute > 59 || (second !== undefined && second > 59)) {
      return { kind: 'unsupported', reason: 'invalid' };
    }

    pattern += `${dateTimeSeparator === 'T' ? "'T'" : ' '}HH:mm`;
    if (secondText !== undefined) {
      pattern += `:ss`;
      if (fraction !== undefined) {
        pattern += `${fractionSeparator}${'S'.repeat(fraction.length)}`;
      }
    }
  }

  return { kind: 'detected', pattern, sample: value };
}
