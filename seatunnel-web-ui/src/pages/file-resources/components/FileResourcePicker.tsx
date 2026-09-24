import { FolderOpenOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Button, Modal, Progress, Space, Typography, Upload } from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import { uploadFileResources } from '../service';
import type {
  FileResourceEntry,
  FileResourcePickerProps,
  FileResourceUploadProgress,
} from '../types';
import {
  getUploadRelativePath,
  isDirectoryResource,
  normalizeResourcePath,
  resourceId,
  resourceMatchesFormats,
  resourceName,
} from '../utils';
import FileResourceBrowser from './FileResourceBrowser';
import ResourceBreadcrumb from './ResourceBreadcrumb';
import '../index.less';

interface PickerUploadTask {
  name: string;
  percent: number;
  status: 'active' | 'success' | 'exception';
}

const FileResourcePicker: React.FC<FileResourcePickerProps> = ({
  open,
  onCancel,
  onSelect,
  initialPath = '/',
  title = '选择文件资源',
  value,
  allowedFormats,
  selectionMode = 'file',
}) => {
  const { message } = App.useApp();
  const [path, setPath] = useState(() => normalizeResourcePath(initialPath));
  const [selectedResource, setSelectedResource] = useState<FileResourceEntry>();
  const [refreshToken, setRefreshToken] = useState(0);
  const [uploadTasks, setUploadTasks] = useState<Record<string, PickerUploadTask>>({});
  // One customRequest fires per selected file; remember the batch size so a
  // single-file upload can be auto-selected while folder uploads only refresh.
  const uploadBatchRef = useRef({ total: 0, autoSelected: false });

  useEffect(() => {
    if (!open) return;
    setPath(normalizeResourcePath(initialPath));
    setSelectedResource(value || undefined);
    setUploadTasks({});
    uploadBatchRef.current = { total: 0, autoSelected: false };
  }, [initialPath, open, value]);

  const canSelect = (resource: FileResourceEntry) =>
    isDirectoryResource(resource)
      ? selectionMode === 'file-or-directory'
      : resourceMatchesFormats(resource, allowedFormats);

  const removeUploadTask = (key: string) => {
    setUploadTasks((current) => {
      const next = { ...current };
      delete next[key];
      return next;
    });
  };

  const handleUploaded = (resource?: FileResourceEntry) => {
    // Uploads land in the directory the user is currently browsing; refresh it
    // and auto-select the file when a single file was picked.
    setRefreshToken((token) => token + 1);
    const batch = uploadBatchRef.current;
    if (!resource || batch.total !== 1 || batch.autoSelected) return;
    batch.autoSelected = true;
    if (isDirectoryResource(resource) || !canSelect(resource)) return;
    setSelectedResource(resource);
  };

  const uploadProps = {
    name: 'file',
    action: '',
    showUploadList: false,
    multiple: true,
    beforeUpload: (file: File, fileList: File[]) => {
      uploadBatchRef.current = { total: fileList?.length || 1, autoSelected: false };
      return getUploadRelativePath(file) ? true : Upload.LIST_IGNORE;
    },
    customRequest: async (options: any) => {
      const file = options.file as File;
      if (!file || typeof file.name !== 'string') {
        options.onError?.(new Error('无法读取待上传文件'));
        return;
      }
      const uploadKey = String((file as any).uid || `${file.name}-${Date.now()}`);
      const updateProgress = (progress: FileResourceUploadProgress) => {
        setUploadTasks((current) => ({
          ...current,
          [uploadKey]: { name: file.name, percent: progress.percent, status: 'active' },
        }));
        options.onProgress?.({ percent: progress.percent }, file);
      };
      updateProgress({ loaded: 0, total: file.size, percent: 0 });
      try {
        const response = await uploadFileResources(
          path,
          [{ file, relativePath: getUploadRelativePath(file) }],
          updateProgress,
        );
        updateProgress({ loaded: file.size, total: file.size, percent: 100 });
        setUploadTasks((current) => ({
          ...current,
          [uploadKey]: { ...current[uploadKey], status: 'success' },
        }));
        options.onSuccess?.((response || {}) as Record<string, unknown>, file);
        handleUploaded(response?.resources?.[0]);
        window.setTimeout(() => removeUploadTask(uploadKey), 1800);
      } catch (error) {
        const reason = error instanceof Error ? error : new Error('文件上传失败，请稍后重试');
        setUploadTasks((current) => ({
          ...current,
          [uploadKey]: { ...current[uploadKey], status: 'exception' },
        }));
        options.onError?.(reason as any);
        message.error(`${file.name} 上传失败：${reason.message}`);
        window.setTimeout(() => removeUploadTask(uploadKey), 6000);
      }
    },
  };

  const confirmSelection = () => {
    if (!selectedResource) return;
    if (!canSelect(selectedResource)) return;
    onSelect(selectedResource);
    onCancel();
  };

  return (
    <Modal
      open={open}
      title={title}
      width={960}
      destroyOnHidden
      onCancel={onCancel}
      footer={
        <Space>
          <Button onClick={onCancel}>取消</Button>
          <Button type="primary" disabled={!selectedResource || !canSelect(selectedResource)} onClick={confirmSelection}>
            使用此文件
          </Button>
        </Space>
      }
    >
      <div className="file-resource-picker__upload">
        <Space wrap size={12}>
          <Upload {...uploadProps}>
            <Button icon={<UploadOutlined />}>上传文件</Button>
          </Upload>
          <Upload {...uploadProps} directory>
            <Button icon={<FolderOpenOutlined />}>上传文件夹</Button>
          </Upload>
          <Typography.Text type="secondary">
            上传到当前目录，单个文件上传完成后自动选中
          </Typography.Text>
        </Space>
        {Object.keys(uploadTasks).length > 0 ? (
          <div className="file-resource-picker__upload-tasks">
            {Object.entries(uploadTasks).map(([key, task]) => (
              <div key={key} className="file-resource-picker__upload-task">
                <span className="file-resource-picker__upload-task-name">{task.name}</span>
                <Progress
                  percent={Math.round(task.percent)}
                  size="small"
                  status={
                    task.status === 'exception'
                      ? 'exception'
                      : task.status === 'success'
                        ? 'success'
                        : 'active'
                  }
                />
              </div>
            ))}
          </div>
        ) : null}
      </div>
      <div className="file-resource-picker__path">
        <ResourceBreadcrumb path={path} onNavigate={setPath} />
      </div>
      <FileResourceBrowser
        path={path}
        onPathChange={setPath}
        refreshToken={refreshToken}
        selectable
        canSelect={canSelect}
        selectedId={selectedResource ? resourceId(selectedResource) : undefined}
        onSelect={setSelectedResource}
        compact
      />
      {selectedResource ? (
        <Typography.Text className="file-resource-picker__selected" type="secondary">
          已选择：{resourceName(selectedResource)}
        </Typography.Text>
      ) : null}
    </Modal>
  );
};

export default FileResourcePicker;
