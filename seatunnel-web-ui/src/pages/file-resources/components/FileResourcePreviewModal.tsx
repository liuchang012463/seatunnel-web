import { Alert, Empty, Modal, Spin } from 'antd';
import React, { useEffect, useState } from 'react';
import { downloadFileResource, previewFileResourceAsPdf } from '../service';
import type { FileResourceEntry } from '../types';
import { getResourceExtension, previewMimeType, resourceName } from '../utils';

function asBlob(result: unknown): Blob | undefined {
  if (result instanceof Blob) return result;
  if (!result || typeof result !== 'object') return undefined;
  const data = (result as { data?: unknown }).data;
  return data instanceof Blob ? data : undefined;
}

export interface FileResourcePreviewModalProps {
  open: boolean;
  resource?: FileResourceEntry | null;
  onCancel: () => void;
}

const FileResourcePreviewModal: React.FC<FileResourcePreviewModalProps> = ({
  open,
  resource,
  onCancel,
}) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [previewUrl, setPreviewUrl] = useState<string>();
  const extension = resource ? getResourceExtension(resource) : '';
  const isPdf = extension === 'pdf';
  const isWord = extension === 'doc' || extension === 'docx';

  useEffect(() => {
    if (!open || !resource) {
      return undefined;
    }

    let active = true;
    let objectUrl: string | undefined;
    setLoading(true);
    setError(undefined);
    setPreviewUrl(undefined);

    const loadPreview = async () => {
      if (isWord) {
        const result = await previewFileResourceAsPdf(resource);
        const blob = asBlob(result);
        if (!blob) throw new Error('预览响应不是 PDF 内容');
        const typedBlob = blob.type === 'application/pdf'
          ? blob
          : new Blob([blob], { type: 'application/pdf' });
        return typedBlob;
      }

      const result = await downloadFileResource(resource);
      const blob = asBlob(result);
      if (!blob) throw new Error('预览响应不是文件内容');
      return blob.type && blob.type !== 'application/octet-stream'
        ? blob
        : new Blob([blob], { type: previewMimeType(resource) });
    };

    loadPreview()
      .then((blob) => {
        if (!active) return;
        objectUrl = URL.createObjectURL(blob);
        setPreviewUrl(objectUrl);
      })
      .catch((reason: unknown) => {
        if (!active) return;
        setError(reason instanceof Error ? reason.message : '文件预览失败，请稍后重试');
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [isWord, open, resource]);

  return (
    <Modal
      title={resource ? `预览：${resourceName(resource)}` : '文件预览'}
      open={open}
      width={960}
      footer={null}
      destroyOnHidden
      className="file-resource-preview-modal"
      onCancel={onCancel}
    >
      <Spin spinning={loading}>
        {error ? (
          <Alert type="error" showIcon message={error} />
        ) : null}
        {!error && isWord ? (
          <Alert
            className="file-resource-preview-modal__tip"
            type="info"
            showIcon
            message="已将 Word 文档转换为 PDF 进行预览（复杂排版/图片可能简化为文本）。"
          />
        ) : null}
        {!error && previewUrl && (isPdf || isWord) ? (
          <iframe
            className="file-resource-preview-modal__frame file-resource-preview-modal__frame--pdf"
            title={resource ? resourceName(resource) : '文件预览'}
            src={previewUrl}
          />
        ) : null}
        {!error && previewUrl && !isPdf && !isWord ? (
          <Empty description="当前文件类型暂不支持预览" />
        ) : null}
        {!loading && !error && !previewUrl ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无预览内容" />
        ) : null}
      </Spin>
    </Modal>
  );
};

export default FileResourcePreviewModal;
