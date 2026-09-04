import { FileOutlined, FolderOpenOutlined, UploadOutlined } from '@ant-design/icons';
import { Button, Input, InputNumber, Radio, Select, Switch, message } from 'antd';
import { FileUp, FolderUp, Trash2, UploadCloud } from 'lucide-react';
import { useMemo, useRef, useState } from 'react';
import PanelShell from '../../workflow/panel/components/PanelShell';
import { fileUploadApi } from '../../api';
import { dataSourceCatalogApi } from '@/pages/data-source/service';
import { canUseIncrementalFileSync, fileDataSourceLabel } from './support';
import DirectoryPickerModal from './DirectoryPickerModal';
import type { FileDataSourceType } from './support';
import { splitUploadBatches, type PickedFile } from './uploadUtils';
import '@/pages/batch-link-up/workflow/panel/components/PanelShell/index.less';
import '@/pages/batch-link-up/workflow/panel/components/SourcePanel/index.less';

interface UploadAsset {
  id?: string | number;
  relativePath: string;
  originalName?: string;
  size?: number;
  contentType?: string;
  status?: string;
}

interface FileSyncSourcePanelProps {
  selectedNode: any;
  onClose: () => void;
  onNodeDataChange: (nodeId: string, newData: any) => void;
  datasourceOptions: Array<{
    label: string;
    value: string;
    dbType: string;
    connectorType?: string;
  }>;
  jobDefinitionId?: string | number;
}

const formatSize = (size?: number) => {
  if (size === undefined || size === null) return '';
  if (size >= 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`;
  if (size >= 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${size} B`;
};

const fileRelativePath = (file: File) =>
  String((file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name);

const readEntry = (entry: any): Promise<any[]> =>
  new Promise((resolve, reject) => {
    let reader: any;
    try {
      reader = entry.createReader();
    } catch (error) {
      reject(error);
      return;
    }

    const entries: any[] = [];
    const readPage = () => {
      reader.readEntries((page: any[]) => {
        if (!page.length) {
          resolve(entries);
          return;
        }
        entries.push(...page);
        readPage();
      }, reject);
    };
    readPage();
  });

const readEntryFile = (entry: any): Promise<File> =>
  new Promise((resolve, reject) => entry.file(resolve, reject));

const collectEntry = async (entry: any, prefix = ''): Promise<PickedFile[]> => {
  if (entry.isFile) {
    const file = await readEntryFile(entry);
    return [{ file, relativePath: `${prefix}${file.name}` }];
  }

  if (!entry.isDirectory) return [];
  const nextPrefix = `${prefix}${entry.name}/`;
  const children = await readEntry(entry);

  const result: PickedFile[] = [];
  for (const child of children) {
    result.push(...(await collectEntry(child, nextPrefix)));
  }
  return result;
};

const collectDroppedFiles = async (dataTransfer: DataTransfer): Promise<PickedFile[]> => {
  const items = Array.from(dataTransfer.items || []);
  const entries = items
    .map((item) => (item as any).webkitGetAsEntry?.())
    .filter(Boolean);
  if (entries.length > 0) {
    const result: PickedFile[] = [];
    for (const entry of entries) {
      result.push(...(await collectEntry(entry)));
    }
    return result;
  }

  return Array.from(dataTransfer.files || []).map((file) => ({
    file,
    relativePath: fileRelativePath(file),
  }));
};

const FileSyncSourcePanel: React.FC<FileSyncSourcePanelProps> = ({
  selectedNode,
  onClose,
  onNodeDataChange,
  datasourceOptions,
  jobDefinitionId,
}) => {
  const config = selectedNode?.data?.config || {};
  const isWebUpload = String(config.sourceMode || '').toUpperCase() === 'WEB_UPLOAD';
  const isRemote = !isWebUpload;
  const [pickerOpen, setPickerOpen] = useState(false);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const folderInputRef = useRef<HTMLInputElement>(null);

  const assets: UploadAsset[] = Array.isArray(config.uploadedAssets)
    ? config.uploadedAssets
    : [];

  const filteredOptions = useMemo(
    () => datasourceOptions.filter((item) => ['FTP', 'SFTP', 'S3', 'MINIO'].includes(item.dbType)),
    [datasourceOptions],
  );

  const updateConfig = (patch: Record<string, any>) => {
    onNodeDataChange(selectedNode.id, {
      ...selectedNode.data,
      config: { ...config, ...patch },
    });
  };

  const handleDataSourceChange = (value: string) => {
    const meta = datasourceOptions.find((item) => String(item.value) === String(value));
    updateConfig({
      dataSourceId: value,
      dbType: meta?.dbType,
      pluginName: meta?.connectorType,
      connectorType: meta?.connectorType,
      path: undefined,
    });
  };

  const uploadPickedFiles = async (pickedFiles: PickedFile[]) => {
    if (!pickedFiles.length) return;
    let sessionId = config.uploadSessionId;

    setUploading(true);
    try {
      if (!sessionId && jobDefinitionId) {
        const sessionResponse = await fileUploadApi.ensureSession(jobDefinitionId);
        const session = sessionResponse?.data as any;
        if (sessionResponse?.code !== 0 || !session?.id) {
          throw new Error(sessionResponse?.message || '创建上传会话失败');
        }
        sessionId = session.id;
        updateConfig({ uploadSessionId: sessionId });
      }
      if (!sessionId) {
        throw new Error('上传会话未准备好，请刷新页面后重试');
      }

      const byPath = new Map<string, UploadAsset>(
        assets.map((asset) => [asset.relativePath, asset]),
      );
      let uploadedCount = 0;
      for (const batch of splitUploadBatches(pickedFiles)) {
        const response = await fileUploadApi.upload(
          sessionId,
          batch.map((item) => item.file),
          batch.map((item) => item.relativePath),
        );
        if (response?.code !== 0) {
          throw new Error(response?.message || '上传失败');
        }

        const nextAssets = Array.isArray(response?.data) ? response.data : [];
        nextAssets.forEach((asset: UploadAsset) => byPath.set(asset.relativePath, asset));
        uploadedCount += nextAssets.length || batch.length;
        updateConfig({
          sourceMode: 'WEB_UPLOAD',
          dbType: 'MINIO',
          pluginName: 'S3File',
          connectorType: 'S3File',
          readMode: 'upload',
          syncType: 'FULL',
          binaryChunkSize: 1048576,
          binaryCompleteFileMode: false,
          uploadSessionId: sessionId,
          uploadedAssets: Array.from(byPath.values()),
        });
      }
      message.success(`已上传 ${uploadedCount || pickedFiles.length} 个文件`);
    } catch (error: any) {
      message.error(error?.message || '上传失败');
    } finally {
      setUploading(false);
    }
  };

  const handleInputChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files || []).map((file) => ({
      file,
      relativePath: fileRelativePath(file),
    }));
    event.target.value = '';
    await uploadPickedFiles(files);
  };

  const handleDrop = async (event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    await uploadPickedFiles(await collectDroppedFiles(event.dataTransfer));
  };

  const removeAsset = async (asset: UploadAsset) => {
    if (!config.uploadSessionId || !asset.id) return;
    try {
      const response = await fileUploadApi.deleteAsset(config.uploadSessionId, asset.id);
      if (response?.code !== 0) throw new Error(response?.message || '删除文件失败');
      updateConfig({
        uploadedAssets: assets.filter((item) => item.id !== asset.id),
      });
      message.success('文件已移除');
    } catch (error: any) {
      message.error(error?.message || '删除文件失败');
    }
  };

  const incrementalSupported = isWebUpload
    ? false
    : canUseIncrementalFileSync(
        config.dbType as FileDataSourceType,
        config.targetDbType as FileDataSourceType,
      );

  return (
    <PanelShell
      eyebrow="Source Config"
      title="来源配置（文件）"
      badge="输入节点"
      desc={isWebUpload ? '选择本地文件或文件夹，作为同步来源' : '配置远程文件来源目录'}
      heroTitle={isWebUpload ? '本地文件' : fileDataSourceLabel(config.dbType) || '来源'}
      heroDesc={isWebUpload ? `${assets.length} 个文件已准备` : config.path || '未选择目录'}
      heroTag="SOURCE"
      dbType={isWebUpload ? undefined : config.dbType}
      icon={isWebUpload ? <FileOutlined /> : undefined}
      onClose={onClose}
      footer={
        <button
          type="button"
          className="workflow-panel__btn workflow-panel__btn--ghost"
          onClick={onClose}
        >
          关闭
        </button>
      }
    >
      <section className="workflow-panel__section">
        {isWebUpload ? (
          <>
            <div className="rounded-2xl border border-sky-100 bg-sky-50/80 px-4 py-4">
              <div className="flex items-start gap-3">
                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-white text-sky-600 shadow-sm">
                  <FileOutlined className="text-sky-600" />
                </div>
                <div className="min-w-0">
                  <div className="text-sm font-semibold text-sky-900">选择本地文件</div>
                  <div className="mt-1 text-xs leading-5 text-sky-700">
                    支持单个/多个文件、文件夹选择和拖拽，文件夹层级会原样保留。
                  </div>
                </div>
              </div>
            </div>

            <div className="mt-4 flex flex-wrap gap-2">
              <Button
                type="primary"
                icon={<FileUp size={15} />}
                loading={uploading}
                onClick={() => fileInputRef.current?.click()}
              >
                选择文件
              </Button>
              <Button
                icon={<FolderUp size={15} />}
                loading={uploading}
                onClick={() => folderInputRef.current?.click()}
              >
                选择文件夹
              </Button>
              <input
                ref={fileInputRef}
                type="file"
                multiple
                className="hidden"
                onChange={handleInputChange}
              />
              <input
                ref={folderInputRef}
                type="file"
                multiple
                className="hidden"
                onChange={handleInputChange}
                {...({ webkitdirectory: '', directory: '' } as any)}
              />
            </div>

            <div
              className="mt-3 flex min-h-[132px] cursor-pointer flex-col items-center justify-center rounded-2xl border border-dashed border-sky-200 bg-white px-4 py-6 text-center transition-colors hover:border-sky-400 hover:bg-sky-50/40"
              onClick={() => fileInputRef.current?.click()}
              onDragOver={(event) => {
                event.preventDefault();
                event.dataTransfer.dropEffect = 'copy';
              }}
              onDrop={handleDrop}
              role="button"
              tabIndex={0}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') fileInputRef.current?.click();
              }}
            >
              <UploadCloud className="text-sky-500" size={26} />
              <div className="mt-2 text-sm font-medium text-slate-700">
                {uploading ? '正在上传…' : '拖拽文件或文件夹到这里'}
              </div>
              <div className="mt-1 text-xs text-slate-400">也可以点击选择文件，或使用上方的文件夹按钮</div>
            </div>

            <div className="mt-4 flex items-center justify-between">
              <div className="text-xs font-semibold uppercase tracking-[0.12em] text-slate-500">
                已上传文件（{assets.length}）
              </div>
              {assets.length > 0 ? (
                <span className="text-xs text-emerald-600">已准备好发布</span>
              ) : null}
            </div>

            {assets.length > 0 ? (
              <div className="mt-2 max-h-[190px] space-y-1 overflow-y-auto rounded-xl border border-slate-100 bg-slate-50/70 p-2">
                {assets.map((asset) => (
                  <div
                    key={`${asset.id || asset.relativePath}`}
                    className="flex items-center gap-2 rounded-lg bg-white px-3 py-2 text-xs text-slate-600 shadow-sm"
                  >
                    <UploadOutlined className="shrink-0 text-sky-500" />
                    <span className="min-w-0 flex-1 truncate" title={asset.relativePath}>
                      {asset.relativePath}
                    </span>
                    <span className="shrink-0 text-slate-400">{formatSize(asset.size)}</span>
                    {asset.id ? (
                      <Button
                        type="text"
                        size="small"
                        danger
                        icon={<Trash2 size={14} />}
                        aria-label={`移除 ${asset.relativePath}`}
                        onClick={() => removeAsset(asset)}
                      />
                    ) : null}
                  </div>
                ))}
              </div>
            ) : null}
          </>
        ) : (
          <>
            <div className="workflow-panel__group">
              <div className="workflow-panel__group-head">
                <div className="workflow-panel__group-kicker">远程数据源</div>
              </div>
              <div className="workflow-panel__field workflow-panel__field--full">
                <Select
                  value={config.dataSourceId || undefined}
                  onChange={handleDataSourceChange}
                  options={filteredOptions.map((item) => ({
                    label: `${item.label} · ${fileDataSourceLabel(item.dbType)}`,
                    value: item.value,
                  }))}
                  placeholder="请选择 FTP/SFTP/S3/MinIO 数据源"
                  showSearch
                  optionFilterProp="label"
                  className="workflow-panel__antd-select"
                  style={{ width: '100%' }}
                  popupClassName="workflow-panel__dropdown"
                />
              </div>
              <div className="workflow-panel__field workflow-panel__field--full mt-3">
                <div className="mb-1 text-xs text-slate-500">同步目录</div>
                <Input
                  value={config.path}
                  placeholder="/incoming/files 或 /bucket-prefix"
                  onChange={(event) => updateConfig({ path: event.target.value })}
                  addonAfter={
                    <Button
                      type="text"
                      icon={<FolderOpenOutlined />}
                      onClick={() => {
                        if (!config.dataSourceId) {
                          message.warning('请先选择数据源');
                          return;
                        }
                        setPickerOpen(true);
                      }}
                    >
                      浏览
                    </Button>
                  }
                />
              </div>
            </div>

            <DirectoryPickerModal
              open={pickerOpen}
              datasourceId={config.dataSourceId}
              title="选择同步目录"
              onCancel={() => setPickerOpen(false)}
              onSelect={(path) => updateConfig({ path })}
            />
          </>
        )}

        <div className="workflow-panel__divider" />

        <div className="workflow-panel__group">
          <div className="workflow-panel__group-head">
            <div className="workflow-panel__group-kicker">文件与二进制策略</div>
          </div>
          <div className="workflow-panel__field workflow-panel__field--full">
            <div className="mb-1 text-xs text-slate-500">同步方式</div>
            <Radio.Group
              value={isWebUpload ? 'FULL' : config.syncType || 'FULL'}
              onChange={(event) => {
                if (!isWebUpload) updateConfig({ syncType: event.target.value });
              }}
              optionType="button"
              options={[
                { label: '全量复制', value: 'FULL' },
                {
                  label: '增量 update',
                  value: 'INCREMENTAL',
                  disabled: !incrementalSupported,
                },
              ]}
            />
          </div>
          <div className="workflow-panel__field workflow-panel__field--full mt-3">
            <div className="mb-1 text-xs text-slate-500">文件名正则</div>
            <Input
              value={config.fileFilterPattern}
              placeholder=".*"
              onChange={(event) => updateConfig({ fileFilterPattern: event.target.value })}
            />
          </div>
          <div className="workflow-panel__field workflow-panel__field--full mt-3">
            <div className="mb-1 text-xs text-slate-500">扩展名过滤</div>
            <Input
              value={config.filenameExtension}
              placeholder="zip,bin"
              onChange={(event) => updateConfig({ filenameExtension: event.target.value })}
            />
          </div>
          <div className="mt-3 grid grid-cols-2 gap-3">
            <div>
              <div className="mb-1 text-xs text-slate-500">分块字节数</div>
              <InputNumber
                className="!w-full"
                min={1024}
                value={config.binaryChunkSize}
                onChange={(value) => updateConfig({ binaryChunkSize: value })}
              />
            </div>
            <div>
              <div className="mb-1 text-xs text-slate-500">完整文件模式</div>
              <Switch
                checked={isWebUpload ? false : config.binaryCompleteFileMode !== false}
                onChange={(checked) => {
                  if (!isWebUpload) updateConfig({ binaryCompleteFileMode: checked });
                }}
                disabled={isWebUpload}
              />
            </div>
          </div>
          {isRemote && config.syncType === 'INCREMENTAL' && (
            <div className="mt-3 rounded-xl bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-600">
              增量模式要求来源与去向为同一数据源；比较方式固定为文件长度 + 修改时间。
            </div>
          )}
        </div>
      </section>
    </PanelShell>
  );
};

export default FileSyncSourcePanel;
