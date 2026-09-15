import { FileOutlined, FolderOpenOutlined, LinkOutlined } from '@ant-design/icons';
import { Alert, Button, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { fileResourceApi } from './api';
import FileResourcePicker from './FileResourcePicker';
import type { FileFormat, FileResource, FileResourcePickerProps } from './types';

interface FileResourceSourceCardProps {
  sourceConfig: Record<string, any>;
  onChange: (patch: Record<string, any>) => void;
  onOpenManager?: () => void;
  selectionMode?: FileResourcePickerProps['selectionMode'];
  allowedFormats?: FileFormat[];
  title?: string;
  description?: string;
  binary?: boolean;
}

const FileResourceSourceCard: React.FC<FileResourceSourceCardProps> = ({
  sourceConfig,
  onChange,
  onOpenManager,
  selectionMode = 'file',
  allowedFormats,
  title = '文件资源来源',
  description = '从文件资源库读取对象，执行节点直接访问对象存储。',
  binary = false,
}) => {
  const [pickerOpen, setPickerOpen] = useState(false);
  const isResourceSource = String(sourceConfig?.sourceMode || '').toUpperCase() === 'FILE_RESOURCE';
  const resource = (sourceConfig?.fileResource || sourceConfig?.resource) as FileResource | undefined;

  useEffect(() => {
    const resourceId = sourceConfig?.fileResourceId;
    if (!isResourceSource || !resourceId || resource?.id) return;

    let active = true;
    fileResourceApi
      .get(resourceId)
      .then((response) => {
        if (!active || response?.code !== 0 || !response?.data) return;
        const nextResource = response.data;
        onChange({
          fileResource: nextResource,
          objectKey: nextResource.objectKey || nextResource.path,
          path: nextResource.path || nextResource.logicalPath || nextResource.objectKey,
        });
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [isResourceSource, onChange, resource?.id, sourceConfig?.fileResourceId]);

  const selectResource = (nextResource: FileResource) => {
    onChange({
      sourceMode: 'FILE_RESOURCE',
      fileResourceId: nextResource.id,
      fileResource: nextResource,
      objectKey: nextResource.objectKey || nextResource.path,
      path: nextResource.path || nextResource.objectKey,
      dbType: 'MINIO',
      connectorType: 'S3File',
      pluginName: 'S3File',
      readMode: binary ? 'resource' : 'file',
      syncType: 'FULL',
      ...(binary
        ? {
            fileFormatType: 'binary',
            binaryChunkSize: sourceConfig?.binaryChunkSize || 1048576,
            binaryCompleteFileMode: sourceConfig?.binaryCompleteFileMode ?? false,
          }
        : {}),
    });
    setPickerOpen(false);
  };

  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-3">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-blue-50 text-blue-600">
            {resource?.kind === 'DIRECTORY' ? <FolderOpenOutlined /> : <FileOutlined />}
          </div>
          <div className="min-w-0">
            <div className="text-sm font-semibold text-slate-900">{title}</div>
            <div className="mt-1 text-xs leading-5 text-slate-500">{description}</div>
          </div>
        </div>
        <Tag color="blue">{binary ? 'BINARY' : 'S3File'}</Tag>
      </div>

      {!isResourceSource ? (
        <Alert
          className="mt-4"
          type="info"
          showIcon
          message="当前来源为远端数据源"
          description="如果要使用平台上传的文件，请在来源类型中选择文件资源；远端 FTP/SFTP/S3/MinIO 目录仍在画布来源节点中配置。"
        />
      ) : (
        <>
          <Alert
            className="mt-4"
            type="warning"
            showIcon
            message="文件需先上传到文件资源库"
            description={
              binary
                ? '文件传输只搬运二进制对象，不解析 CSV、Excel、JSON 或 TXT 内容。'
                : '选择一个文件资源后，任务将保存资源 ID 和对象存储引用。'
            }
            action={
            <Button type="link" icon={<LinkOutlined />} onClick={onOpenManager}>
                前往文件资源管理
              </Button>
            }
          />

          <div className="mt-4 flex items-center justify-between gap-3 rounded-xl border border-dashed border-slate-200 bg-slate-50 px-3 py-3">
            <div className="min-w-0">
              <div className="truncate text-sm font-medium text-slate-800">
                {resource?.name || '尚未选择文件资源'}
              </div>
              <div className="mt-1 truncate text-xs text-slate-500">
                {resource?.path || resource?.objectKey || '请选择文件资源库中的文件'}
              </div>
            </div>
            <Button type="primary" ghost onClick={() => setPickerOpen(true)}>
              {resource ? '更换资源' : '选择资源'}
            </Button>
          </div>
        </>
      )}

      <FileResourcePicker
        open={pickerOpen}
        value={resource}
        allowedFormats={allowedFormats}
        selectionMode={selectionMode}
        title={binary ? '选择二进制文件资源' : '选择结构化文件资源'}
        onCancel={() => setPickerOpen(false)}
        onSelect={selectResource}
        onOpenManager={onOpenManager}
      />
    </section>
  );
};

export default FileResourceSourceCard;
