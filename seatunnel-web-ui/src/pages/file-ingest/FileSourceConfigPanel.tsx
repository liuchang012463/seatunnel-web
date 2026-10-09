import {
  DeleteOutlined,
  EyeOutlined,
  FileSearchOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { Alert, App, Button, Collapse, Input, InputNumber, Modal, Select, Switch, Table } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import FileResourceSourceCard from './FileResourceSourceCard';
import { buildFileResourcePreviewOptions, fileResourceApi } from './api';
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
  { value: 'json', label: 'JSON（建议 NDJSON）' },
  { value: 'text', label: 'TEXT / TXT' },
  { value: 'duckdb', label: 'DuckDB（.db / .duckdb）' },
];

const PICKER_ALLOWED_FORMATS: FileFormat[] = ['csv', 'excel', 'json', 'text', 'duckdb'];

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

const detectFormatFromName = (name?: string): FileFormat | undefined => {
  const extension = String(name || '').split('.').pop()?.toLowerCase();
  if (extension === 'csv') return 'csv';
  if (extension === 'xls' || extension === 'xlsx') return 'excel';
  if (extension === 'json') return 'json';
  if (extension === 'txt' || extension === 'text') return 'text';
  if (extension === 'db' || extension === 'duckdb') return 'duckdb';
  return undefined;
};

const resourceMatchesFormat = (name: string, format: FileFormat) => {
  const extension = String(name || '').toLowerCase().split('.').pop() || '';
  return format === 'duckdb'
    ? extension === 'db' || extension === 'duckdb'
    : localFileExtensionMatches(name, format);
};

const sourcePluginForFormat = (format: FileFormat) =>
  format === 'duckdb'
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

const inferTypeFromSamples = (values: unknown[]): string => {
  const samples = values
    .filter((value) => value !== null && value !== undefined && String(value).trim() !== '')
    .map((value) => String(value).trim());
  if (!samples.length) return 'string';
  if (samples.every((value) => /^[+-]?\d+$/.test(value))) return 'int';
  // 小数列允许混入整数值（如 1.5 与 2），只要存在小数点即建议 double。
  if (samples.every((value) => /^[+-]?\d+(\.\d+)?$/.test(value)) && samples.some((value) => value.includes('.'))) {
    return 'double';
  }
  return 'string';
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
  const hasHeader =
    sourceConfig?.csvUseHeaderLine !== false && sourceConfig?.skipHeader !== false;

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
    onChange({
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
    if (nextFormat === 'duckdb') {
      message.info('DuckDB 表结构由 SeaTunnel Engine 查询；请填写表名，需要时手动配置字段映射');
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
        config.csvUseHeaderLine !== false && config.skipHeader !== false,
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
    const detectedFormat = nextResourceId
      ? detectFormatFromName(nextResource?.name || nextResource?.path)
      : undefined;

    if (detectedFormat) {
      // 文件后缀决定来源类型；更换文件时同步更新对应的 SeaTunnel connector。
      patch = {
        ...patch,
        fileFormatType: detectedFormat,
        sourceMode: 'FILE_RESOURCE',
        ...sourcePluginForFormat(detectedFormat),
        ...(detectedFormat === 'csv' ? { fieldDelimiter: ',' } : {}),
        ...(detectedFormat === 'text' ? { fieldDelimiter: '\\001' } : {}),
      };
    }

    onChange(patch);

    if (nextResourceId && nextResourceId !== prevResourceId) {
      if (detectedFormat === 'duckdb') {
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
      label: (
        <span className="text-sm font-medium text-slate-800">
          高级配置
          <span className="ml-2 text-xs font-normal text-slate-400">
            已按默认值处理，通常无需修改
          </span>
        </span>
      ),
      children: (
        <div className="grid grid-cols-1 gap-3">
          <label className="text-xs text-slate-600">
            <span className="mb-1 block">编码</span>
            <Input
              value={sourceConfig?.encoding || 'UTF-8'}
              onChange={(event) => onChange({ encoding: event.target.value })}
              placeholder="UTF-8"
            />
          </label>
          {format === 'csv' ? (
            <>
              <label className="text-xs text-slate-600">
                <span className="mb-1 block">跳过行数</span>
                <InputNumber
                  className="w-full"
                  min={0}
                  value={sourceConfig?.skipHeaderRowNumber ?? 0}
                  onChange={(value) => onChange({ skipHeaderRowNumber: value ?? 0 })}
                />
              </label>
              <label className="text-xs text-slate-600">
                <span className="mb-1 block">引号字符</span>
                <Input
                  maxLength={1}
                  value={sourceConfig?.quoteChar ?? '"'}
                  onChange={(event) => onChange({ quoteChar: event.target.value })}
                  placeholder='"'
                />
              </label>
              <label className="text-xs text-slate-600">
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
              <label className="text-xs text-slate-600">
                <span className="mb-1 block">行分隔符</span>
                <Input
                  value={sourceConfig?.rowDelimiter ?? '\\n'}
                  onChange={(event) => onChange({ rowDelimiter: event.target.value })}
                  placeholder="\\n"
                />
              </label>
              <label className="text-xs text-slate-600">
                <span className="mb-1 block">跳过行数</span>
                <InputNumber
                  className="w-full"
                  min={0}
                  value={sourceConfig?.skipHeaderRowNumber ?? 0}
                  onChange={(value) => onChange({ skipHeaderRowNumber: value ?? 0 })}
                />
              </label>
              <label className="text-xs text-slate-600">
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
              <label className="text-xs text-slate-600">
                <span className="mb-1 block">工作表</span>
                <Input
                  value={sourceConfig?.sheetName ?? ''}
                  onChange={(event) => onChange({ sheetName: event.target.value })}
                  placeholder="留空读取第一个工作表"
                />
              </label>
              <label className="text-xs text-slate-600">
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
        allowedFormats={PICKER_ALLOWED_FORMATS}
        selectionMode="file"
        title="文件资源来源"
        description={format === 'duckdb'
          ? '选择或上传 MinIO 文件区中的 DuckDB 数据库文件。'
          : '选择或上传文件后，格式与字段将自动识别。'}
      />

      <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
        <div className="flex items-center justify-between gap-3">
          <div>
            <div className="text-sm font-semibold text-slate-900">
              {format === 'duckdb' ? 'DuckDB 数据库与表' : '文件格式与解析'}
            </div>
            <div className="mt-1 text-xs text-slate-500">
              {format === 'duckdb'
                ? 'DuckDB 文件通过 SeaTunnel Engine 的 JDBC source 只读查询。'
                : '选择格式即可，其余参数使用默认值。'}
            </div>
          </div>
          {format !== 'duckdb' ? (
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
          <label className="text-xs text-slate-600">
            <span className="mb-1 block">格式</span>
            <Select className="w-full" value={format} options={FORMAT_OPTIONS} onChange={changeFormat} />
          </label>

          {format === 'duckdb' ? (
            <>
              <label className="text-xs text-slate-600">
                <span className="mb-1 block">DuckDB 表名</span>
                <Input
                  value={sourceConfig?.duckdbTable ?? ''}
                  onChange={(event) => onChange({ duckdbTable: event.target.value })}
                  placeholder="orders 或 main.orders"
                />
              </label>
              <Alert
                type="info"
                showIcon
                message="SeaTunnel Engine 运行要求"
                description="Engine 需要 DuckDB JDBC 驱动和 httpfs 扩展；Web 与所有 Engine 节点还需挂载同一路径的 DuckDB 初始化 SQL 共享目录。"
              />
            </>
          ) : null}

          {format === 'csv' || format === 'text' ? (
            <label className="text-xs text-slate-600">
              <span className="mb-1 block">字段分隔符</span>
              <Input
                value={sourceConfig?.fieldDelimiter ?? (format === 'csv' ? ',' : '\\001')}
                onChange={(event) => onChange({ fieldDelimiter: event.target.value })}
                placeholder={format === 'csv' ? ',' : '\\001'}
              />
            </label>
          ) : null}

          {format === 'csv' ? (
            <label className="flex items-center gap-2 text-xs text-slate-600">
              <Switch
                size="small"
                checked={hasHeader}
                onChange={(checked) => onChange({ csvUseHeaderLine: checked, skipHeader: checked })}
              />
              首行作为表头（关闭后字段名按 field_1..N 识别）
            </label>
          ) : null}

          {format === 'json' || format === 'excel' ? (
            <div className="flex items-end pb-1 text-xs leading-5 text-slate-400">
              无需额外解析参数，保持默认即可；字段将在选择文件后自动识别。
            </div>
          ) : null}
        </div>

        {format !== 'duckdb' ? (
          <Collapse className="mt-3" items={advancedItems} defaultActiveKey={[]} />
        ) : null}
      </section>

      <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
        <div className="flex items-center justify-between gap-3">
          <div>
            <div className="text-sm font-semibold text-slate-900">
              字段 Schema
              <span className="ml-2 text-xs font-normal text-slate-400">
                {Object.keys(fields).length
                  ? `已配置 ${Object.keys(fields).length} 个字段`
                  : '尚未识别'}
              </span>
            </div>
            <div className="mt-1 text-xs text-slate-500">
              {format === 'duckdb'
                ? 'SeaTunnel Engine 会读取查询结果的字段类型；如需固定目标端字段映射，可在此手动配置。'
                : '自动读取文件字段名，类型默认 string，请逐个下拉确认。'}
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            {format !== 'duckdb' ? (
              <Button
                size="small"
                icon={<FileSearchOutlined />}
                loading={recognizing}
                disabled={!resource?.id}
                onClick={() => void recognizeFields(sourceConfig)}
              >
                从文件识别
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
            <div className="rounded-xl border border-dashed border-slate-200 bg-slate-50 px-3 py-4 text-center text-xs text-slate-500">
              {format === 'duckdb'
                ? 'DuckDB 表结构由 SeaTunnel Engine 查询；如需显式字段映射，可手动添加。'
                : '选择文件后将自动识别字段；也可点击「从文件识别」或手动添加。'}
            </div>
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
            columns={(previewContent as any).columns.map((column: string) => ({
              title: column,
              dataIndex: column,
              key: column,
              ellipsis: true,
            }))}
            dataSource={previewRows}
            pagination={false}
            scroll={{ y: 360 }}
          />
        ) : (
          <pre className="max-h-[480px] overflow-auto rounded-xl bg-slate-950 p-4 text-xs leading-5 text-emerald-200">
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
