import { FolderOpenOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Input, InputNumber, Radio, Segmented, Select, Switch, Tooltip, Upload, message } from 'antd';
import { Cloud, FolderUp } from 'lucide-react';
import { useMemo, useState } from 'react';
import PanelShell from '../../workflow/panel/components/PanelShell';
import { dataSourceCatalogApi } from '@/pages/data-source/service';
import { canUseIncrementalFileSync, fileDataSourceLabel } from './support';
import DirectoryPickerModal from './DirectoryPickerModal';
import type { FileDataSourceType } from './support';
import '@/pages/batch-link-up/workflow/panel/components/PanelShell/index.less';
import '@/pages/batch-link-up/workflow/panel/components/SourcePanel/index.less';

interface UploadFileRecord {
  name: string;
  size?: number;
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
}

const formatSize = (size?: number) => {
  if (size === undefined || size === null) return '';
  if (size >= 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`;
  if (size >= 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${size} B`;
};

const FileSyncSourcePanel: React.FC<FileSyncSourcePanelProps> = ({
  selectedNode,
  onClose,
  onNodeDataChange,
  datasourceOptions,
}) => {
  const config = selectedNode?.data?.config || {};
  const readMode = String(config.readMode || 'upload');
  const isUpload = readMode === 'upload';
  const [pickerOpen, setPickerOpen] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [refreshToken, setRefreshToken] = useState(0);

  const filteredOptions = useMemo(
    () =>
      datasourceOptions.filter((item) =>
        isUpload
          ? item.dbType === 'LOCAL_FILE'
          : item.dbType !== 'LOCAL_FILE',
      ),
    [datasourceOptions, isUpload],
  );

  const updateConfig = (patch: Record<string, any>) => {
    onNodeDataChange(selectedNode.id, {
      ...selectedNode.data,
      config: { ...config, ...patch },
    });
  };

  const handleReadModeChange = (value: string | number) => {
    const nextMode = String(value);
    if (nextMode === readMode) return;

    // 切换本地上传/远程文件时，数据源类型与实例需要重新选择。
    onNodeDataChange(selectedNode.id, {
      ...selectedNode.data,
      config: {
        ...config,
        readMode: nextMode,
        dbType: nextMode === 'upload' ? 'LOCAL_FILE' : '',
        pluginName: nextMode === 'upload' ? 'LocalFile' : '',
        connectorType: nextMode === 'upload' ? 'LocalFile' : '',
        dataSourceId: undefined,
        path: undefined,
      },
    });
  };

  const handleDataSourceChange = (value: string) => {
    const meta = datasourceOptions.find((item) => String(item.value) === String(value));
    updateConfig({
      dataSourceId: value,
      dbType: meta?.dbType,
      pluginName: meta?.dbType === 'LOCAL_FILE' ? 'LocalFile' : meta?.connectorType,
      connectorType: meta?.connectorType,
      path: undefined,
    });
  };

  const handleUpload = async (file: File) => {
    if (!config.dataSourceId) {
      message.warning('请先选择本地文件数据源');
      return false;
    }
    if (!config.path) {
      message.warning('请先选择上传目录');
      return false;
    }
    setUploading(true);
    try {
      const res = await dataSourceCatalogApi.uploadFiles(
        config.dataSourceId,
        config.path,
        [file],
      );
      if (res?.code !== 0) {
        throw new Error(res?.message || '上传失败');
      }
      const stored = (res?.data || []).map((item: any) => ({
        name: item.name,
        size: item.size,
      }));
      const merged: UploadFileRecord[] = [
        ...((config.uploadedFiles as UploadFileRecord[]) || []),
        ...stored,
      ];
      updateConfig({ uploadedFiles: merged });
      setRefreshToken((token) => token + 1);
      message.success(`${file.name} 上传成功`);
    } catch (error: any) {
      message.error(error?.message || '上传失败');
    } finally {
      setUploading(false);
    }
    return false;
  };

  const incrementalSupported = canUseIncrementalFileSync(
    config.dbType as FileDataSourceType,
    config.targetDbType as FileDataSourceType,
  );

  return (
    <PanelShell
      eyebrow="Source Config"
      title="来源配置（文件）"
      badge="输入节点"
      desc="选择本地上传或远程文件读取，修改后实时同步到画布节点"
      heroTitle={fileDataSourceLabel(config.dbType) || '来源'}
      heroDesc={config.path || '未选择目录'}
      heroTag="SOURCE"
      dbType={config.dbType}
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
        <div className="workflow-panel__group">
          <div className="workflow-panel__group-head">
            <div className="workflow-panel__group-kicker">读取方式</div>
          </div>
          <Segmented
            block
            value={readMode}
            onChange={(value) => handleReadModeChange(value as string)}
            options={[
              {
                label: (
                  <div className="workflow-panel__segmented-item">
                    <FolderUp size={14} />
                    <span>本地上传</span>
                  </div>
                ),
                value: 'upload',
              },
              {
                label: (
                  <div className="workflow-panel__segmented-item">
                    <Cloud size={14} />
                    <span>远程文件</span>
                  </div>
                ),
                value: 'remote',
              },
            ]}
          />
          <div className="mt-2 text-xs leading-5 text-slate-500">
            {isUpload
              ? '文件通过浏览器上传到所选本地文件数据源的目录，任务运行时由 SeaTunnel LocalFile 连接器读取。'
              : '从 FTP/SFTP/S3/MinIO 数据源的远程目录读取文件。'}
          </div>
        </div>

        <div className="workflow-panel__divider" />

        <div className="workflow-panel__group">
          <div className="workflow-panel__group-head">
            <div className="workflow-panel__group-kicker">
              {isUpload ? '本地文件数据源' : '远程数据源'}
            </div>
          </div>
          <div className="workflow-panel__field workflow-panel__field--full">
            <Select
              value={config.dataSourceId || undefined}
              onChange={handleDataSourceChange}
              options={filteredOptions.map((item) => ({
                label: `${item.label} · ${fileDataSourceLabel(item.dbType)}`,
                value: item.value,
              }))}
              placeholder={isUpload ? '请选择本地文件数据源' : '请选择 FTP/SFTP/S3/MinIO 数据源'}
              showSearch
              optionFilterProp="label"
              className="workflow-panel__antd-select"
              style={{ width: '100%' }}
              popupClassName="workflow-panel__dropdown"
            />
          </div>
          <div className="workflow-panel__field workflow-panel__field--full mt-3">
            <div className="mb-1 text-xs text-slate-500">
              {isUpload ? '上传目录' : '同步目录'}
            </div>
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

        {isUpload && (
          <>
            <div className="workflow-panel__divider" />
            <div className="workflow-panel__group">
              <div className="workflow-panel__group-head" style={{ display: 'flex', justifyContent: 'space-between' }}>
                <div className="workflow-panel__group-kicker">上传文件</div>
                <Tooltip title="刷新目录内容">
                  <Button
                    size="small"
                    type="text"
                    icon={<ReloadOutlined />}
                    onClick={() => setRefreshToken((token) => token + 1)}
                  />
                </Tooltip>
              </div>
              <Upload.Dragger
                multiple
                showUploadList={false}
                beforeUpload={handleUpload}
                disabled={uploading || !config.dataSourceId || !config.path}
              >
                <div className="py-2">
                  <p className="text-[13px] font-medium text-slate-700">
                    {uploading ? '上传中…' : '点击或拖拽文件到此处上传'}
                  </p>
                  <p className="mt-1 text-xs text-slate-400">
                    文件将上传到 {config.path || '所选目录'}
                  </p>
                </div>
              </Upload.Dragger>
              {Array.isArray(config.uploadedFiles) && config.uploadedFiles.length > 0 && (
                <div className="mt-3 space-y-1">
                  {config.uploadedFiles.map((file: UploadFileRecord, index: number) => (
                    <div
                      key={`${file.name}-${index}`}
                      className="flex items-center justify-between rounded-lg bg-slate-50 px-3 py-1.5 text-xs text-slate-600"
                    >
                      <span className="truncate">{file.name}</span>
                      <span className="ml-2 shrink-0 text-slate-400">{formatSize(file.size)}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
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
              value={config.syncType || 'FULL'}
              onChange={(event) => updateConfig({ syncType: event.target.value })}
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
                checked={config.binaryCompleteFileMode !== false}
                onChange={(checked) => updateConfig({ binaryCompleteFileMode: checked })}
              />
            </div>
          </div>
          {config.syncType === 'INCREMENTAL' && (
            <div className="mt-3 rounded-xl bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-600">
              增量模式要求来源与去向为同一数据源；比较方式固定为文件长度 + 修改时间。
            </div>
          )}
        </div>
      </section>

      <DirectoryPickerModal
        open={pickerOpen}
        datasourceId={config.dataSourceId}
        title={isUpload ? '选择上传目录' : '选择同步目录'}
        refreshToken={refreshToken}
        onCancel={() => setPickerOpen(false)}
        onSelect={(path) => updateConfig({ path })}
      />
    </PanelShell>
  );
};

export default FileSyncSourcePanel;
