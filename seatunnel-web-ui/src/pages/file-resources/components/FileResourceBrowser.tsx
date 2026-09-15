import {
  CheckOutlined,
  DeleteOutlined,
  DownloadOutlined,
  FileOutlined,
  FolderOpenOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { ProTable } from '@ant-design/pro-components';
import type { ProColumns } from '@ant-design/pro-components';
import { Alert, Button, Empty, Input, Popconfirm, Space, Tag, Typography } from 'antd';
import React, { useEffect, useMemo, useState } from 'react';
import { fetchFileResourcePage } from '../service';
import type { FileResourceEntry, FileResourceId, FileResourcePagination } from '../types';
import {
  formatBytes,
  formatResourceTime,
  isDirectoryResource,
  resourceId,
  resourceName,
  resourcePath,
} from '../utils';

export interface FileResourceBrowserProps {
  path: string;
  onPathChange: (path: string) => void;
  refreshToken?: number;
  selectable?: boolean;
  selectedId?: FileResourceId;
  onSelect?: (resource: FileResourceEntry) => void;
  canSelect?: (resource: FileResourceEntry) => boolean;
  onDownload?: (resource: FileResourceEntry) => void | Promise<void>;
  onDelete?: (resource: FileResourceEntry) => void | Promise<void>;
  notice?: React.ReactNode;
  compact?: boolean;
  pageSize?: number;
}

const statusLabel: Record<string, string> = {
  READY: '就绪',
  UPLOADING: '上传中',
  FAILED: '失败',
  DELETED: '已删除',
};

const statusColor: Record<string, string> = {
  READY: 'success',
  UPLOADING: 'processing',
  FAILED: 'error',
  DELETED: 'default',
};

function fileResourceKey(resource: FileResourceEntry): string {
  const id = resourceId(resource);
  return id === undefined ? `${resourceName(resource)}-${resource.path || ''}` : String(id);
}

const FileResourceBrowser: React.FC<FileResourceBrowserProps> = ({
  path,
  onPathChange,
  refreshToken = 0,
  selectable = false,
  selectedId,
  onSelect,
  canSelect,
  onDownload,
  onDelete,
  notice,
  compact = false,
  pageSize = 20,
}) => {
  const [searchText, setSearchText] = useState('');
  const [keyword, setKeyword] = useState('');
  const [resources, setResources] = useState<FileResourceEntry[]>([]);
  const [pagination, setPagination] = useState<FileResourcePagination>({ pageNo: 1, pageSize, total: 0 });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [reloadVersion, setReloadVersion] = useState(0);

  useEffect(() => {
    setSearchText('');
    setKeyword('');
    setPagination((current) => ({ ...current, pageNo: 1, pageSize }));
  }, [path, pageSize]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(undefined);

    fetchFileResourcePage({
      path,
      keyword,
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
    })
      .then((result) => {
        if (!active) return;
        setResources(result.bizData);
        setPagination(result.pagination);
      })
      .catch((reason: unknown) => {
        if (!active) return;
        setResources([]);
        setPagination((current) => ({ ...current, total: 0 }));
        setError(reason instanceof Error ? reason.message : '文件资源列表加载失败，请稍后重试');
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
  }, [keyword, pagination.pageNo, pagination.pageSize, path, refreshToken, reloadVersion]);

  const reload = () => setReloadVersion((version) => version + 1);

  const visibleResources = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLocaleLowerCase();
    if (!normalizedKeyword) return resources;
    return resources.filter((resource) =>
      resourceName(resource).toLocaleLowerCase().includes(normalizedKeyword),
    );
  }, [keyword, resources]);

  const columns = useMemo<ProColumns<FileResourceEntry>[]>(() => {
    const result: ProColumns<FileResourceEntry>[] = [
      {
        title: '名称',
        dataIndex: 'name',
        key: 'name',
        width: compact ? 260 : 360,
        ellipsis: true,
        render: (_, record) => {
          const directory = isDirectoryResource(record);
          const label = resourceName(record);
          const handleClick = () => {
            if (directory) {
              onPathChange(resourcePath(record, path));
            } else if (selectable && (canSelect?.(record) ?? true)) {
              onSelect?.(record);
            }
          };

          return (
            <Space size={8} className="file-resource-browser__name">
              {directory ? <FolderOpenOutlined className="file-resource-browser__folder-icon" /> : <FileOutlined />}
              <Button
                type="link"
                size="small"
                className="file-resource-browser__name-button"
                onClick={handleClick}
                title={label}
              >
                {label}
              </Button>
            </Space>
          );
        },
      },
      {
        title: '类型',
        key: 'resourceType',
        width: 120,
        render: (_, record) => (isDirectoryResource(record) ? <Tag color="blue">文件夹</Tag> : <Tag>文件</Tag>),
      },
      {
        title: '大小',
        key: 'size',
        width: 120,
        render: (_, record) => (isDirectoryResource(record) ? '-' : formatBytes(record.size)),
      },
      {
        title: '更新时间',
        key: 'updateTime',
        width: 190,
        render: (_, record) => formatResourceTime(record.updateTime || record.modifiedTime || record.createTime),
      },
      {
        title: '状态',
        key: 'status',
        width: 110,
        render: (_, record) => {
          const status = String(record.status || 'READY').toUpperCase();
          return <Tag color={statusColor[status]}>{statusLabel[status] || status}</Tag>;
        },
      },
    ];

    if (selectable || onDownload || onDelete) {
      result.push({
        title: '操作',
        key: 'option',
        valueType: 'option',
        width: compact ? 180 : 220,
        fixed: 'right',
        render: (_, record) => {
          const directory = isDirectoryResource(record);
          const actions: React.ReactNode[] = [];
          if (directory) {
            actions.push(
              <Button
                key="open"
                type="link"
                size="small"
                icon={<FolderOpenOutlined />}
                onClick={() => onPathChange(resourcePath(record, path))}
              >
                进入
              </Button>,
            );
            if (selectable && (canSelect?.(record) ?? false)) {
              actions.push(
                <Button
                  key="select-directory"
                  type={resourceId(record) === selectedId ? 'primary' : 'link'}
                  size="small"
                  icon={<CheckOutlined />}
                  onClick={() => onSelect?.(record)}
                >
                  {resourceId(record) === selectedId ? '已选择' : '选择'}
                </Button>,
              );
            }
          } else if (selectable && (canSelect?.(record) ?? true)) {
            actions.push(
              <Button
                key="select"
                type={resourceId(record) === selectedId ? 'primary' : 'link'}
                size="small"
                icon={<CheckOutlined />}
                onClick={() => onSelect?.(record)}
              >
                {resourceId(record) === selectedId ? '已选择' : '选择'}
              </Button>,
            );
          }
          if (!directory && onDownload) {
            actions.push(
              <Button
                key="download"
                type="link"
                size="small"
                icon={<DownloadOutlined />}
                onClick={() => void onDownload(record)}
              >
                下载
              </Button>,
            );
          }
          if (onDelete) {
            actions.push(
              <Popconfirm
                key="delete"
                title={directory ? '确定删除这个文件夹及其内容吗？' : '确定删除这个文件吗？'}
                description="删除后，引用该资源的任务将无法读取。"
                okText="删除"
                cancelText="取消"
                okButtonProps={{ danger: true }}
                onConfirm={() => onDelete(record)}
              >
                <Button type="link" danger size="small" icon={<DeleteOutlined />}>
                  删除
                </Button>
              </Popconfirm>,
            );
          }
          return <Space size={0}>{actions}</Space>;
        },
      });
    }

    return result;
  }, [canSelect, compact, onDelete, onDownload, onPathChange, onSelect, path, selectable, selectedId]);

  const applySearch = (value: string) => {
    setPagination((current) => ({ ...current, pageNo: 1 }));
    setKeyword(value.trim());
  };

  const emptyDescription = keyword ? '当前目录没有匹配的资源' : '当前目录为空，先上传文件或新建目录';

  return (
    <div className={`file-resource-browser${compact ? ' file-resource-browser--compact' : ''}`}>
      {notice}
      <div className="file-resource-browser__search">
        <Input.Search
          allowClear
          value={searchText}
          prefix={<SearchOutlined />}
          placeholder="搜索当前目录"
          enterButton="搜索"
          onChange={(event) => setSearchText(event.target.value)}
          onSearch={applySearch}
        />
        <Button
          aria-label="刷新文件资源"
          icon={<ReloadOutlined />}
          loading={loading}
          onClick={reload}
        >
          刷新
        </Button>
      </div>
      {error ? (
        <Alert
          className="file-resource-browser__error"
          type="error"
          showIcon
          message={error}
          action={<Button onClick={reload}>重试</Button>}
        />
      ) : null}
      <ProTable<FileResourceEntry>
        rowKey={fileResourceKey}
        columns={columns}
        dataSource={visibleResources}
        loading={loading}
        search={false}
        options={false}
        dateFormatter="string"
        cardBordered={false}
        scroll={{ x: compact ? 760 : 980 }}
        pagination={{
          current: pagination.pageNo,
          pageSize: pagination.pageSize,
          total: keyword ? visibleResources.length : pagination.total,
          showSizeChanger: true,
          showTotal: (total) => `共 ${total} 项`,
          onChange: (page, size) => setPagination((current) => ({ ...current, pageNo: page, pageSize: size || current.pageSize })),
        }}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyDescription} /> }}
        toolbar={{
          title: <Typography.Text type="secondary">当前目录资源</Typography.Text>,
        }}
      />
    </div>
  );
};

export default FileResourceBrowser;
