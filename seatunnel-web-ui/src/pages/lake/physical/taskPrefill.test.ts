import { lakeTaskForm } from './taskPrefill';

test('full task keeps the exact source namespace and overwrites data without recreating ODS', () => {
  const form = lakeTaskForm({ id: 7, sourceDataSourceId: 10, lakeDataSourceId: 20, odsDatabaseBindingId: 3,
    sourceDbType: 'POSTGRE_SQL', sourceDatabaseName: 'source.db', sourceSchemaName: 'sales',
    sourceTableName: 'orders', databaseName: 'ods_erp', targetTableName: 'source_sales_orders' }, 'job');
  const source = form.workflow.nodes.find((node: any) => node.data.nodeType === 'source')!.data.config;
  const sink = form.workflow.nodes.find((node: any) => node.data.nodeType === 'sink')!.data.config;
  expect(source).toMatchObject({ database: 'source.db', schemaName: 'sales', table: 'orders' });
  expect(sink).toMatchObject({ database: 'ods_erp', table: 'source_sales_orders', data_save_mode: 'DROP_DATA', schemaSaveMode: 'ERROR_WHEN_SCHEMA_NOT_EXIST', autoCreateTable: false });
});

test('a missing source locator never silently falls back to the target table', () => {
  expect(() => lakeTaskForm({ id: 7, targetTableName: 'wrong_source' }, 'job')).toThrow('来源库表定位不完整');
});
