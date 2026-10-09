import { Alert, App, Button, Divider, Segmented, Select, Tooltip } from 'antd';
import { BarChart3, ChevronDown, Database, Eye, FileCode2, Table2 } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import QualityDetail from '@/pages/batch-link-up/DataViewSQL';
import FileResourcePicker from '@/pages/file-resources/components/FileResourcePicker';
import {
  inspectDuckDbFileResource,
  previewDuckDbFileResource,
} from '@/pages/file-resources/service';
import type {
  DuckDbCatalog,
  DuckDbPreviewRequest,
  FileResourceEntry,
} from '@/pages/file-resources/types';
import SqlEditorSection from './SqlEditorSection';
import PanelShell from '../PanelShell';
import './DuckDbFileSourcePanel.less';

interface DuckDbFileSourcePanelProps {
  sourceConfig: Record<string, any>;
  onChange: (patch: Record<string, any>) => void;
  onClose: () => void;
}

const resourceName = (resource?: FileResourceEntry) =>
  String(resource?.name || resource?.logicalPath || resource?.path || 'DuckDB 文件');

const resourcePath = (resource?: FileResourceEntry) =>
  String(resource?.logicalPath || resource?.path || resource?.objectKey || '').replace(/^\/+/, '');

const databaseName = (catalog?: DuckDbCatalog, resource?: FileResourceEntry) => {
  if (catalog?.databaseName) return catalog.databaseName;
  return resourceName(resource).replace(/\.(db|duckdb)$/i, '');
};

const quoteIdentifier = (value: string) => `"${value.replace(/"/g, '""')}"`;

const DuckDbFileSourcePanel: React.FC<DuckDbFileSourcePanelProps> = ({
  sourceConfig,
  onChange,
  onClose,
}) => {
  const { message } = App.useApp();
  const qualityDetailRef = useRef<any>(null);
  const inspectSequence = useRef(0);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [catalog, setCatalog] = useState<DuckDbCatalog>();
  const [catalogError, setCatalogError] = useState('');
  const [catalogLoading, setCatalogLoading] = useState(false);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [resolveLoading, setResolveLoading] = useState(false);
  const [sqlPopoverOpen, setSqlPopoverOpen] = useState(false);
  const [selectedSqlTable, setSelectedSqlTable] = useState<string>();
  const resource = (sourceConfig.fileResource || sourceConfig.resource) as FileResourceEntry | undefined;
  const selectedResourceId = resource?.id ?? sourceConfig.fileResourceId;
  const readMode = String(sourceConfig.readMode || 'table') === 'sql' ? 'sql' : 'table';
  const configuredTable = String(sourceConfig.duckdbTable || sourceConfig.table || '');
  const savedSchema = String(sourceConfig.duckdbSchema || sourceConfig.schemaName || '');
  const sql = String(sourceConfig.sql || '');

  const readCatalog = useCallback(async (resourceId: string | number) => {
    const sequence = ++inspectSequence.current;
    setCatalogLoading(true);
    setCatalogError('');
    try {
      const result = await inspectDuckDbFileResource(resourceId);
      if (sequence === inspectSequence.current) setCatalog(result);
    } catch (error: any) {
      if (sequence === inspectSequence.current) {
        setCatalog(undefined);
        setCatalogError(error?.message || 'DuckDB 数据库结构读取失败');
      }
    } finally {
      if (sequence === inspectSequence.current) setCatalogLoading(false);
    }
  }, []);

  useEffect(() => {
    inspectSequence.current += 1;
    setCatalog(undefined);
    setCatalogError('');
    setSelectedSqlTable(undefined);
    if (selectedResourceId !== undefined && selectedResourceId !== null && String(selectedResourceId)) {
      void readCatalog(selectedResourceId);
    } else {
      setCatalogLoading(false);
    }
    return () => {
      inspectSequence.current += 1;
    };
  }, [selectedResourceId, readCatalog]);

  const activeSchema = useMemo(() => {
    if (!catalog?.schemas.length) return undefined;
    return catalog.schemas.find((schema) => schema.name === savedSchema)
      || catalog.schemas.find((schema) => schema.name === 'main')
      || catalog.schemas[0];
  }, [catalog, savedSchema]);

  const selectedTableName = !sourceConfig.duckdbSchema && configuredTable.includes('.')
    ? configuredTable.slice(configuredTable.indexOf('.') + 1)
    : configuredTable;
  const selectedTable = activeSchema?.tables.find((table) => table.name === selectedTableName);
  const tableOptions = useMemo(
    () => (activeSchema?.tables || []).map((table) => ({
      label: table.name,
      value: table.name,
      rawLabel: table.name,
      description: table.type === 'VIEW' ? '视图' : '表',
    })),
    [activeSchema],
  );

  const handleSelectResource = (next: FileResourceEntry) => {
    const sameResource = String(next.id) === String(selectedResourceId);
    setPickerOpen(false);
    if (!sameResource) setCatalog(undefined);
    setCatalogError('');
    setSelectedSqlTable(undefined);
    onChange({
      sourceMode: 'FILE_RESOURCE',
      fileFormatType: 'duckdb',
      dbType: 'MINIO',
      connectorType: 'DuckDB',
      pluginName: 'DuckDB',
      fileResource: next,
      fileResourceId: next.id === undefined ? '' : String(next.id),
      database: '',
      duckdbSchema: '',
      schemaName: '',
      duckdbTable: '',
      table: '',
      tableNames: [],
      readMode: 'table',
      sql: '',
      schema: { fields: {} },
      outputSchema: [],
      schemaStatus: 'idle',
      schemaError: '',
    });
  };

  const handleSchemaChange = (schemaName: string) => {
    setSelectedSqlTable(undefined);
    onChange({
      duckdbSchema: schemaName,
      schemaName,
      duckdbTable: '',
      table: '',
      tableNames: [],
      outputSchema: [],
      schemaStatus: 'idle',
      schemaError: '',
    });
  };

  const handleTableChange = (tableName: string) => {
    onChange({
      duckdbSchema: activeSchema?.name || savedSchema || 'main',
      schemaName: activeSchema?.name || savedSchema || 'main',
      duckdbTable: tableName,
      table: tableName,
      tableNames: [tableName],
      outputSchema: [],
      schemaStatus: 'idle',
      schemaError: '',
    });
  };

  const handleReadModeChange = (value: string) => {
    setSelectedSqlTable(undefined);
    onChange({
      readMode: value,
      ...(value === 'table' ? { sql: '' } : { duckdbTable: '', table: '', tableNames: [] }),
      outputSchema: [],
      schemaStatus: 'idle',
      schemaError: '',
    });
  };

  const getPreviewRequest = (limit: number): DuckDbPreviewRequest | undefined => {
    if (selectedResourceId === undefined || selectedResourceId === null || !String(selectedResourceId)) {
      message.warning('请先选择 DuckDB 数据库文件');
      return undefined;
    }
    if (readMode === 'table' && !selectedTableName) {
      message.warning('请选择来源表');
      return undefined;
    }
    if (readMode === 'sql' && !sql.trim()) {
      message.warning('请输入 SQL');
      return undefined;
    }
    return {
      readMode,
      schemaName: activeSchema?.name || savedSchema || 'main',
      ...(readMode === 'table' ? { tableName: selectedTableName } : { query: sql }),
      limit,
    };
  };

  const handlePreview = async () => {
    const request = getPreviewRequest(20);
    if (!request || selectedResourceId === undefined || selectedResourceId === null) return;
    setPreviewLoading(true);
    try {
      const result = await previewDuckDbFileResource(selectedResourceId, request);
      const columns = result.columns.map((column) => ({
        title: column.name,
        dataIndex: column.key,
        key: column.key,
        ellipsis: true,
        render: (value: unknown) => value == null
          ? '-'
          : typeof value === 'object'
            ? JSON.stringify(value)
            : String(value),
      }));
      qualityDetailRef.current?.onOpen(true, {
        data: { columns, data: result.data, total: result.total },
      });
    } catch (error: any) {
      message.error(error?.message || 'DuckDB 数据预览失败');
    } finally {
      setPreviewLoading(false);
    }
  };

  const handleResolveColumns = async () => {
    const request = getPreviewRequest(0);
    if (!request || selectedResourceId === undefined || selectedResourceId === null) return;
    setResolveLoading(true);
    onChange({ schemaStatus: 'loading', schemaError: '' });
    try {
      const result = await previewDuckDbFileResource(selectedResourceId, request);
      const outputSchema = result.columns.map((column) => ({
        name: column.name,
        fieldName: column.name,
        originFieldName: column.name,
        type: column.type,
        nullable: column.nullable,
      }));
      onChange({
        outputSchema,
        schemaStatus: 'success',
        schemaError: '',
      });
      message.success(`已解析 ${outputSchema.length} 个字段`);
    } catch (error: any) {
      onChange({
        outputSchema: [],
        schemaStatus: 'error',
        schemaError: error?.message || '字段解析失败',
      });
      message.error(error?.message || 'DuckDB 字段解析失败');
    } finally {
      setResolveLoading(false);
    }
  };

  const handleGenerateSql = () => {
    const selected = activeSchema?.tables.find((table) => table.name === selectedSqlTable);
    if (!selected) return;
    onChange({
      sql: `SELECT * FROM ${quoteIdentifier(selected.name)}`,
      outputSchema: [],
      schemaStatus: 'idle',
      schemaError: '',
    });
    setSqlPopoverOpen(false);
  };

  const handleChangeSql = (value: string) => {
    onChange({
      sql: value,
      outputSchema: [],
      schemaStatus: 'idle',
      schemaError: '',
    });
  };

  const database = databaseName(catalog, resource);

  return (
    <>
      <PanelShell
        eyebrow="Source Config"
        title="来源配置"
        badge="输入节点"
        desc="修改后会实时同步到当前画布节点"
        heroTitle="DuckDB 数据源"
        heroDesc={resource ? 'MinIO 文件资源' : '选择 DuckDB 数据库文件'}
        heroTag="SOURCE"
        dbType="DUCKDB"
        onClose={onClose}
        footer={(
          <button
            type="button"
            className="workflow-panel__btn workflow-panel__btn--ghost"
            onClick={onClose}
          >
            关闭
          </button>
        )}
      >
        <section className="workflow-panel__section">
          <div className="workflow-panel__group">
            <div className="workflow-panel__group-head">
              <div className="workflow-panel__group-kicker">数据源</div>
            </div>
            <div className="workflow-panel__meta-card workflow-panel__meta-card--compact duckdb-source__resource-card">
              <div className="workflow-panel__meta-icon">
                <Database size={16} />
              </div>
              <button
                type="button"
                className="duckdb-source__resource-trigger"
                onClick={() => setPickerOpen(true)}
                aria-label={resource ? '更换 DuckDB 数据库文件' : '选择 DuckDB 数据库文件'}
              >
                <span className="duckdb-source__resource-name">
                  {resource ? resourceName(resource) : '选择 DuckDB 数据库文件'}
                </span>
                <ChevronDown size={15} />
              </button>
            </div>
            {resource ? (
              <div className="duckdb-source__resource-path" title={resourcePath(resource)}>
                {resourcePath(resource)}
              </div>
            ) : null}
          </div>

          <div className="workflow-panel__divider" />

          <div className="workflow-panel__group">
            <div className="workflow-panel__group-head">
              <div className="workflow-panel__group-kicker">数据库</div>
            </div>
            <Select
              value={database || undefined}
              options={database ? [{ label: database, value: database }] : []}
              loading={catalogLoading}
              placeholder="选择 DuckDB 文件后读取数据库"
              className="workflow-panel__antd-select"
              style={{ width: '100%' }}
              classNames={{ popup: { root: 'workflow-panel__dropdown' } }}
              disabled={!catalog}
            />
            <div className="mt-3 mb-1 text-xs text-slate-500">Schema</div>
            <Select
              value={activeSchema?.name || undefined}
              onChange={handleSchemaChange}
              options={(catalog?.schemas || []).map((schema) => ({
                label: schema.name,
                value: schema.name,
              }))}
              loading={catalogLoading}
              placeholder="请选择 Schema"
              className="workflow-panel__antd-select"
              style={{ width: '100%' }}
              classNames={{ popup: { root: 'workflow-panel__dropdown' } }}
              showSearch
              optionFilterProp="label"
              disabled={!catalog || catalog.schemas.length === 0}
            />
            <div className="mt-1 text-xs leading-5 text-slate-500">
              选择数据库和 Schema 后，再选择对应范围内的表。
            </div>
            {catalogError ? (
              <Alert
                type="error"
                showIcon
                message="数据库结构读取失败"
                description={catalogError}
                className="duckdb-source__error"
              />
            ) : null}
          </div>

          <div className="workflow-panel__divider" />

          <div className="workflow-panel__group">
            <div className="workflow-panel__group-head duckdb-source__read-head">
              <div className="workflow-panel__group-kicker">读取方式</div>
              <div className="duckdb-source__read-actions">
                <Tooltip title="预览读取结果样例数据">
                  <Button
                    size="small"
                    type="text"
                    icon={<Eye size={14} />}
                    onClick={handlePreview}
                    loading={previewLoading}
                    disabled={!catalog}
                  >
                    预览
                  </Button>
                </Tooltip>
                <Divider type="vertical" style={{ padding: 0, margin: '0 4px' }} />
                <Tooltip title="解析当前读取配置下的字段信息">
                  <Button
                    size="small"
                    type="text"
                    icon={<BarChart3 size={14} />}
                    onClick={handleResolveColumns}
                    loading={resolveLoading}
                    disabled={!catalog}
                  >
                    字段解析
                  </Button>
                </Tooltip>
              </div>
            </div>

            <Segmented
              block
              value={readMode}
              onChange={(value) => handleReadModeChange(String(value))}
              options={[
                {
                  label: (
                    <div className="workflow-panel__segmented-item">
                      <Table2 size={14} />
                      <span>按表读取</span>
                    </div>
                  ),
                  value: 'table',
                },
                {
                  label: (
                    <div className="workflow-panel__segmented-item">
                      <FileCode2 size={14} />
                      <span>自定义 SQL</span>
                    </div>
                  ),
                  value: 'sql',
                },
              ]}
            />

            {readMode === 'table' ? (
              <>
                <Select
                  value={selectedTableName || undefined}
                  onChange={handleTableChange}
                  options={tableOptions}
                  loading={catalogLoading}
                  placeholder="请选择来源表"
                  className="workflow-panel__antd-select"
                  style={{ width: '100%' }}
                  classNames={{ popup: { root: 'workflow-panel__dropdown' } }}
                  showSearch
                  optionFilterProp="rawLabel"
                  disabled={!activeSchema || activeSchema.tables.length === 0}
                />
                {selectedTable ? (
                  <div className="duckdb-source__table-meta">
                    {activeSchema?.name}.{selectedTable.name} · {selectedTable.columns.length} 列
                  </div>
                ) : null}
              </>
            ) : (
              <SqlEditorSection
                sourceDataSourceId={selectedResourceId ? String(selectedResourceId) : undefined}
                dbType="DUCKDB"
                sql={sql}
                tableOptions={tableOptions}
                sqlPopoverOpen={sqlPopoverOpen}
                setSqlPopoverOpen={setSqlPopoverOpen}
                resolvePopoverOpen={false}
                selectedSqlTable={selectedSqlTable}
                setSelectedSqlTable={setSelectedSqlTable}
                generateSqlLoading={false}
                resolveSqlLoading={false}
                resolvedSqlPreview=""
                onSqlChange={handleChangeSql}
                onGenerateSql={handleGenerateSql}
                onResolveSqlPreview={() => undefined}
                onOpenResolvePopover={() => undefined}
                showResolvePreview={false}
              />
            )}
          </div>

        </section>
      </PanelShell>

      <QualityDetail ref={qualityDetailRef} />
      <FileResourcePicker
        open={pickerOpen}
        onCancel={() => setPickerOpen(false)}
        onSelect={handleSelectResource}
        value={resource || null}
        allowedFormats={['duckdb']}
        title="选择 DuckDB 数据库文件"
      />
    </>
  );
};

export default DuckDbFileSourcePanel;
