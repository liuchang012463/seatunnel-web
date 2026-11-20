import {
  buildHttpSchemaFieldCandidates,
  findHttpContentFields,
  mergeHttpSchemaFieldTypes,
  resolveHttpContentRecords,
} from './HttpResponseSchemaUtils';

const response = {
  code: 0,
  data: [
    { id: 1, event_time: '2026-08-09 01:00:00', active: true },
    { id: 2, event_time: '2026-08-09 02:00:00', active: false },
  ],
};

describe('HTTP response schema helpers', () => {
  it('finds array content fields and resolves records', () => {
    expect(findHttpContentFields(response)).toEqual([
      { value: '$.data.*', label: '$.data.*（数组记录）' },
    ]);
    expect(resolveHttpContentRecords(response, '$.data.*')).toHaveLength(2);
  });

  it('generates fields with inferred SeaTunnel types', () => {
    expect(buildHttpSchemaFieldCandidates(response, '$.data.*')).toEqual([
      { name: 'id', sample: 1, inferredType: 'bigint' },
      { name: 'event_time', sample: '2026-08-09 01:00:00', inferredType: 'timestamp' },
      { name: 'active', sample: true, inferredType: 'boolean' },
    ]);
  });

  it('keeps fields that only appear in later records', () => {
    const candidates = buildHttpSchemaFieldCandidates(
      { data: [{ id: 1 }, { id: 2, created_at: '2026-08-09', observed_at: '09:30:00' }] },
      '$.data.*',
    );

    expect(candidates).toEqual([
      { name: 'id', sample: 1, inferredType: 'bigint' },
      { name: 'created_at', sample: '2026-08-09', inferredType: 'date' },
      { name: 'observed_at', sample: '09:30:00', inferredType: 'time' },
    ]);
  });

  it.each([
    ['2026/08/09', 'date'],
    ['2026-08-09T01:00:00Z', 'timestamp'],
    ['2026/08/09 01:00', 'timestamp'],
    ['2026-08-09 01:00:00.123+08:00', 'timestamp'],
    ['01:00', 'time'],
  ])('infers %s as %s', (value, expected) => {
    expect(
      buildHttpSchemaFieldCandidates({ data: [{ value }] }, '$.data.*')[0].inferredType,
    ).toBe(expected);
  });

  it('prefills missing types without overwriting user choices', () => {
    const candidates = buildHttpSchemaFieldCandidates(response, '$.data.*');

    expect(mergeHttpSchemaFieldTypes(candidates, { id: 'int' })).toEqual({
      id: 'int',
      event_time: 'timestamp',
      active: 'boolean',
    });
  });
});
