import {
  buildHttpSchemaFieldCandidates,
  findHttpContentFields,
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
});
