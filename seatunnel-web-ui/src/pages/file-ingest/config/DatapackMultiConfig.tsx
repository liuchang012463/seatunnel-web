import { history } from '@umijs/max';
import { Alert, App, Button, Collapse, Modal, Select, Space, Table } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { seatunnelJobDefinitionApi } from '@/pages/batch-link-up/api';
import CodeBlockWithCopy from '@/pages/batch-link-up/workflow/operator/CodeBlockWithCopy';
import { buildSchedulePayload } from '@/pages/batch-link-up/workflow/components/ScheduleConfigContent/types';
import { supportsSchemaScope } from '@/pages/data-source/dataSourceRegistry';
import { dataSourceCatalogApi, fetchDataSourceOptions } from '@/pages/data-source/service';
import { fileResourceApi } from '../api';
import DatapackCsvImportStep from '../DatapackCsvImportStep';
import type { DatapackIdentity, DatapackSelection } from './datapack';
import { mergeEnvConfig, mergeScheduleConfig } from './runtime';

interface CsvMapping {
  fileName: string;
  fileResourceId: string;
  fields: Record<string, string>;
  nullValue: string;
  targetTable?: string;
  fieldMapping?: Record<string, string>;
}

interface Target {
  dbType: string;
  connectorType: string;
  pluginName: string;
  datasourceId?: string;
  database?: string;
  schemaName?: string;
}

type Catalog = { code: number; data: any[]; message?: string };
type Column = { fieldName: string; fieldType: string };

function useCatalog(request: (() => Promise<Catalog>) | undefined) {
  const [state, setState] = useState<{ request?: typeof request; items: any[]; error?: string }>({ items: [] });

  useEffect(() => {
    if (!request) return;
    let active = true;
    request().then((response) => {
      if (response.code !== 0) throw new Error(response.message || '读取目标目录失败');
      if (active) setState({ request, items: Array.isArray(response.data) ? response.data : [] });
    }).catch((error: Error) => {
      if (active) setState({ request, items: [], error: error.message || '读取目标目录失败' });
    });
    return () => { active = false; };
  }, [request]);

  return state.request === request
    ? { ...state, loading: false }
    : { items: [], loading: !!request, error: undefined };
}

const optionsOf = (items: any[]) => items.map((item) => ({
  value: String(item.value ?? item.id ?? ''),
  label: String(item.label ?? item.name ?? item.value ?? ''),
})).filter((item) => item.value);

const bareName = (path: string) => path.split('/').pop()?.replace(/\.csv$/i, '') || path;
const similarName = (left: string, right: string) => left.toLowerCase() === right.toLowerCase();

function typeFamily(value: string) {
  const type = String(value || '').toLowerCase().trim().replace(/\s*\(.*\)/, '').replace(/\s+unsigned$/, '');
  if (/^(byte|tinyint|short|smallint|int2|int|int4|integer|mediumint|long|bigint|int8)$/.test(type)) return 'integer';
  if (/^(float|float4|float8|real|double|double precision|decimal|numeric|number)$/.test(type)) return 'numeric';
  if (/^(string|.*char.*|.*text|.*clob)$/.test(type)) return 'text';
  if (/^(bool|boolean|bit)$/.test(type)) return 'boolean';
  if (/^(timestamp.*|datetime.*)$/.test(type)) return 'timestamp';
  return type === 'date' || type === 'time' ? type : undefined;
}

function compatible(sourceType: string, targetType: string) {
  const source = typeFamily(sourceType);
  const target = typeFamily(targetType);
  // Dialect-specific types are resolved by the connector.
  return !source || !target || source === target || (source === 'integer' && target === 'numeric');
}

function mappingFor(row: CsvMapping, columns: Column[]) {
  if (row.fieldMapping) return row.fieldMapping;
  return Object.fromEntries(Object.entries(row.fields).flatMap(([field, type]) => {
    const matches = columns.filter((column) => similarName(field, column.fieldName) && compatible(type, column.fieldType));
    return matches.length === 1 ? [[field, matches[0].fieldName]] : [];
  }));
}

const DatapackMultiConfig = ({ params, goBack }: { params: any; goBack: () => void }) => {
  const { message } = App.useApp();
  const saved = params.content?.datapack;
  const [datapack, setDatapack] = useState<DatapackIdentity | undefined>(saved);
  const [rows, setRows] = useState<CsvMapping[]>(saved?.mappings || []);
  const [selecting, setSelecting] = useState(false);
  const [target, setTarget] = useState<Target>(() => {
    const value = params.content?.target || params.targetType;
    return {
      dbType: value.dbType,
      connectorType: value.connectorType,
      pluginName: value.pluginName,
      datasourceId: value.datasourceId ?? params.targetDataSourceId,
      database: value.database,
      schemaName: value.schemaName,
    };
  });
  const [busy, setBusy] = useState<'preview' | 'save'>();
  const [hocon, setHocon] = useState<string>();
  const [sample, setSample] = useState<{ fileName: string; columns: string[]; rows: Record<string, unknown>[] }>();
  const [previewId, setPreviewId] = useState<string>();

  const { datasourceId, dbType, pluginName, database, schemaName } = target;
  const schemaScoped = supportsSchemaScope(dbType);
  const datasourceRequest = useMemo(() => () => fetchDataSourceOptions(dbType, 'SINK', pluginName), [dbType, pluginName]);
  const datasources = useCatalog(datasourceRequest);
  const datasourceOptions = optionsOf(datasources.items.filter((item) => !item.systemManaged));
  const canUseDatasource = datasourceOptions.some((item) => item.value === String(datasourceId));
  const databases = useCatalog(useMemo(() => canUseDatasource && datasourceId
    ? () => dataSourceCatalogApi.listDatabases(String(datasourceId))
    : undefined, [canUseDatasource, datasourceId]));
  const canUseDatabase = optionsOf(databases.items).some((item) => item.value === database);
  const schemas = useCatalog(useMemo(() => canUseDatabase && datasourceId && database && schemaScoped
    ? () => dataSourceCatalogApi.listSchemas(String(datasourceId), database)
    : undefined, [canUseDatabase, datasourceId, database, schemaScoped]));
  const canUseSchema = !schemaScoped || (!schemas.loading && !schemas.error
    && (schemas.items.length === 0 || optionsOf(schemas.items).some((item) => item.value === schemaName)));
  const tables = useCatalog(useMemo(() => canUseDatabase && canUseSchema && datasourceId && database
    ? () => dataSourceCatalogApi.listTablesByDatabase(String(datasourceId), database, schemaName)
    : undefined, [canUseDatabase, canUseSchema, datasourceId, database, schemaName]));
  const tableOptions = useMemo(() => optionsOf(tables.items), [tables.items]);

  useEffect(() => {
    if (!tableOptions.length) return;
    setRows((current) => {
      const occupied = new Set(current.flatMap((row) => row.targetTable ? [row.targetTable] : []));
      let changed = false;
      const next = current.map((row) => {
        if (row.targetTable) return row;
        const matches = tableOptions.filter(({ value }) => similarName(value.split('.').pop() || value, bareName(row.fileName)));
        if (matches.length !== 1 || occupied.has(matches[0].value)) return row;
        occupied.add(matches[0].value);
        changed = true;
        return { ...row, targetTable: matches[0].value };
      });
      return changed ? next : current;
    });
  }, [tableOptions, rows.length]);

  // A saved mapping is keyed by CSV and target table. Changing either namespace or table
  // invalidates that row's fields and any earlier catalog response.
  const selectedTables = JSON.stringify([...new Set(rows.flatMap((row) => row.targetTable ? [row.targetTable] : []))].sort());
  const columns = useCatalog(useMemo(() => {
    const names: string[] = JSON.parse(selectedTables);
    if (!names.length || !canUseDatabase || !canUseSchema || !datasourceId || !database) return undefined;
    return async () => {
      const results = await Promise.all(names.map(async (table) => {
        const response = await dataSourceCatalogApi.listColumn(String(datasourceId), {
          read_mode: 'table', table_path: table, database, schema_name: schemaName, query: '',
        }).catch(() => undefined);
        return {
          table,
          columns: response?.code === 0 && Array.isArray(response.data) ? response.data as Column[] : [],
          error: response?.code === 0 ? undefined : response?.message || '读取目标字段失败',
        };
      }));
      return { code: 0, data: results };
    };
  }, [canUseDatabase, canUseSchema, datasourceId, database, schemaName, selectedTables]));
  const metadataByTable = new Map<string, { columns: Column[]; error?: string }>(
    columns.items.map((item) => [item.table, item]),
  );
  const targetsFor = (row: CsvMapping) => metadataByTable.get(row.targetTable || '')?.columns || [];

  const updateTarget = (patch: Partial<Target>) => {
    setTarget((current) => ({ ...current, ...patch }));
    setRows((current) => current.map((row) => ({ ...row, targetTable: undefined, fieldMapping: undefined })));
  };
  const updateRow = (fileName: string, patch: Partial<CsvMapping>) =>
    setRows((current) => current.map((row) => row.fileName === fileName ? { ...row, ...patch } : row));

  const acceptFiles = ({ files, ...identity }: DatapackSelection) => {
    setRows((current) => files.map((file) => {
      const previous = datapack?.packageFingerprint === identity.packageFingerprint
        ? current.find((row) => row.fileName === file.fileName)
        : undefined;
      return {
        ...previous,
        fileName: file.fileName,
        fileResourceId: file.fileResource.id,
        fields: file.schema,
        nullValue: file.nullValue,
      };
    }));
    setDatapack(identity);
    setSelecting(false);
  };

  const rowIssue = (row: CsvMapping) => {
    if (!row.targetTable) return '请选择目标表';
    if (rows.some((other) => other.fileName !== row.fileName && other.targetTable === row.targetTable)) return '目标表重复';
    if (!tableOptions.some(({ value }) => value === row.targetTable)) return '目标表不在当前目录';
    const metadata = metadataByTable.get(row.targetTable);
    if (metadata?.error) return metadata.error;
    if (!metadata?.columns.length) return '等待目标字段';
    const fields = Object.entries(mappingFor(row, metadata.columns));
    if (!fields.length) return '请至少映射一个字段';
    if (new Set(fields.map((entry) => entry[1])).size !== fields.length) return '目标字段重复';
    if (fields.some(([from, to]) => !row.fields[from] || !metadata.columns.some(
      (column) => column.fieldName === to && compatible(row.fields[from], column.fieldType),
    ))) return '字段不存在或类型不兼容';
    return undefined;
  };

  const submit = async (action: 'preview' | 'save') => {
    if (!datapack || !rows.length || !canUseDatabase || !canUseSchema) {
      message.warning('请选择 CSV 和目标数据库');
      return;
    }
    const incomplete = rows.find((row) => rowIssue(row));
    if (incomplete) {
      message.warning(`${incomplete.fileName} · ${rowIssue(incomplete)}`);
      return;
    }
    setBusy(action);
    const payload = {
      id: params.id,
      basic: { ...(params.basic || {
        jobName: params.jobName, jobDesc: params.jobDesc, clientId: params.clientId,
      }), mode: 'GUIDE_MULTI', runtimeType: 'BATCH' },
      content: { target, datapack: {
        packageResourceId: datapack.packageResourceId,
        packageName: datapack.packageName,
        packageFingerprint: datapack.packageFingerprint,
        mappings: rows.map((row) => ({ ...row, fieldMapping: mappingFor(row, targetsFor(row)) })),
      } },
      schedule: buildSchedulePayload(mergeScheduleConfig(params.schedule), false),
      env: mergeEnvConfig(params.env),
    };
    try {
      if (action === 'preview') {
        const response = await seatunnelJobDefinitionApi.buildGuideMultiConfig(payload);
        if (response.code !== 0) throw new Error(response.message || '预览失败');
        setHocon(response.data);
      } else {
        const response = await seatunnelJobDefinitionApi.saveOrUpdateGuideMulti(payload);
        if (response.code !== 0) throw new Error(response.message || response.msg || '保存配置失败');
        message.success('配置已保存，请返回任务列表上线并运行');
        goBack();
      }
    } catch (error: any) {
      message.error(error?.message || '操作失败');
    } finally {
      setBusy(undefined);
    }
  };

  const preview = async (row: CsvMapping) => {
    setPreviewId(row.fileResourceId);
    try {
      const response = await fileResourceApi.preview(row.fileResourceId, {
        fileFormatType: 'csv', encoding: 'UTF-8', fieldDelimiter: ',', limit: 20,
      });
      if (response.code !== 0) throw new Error(response.message || '数据预览失败');
      const result = response.data as { columns: string[]; rows: Record<string, unknown>[] };
      setSample({ fileName: row.fileName, ...result });
    } catch (error: any) {
      message.error(error?.message || '数据预览失败');
    } finally {
      setPreviewId(undefined);
    }
  };

  if (!datapack) {
    return (
      <div className="mx-auto w-full max-w-[1240px] p-6">
        <DatapackCsvImportStep multiple onImported={acceptFiles} onBack={goBack} />
      </div>
    );
  }

  const error = datasources.error || databases.error || schemas.error || tables.error;
  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-auto p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Space wrap>
          <Button onClick={goBack}>返回任务列表</Button>
          <span className="text-sm">{datapack.packageName} · 已选 {rows.length} 个 CSV</span>
          <Button onClick={() => setSelecting(true)} disabled={!!busy}>调整 CSV</Button>
        </Space>
        <Space>
          <Button loading={busy === 'preview'} disabled={!!busy} onClick={() => void submit('preview')}>预览配置</Button>
          <Button type="primary" loading={busy === 'save'} disabled={!!busy} onClick={() => void submit('save')}>
            保存配置
          </Button>
        </Space>
      </div>
      <div className="grid grid-cols-3 gap-4 rounded-xl border p-4">
        <label className="text-sm">
          <span className="mb-2 block">目标数据源</span>
          <Select className="w-full" aria-label="目标数据源" value={datasourceId ? String(datasourceId) : undefined}
            loading={datasources.loading} disabled={!!busy} options={datasourceOptions} showSearch optionFilterProp="label"
            onChange={(value) => updateTarget({ datasourceId: value, database: undefined, schemaName: undefined })}
          />
        </label>
        <label className="text-sm">
          <span className="mb-2 block">目标数据库</span>
          <Select className="w-full" aria-label="目标数据库" value={database}
            loading={databases.loading} disabled={!canUseDatasource || !!busy} options={optionsOf(databases.items)}
            showSearch optionFilterProp="label"
            onChange={(value) => updateTarget({ database: value, schemaName: undefined })}
          />
        </label>
        {schemaScoped && (
          <label className="text-sm">
            <span className="mb-2 block">目标 Schema</span>
            <Select className="w-full" aria-label="目标 Schema" value={schemaName}
              loading={schemas.loading} disabled={!canUseDatabase || !!busy} options={optionsOf(schemas.items)}
              showSearch optionFilterProp="label"
              onChange={(value) => updateTarget({ schemaName: value })}
            />
          </label>
        )}
      </div>
      {error && <Alert type="error" showIcon message={error} />}
      <section className="min-h-0 flex-1 rounded-xl border p-4">
        <Collapse
          items={rows.map((row) => {
            const fieldOptions = targetsFor(row);
            const mapping = mappingFor(row, fieldOptions);
            return {
              key: row.fileName,
              label: <span>{row.fileName} · {rowIssue(row) || '映射已就绪'}</span>,
              extra: (
                <Space onClick={(event) => event.stopPropagation()}>
                  <Select
                    aria-label={`${row.fileName} 目标表`}
                    className="w-56"
                    placeholder="请选择目标表"
                    value={row.targetTable}
                    options={tableOptions}
                    loading={tables.loading}
                    disabled={!canUseDatabase || !canUseSchema || !!busy}
                    showSearch
                    optionFilterProp="label"
                    onChange={(value) => updateRow(row.fileName, { targetTable: value, fieldMapping: undefined })}
                  />
                  <Button loading={previewId === row.fileResourceId} disabled={!!previewId} onClick={() => void preview(row)}>
                    数据预览
                  </Button>
                </Space>
              ),
              children: (
                <Table
                  size="small"
                  pagination={false}
                  rowKey="name"
                  dataSource={Object.entries(row.fields).map(([name, type]) => ({ name, type }))}
                  columns={[
                    { title: 'CSV 字段', dataIndex: 'name' },
                    { title: '字段类型', dataIndex: 'type' },
                    {
                      title: '目标字段',
                      render: (_, field) => (
                        <Select
                          aria-label={`${row.fileName} 字段 ${field.name}`}
                          className="w-full"
                          value={mapping[field.name]}
                          disabled={!fieldOptions.length || !!busy}
                          loading={columns.loading}
                          placeholder="未映射"
                          allowClear
                          showSearch
                          optionFilterProp="label"
                          options={fieldOptions.map((column) => ({
                            value: column.fieldName,
                            label: `${column.fieldName}（${column.fieldType}）`,
                            disabled: !compatible(field.type, column.fieldType),
                          }))}
                          onChange={(value) => {
                            const next = { ...mapping };
                            if (value) next[field.name] = value;
                            else delete next[field.name];
                            updateRow(row.fileName, { fieldMapping: next });
                          }}
                        />
                      ),
                    },
                  ]}
                />
              ),
            };
          })}
        />
      </section>
      {selecting && (
        <Modal open title="调整包内 CSV" footer={null} width={1120} onCancel={() => setSelecting(false)}>
          <DatapackCsvImportStep
            multiple
            initialPackage={{ id: datapack.packageResourceId, name: datapack.packageName, kind: 'FILE' }}
            preferredFileNames={rows.map((row) => row.fileName)}
            onImported={acceptFiles}
            onBack={() => setSelecting(false)}
          />
        </Modal>
      )}
      <Modal open={hocon !== undefined} title="任务配置预览" footer={null} width={960} onCancel={() => setHocon(undefined)}>
        {hocon !== undefined && <CodeBlockWithCopy content={hocon} height={500} />}
      </Modal>
      <Modal open={!!sample} title={sample?.fileName} footer={null} width={960} onCancel={() => setSample(undefined)}>
        <Table
          size="small"
          pagination={false}
          columns={(sample?.columns || []).map((name) => ({ title: name, dataIndex: name, key: name }))}
          dataSource={(sample?.rows || []).map((row, index) => ({ ...row, key: index }))}
          scroll={{ x: 'max-content', y: 360 }}
        />
      </Modal>
    </div>
  );
};

export default DatapackMultiConfig;
