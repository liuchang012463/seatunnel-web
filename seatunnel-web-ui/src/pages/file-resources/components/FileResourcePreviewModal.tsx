import { Alert, Button, Empty, Modal, Spin } from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import { downloadFileResource, previewFileResourceAsPdf } from '../service';
import type { FileResourceEntry } from '../types';
import { getResourceExtension, resourceName } from '../utils';

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

type PreviewFailure = 'invalid-file' | 'unavailable' | 'temporary';
type PreviewResult = { state: 'ready'; url: string } | { state: 'error'; failure: PreviewFailure };
type PreviewSnapshot = { resource: FileResourceEntry; attempt: number; result: PreviewResult };

function classifyPreviewFailure(reason: unknown): PreviewFailure {
  const error = reason as { code?: number; response?: { status?: number } } | undefined;
  const status = error?.response?.status;
  // REQUEST_PARAMS_NOT_VALID_ERROR includes unreadable files and unsupported Word formats.
  if (error?.code === 10001 || status === 400 || status === 413 || status === 415 || status === 422) {
    return 'invalid-file';
  }
  if (status === 401 || status === 403 || status === 404) return 'unavailable';
  console.warn('[湖文件管理] 预览请求失败', { status, code: error?.code });
  return 'temporary';
}

const FileResourcePreviewModal: React.FC<FileResourcePreviewModalProps> = ({
  open,
  resource,
  onCancel,
}) => {
  const [attempt, setAttempt] = useState(0);
  const [snapshot, setSnapshot] = useState<PreviewSnapshot>();
  const titleRef = useRef<HTMLSpanElement>(null);
  const extension = resource ? getResourceExtension(resource) : '';
  const isPdf = extension === 'pdf';
  const isWord = extension === 'doc' || extension === 'docx';
  const supported = isPdf || isWord;
  const result = snapshot?.resource === resource && snapshot?.attempt === attempt ? snapshot?.result : undefined;

  useEffect(() => {
    if (!open || !resource || !supported) return;

    let active = true;
    let objectUrl: string | undefined;

    const loadPreview = async () => {
      try {
        const response = isWord
          ? await previewFileResourceAsPdf(resource)
          : await downloadFileResource(resource, { skipErrorHandler: true });
        const blob = asBlob(response);
        if (!blob) throw new Error('预览响应不是文件内容');
        const prefix = await blob.slice(0, 1024).text();
        if (!active) return;
        if (!/%PDF-\d\.\d/.test(prefix)) {
          setSnapshot({ resource, attempt, result: { state: 'error', failure: 'invalid-file' } });
          return;
        }
        objectUrl = URL.createObjectURL(blob.type === 'application/pdf' ? blob : new Blob([blob], { type: 'application/pdf' }));
        setSnapshot({ resource, attempt, result: { state: 'ready', url: objectUrl } });
      } catch (reason: unknown) {
        if (active) setSnapshot({ resource, attempt, result: { state: 'error', failure: classifyPreviewFailure(reason) } });
      }
    };
    void loadPreview();

    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [attempt, isWord, open, resource, supported]);

  const retryPreview = () => {
    // Keep keyboard events inside the dialog when the retry button is replaced by the loading state.
    titleRef.current?.focus({ preventScroll: true });
    setAttempt((value) => value + 1);
  };

  return (
    <Modal
      title={(
        <span ref={titleRef} tabIndex={-1}>
          {resource ? `预览：${resourceName(resource)}` : '文件预览'}
        </span>
      )}
      open={open}
      width={960}
      footer={null}
      destroyOnHidden
      className="file-resource-preview-modal"
      onCancel={onCancel}
    >
      {resource && supported && !result && (
        <div className="file-resource-preview-modal__loading" role="status">
          <Spin size="small" /> 正在加载文件预览
        </div>
      )}
      {result?.state === 'error' && (
        <Alert
          type="error"
          showIcon
          message={result.failure === 'invalid-file' ? '此文件无法预览' : '文件预览暂不可用'}
          description={result.failure === 'invalid-file'
            ? `文件格式或内容不符合${isWord ? ' Word 转换' : ' PDF'}要求。可关闭预览，下载原件检查。`
            : result.failure === 'unavailable'
              ? '文件已不可访问，请关闭预览并刷新文件列表。'
              : '暂时无法加载文件。请重试，或关闭预览后下载原件。'}
          action={result.failure === 'temporary' ? (
            <Button size="small" aria-label="重试加载文件预览" onClick={retryPreview}>
              重试
            </Button>
          ) : undefined}
        />
      )}
      {result?.state === 'ready' && (
        <>
          {isWord && (
            <Alert
              className="file-resource-preview-modal__tip"
              type="info"
              showIcon
              message="已将 Word 文档转换为 PDF 进行预览（复杂排版/图片可能简化为文本）。"
            />
          )}
          <iframe
            className="file-resource-preview-modal__frame file-resource-preview-modal__frame--pdf"
            title={resource ? resourceName(resource) : '文件预览'}
            src={result.url}
          />
        </>
      )}
      {resource && !supported && <Empty description="当前文件类型暂不支持预览" />}
    </Modal>
  );
};

export default FileResourcePreviewModal;
