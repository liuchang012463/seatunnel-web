import { FolderOpenOutlined, UploadOutlined } from '@ant-design/icons';
import { Alert, Button, Modal, Space, Typography } from 'antd';
import { history } from '@umijs/max';
import React, { useEffect, useMemo, useState } from 'react';
import FileResourceBrowser from './FileResourceBrowser';
import ResourceBreadcrumb from './ResourceBreadcrumb';
import type { FileResourceEntry, FileResourcePickerProps } from '../types';
import {
  buildResourceManagerUrl,
  normalizeResourcePath,
  resourceId,
  resourceMatchesFormats,
  resourceName,
  serializeSelectionContext,
  isDirectoryResource,
} from '../utils';

const FileResourcePicker: React.FC<FileResourcePickerProps> = ({
  open,
  onCancel,
  onSelect,
  returnTo,
  selection,
  selectionContext,
  initialPath = '/',
  title = '选择文件资源',
  onOpenResourceManager,
  onOpenManager,
  value,
  allowedFormats,
  selectionMode = 'file',
}) => {
  const [path, setPath] = useState(() => normalizeResourcePath(initialPath));
  const [selectedResource, setSelectedResource] = useState<FileResourceEntry>();

  useEffect(() => {
    if (!open) return;
    setPath(normalizeResourcePath(initialPath));
    setSelectedResource(value || undefined);
  }, [initialPath, open, value]);

  const selectionQuery = useMemo(
    () => selection || serializeSelectionContext(selectionContext),
    [selection, selectionContext],
  );

  const openResourceManager = () => {
    const effectiveReturnTo =
      returnTo || (typeof window !== 'undefined' ? `${window.location.pathname}${window.location.search}` : undefined);
    const url = buildResourceManagerUrl({
      returnTo: effectiveReturnTo,
      selection: selectionQuery,
      selectionMode: 'single',
    });
    onCancel();
    if (onOpenManager) {
      onOpenManager();
      return;
    }
    if (onOpenResourceManager) {
      onOpenResourceManager(url);
      return;
    }
    history.push(url);
  };

  const confirmSelection = () => {
    if (!selectedResource) return;
    if (!canSelect(selectedResource)) return;
    onSelect(selectedResource);
    onCancel();
  };

  const canSelect = (resource: FileResourceEntry) =>
    isDirectoryResource(resource)
      ? selectionMode === 'file-or-directory'
      : resourceMatchesFormats(resource, allowedFormats);

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
      <Alert
        className="file-resource-picker__notice"
        type="info"
        showIcon
        icon={<UploadOutlined />}
        message="文件需先上传到文件资源库"
        description="任务执行节点会直接读取对象存储中的文件，不依赖 Web 服务本地临时文件。"
        action={
          <Button type="link" icon={<FolderOpenOutlined />} onClick={openResourceManager}>
            前往文件资源管理
          </Button>
        }
      />
      <div className="file-resource-picker__path">
        <ResourceBreadcrumb path={path} onNavigate={setPath} />
      </div>
      <FileResourceBrowser
        path={path}
        onPathChange={setPath}
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
