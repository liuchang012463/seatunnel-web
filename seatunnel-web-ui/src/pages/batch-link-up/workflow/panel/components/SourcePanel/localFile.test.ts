import {
  buildOutputSchema,
  getSchemaFields,
  localFileExtensionMatches,
} from './localFile';

describe('local file source config helpers', () => {
  it('builds downstream schema fields from the SeaTunnel schema model', () => {
    expect(buildOutputSchema({ fields: { id: 'long', name: 'string' } })).toEqual([
      expect.objectContaining({ originFieldName: 'id', type: 'long' }),
      expect.objectContaining({ originFieldName: 'name', type: 'string' }),
    ]);
  });

  it('accepts the four supported single-file extensions', () => {
    expect(localFileExtensionMatches('orders.csv', 'csv')).toBe(true);
    expect(localFileExtensionMatches('orders.xlsx', 'excel')).toBe(true);
    expect(localFileExtensionMatches('orders.json', 'json')).toBe(true);
    expect(localFileExtensionMatches('orders.txt', 'text')).toBe(true);
    expect(getSchemaFields({ fields: { id: 'long' } })).toEqual({ id: 'long' });
  });
});
