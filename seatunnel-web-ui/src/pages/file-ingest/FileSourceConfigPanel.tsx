import { DeleteOutlined, EyeOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Input, InputNumber, Modal, Select, Switch, message } from 'antd';
import { useMemo, useState } from 'react';
import FileResourceSourceCard from './FileResourceSourceCard';
import { buildFileResourcePreviewOptions, fileResourceApi } from './api';
import type { FileFormat, FileResource } from './types';
import {
  buildOutputSchema,
  getSchemaFields,
  hasSchemaFields,
  isLocalFileFormat,
  localFileExtensionMatches,
} from '@/pages/batch-link-up/workflow/panel/components/SourcePanel/localFile';

const FORMAT_OPTIONS: Array<{ value: FileFormat; label: string }> = [
  { value: 'csv', label: 'CSV' },
  { value: 'excel', label: 'Excel' },
  { value: 'json', label: 'JSON（建议 NDJSON）' },
  { value: 'text', label: 'TEXT / TXT' },
];

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
  onOpenManager?: () => void;
  onPreview?: (resource: FileResource, options: Record<string, any>) => Promise<unknown>;
}

const getFormat = (value: unknown): FileFormat => {
  const normalized = String(value || '').toLowerCase();
  return isLocalFileFormat(normalized) ? normalized : 'csv';
};

const FileSourceConfigPanel: React.FC<FileSourceConfigPanelProps> = ({
  sourceConfig,
  onChange,
  onOpenManager,
  onPreview,
}) => {
  const format = getFormat(sourceConfig?.fileFormatType);
  const resource = (sourceConfig?.fileResource || sourceConfig?.resource) as FileResource | undefined;
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewContent, setPreviewContent] = useState<unknown>();

  const fields = useMemo(() => getSchemaFields(sourceConfig?.schema), [sourceConfig?.schema]);

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
    if (resource && !localFileExtensionMatches(resource.name || resource.path || '', nextFormat)) {
      message.warning('切换格式前请先选择后缀匹配的文件资源');
      return;
    }
    onChange({
      fileFormatType: nextFormat,
      sourceMode: 'FILE_RESOURCE',
      dbType: 'MINIO',
      connectorType: 'S3File',
      pluginName: 'S3File',
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

  const handlePreview = async () => {
    if (!resource?.id) {
      message.warning('请先选择文件资源');
      return;
    }
    setPreviewLoading(true);
    try {
      const result = onPreview
        ? await onPreview(resource, buildFileResourcePreviewOptions(format))
        : await fileResourceApi.preview(resource.id, buildFileResourcePreviewOptions(format));
      const payload = (result as any)?.data ?? result;
      setPreviewContent(payload);
      setPreviewOpen(true);
    } catch (error: any) {
      message.error(error?.message || '文件预览失败');
    } finally {
      setPreviewLoading(false);
    }
  };

  return (
    <div className="space-y-3">
      <FileResourceSourceCard
        sourceConfig={sourceConfig}
        onChange={onChange}
        onOpenManager={onOpenManager}
        allowedFormats={[format]}
        selectionMode="file"
        title="文件资源来源"
        description="仅支持单个文件；SeaTunnel 执行节点将直接读取 S3File 对象。"
      />

      <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
        <div className="flex items-center justify-between gap-3">
          <div>
            <div className="text-sm font-semibold text-slate-900">文件格式与解析</div>
            <div className="mt-1 text-xs text-slate-500">四种格式共用一套 File Source 配置。</div>
          </div>
          <Button
            size="small"
            icon={<EyeOutlined />}
            loading={previewLoading}
            disabled={!resource?.id}
            onClick={() => void handlePreview()}
          >
            数据预览
          </Button>
        </div>

        <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2">
          <label className="text-xs text-slate-600">
            <span className="mb-1 block">格式</span>
            <Select className="w-full" value={format} options={FORMAT_OPTIONS} onChange={changeFormat} />
          </label>

          <label className="text-xs text-slate-600">
            <span className="mb-1 block">编码</span>
            <Input
              value={sourceConfig?.encoding || 'UTF-8'}
              onChange={(event) => onChange({ encoding: event.target.value })}
              placeholder="UTF-8"
            />
          </label>
        </div>

        {format === 'csv' ? (
          <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
            <label className="text-xs text-slate-600">
              <span className="mb-1 block">字段分隔符</span>
              <Input
                value={sourceConfig?.fieldDelimiter ?? ','}
                onChange={(event) => onChange({ fieldDelimiter: event.target.value })}
                placeholder=","
              />
            </label>
            <label className="flex items-center gap-2 pt-6 text-xs text-slate-600">
              <Switch
                size="small"
                checked={sourceConfig?.csvUseHeaderLine !== false && sourceConfig?.skipHeader !== false}
                onChange={(checked) => onChange({ csvUseHeaderLine: checked, skipHeader: checked })}
              />
              首行作为表头
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
              <span className="mb-1 block">引号</span>
              <Input
                maxLength={1}
                value={sourceConfig?.quoteChar ?? '"'}
                onChange={(event) => onChange({ quoteChar: event.target.value })}
                placeholder='"'
              />
            </label>
            <label className="text-xs text-slate-600">
              <span className="mb-1 block">转义</span>
              <Input
                maxLength={1}
                value={sourceConfig?.escapeChar ?? ''}
                onChange={(event) => onChange({ escapeChar: event.target.value })}
                placeholder="\\"
              />
            </label>
          </div>
        ) : null}

        {format === 'excel' ? (
          <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
            <label className="text-xs text-slate-600">
              <span className="mb-1 block">工作表</span>
              <Input
                value={sourceConfig?.sheetName ?? ''}
                onChange={(event) => onChange({ sheetName: event.target.value })}
                placeholder="例如 Sheet1"
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
          </div>
        ) : null}

        {format === 'text' ? (
          <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
            <label className="text-xs text-slate-600">
              <span className="mb-1 block">字段分隔符</span>
              <Input
                value={sourceConfig?.fieldDelimiter ?? '\\001'}
                onChange={(event) => onChange({ fieldDelimiter: event.target.value })}
                placeholder="\\001"
              />
            </label>
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
          </div>
        ) : null}

        {format === 'json' ? (
          <div className="mt-3 rounded-xl border border-amber-100 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-700">
            JSON/NDJSON 需要明确字段 Schema，避免推断类型造成目标字段不稳定。
          </div>
        ) : null}

        {format === 'excel' ? (
          <div className="mt-3 rounded-xl border border-amber-100 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-700">
            Excel 需要明确字段 Schema；任务执行由 SeaTunnel 直接读取对象存储，预览仅读取受限样本。
          </div>
        ) : null}
      </section>

      <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
        <div className="flex items-center justify-between gap-3">
          <div>
            <div className="text-sm font-semibold text-slate-900">字段 Schema</div>
            <div className="mt-1 text-xs text-slate-500">字段映射会复用此 Schema 作为上游字段。</div>
          </div>
          <Button size="small" icon={<PlusOutlined />} onClick={addField}>
            添加字段
          </Button>
        </div>

        <div className="mt-3 space-y-2">
          {Object.entries(fields).map(([name, type]) => (
            <div key={name} className="grid grid-cols-[minmax(0,1fr)_130px_32px] gap-2">
              <Input
                value={name}
                aria-label={`字段名 ${name}`}
                onChange={(event) => renameField(name, event.target.value)}
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
              尚未配置字段。请添加字段，或在后续字段解析后补充 Schema。
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
        <pre className="max-h-[480px] overflow-auto rounded-xl bg-slate-950 p-4 text-xs leading-5 text-emerald-200">
          {typeof previewContent === 'string'
            ? previewContent
            : JSON.stringify(previewContent, null, 2)}
        </pre>
      </Modal>
    </div>
  );
};

export default FileSourceConfigPanel;
