import {
  DeleteOutlined,
  EyeOutlined,
  FileSearchOutlined,
  InfoCircleOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Collapse,
  Input,
  InputNumber,
  Modal,
  Select,
  Switch,
  Table,
  Tooltip,
} from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import FileResourceSourceCard from './FileResourceSourceCard';
import { buildFileResourcePreviewOptions, fileResourceApi } from './api';
import {
  defaultExcelHeaderSkipOnSelection,
  isExcelHeaderEnabled,
} from './config/fileHeaderDefaults';
import { isDatabaseFileFormat, resolveFormatFromFileName } from './config/fileFormat';
import { inferTypeFromSamples } from './config/fileTypeInference';
import type { FileFormat, FileResource } from './types';
import {
  buildOutputSchema,
  getSchemaFields,
  hasSchemaFields,
  localFileExtensionMatches,
} from '@/pages/batch-link-up/workflow/panel/components/SourcePanel/localFile';

const FORMAT_OPTIONS: Array<{ value: FileFormat; label: string }> = [
  { value: 'csv', label: 'CSV' },
  { value: 'excel', label: 'Excel' },
  { value: 'json', label: 'JSON' },
  { value: 'text', label: 'TEXT / TXT' },
  { value: 'duckdb', label: 'DuckDB（.duckdb）' },
  { value: 'sqlite', label: 'SQLite（.db / .sqlite / .sqlite3）' },
];

const PICKER_ALLOWED_FORMATS: FileFormat[] = ['csv', 'excel', 'json', 'text', 'duckdb', 'sqlite'];

const databaseFileLabel = (format: FileFormat) => format === 'sqlite' ? 'SQLite' : 'DuckDB';

const TYPE_OPTIONS = [
  'string',
  'boolean',
  'byte',
  'short',
  'int',
  'long',
  'float',
  'double',
  'decimal',
  'date',
  'timestamp',
  'time',
].map((value) => ({ value, label: value }));

// 固定预览列宽，字段较多时让表格按总列宽提供横向滚动。
const PREVIEW_COLUMN_WIDTH = 160;
const PREVIEW_TABLE_MIN_WIDTH = 700;

const ConfigHint: React.FC<{ label: string; title: string }> = ({ label, title }) => (
  <Tooltip title={title} trigger={['hover', 'focus']}>
    <Button
      type="text"
      size="small"
      shape="circle"
      aria-label={label}
      icon={<InfoCircleOutlined />}
      className="!h-6 !w-6 !text-slate-400"
    />
  </Tooltip>
);

interface FileSourceConfigPanelProps {
  sourceConfig: Record<string, any>;
  onChange: (patch: Record<string, any>) => void;
  onPreview?: (resource: FileResource, options: Record<string, any>) => Promise<unknown>;
}

interface SchemaFieldNameInputProps {
  name: string;
  existingNames: string[];
  onCommit: (oldName: string, nextName: string) => void;
}

const SchemaFieldNameInput: React.FC<SchemaFieldNameInputProps> = ({
  name,
  existingNames,
  onCommit,
}) => {
  const [value, setValue] = useState(name);

  useEffect(() => {
    setValue(name);
  }, [name]);

  const commit = () => {
    const nextName = value.trim();
    if (!nextName || (nextName !== name && existingNames.includes(nextName))) {
      setValue(name);
      return;
    }
    onCommit(name, nextName);
  };

  return (
    <Input
      value={value}
      aria-label={`字段名 ${name}`}
      onChange={(event) => setValue(event.target.value)}
      onBlur={commit}
      onPressEnter={(event) => {
        event.currentTarget.blur();
      }}
    />
  );
};

const getFormat = (value: unknown): FileFormat => {
  const normalized = String(value || '').toLowerCase();
  return FORMAT_OPTIONS.some((option) => option.value === normalized)
    ? normalized as FileFormat
    : 'csv';
};

const resourceMatchesFormat = (name: string, format: FileFormat) => {
  const extension = String(name || '').toLowerCase().split('.').pop() || '';
  if (format === 'duckdb') return extension === 'db' || extension === 'duckdb';
  if (format === 'sqlite') return extension === 'db' || extension === 'sqlite' || extension === 'sqlite3';
  return localFileExtensionMatches(name, format);
};

const sourcePluginForFormat = (format: FileFormat) =>
  isDatabaseFileFormat(format)
    ? { dbType: 'MINIO', connectorType: 'DuckDB', pluginName: 'DuckDB' }
    : { dbType: 'MINIO', connectorType: 'S3File', pluginName: 'S3File' };

/** Decode the delimiter as the engine would ('\\001' -> \x01, '\\t' -> tab). */
const decodeEscapeSequence = (value: unknown): string => {
  const raw = String(value ?? '');
  if (/^\\[0-7]{1,3}$/.test(raw)) {
    return String.fromCharCode(parseInt(raw.slice(1), 8));
  }
  return raw.replace(/\\t/g, '\t').replace(/\\n/g, '\n');
};

/**
 * Build the schema fields from a preview response. Column names come from the
 * file itself (header row / JSON keys); text files have no names, so they fall
 * back to positional field_N columns split by the configured delimiter.
 */
const deriveFieldsFromPreview = (
  data: any,
  format: FileFormat,
  hasHeader: boolean,
  fieldDelimiter: unknown,
): Record<string, string> => {
  const columns: string[] = Array.isArray(data?.columns)
    ? data.columns.map((column: any) => String(column))
    : [];
  const rows: any[] = Array.isArray(data?.rows) ? data.rows : [];

  if (format === 'text') {
    const delimiter = decodeEscapeSequence(fieldDelimiter || '\t') || '\t';
    const lines = rows.map((row) => String(Array.isArray(row) ? row[0] : row?.value ?? ''));
    const width = Math.max(
      1,
      ...lines.slice(0, 5).map((line) => line.split(delimiter).length),
    );
    const fields: Record<string, string> = {};
    for (let index = 0; index < width; index += 1) {
      fields[`field_${index + 1}`] = inferTypeFromSamples(
        lines.slice(0, 20).map((line) => line.split(delimiter)[index]),
        format,
      );
    }
    return fields;
  }

  const namedColumns = hasHeader && columns.length > 0;
  const fields: Record<string, string> = {};
  columns.forEach((column, index) => {
    let fieldName = namedColumns && column.trim() ? column.trim() : `field_${index + 1}`;
    let suffix = 2;
    while (fields[fieldName]) {
      fieldName = `${namedColumns && column.trim() ? column.trim() : `field_${index + 1}`}_${suffix}`;
      suffix += 1;
    }
    // 预览行按后端返回的列名键控（关闭表头时列名仅用于取值，不作为字段名）。
    fields[fieldName] = inferTypeFromSamples(
      rows.map((row) => (Array.isArray(row) ? row[index] : row?.[column])),
      format,
    );
  });
  return fields;
};

const FileSourceConfigPanel: React.FC<FileSourceConfigPanelProps> = ({
  sourceConfig,
  onChange,
  onPreview,
}) => {
  const { message } = App.useApp();
  const format = getFormat(sourceConfig?.fileFormatType);
  const isDatabaseFile = isDatabaseFileFormat(format);
  const formatLocked = sourceConfig?.formatLocked === true;
  const formatLabel = FORMAT_OPTIONS.find((option) => option.value === format)?.label || format;
  const databaseTypeLabel = databaseFileLabel(format);
  const resource = (sourceConfig?.fileResource || sourceConfig?.resource) as FileResource | undefined;
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewContent, setPreviewContent] = useState<unknown>();
  const [recognizing, setRecognizing] = useState(false);
  const recognizeSeqRef = useRef(0);

  const fields = useMemo(() => getSchemaFields(sourceConfig?.schema), [sourceConfig?.schema]);
  const previewRows = useMemo(() => {
    const rows = (previewContent as any)?.rows;
    if (!Array.isArray(rows)) return [];
    return rows.map((row: Record<string, unknown>, index: number) => {
      const previewRow = row && typeof row === 'object' ? { ...row } : { value: row };
      Object.defineProperty(previewRow, '__seatunnelPreviewRowKey', {
        value: `file-preview-${index}`,
        enumerable: false,
      });
      return previewRow;
    });
  }, [previewContent]);
  const previewColumns = useMemo(() => {
    const columns = (previewContent as any)?.columns;
    if (!Array.isArray(columns)) return [];
    return columns.map((column: string) => ({
      title: column,
      dataIndex: column,
      key: column,
      width: PREVIEW_COLUMN_WIDTH,
      ellipsis: true,
    }));
  }, [previewContent]);
  // Excel uses the generic FileBase skip_header_row_number option; absent legacy values mean 0.
  const hasHeader = format === 'excel'
    ? isExcelHeaderEnabled(sourceConfig?.skipHeaderRowNumber)
    : sourceConfig?.csvUseHeaderLine !== false && sourceConfig?.skipHeader !== false;

  const updateSchema = (nextFields: Record<string, string>) => {
    const schema = { fields: nextFields };
    onChange({
      schema,
      outputSchema: buildOutputSchema(schema),
      schemaStatus: hasSchemaFields(schema) ? 'success' : 'idle',
      schemaError: '',
    });
  };

  const changeFormat = (nextFormat: FileFormat) => {
    if (resource && !resourceMatchesFormat(resource.name || resource.path || '', nextFormat)) {
      message.warning('切换格式前请先选择后缀匹配的文件资源');
      return;
    }
    // An explicit format change is a new-input action; old loaded Excel configs do not take this default.
    const excelHeaderDefault = nextFormat === 'excel' && format !== 'excel'
      ? { skipHeaderRowNumber: defaultExcelHeaderSkipOnSelection(sourceConfig?.skipHeaderRowNumber) }
      : {};
    const clearDatabaseSelection = nextFormat !== format
      && (isDatabaseFileFormat(nextFormat) || isDatabaseFileFormat(format))
      ? {
          duckdbSchema: '',
          sqliteSchema: '',
          schemaName: '',
          duckdbTable: '',
          sqliteTable: '',
          table: '',
          tableNames: [],
        }
      : {};
    onChange({
      ...clearDatabaseSelection,
      ...excelHeaderDefault,
      fileFormatType: nextFormat,
      sourceMode: 'FILE_RESOURCE',
      ...sourcePluginForFormat(nextFormat),
    });
  };

  const addField = () => {
    let index = Object.keys(fields).length + 1;
    let name = `field_${index}`;
    while (fields[name]) {
      index += 1;
      name = `field_${index}`;
    }
    updateSchema({ ...fields, [name]: 'string' });
  };

  const renameField = (oldName: string, nextName: string) => {
    const normalized = nextName.trim();
    if (!normalized || normalized === oldName || fields[normalized]) return;
    const nextFields: Record<string, string> = {};
    Object.entries(fields).forEach(([name, type]) => {
      nextFields[name === oldName ? normalized : name] = type;
    });
    updateSchema(nextFields);
  };

  const removeField = (name: string) => {
    const nextFields = { ...fields };
    delete nextFields[name];
    updateSchema(nextFields);
  };

  const recognizeFields = async (config: Record<string, any>) => {
    const targetResource = (config.fileResource || config.resource) as FileResource | undefined;
    if (!targetResource?.id) {
      message.warning('请先选择文件资源');
      return;
    }
    const nextFormat = getFormat(config.fileFormatType);
    if (isDatabaseFileFormat(nextFormat)) {
      message.info(`${databaseFileLabel(nextFormat)} 表结构由 SeaTunnel Engine 查询；请填写表名，需要时手动配置字段映射`);
      return;
    }
    const seq = (recognizeSeqRef.current += 1);
    setRecognizing(true);
    try {
      const result = onPreview
        ? await onPreview(targetResource, buildFileResourcePreviewOptions(nextFormat, config))
        : await fileResourceApi.preview(
            targetResource.id,
            buildFileResourcePreviewOptions(nextFormat, config),
          );
      if (seq !== recognizeSeqRef.current) return;
      const payload = (result as any)?.data ?? result;
      const nextFields = deriveFieldsFromPreview(
        payload,
        nextFormat,
        nextFormat === 'excel'
          ? isExcelHeaderEnabled(config.skipHeaderRowNumber)
          : config.csvUseHeaderLine !== false && config.skipHeader !== false,
        config.fieldDelimiter,
      );
      if (!Object.keys(nextFields).length) {
        message.warning('未能从文件识别出字段，请手动添加');
        return;
      }
      updateSchema(nextFields);
    } catch (error: any) {
      if (seq === recognizeSeqRef.current) {
        message.error(error?.message || '字段识别失败，请手动配置');
      }
    } finally {
      if (seq === recognizeSeqRef.current) {
        setRecognizing(false);
      }
    }
  };

  const handleSourceChange = (patch: Record<string, any>) => {
    const nextResourceId = patch.fileResourceId ? String(patch.fileResourceId) : undefined;
    const prevResourceId = resource?.id ? String(resource.id) : undefined;
    const nextResource = (patch.fileResource || (nextResourceId ? resource : undefined)) as
      | FileResource
      | undefined;
    const selectedFormat = getFormat(patch.fileFormatType ?? sourceConfig?.fileFormatType);
    const resourceChanged = Boolean(nextResourceId && nextResourceId !== prevResourceId);
    if (
      formatLocked
      && resourceChanged
      && nextResource
      && !resourceMatchesFormat(nextResource.name || nextResource.path || '', selectedFormat)
    ) {
      message.warning(`当前入口已固定为 ${formatLabel}，请选择后缀匹配的文件资源`);
      return;
    }
    const resolvedFormat = nextResourceId
      ? formatLocked
        ? selectedFormat
        : resolveFormatFromFileName(nextResource?.name || nextResource?.path, selectedFormat)
      : undefined;

    if (resolvedFormat) {
      // .db is shared by DuckDB and SQLite, so keep an explicit SQLite selection.
      const excelHeaderDefault = resolvedFormat === 'excel'
        && resourceChanged
        ? { skipHeaderRowNumber: defaultExcelHeaderSkipOnSelection(
            patch.skipHeaderRowNumber ?? sourceConfig?.skipHeaderRowNumber,
          ) }
        : {};
      const clearDatabaseSelection = resourceChanged && isDatabaseFileFormat(resolvedFormat)
        ? {
            duckdbSchema: '',
            sqliteSchema: '',
            schemaName: '',
            duckdbTable: '',
            sqliteTable: '',
            table: '',
            tableNames: [],
          }
        : {};
      patch = {
        ...patch,
        ...clearDatabaseSelection,
        ...excelHeaderDefault,
        fileFormatType: resolvedFormat,
        sourceMode: 'FILE_RESOURCE',
        ...sourcePluginForFormat(resolvedFormat),
        ...(resolvedFormat === 'csv' ? { fieldDelimiter: ',' } : {}),
        ...(resolvedFormat === 'text' ? { fieldDelimiter: '\\001' } : {}),
      };
    }

    onChange(patch);

    if (resourceChanged) {
      if (isDatabaseFileFormat(resolvedFormat || 'csv')) {
        recognizeSeqRef.current += 1;
        setRecognizing(false);
      } else {
        void recognizeFields({ ...sourceConfig, ...patch });
      }
    }
  };

  const handlePreview = async () => {
    if (!resource?.id) {
      message.warning('请先选择文件资源');
      return;
    }
    setPreviewLoading(true);
    try {
      const result = onPreview
        ? await onPreview(resource, buildFileResourcePreviewOptions(format, sourceConfig))
        : await fileResourceApi.preview(
            resource.id,
            buildFileResourcePreviewOptions(format, sourceConfig),
          );
      const payload = (result as any)?.data ?? result;
      setPreviewContent(payload);
      setPreviewOpen(true);
    } catch (error: any) {
      message.error(error?.message || '文件预览失败');
    } finally {
      setPreviewLoading(false);
    }
  };

  const advancedItems = [
    {
      key: 'parse',
      label: <span className="text-sm font-medium text-slate-800">高级配置</span>,
      children: (
        <div className="grid grid-cols-1 gap-3">
          <label className="text-sm text-slate-600">
            <span className="mb-1 block">编码</span>
            <Input
              value={sourceConfig?.encoding || 'UTF-8'}
              onChange={(event) => onChange({ encoding: event.target.value })}
              placeholder="UTF-8"
            />
          </label>
          {format === 'csv' ? (
            <>
              <label className="text-sm text-slate-600">
                <span className="mb-1 block">跳过行数</span>
                <InputNumber
                  className="w-full"
                  min={0}
                  value={sourceConfig?.skipHeaderRowNumber ?? 0}
                  onChange={(value) => onChange({ skipHeaderRowNumber: value ?? 0 })}
                />
              </label>
              <label className="text-sm text-slate-600">
                <span className="mb-1 block">引号字符</span>
                <Input
                  maxLength={1}
                  value={sourceConfig?.quoteChar ?? '"'}
                  onChange={(event) => onChange({ quoteChar: event.target.value })}
                  placeholder='"'
                />
              </label>
              <label className="text-sm text-slate-600">
                <span className="mb-1 block">转义字符</span>
                <Input
                  maxLength={1}
                  value={sourceConfig?.escapeChar ?? ''}
                  onChange={(event) => onChange({ escapeChar: event.target.value })}
                  placeholder="\\"
                />
              </label>
            </>
          ) : null}
          {format === 'text' ? (
            <>
              <label className="text-sm text-slate-600">
                <span className="mb-1 block">行分隔符</span>
                <Input
                  value={sourceConfig?.rowDelimiter ?? '\\n'}
                  onChange={(event) => onChange({ rowDelimiter: event.target.value })}
                  placeholder="\\n"
                />
              </label>
              <label className="text-sm text-slate-600">
                <span className="mb-1 block">跳过行数</span>
                <InputNumber
                  className="w-full"
                  min={0}
                  value={sourceConfig?.skipHeaderRowNumber ?? 0}
                  onChange={(value) => onChange({ skipHeaderRowNumber: value ?? 0 })}
                />
              </label>
              <label className="text-sm text-slate-600">
                <span className="mb-1 block">空值标记</span>
                <Input
                  value={sourceConfig?.nullFormat ?? ''}
                  onChange={(event) => onChange({ nullFormat: event.target.value })}
                  placeholder="留空表示默认"
                />
              </label>
            </>
          ) : null}
          {format === 'excel' ? (
            <>
              <label className="text-sm text-slate-600">
                <span className="mb-1 block">工作表</span>
                <Input
                  value={sourceConfig?.sheetName ?? ''}
                  onChange={(event) => onChange({ sheetName: event.target.value })}
                  placeholder="留空读取第一个工作表"
                />
              </label>
              <label className="text-sm text-slate-600">
                <span className="mb-1 block">Excel 引擎</span>
                <Select
                  className="w-full"
                  value={sourceConfig?.excelEngine ?? 'POI'}
                  options={[{ value: 'POI', label: 'POI' }, { value: 'EasyExcel', label: 'EasyExcel' }]}
                  onChange={(value) => onChange({ excelEngine: value })}
                />
              </label>
            </>
          ) : null}
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-3">
      <FileResourceSourceCard
        sourceConfig={sourceConfig}
        onChange={handleSourceChange}
        allowedFormats={formatLocked ? [format] : PICKER_ALLOWED_FORMATS}
        selectionMode="file"
        title="文件资源来源"
        description={isDatabaseFile
          ? `选择或上传 MinIO 文件区中的 ${databaseTypeLabel} 数据库文件。`
          : formatLocked
            ? `入口已确定为 ${formatLabel}，请选择匹配格式的文件。`
            : '选择或上传文件后，格式与字段将自动识别。'}
      />

      <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-1">
            <div className="text-sm font-semibold text-slate-900">
              {isDatabaseFile
                ? `${databaseTypeLabel} 数据库与表`
                : formatLocked
                  ? `${formatLabel} 文件解析`
                  : '文件格式与解析'}
            </div>
            <ConfigHint
              label="文件解析说明"
              title={isDatabaseFile
                ? `${databaseTypeLabel} 文件由 SeaTunnel Engine 以只读方式查询。`
                : format === 'json'
                  ? 'JSON 建议使用 NDJSON；常用解析参数已采用默认值，可在高级配置中调整。'
                  : '常用解析参数已采用默认值，可在高级配置中调整。'}
            />
          </div>
          {!isDatabaseFile && !formatLocked ? (
            <Button
              size="small"
              icon={<EyeOutlined />}
              loading={previewLoading}
              disabled={!resource?.id}
              onClick={() => void handlePreview()}
            >
              数据预览
            </Button>
          ) : null}
        </div>

        <div className="mt-4 grid grid-cols-1 gap-3">
          {!isDatabaseFile ? (
            <label className="text-sm text-slate-600">
              <span className="mb-1 block">格式</span>
              <Select className="w-full" value={format} options={FORMAT_OPTIONS} onChange={changeFormat} />
            </label>
          ) : null}

          {isDatabaseFile ? (
            <>
              <label className="text-sm text-slate-600">
                <span className="mb-1 block">{databaseTypeLabel} 表名</span>
                <Input
                  value={sourceConfig?.[format === 'sqlite' ? 'sqliteTable' : 'duckdbTable'] ?? sourceConfig?.table ?? ''}
                  onChange={(event) => onChange({
                    [format === 'sqlite' ? 'sqliteTable' : 'duckdbTable']: event.target.value,
                    table: event.target.value,
                  })}
                  placeholder="orders 或 main.orders"
                />
              </label>
              <Alert
                type="info"
                showIcon
                message="SeaTunnel Engine 运行要求"
                description={format === 'sqlite'
                  ? 'Engine 每个节点需预装 DuckDB JDBC 驱动、httpfs 和 sqlite_scanner 扩展；MinIO 凭据与挂载 SQL 随任务配置发送。'
                  : 'Engine 每个节点需预装 DuckDB JDBC 驱动和 httpfs 扩展；初始化 SQL 随任务配置发送，无需 Web 与 Engine 共享目录。'}
              />
            </>
          ) : null}

          {format === 'csv' || format === 'text' ? (
            <label className="text-sm text-slate-600">
              <span className="mb-1 block">字段分隔符</span>
              <Input
                value={sourceConfig?.fieldDelimiter ?? (format === 'csv' ? ',' : '\\001')}
                onChange={(event) => onChange({ fieldDelimiter: event.target.value })}
                placeholder={format === 'csv' ? ',' : '\\001'}
              />
            </label>
          ) : null}

          {format === 'csv' ? (
            <div className="flex items-center gap-1">
              <label className="flex items-center gap-2 text-sm text-slate-600">
                <Switch
                  size="small"
                  checked={hasHeader}
                  onChange={(checked) => onChange({ csvUseHeaderLine: checked, skipHeader: checked })}
                />
                首行作为表头
              </label>
              <ConfigHint
                label="首行表头说明"
                title="关闭后，字段名将使用 field_1、field_2 等默认名称。"
              />
            </div>
          ) : null}
          {format === 'excel' ? (
            <div className="flex items-center gap-1">
              <label className="flex items-center gap-2 text-sm text-slate-600">
                <Switch
                  size="small"
                  checked={hasHeader}
                  onChange={(checked) => onChange({ skipHeaderRowNumber: checked ? 1 : 0 })}
                />
                首行作为表头
              </label>
              <ConfigHint
                label="Excel 首行表头说明"
                title="开启后会跳过首行，字段名使用首行内容；关闭后首行会作为数据导入，字段名使用 field_1、field_2 等默认名称。"
              />
            </div>
          ) : null}
        </div>

        {!isDatabaseFile ? (
          <Collapse className="mt-3" items={advancedItems} defaultActiveKey={[]} />
        ) : null}
      </section>

      <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
        <div>
          <div className="flex items-center gap-1">
            <div className="text-sm font-semibold text-slate-900">
              字段 Schema
            </div>
            {Object.keys(fields).length ? (
              <span className="text-sm text-slate-400">{Object.keys(fields).length} 个字段</span>
            ) : null}
            <ConfigHint
              label="字段 Schema 说明"
              title={isDatabaseFile
                ? 'SeaTunnel Engine 会读取查询结果的字段类型；需要固定目标字段时，可在此手动配置。'
                : format === 'excel'
                  ? 'Excel 字段默认使用 string，避免 20 行预览遗漏后续非数字内容导致导入失败；确认整列为数值后可手动调整类型。'
                  : '字段名和类型会按文件内容识别，可在列表中调整。'}
            />
          </div>
          <div className="mt-3 flex justify-end gap-2">
            {!isDatabaseFile ? (
              <Button
                size="small"
                icon={<FileSearchOutlined />}
                loading={recognizing}
                disabled={!resource?.id}
                onClick={() => void recognizeFields(sourceConfig)}
              >
                识别字段
              </Button>
            ) : null}
            <Button size="small" icon={<PlusOutlined />} onClick={addField}>
              添加字段
            </Button>
          </div>
        </div>

        <div className="mt-3 max-h-[260px] space-y-2 overflow-y-auto">
          {Object.entries(fields).map(([name, type]) => (
            <div key={name} className="grid grid-cols-[minmax(0,1fr)_120px_32px] gap-2">
              <SchemaFieldNameInput
                name={name}
                existingNames={Object.keys(fields).filter((field) => field !== name)}
                onCommit={renameField}
              />
              <Select
                value={type}
                aria-label={`字段类型 ${name}`}
                options={TYPE_OPTIONS}
                onChange={(nextType) => updateSchema({ ...fields, [name]: nextType })}
              />
              <Button
                type="text"
                danger
                aria-label={`删除字段 ${name}`}
                icon={<DeleteOutlined />}
                onClick={() => removeField(name)}
              />
            </div>
          ))}
          {!Object.keys(fields).length ? (
            <div className="py-2 text-sm text-slate-400">暂无字段</div>
          ) : null}
        </div>
      </section>

      <Modal
        open={previewOpen}
        title={`文件预览 · ${resource?.name || ''}`}
        width={760}
        footer={<Button onClick={() => setPreviewOpen(false)}>关闭</Button>}
        onCancel={() => setPreviewOpen(false)}
      >
        {previewContent && Array.isArray((previewContent as any)?.columns) ? (
          <Table
            size="small"
            rowKey="__seatunnelPreviewRowKey"
            columns={previewColumns}
            dataSource={previewRows}
            pagination={false}
            scroll={{
              x: Math.max(PREVIEW_TABLE_MIN_WIDTH, previewColumns.length * PREVIEW_COLUMN_WIDTH),
              y: 360,
            }}
          />
        ) : (
          <pre className="max-h-[480px] overflow-auto rounded-xl bg-slate-950 p-4 text-sm leading-5 text-emerald-200">
            {typeof previewContent === 'string'
              ? previewContent
              : JSON.stringify(previewContent, null, 2)}
          </pre>
        )}
      </Modal>
    </div>
  );
};

export default FileSourceConfigPanel;
