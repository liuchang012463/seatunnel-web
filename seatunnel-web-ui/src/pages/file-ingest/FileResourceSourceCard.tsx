import { FileOutlined, FolderOpenOutlined } from '@ant-design/icons';
import { Alert, Button, Tag } from 'antd';
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
        <Tag color="blue">
          {binary ? '二进制对象' : isDuckDbSource ? 'DuckDB 文件' : '结构化文件'}
        </Tag>
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
            type={resource ? 'success' : 'warning'}
            showIcon
            message={resource
              ? isDuckDbSource ? '已选择 DuckDB 文件' : '已选择湖文件'
              : isDuckDbSource ? '尚未选择 DuckDB 文件' : '尚未选择湖文件'}
            description={
              resource
                ? binary
                  ? '当前任务将直接同步这个二进制对象到目标端，不解析文件内容。'
                  : isDuckDbSource
                    ? 'SeaTunnel Engine 将以只读方式挂载该 DuckDB 对象，并查询指定表。'
                    : '当前任务将从这个对象读取结构化数据，并按解析配置写入目标表。'
                : '点击下方按钮，在弹窗中直接上传或选择一个文件。'
            }
          />

          <div className="mt-4 flex items-center justify-between gap-3 rounded-xl border border-dashed border-slate-200 bg-slate-50 px-3 py-3">
            <div className="min-w-0">
              <div className="truncate text-sm font-medium text-slate-800">
                {resource?.name || '尚未选择湖文件'}
              </div>
              <div className="mt-1 truncate text-xs text-slate-500">
                {resource?.path || resource?.objectKey || '请选择湖文件区中的文件'}
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
        title={binary ? '选择二进制文件资源' : isDuckDbSource ? '选择 DuckDB 文件' : '选择结构化文件资源'}
        onCancel={() => setPickerOpen(false)}
        onSelect={selectResource}
      />
    </section>
  );
};

export default FileResourceSourceCard;
