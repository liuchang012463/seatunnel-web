import type { ManagedTable } from './types';

const jdbcPlugin = (dbType?: string) => {
  const normalized = String(dbType || 'MYSQL').toUpperCase();
  const pluginType = normalized === 'POSTGRE_SQL' ? 'POSTGRESQL' : normalized;
  return {
    dbType: normalized,
    connectorType: 'Jdbc',
    pluginName: `JDBC-${pluginType}`,
  };
};

export const buildLakeWorkflow = (table: ManagedTable) => {
  const sourceType = jdbcPlugin(table.sourceDbType);
  const sourceId = `lake-source-${Date.now()}`;
  const sinkId = `lake-sink-${Date.now()}`;
  const transformId = `lake-field-mapper-${Date.now()}`;
  const sourceTable = table.sourceTableName || '';
  if (!sourceTable || !table.sourceDatabaseName) throw new Error('来源库表定位不完整，请刷新元数据');
  const mappings = (table.fieldMappings || []).filter((item) => item.sourceField && item.targetField).map((item, index) => ({
    id: `lake-mapping-${index}`,
    sourceField: item.sourceField,
    targetField: item.targetField,
    targetType: item.targetType,
    enabled: true,
  }));
  const sourceSchema = mappings.map((item) => ({ name: item.sourceField, type: item.targetType || 'STRING', nullable: true }));
  const sourceNode = {
    id: sourceId,
    type: 'custom',
    position: { x: 80, y: 180 },
    data: {
      nodeType: 'source',
      title: sourceType.dbType,
      description: '读取已登记源表',
      ...sourceType,
      config: {
        dataSourceId: String(table.sourceDataSourceId || ''),
        ...sourceType,
        readMode: 'table',
        database: table.sourceDatabaseName,
        schemaName: table.sourceSchemaName,
        table: sourceTable,
        sql: '',
        extraParams: [],
        pluginOutput: sourceId,
      },
      meta: { outputSchema: sourceSchema, schemaStatus: mappings.length ? 'success' : 'idle', schemaError: '' },
    },
  };
  const sinkNode = {
    id: sinkId,
    type: 'custom',
    position: { x: mappings.length ? 700 : 460, y: 180 },
    data: {
      nodeType: 'sink',
      title: 'DORIS',
      description: '写入受控 ODS 表',
      dbType: 'DORIS',
      connectorType: 'Doris',
      pluginName: 'DORIS',
      config: {
        dataSourceId: String(table.lakeDataSourceId || ''),
        dbType: 'DORIS',
        connectorType: 'Doris',
        pluginName: 'DORIS',
        database: table.databaseName,
        autoCreateTable: false,
        targetMode: 'table',
        table: table.targetTableName || '',
        targetTableName: table.targetTableName || '',
        schemaSaveMode: 'ERROR_WHEN_SCHEMA_NOT_EXIST',
        writeMode: 'overwrite',
        data_save_mode: 'DROP_DATA',
        odsDatabaseBindingId: table.odsDatabaseBindingId,
        pluginInput: mappings.length ? transformId : sourceId,
        extraParams: [],
      },
      meta: { inputSchema: sourceSchema, outputSchema: sourceSchema, schemaStatus: 'success', schemaError: '' },
    },
  };
  const nodes = [sourceNode as any];
  const edges: any[] = [];
  if (mappings.length) {
    nodes.push({
      id: transformId,
      type: 'custom',
      position: { x: 390, y: 180 },
      data: {
        nodeType: 'transform',
        componentType: 'FIELDMAPPER',
        label: '字段映射',
        title: '字段映射',
        description: '沿用 MANAGED 表已确认的字段关系',
        iconType: 'braces',
        // Transform ports refer to the adjacent nodes in the normal workflow contract.
        config: { mappings, passThroughUnmapped: false, pluginInput: sourceId, pluginOutput: sinkId },
        meta: { inputSchema: sourceSchema, outputSchema: mappings.map((item) => ({ name: item.targetField, type: item.targetType || 'STRING', nullable: true, originFieldName: item.sourceField })), schemaStatus: 'success', schemaError: '' },
      },
    });
    edges.push({ id: `${sourceId}-${transformId}`, source: sourceId, target: transformId, type: 'custom', data: {} });
    edges.push({ id: `${transformId}-${sinkId}`, source: transformId, target: sinkId, type: 'custom', data: {} });
  } else {
    edges.push({ id: `${sourceId}-${sinkId}`, source: sourceId, target: sinkId, type: 'custom', data: {} });
  }
  nodes.push(sinkNode as any);
  return { nodes, edges, sourceType, targetType: { dbType: 'DORIS', connectorType: 'Doris', pluginName: 'DORIS' } };
};


/** The mapping URL can reconstruct this initial form after a tab/cache reset. */
export function lakeTaskForm(table: ManagedTable, id: string | number) {
  const workflow = buildLakeWorkflow(table);
  return { id, mode: 'GUIDE_SINGLE', jobName: `${table.sourceDbType || 'JDBC'} → DORIS · ${table.targetTableName || 'ODS 表'}`,
    jobDesc: '物理入湖全量任务；执行时清空目标数据后重新导入，失败后需重跑。',
    sourceType: workflow.sourceType, targetType: workflow.targetType,
    sourceDataSourceId: String(table.sourceDataSourceId), targetDataSourceId: String(table.lakeDataSourceId),
    odsDatabaseBindingId: table.odsDatabaseBindingId, workflow };
}
