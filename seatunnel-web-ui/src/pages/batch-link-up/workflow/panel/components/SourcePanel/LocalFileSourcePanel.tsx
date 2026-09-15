import { DeleteOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons';
import { Button, Input, InputNumber, Select, Switch, message } from 'antd';
import { useEffect, useMemo, useRef, useState, type ChangeEvent, type FC } from 'react';
import { fileUploadApi } from '@/pages/batch-link-up/api';
import PanelShell from '../PanelShell';
import {
  buildOutputSchema,
  getSchemaFields,
  hasSchemaFields,
  isLocalFileFormat,
  localFileAccept,
  localFileExtensionMatches,
  type LocalFileFormat,
} from './localFile';

interface LocalFileSourcePanelProps {
  selectedNode: any;
  onClose: () => void;
  jobDefinitionId?: string | number;
  updateNode: (
    configPatch?: Record<string, any>,
    extraNodeDataPatch?: Record<string, any>,
    metaPatch?: Record<string, any>,
  ) => void;
}

const FORMAT_OPTIONS = [
  { value: 'csv', label: 'CSV' },
  { value: 'excel', label: 'Excel' },
  { value: 'json', label: 'JSON（建议 NDJSON）' },
  { value: 'text', label: 'Text / 文本' },
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

const LocalFileSourcePanel: FC<LocalFileSourcePanelProps> = ({
  selectedNode,
  onClose,
  jobDefinitionId,
  updateNode,
}) => {
  const config = selectedNode?.data?.config || {};
  const normalizedFormat = String(config.fileFormatType || '').toLowerCase();
  const format: LocalFileFormat = isLocalFileFormat(normalizedFormat)
    ? normalizedFormat
    : 'csv';
  const assets = Array.isArray(config.uploadedAssets) ? config.uploadedAssets : [];
  const fields = useMemo(() => getSchemaFields(config.schema), [config.schema]);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);

  useEffect(() => {
    if (!jobDefinitionId) return;

    let cancelled = false;
    const loadSession = async () => {
      try {
        const response = config.uploadSessionId
          ? await fileUploadApi.getSession(String(config.uploadSessionId))
          : await fileUploadApi.ensureSession(jobDefinitionId);
        const session = response?.data as any;
        if (cancelled || response?.code !== 0 || !session?.id) return;

        updateNode({
          sourceMode: 'WEB_UPLOAD',
          dbType: 'MINIO',
          pluginName: 'S3File',
          connectorType: 'S3File',
          jobDefinitionId,
          uploadSessionId: session.id,
          uploadedAssets: session.assets || config.uploadedAssets || [],
          fileFormatType: format,
        });
      } catch (error) {
        if (!cancelled) message.error('加载本地文件上传会话失败');
      }
    };

    loadSession();
    return () => {
      cancelled = true;
    };
  }, [config.uploadSessionId, jobDefinitionId]);

  const updateSchema = (nextFields: Record<string, string>) => {
    const schema = { fields: nextFields };
    updateNode(
      { schema },
      undefined,
      {
        outputSchema: buildOutputSchema(schema),
        schemaStatus: hasSchemaFields(schema) ? 'success' : 'idle',
        schemaError: '',
      },
    );
  };

  const handleFormatChange = (value: LocalFileFormat) => {
    if (assets.some((asset: any) => !localFileExtensionMatches(
      asset?.originalName || asset?.relativePath,
      value,
    ))) {
      message.warning('切换格式前请先移除当前文件');
      return;
    }

    updateNode({
      fileFormatType: value,
      sourceMode: 'WEB_UPLOAD',
      dbType: 'MINIO',
      pluginName: 'S3File',
      connectorType: 'S3File',
    });
  };

  const handleUpload = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    if (assets.length > 0) {
      message.warning('单表本地文件来源只能保留一个文件，请先移除当前文件');
      return;
    }
    if (!localFileExtensionMatches(file.name, format)) {
      message.warning(`当前格式只接受 ${format.toUpperCase()} 文件`);
      return;
    }

    setUploading(true);
    try {
      let sessionId = config.uploadSessionId;
      if (!sessionId && jobDefinitionId) {
        const sessionResponse = await fileUploadApi.ensureSession(jobDefinitionId);
        const session = sessionResponse?.data as any;
        if (sessionResponse?.code !== 0 || !session?.id) {
          throw new Error(sessionResponse?.message || '创建上传会话失败');
        }
        sessionId = session.id;
      }
      if (!sessionId) throw new Error('上传会话未准备好，请刷新页面后重试');

      const response = await fileUploadApi.upload(
        sessionId,
        [file],
        [file.name],
      );
      if (response?.code !== 0) throw new Error(response?.message || '上传失败');

      const uploadedAssets = Array.isArray(response?.data) ? response.data : [];
      updateNode({
        sourceMode: 'WEB_UPLOAD',
        dbType: 'MINIO',
        pluginName: 'S3File',
        connectorType: 'S3File',
        jobDefinitionId,
        uploadSessionId: sessionId,
        uploadedAssets,
      });
      message.success('文件已上传到平台 MinIO');
    } catch (error: any) {
      message.error(error?.message || '上传失败');
    } finally {
      setUploading(false);
    }
  };

  const removeAsset = async (asset: any) => {
    if (!config.uploadSessionId || !asset?.id) return;
    try {
      const response = await fileUploadApi.deleteAsset(config.uploadSessionId, asset.id);
      if (response?.code !== 0) throw new Error(response?.message || '删除文件失败');
      updateNode({ uploadedAssets: assets.filter((item: any) => item.id !== asset.id) });
      message.success('文件已移除');
    } catch (error: any) {
      message.error(error?.message || '删除文件失败');
    }
  };

  const setConfigValue = (key: string, value: any) => updateNode({ [key]: value });

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
    if (!normalized || normalized === oldName) return;
    const nextFields: Record<string, string> = {};
    Object.entries(fields).forEach(([name, type]) => {
      nextFields[name === oldName ? normalized : name] = type;
    });
    updateSchema(nextFields);
  };

  const changeFieldType = (name: string, type: string) => {
    updateSchema({ ...fields, [name]: type });
  };

  const deleteField = (name: string) => {
    const nextFields = { ...fields };
    delete nextFields[name];
    updateSchema(nextFields);
  };

  return (
    <PanelShell
      eyebrow="Source Config"
      title="本地文件来源"
      badge="输入节点"
      desc="文件会由平台自动上传到 MinIO，任务运行时从 MinIO 读取结构化数据"
      heroTitle="本地文件"
      heroDesc={assets[0]?.originalName || '尚未上传文件'}
      heroTag="SOURCE"
      onClose={onClose}
      footer={
        <button type="button" className="workflow-panel__btn workflow-panel__btn--ghost" onClick={onClose}>
          关闭
        </button>
      }
    >
      <section className="workflow-panel__section">
        <div className="workflow-panel__group">
          <div className="workflow-panel__group-kicker">文件格式</div>
          <Select
            value={format}
            options={FORMAT_OPTIONS}
            onChange={handleFormatChange}
            className="w-full"
          />
        </div>

        <div className="workflow-panel__divider" />

        <div className="workflow-panel__group">
          <div className="workflow-panel__group-head">
            <div className="workflow-panel__group-kicker">上传文件</div>
            <input
              ref={fileInputRef}
              type="file"
              accept={localFileAccept(format)}
              hidden
              onChange={handleUpload}
            />
            <Button
              size="small"
              icon={<UploadOutlined />}
              loading={uploading}
              onClick={() => fileInputRef.current?.click()}
            >
              选择文件
            </Button>
          </div>
          {assets.length === 0 ? (
            <div className="rounded-xl border border-dashed border-slate-300 px-3 py-4 text-xs text-slate-500">
              单表模式只上传一个文件；文件名后缀需与所选格式一致。
            </div>
          ) : (
            <div className="space-y-2">
              {assets.map((asset: any) => (
                <div key={asset.id || asset.relativePath} className="flex items-center justify-between rounded-xl bg-slate-50 px-3 py-2 text-xs">
                  <span className="min-w-0 truncate">{asset.originalName || asset.relativePath}</span>
                  <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => removeAsset(asset)} />
                </div>
              ))}
            </div>
          )}
        </div>

        {format === 'csv' && (
          <>
            <div className="workflow-panel__divider" />
            <div className="workflow-panel__group space-y-3">
              <div className="workflow-panel__group-kicker">CSV 参数</div>
              <Input addonBefore="字段分隔符" value={config.fieldDelimiter ?? ','} onChange={(event) => setConfigValue('fieldDelimiter', event.target.value)} />
              <div className="flex items-center justify-between text-xs text-slate-600">
                <span>首行作为列名（RFC 4180）</span>
                <Switch checked={!!config.csvUseHeaderLine} onChange={(checked) => setConfigValue('csvUseHeaderLine', checked)} />
              </div>
              <InputNumber className="w-full" addonBefore="跳过行数" min={0} value={config.skipHeaderRowNumber ?? 0} onChange={(value) => setConfigValue('skipHeaderRowNumber', value ?? 0)} />
              <Input addonBefore="编码" value={config.encoding ?? 'UTF-8'} onChange={(event) => setConfigValue('encoding', event.target.value)} />
              <Input addonBefore="引号" maxLength={1} value={config.quoteChar ?? '"'} onChange={(event) => setConfigValue('quoteChar', event.target.value)} />
              <Input addonBefore="转义" maxLength={1} value={config.escapeChar ?? ''} onChange={(event) => setConfigValue('escapeChar', event.target.value)} />
            </div>
          </>
        )}

        {format === 'excel' && (
          <>
            <div className="workflow-panel__divider" />
            <div className="workflow-panel__group space-y-3">
              <div className="workflow-panel__group-kicker">Excel 参数</div>
              <Input addonBefore="工作表" placeholder="例如 Sheet1" value={config.sheetName ?? ''} onChange={(event) => setConfigValue('sheetName', event.target.value)} />
              <Select value={config.excelEngine ?? 'POI'} options={[{ value: 'POI' }, { value: 'EasyExcel' }]} onChange={(value) => setConfigValue('excelEngine', value)} className="w-full" />
              <div className="text-xs leading-5 text-slate-500">POI 适合普通工作簿；超过约 65,000 行时建议使用 EasyExcel。</div>
            </div>
          </>
        )}

        {format === 'json' && (
          <>
            <div className="workflow-panel__divider" />
            <div className="workflow-panel__group space-y-3">
              <div className="workflow-panel__group-kicker">JSON 参数</div>
              <Input addonBefore="编码" value={config.encoding ?? 'UTF-8'} onChange={(event) => setConfigValue('encoding', event.target.value)} />
              <div className="text-xs leading-5 text-slate-500">请上传每行一个 JSON 对象的 NDJSON 文件；JSON 需要配置字段 Schema。</div>
            </div>
          </>
        )}

        {format === 'text' && (
          <>
            <div className="workflow-panel__divider" />
            <div className="workflow-panel__group space-y-3">
              <div className="workflow-panel__group-kicker">Text 参数</div>
              <Input addonBefore="字段分隔符" value={config.fieldDelimiter ?? '\\001'} onChange={(event) => setConfigValue('fieldDelimiter', event.target.value)} />
              <Input addonBefore="行分隔符" value={config.rowDelimiter ?? '\\n'} onChange={(event) => setConfigValue('rowDelimiter', event.target.value)} />
              <InputNumber className="w-full" addonBefore="跳过行数" min={0} value={config.skipHeaderRowNumber ?? 0} onChange={(value) => setConfigValue('skipHeaderRowNumber', value ?? 0)} />
              <Input addonBefore="编码" value={config.encoding ?? 'UTF-8'} onChange={(event) => setConfigValue('encoding', event.target.value)} />
              <Input addonBefore="空值标记" value={config.nullFormat ?? ''} onChange={(event) => setConfigValue('nullFormat', event.target.value)} />
            </div>
          </>
        )}

        <div className="workflow-panel__divider" />

        <div className="workflow-panel__group space-y-3">
          <div className="flex items-center justify-between">
            <div>
              <div className="workflow-panel__group-kicker">字段 Schema</div>
              <div className="mt-1 text-xs text-slate-500">JSON / Excel 必填；CSV / Text 可不填，未填时按原始内容读取。</div>
            </div>
            <Button size="small" icon={<PlusOutlined />} onClick={addField}>添加字段</Button>
          </div>
          {Object.entries(fields).map(([name, type]) => (
            <div key={name} className="flex items-center gap-2">
              <Input
                value={name}
                placeholder="字段名"
                onBlur={(event) => renameField(name, event.target.value)}
                onPressEnter={(event) => renameField(name, event.currentTarget.value)}
              />
              <Select className="w-32 shrink-0" value={type} options={TYPE_OPTIONS} onChange={(value) => changeFieldType(name, value)} />
              <Button type="text" danger icon={<DeleteOutlined />} onClick={() => deleteField(name)} />
            </div>
          ))}
        </div>
      </section>
    </PanelShell>
  );
};

export default LocalFileSourcePanel;
