import {
  ClockCircleOutlined,
  CloudUploadOutlined,
  DownloadOutlined,
  FolderAddOutlined,
  FolderOpenOutlined,
  HistoryOutlined,
  ReloadOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import type { UploadProps } from 'antd';
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Form,
  Input,
  Modal,
  Row,
  Space,
  Spin,
  Progress,
  Table,
  Tag,
  Typography,
  Upload,
  message,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { history, useLocation } from '@umijs/max';
import React, { useEffect, useMemo, useState } from 'react';
import FileResourceBrowser from './components/FileResourceBrowser';
import ResourceBreadcrumb from './components/ResourceBreadcrumb';
import {
  appendResourceSelection,
  buildResourceManagerUrl,
  formatBytes,
  formatResourceTime,
  getUploadRelativePath,
  isDirectoryResource,
  joinResourcePath,
  normalizeResourcePath,
  resourceId,
  resourceName,
  resourcePath,
} from './utils';
import {
  createFileResourceDirectory,
  deleteFileResource,
  downloadFileResource,
  fetchFileResourceUploadRecords,
  uploadFileResources,
} from './service';
import type {
  FileResourceEntry,
  FileResourceUploadProgress,
  FileResourceUploadRecord,
  FileResourceUploadRecordPage,
} from './types';
import './index.less';

const { Paragraph, Text, Title } = Typography;

const uploadRecordStatusLabel: Record<string, string> = {
  PENDING: '等待上传',
  UPLOADING: '上传中',
  SUCCESS: '已完成',
  PARTIAL: '部分完成',
  FAILED: '失败',
};

const uploadRecordStatusColor: Record<string, string> = {
  PENDING: 'default',
  UPLOADING: 'processing',
  SUCCESS: 'success',
  PARTIAL: 'warning',
  FAILED: 'error',
};

const emptyUploadRecords: FileResourceUploadRecordPage = {
  bizData: [],
  pagination: { pageNo: 1, pageSize: 10, total: 0 },
};

function downloadBlob(result: unknown): Blob | undefined {
  if (result instanceof Blob) return result;
  if (!result || typeof result !== 'object') return undefined;
  const data = (result as { data?: unknown }).data;
  return data instanceof Blob ? data : undefined;
}

const FileResourcesPage: React.FC = () => {
  const location = useLocation();
  const query = useMemo(() => new URLSearchParams(location.search || ''), [location.search]);
  const returnTo = query.get('returnTo') || undefined;
  const selection = query.get('selection') || undefined;
  const selectionMode =
    (query.get('selectionMode') === 'single' || query.get('select') === '1') && Boolean(returnTo);
  const initialPath = normalizeResourcePath(query.get('path') || '/');

  const [messageApi, contextHolder] = message.useMessage();
  const [path, setPath] = useState(initialPath);
  const [refreshToken, setRefreshToken] = useState(0);
  const [directoryModalOpen, setDirectoryModalOpen] = useState(false);
  const [directoryLoading, setDirectoryLoading] = useState(false);
  const [recordsModalOpen, setRecordsModalOpen] = useState(false);
  const [recordsLoading, setRecordsLoading] = useState(false);
  const [records, setRecords] = useState<FileResourceUploadRecordPage>(emptyUploadRecords);
  const [uploadingCount, setUploadingCount] = useState(0);
  const [uploadProgress, setUploadProgress] = useState<Record<string, {
    name: string;
    loaded: number;
    total: number;
    percent: number;
    status: 'active' | 'success' | 'exception';
  }>>({});
  const [directoryForm] = Form.useForm<{ name: string }>();

  useEffect(() => {
    setPath(initialPath);
  }, [initialPath]);

  const reload = () => setRefreshToken((value) => value + 1);

  const uploadProps = useMemo<UploadProps>(
    () => ({
      name: 'file',
      multiple: true,
      action: '',
      showUploadList: false,
      beforeUpload: (file) => {
        return getUploadRelativePath(file) ? true : Upload.LIST_IGNORE;
      },
      customRequest: async (options) => {
        const file = options.file as File;
        if (!file || typeof file.name !== 'string') {
          options.onError?.(new Error('无法读取待上传文件'));
          return;
        }

        setUploadingCount((count) => count + 1);
        const uploadKey = String((options.file as any).uid || `${file.name}-${Date.now()}`);
        const updateProgress = (progress: FileResourceUploadProgress) => {
          setUploadProgress((current) => ({
            ...current,
            [uploadKey]: {
              name: file.name,
              loaded: progress.loaded,
              total: progress.total,
              percent: progress.percent,
              status: 'active',
            },
          }));
          options.onProgress?.({ percent: progress.percent }, file);
        };
        updateProgress({ loaded: 0, total: file.size, percent: 0 });
        try {
          const response = await uploadFileResources(path, [
            {
              file,
              relativePath: getUploadRelativePath(file),
            },
          ], updateProgress);
          updateProgress({ loaded: file.size, total: file.size, percent: 100 });
          setUploadProgress((current) => ({
            ...current,
            [uploadKey]: { ...current[uploadKey], status: 'success' },
          }));
          options.onSuccess?.((response || {}) as Record<string, unknown>, file);
          reload();
          window.setTimeout(() => {
            setUploadProgress((current) => {
              const next = { ...current };
              delete next[uploadKey];
              return next;
            });
          }, 1800);
        } catch (error) {
          const reason = error instanceof Error ? error : new Error('文件上传失败，请稍后重试');
          setUploadProgress((current) => ({
            ...current,
            [uploadKey]: { ...current[uploadKey], status: 'exception' },
          }));
          options.onError?.(reason as any);
          messageApi.error(reason.message);
          window.setTimeout(() => {
            setUploadProgress((current) => {
              const next = { ...current };
              delete next[uploadKey];
              return next;
            });
          }, 6000);
        } finally {
          setUploadingCount((count) => Math.max(0, count - 1));
        }
      },
    }),
    [messageApi, path],
  );

  const handleCreateDirectory = async (values: { name: string }) => {
    const name = values.name.trim();
    if (!name) return;
    setDirectoryLoading(true);
    try {
      await createFileResourceDirectory(joinResourcePath(path, name), name);
      messageApi.success('目录已创建');
      setDirectoryModalOpen(false);
      directoryForm.resetFields();
      reload();
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '新建目录失败');
    } finally {
      setDirectoryLoading(false);
    }
  };

  const handleDelete = async (resource: FileResourceEntry) => {
    try {
      await deleteFileResource(resource);
      messageApi.success(`${resourceName(resource)} 已删除`);
      reload();
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '删除文件资源失败');
    }
  };

  const handleDownload = async (resource: FileResourceEntry) => {
    try {
      const result = await downloadFileResource(resource);
      const blob = downloadBlob(result);
      if (!blob) throw new Error('下载响应不是文件内容');
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = resourceName(resource);
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '文件下载失败');
    }
  };

  const loadUploadRecords = async (pageNo = 1, pageSize = 10) => {
    setRecordsLoading(true);
    try {
      setRecords(await fetchFileResourceUploadRecords({ pageNo, pageSize }));
    } catch (error) {
      setRecords(emptyUploadRecords);
      messageApi.error(error instanceof Error ? error.message : '上传记录加载失败');
    } finally {
      setRecordsLoading(false);
    }
  };

  const openUploadRecords = () => {
    setRecordsModalOpen(true);
    void loadUploadRecords();
  };

  const handleSelectForReturn = (resource: FileResourceEntry) => {
    if (!returnTo || !returnTo.startsWith('/') || returnTo.startsWith('//')) {
      messageApi.error('返回地址无效，请从任务配置页重新打开资源选择器');
      return;
    }
    const selectedResource = {
      ...resource,
      path: resourcePath(resource, path),
    };
    history.push(appendResourceSelection(returnTo, selectedResource, selection));
  };

  const recordColumns: TableColumnsType<FileResourceUploadRecord> = [
    {
      title: '目标目录',
      key: 'path',
      ellipsis: true,
      render: (_, record) => record.targetPath || record.path || '/',
    },
    {
      title: '文件数',
      key: 'fileCount',
      width: 100,
      render: (_, record) => record.fileCount ?? record.totalFiles ?? 0,
    },
    {
      title: '总大小',
      key: 'totalSize',
      width: 120,
      render: (_, record) => formatBytes(record.totalSize),
    },
    {
      title: '状态',
      key: 'status',
      width: 120,
      render: (_, record) => {
        const status = String(record.status || 'UNKNOWN').toUpperCase();
        return <Tag color={uploadRecordStatusColor[status]}>{uploadRecordStatusLabel[status] || status}</Tag>;
      },
    },
    {
      title: '完成时间',
      key: 'finishTime',
      width: 190,
      render: (_, record) => formatResourceTime(record.finishTime || record.createTime || record.startTime),
    },
    {
      title: '说明',
      key: 'errorMessage',
      ellipsis: true,
      render: (_, record) => record.errorMessage || '-',
    },
  ];

  return (
    <PageContainer className="file-resources-page" title="湖文件管理" subTitle="管理数据湖对象存储中的文件与目录">
      {contextHolder}
      <Card className="file-resources-page__hero" variant="borderless">
        <Row gutter={[20, 16]} align="middle">
          <Col flex="auto">
            <Space align="start" size={14}>
              <div className="file-resources-page__hero-icon" aria-hidden="true">
                <CloudUploadOutlined />
              </div>
              <div>
                <Title level={4}>湖文件区</Title>
                <Paragraph type="secondary">
                  浏览与管理 MinIO 等湖侧对象存储中的文件，供离线文件导入与文件同步任务按需引用。
                </Paragraph>
              </div>
            </Space>
          </Col>
          <Col>
            <div className="file-resources-page__hero-stat">
              <Text type="secondary">当前目录</Text>
              <strong title={path}>{path}</strong>
            </div>
          </Col>
        </Row>
      </Card>

      {selectionMode ? (
        <Alert
          className="file-resources-page__selection-alert"
          type="info"
          showIcon
          message="正在为任务选择文件资源"
          description="请选择一个文件，选定后将返回任务配置页并保留当前选择上下文。"
          action={<Button onClick={() => returnTo && history.push(returnTo)}>返回任务</Button>}
        />
      ) : null}

      <Card className="file-resources-page__workspace" variant="borderless">
        <div className="file-resources-page__toolbar">
          <div className="file-resources-page__location">
            <FolderOpenOutlined />
            <ResourceBreadcrumb path={path} onNavigate={setPath} />
          </div>
          <Space wrap>
            <Upload {...uploadProps}>
              <Button icon={<UploadOutlined />} loading={uploadingCount > 0}>
                上传文件
              </Button>
            </Upload>
            <Upload {...uploadProps} directory>
              <Button icon={<FolderOpenOutlined />} loading={uploadingCount > 0}>
                上传文件夹
              </Button>
            </Upload>
            <Button icon={<FolderAddOutlined />} onClick={() => setDirectoryModalOpen(true)}>
              新建目录
            </Button>
            <Button icon={<HistoryOutlined />} onClick={openUploadRecords}>
              上传记录
            </Button>
            <Button aria-label="刷新目录" icon={<ReloadOutlined />} onClick={reload} />
          </Space>
        </div>

        {Object.entries(uploadProgress).length > 0 ? (
          <div className="file-resources-page__upload-progress" aria-live="polite">
            {Object.entries(uploadProgress).map(([key, progress]) => (
              <div className="file-resources-page__upload-progress-item" key={key}>
                <div className="file-resources-page__upload-progress-heading">
                  <Text ellipsis title={progress.name}>{progress.name}</Text>
                  <Text type="secondary">
                    {formatBytes(progress.loaded)} / {formatBytes(progress.total)}
                  </Text>
                </div>
                <Progress
                  percent={progress.percent}
                  status={progress.status}
                  size="small"
                />
              </div>
            ))}
          </div>
        ) : null}

        <FileResourceBrowser
          path={path}
          onPathChange={setPath}
          refreshToken={refreshToken}
          selectable={selectionMode}
          onSelect={selectionMode ? handleSelectForReturn : undefined}
          onDownload={handleDownload}
          onDelete={handleDelete}
          notice={
            <div className="file-resources-page__hint">
              <ClockCircleOutlined />
              <span>目录浏览只展示当前层级；进入文件夹即可继续管理下一级资源。</span>
            </div>
          }
        />
      </Card>

      <Modal
        title="新建目录"
        open={directoryModalOpen}
        confirmLoading={directoryLoading}
        destroyOnHidden
        onCancel={() => {
          setDirectoryModalOpen(false);
          directoryForm.resetFields();
        }}
        onOk={() => directoryForm.submit()}
      >
        <Form form={directoryForm} layout="vertical" onFinish={handleCreateDirectory}>
          <Form.Item
            name="name"
            label="目录名称"
            rules={[
              { required: true, message: '请输入目录名称' },
              { max: 128, message: '目录名称不能超过 128 个字符' },
              { pattern: /^[^\\/]+$/, message: '目录名称不能包含斜杠' },
            ]}
          >
            <Input prefix={<FolderAddOutlined />} placeholder="例如：2026-年度数据" autoFocus />
          </Form.Item>
          <Text type="secondary">目录将创建在当前路径：{path}</Text>
        </Form>
      </Modal>

      <Modal
        title="上传记录"
        open={recordsModalOpen}
        width={920}
        footer={null}
        destroyOnHidden
        onCancel={() => setRecordsModalOpen(false)}
      >
        <Spin spinning={recordsLoading}>
          <Table<FileResourceUploadRecord>
            rowKey={(record) => String(record.id || `${record.path}-${record.createTime}`)}
            columns={recordColumns}
            dataSource={records.bizData}
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无上传记录" /> }}
            pagination={{
              current: records.pagination.pageNo,
              pageSize: records.pagination.pageSize,
              total: records.pagination.total,
              showSizeChanger: true,
              showTotal: (total) => `共 ${total} 条`,
              onChange: (page, pageSize) => void loadUploadRecords(page, pageSize),
            }}
          />
        </Spin>
      </Modal>
    </PageContainer>
  );
};

export { buildResourceManagerUrl };
export { default as FileResourceBrowser } from './components/FileResourceBrowser';
export { default as FileResourcePicker } from './components/FileResourcePicker';
export default FileResourcesPage;
