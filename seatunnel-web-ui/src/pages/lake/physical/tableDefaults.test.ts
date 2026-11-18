import { primaryKeyNames, sourceNullable, suggestedDorisType, suggestedTableName } from './tableDefaults';

test('table-only composite primary keys are usable even without column constraints', () => {
  const columns = [{ name: 'tenant_id', dataType: 'BIGINT' }, { name: 'id', dataType: 'BIGINT' }];
  const keys = primaryKeyNames({ id: 'table', name: 'orders', fullyQualifiedName: 'svc.db.schema.orders', columns, tableConstraints: [{ constraintType: 'PRIMARY_KEY', columns: ['TENANT_ID', 'id'] }] });
  expect(columns.every((column) => !sourceNullable(column, keys))).toBe(true);
  expect(suggestedDorisType(columns[0])).toBe('BIGINT');
});

test('date and decimal defaults retain usable partition types and precision', () => {
  expect(suggestedDorisType({ name: 'created_at', dataType: 'TIMESTAMP', scale: 6 })).toBe('DATETIME(6)');
  expect(suggestedDorisType({ name: 'amount', dataType: 'DECIMAL', dataTypeDisplay: 'decimal(28,8)' })).toBe('DECIMAL(28,8)');
  expect(suggestedDorisType({ name: 'payload', dataType: 'JSON' })).toBe('STRING');
});

test('ODS names preserve namespace boundaries and remain stable for long identifiers', () => {
  expect(suggestedTableName('sales', 'public', 'orders')).not.toBe(suggestedTableName('sales', 'archive', 'orders'));
  const name = suggestedTableName('long_database'.repeat(10), 'public', 'orders');
  expect(name.length).toBeLessThanOrEqual(64);
  expect(name).toBe(suggestedTableName('long_database'.repeat(10), 'public', 'orders'));
});
