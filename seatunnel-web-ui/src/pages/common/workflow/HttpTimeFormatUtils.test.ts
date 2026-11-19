import { detectHttpTimeFormat } from './HttpTimeFormatUtils';

describe('HTTP incremental time format detection', () => {
  it.each([
    ['2024-02-29', 'yyyy-MM-dd'],
    ['2024/03/01 14:05', 'yyyy/MM/dd HH:mm'],
    ['2024-03-01T14:05:06.123456789', "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS"],
    ['2024/03/01 14:05:06,12', 'yyyy/MM/dd HH:mm:ss,SS'],
  ])('detects %s as %s', (sample, pattern) => {
    expect(detectHttpTimeFormat(sample)).toEqual({ kind: 'detected', pattern, sample });
  });

  it.each(['2024-02-30 14:05:06', '2024-03-01 24:00:00', '2024-03-01 14:05:60'])(
    'rejects invalid local date/time sample %s',
    (sample) => {
      expect(detectHttpTimeFormat(sample)).toEqual({ kind: 'unsupported', reason: 'invalid' });
    },
  );

  it('does not infer timezone-aware samples', () => {
    expect(detectHttpTimeFormat('2024-03-01T14:05:06Z')).toEqual({
      kind: 'unsupported',
      reason: 'timezone',
    });
    expect(detectHttpTimeFormat('2024-03-01T14:05:06+08:00')).toEqual({
      kind: 'unsupported',
      reason: 'timezone',
    });
  });

  it('does not infer Unix timestamp samples', () => {
    expect(detectHttpTimeFormat('1719897600000')).toEqual({
      kind: 'unsupported',
      reason: 'epoch',
    });
  });
});
