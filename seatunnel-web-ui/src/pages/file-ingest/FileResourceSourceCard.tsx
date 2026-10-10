import {
  CheckCircleFilled,
  FileOutlined,
  FolderOpenOutlined,
  InfoCircleOutlined,
} from '@ant-design/icons';
import { Alert, Button, Tag, Tooltip } from 'antd';
import { useEffect, useState } from 'react';
import { fileResourceApi } from './api';
import FileResourcePicker from './FileResourcePicker';
import type { FileFormat, FileResource, FileResourcePickerProps } from './types';

interface FileResourceSourceCardProps {
  sourceConfig: Record<string, any>;
  onChange: (patch: Record<string, any>) => void;
  selectionMode?: FileResourcePickerProps['selectionMode'];
  allowedFormats?: FileFormat[];
  title?: string;
  description?: string;
  binary?: boolean;
}

const FileResourceSourceCard: React.FC<FileResourceSourceCardProps> = ({
  sourceConfig,
  onChange,
  selectionMode = 'file',
  allowedFormats,
  title = '湖文件来源',
  description = '从湖文件区读取对象，执行节点直接访问对象存储。',
  binary = false,
}) => {
  const [pickerOpen, setPickerOpen] = useState(false);
  const isResourceSource = String(sourceConfig?.sourceMode || '').toUpperCase() === 'FILE_RESOURCE';
  const isDuckDbSource = String(sourceConfig?.fileFormatType || '').toLowerCase() === 'duckdb';
  const resource = (sourceConfig?.fileResource || sourceConfig?.resource) as FileResource | undefined;
  const sourceBehavior = resource && resource.kind !== 'DIRECTORY'
    ? binary
      ? '将二进制对象同步到目标端，不解析文件内容。'
      : isDuckDbSource
        ? 'SeaTunnel Engine 将以只读方式挂载该 DuckDB 对象，并查询指定表。'
        : '任务会从所选文件读取结构化数据，并按解析配置写入目标表。'
    : undefined;

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
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-blue-50 text-blue-600">
            {resource?.kind === 'DIRECTORY' ? <FolderOpenOutlined /> : <FileOutlined />}
          </div>
          <div className="min-w-0">
            <div className="flex items-center gap-1">
              <div className="text-sm font-semibold text-slate-900">{title}</div>
              <Tooltip
                trigger={['hover', 'focus']}
                title={(
                  <div className="max-w-[280px] space-y-1">
                    <div>{description}</div>
                    {sourceBehavior ? <div>{sourceBehavior}</div> : null}
                  </div>
                )}
              >
                <Button
                  type="text"
                  size="small"
                  shape="circle"
                  aria-label="文件资源说明"
                  icon={<InfoCircleOutlined />}
                  className="!h-6 !w-6 !text-slate-400"
                />
              </Tooltip>
            </div>
          </div>
        </div>
        {binary || isDuckDbSource ? (
          <Tag color="blue">{binary ? '二进制对象' : 'DuckDB 文件'}</Tag>
        ) : null}
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
        <div className="mt-3 flex items-center justify-between gap-3 rounded-xl border border-dashed border-slate-200 bg-slate-50 px-3 py-2.5">
          <div className="flex min-w-0 items-center gap-2">
            <div className={resource ? 'text-emerald-500' : 'text-slate-400'}>
              {resource ? <CheckCircleFilled /> : <FileOutlined />}
            </div>
            <div className="min-w-0">
              <div className="truncate text-sm font-medium text-slate-800">
                {resource?.name || resource?.path || '尚未选择湖文件'}
              </div>
              {resource ? (
                <div className="truncate text-xs text-slate-500">
                  {resource.path || resource.objectKey}
                </div>
              ) : null}
            </div>
          </div>
          <Button type="primary" ghost onClick={() => setPickerOpen(true)}>
            {resource ? '更换资源' : '选择资源'}
          </Button>
        </div>
      )}

      <FileResourcePicker
        open={pickerOpen}
        value={resource}
        allowedFormats={allowedFormats}
        selectionMode={selectionMode}
        title={binary ? '选择二进制文件资源' : isDuckDbSource ? '选择 DuckDB 文件' : '选择结构化文件资源'}
        onCancel={() => setPickerOpen(false)}
        onSelect={selectResource}
      />
    </section>
  );
};

export default FileResourceSourceCard;
