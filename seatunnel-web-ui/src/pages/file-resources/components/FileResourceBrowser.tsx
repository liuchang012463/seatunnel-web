import {
  CheckOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  FileOutlined,
  FolderOpenOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { ProTable } from '@ant-design/pro-components';
import type { ProColumns } from '@ant-design/pro-components';
import { Alert, Button, Empty, Input, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import React, { useEffect, useMemo, useState } from 'react';
import { fetchFileResourcePage } from '../service';
import type { FileResourceEntry, FileResourceId, FileResourcePagination } from '../types';
import {
  formatBytes,
  formatResourceTime,
  isDirectoryResource,
  isDocumentPreviewable,
  resourceId,
  resourceMatchesExtensionGroup,
  resourceName,
  resourcePath,
} from '../utils';

type ResourceTypeFilter = 'ALL' | 'DIRECTORY' | 'TXT' | 'CSV' | 'JSON' | 'EXCEL' | 'PDF' | 'WORD';

const RESOURCE_TYPE_OPTIONS: { label: string; value: ResourceTypeFilter }[] = [
  { label: '全部类型', value: 'ALL' },
  { label: '文件夹', value: 'DIRECTORY' },
  { label: 'TXT', value: 'TXT' },
  { label: 'CSV', value: 'CSV' },
  { label: 'JSON', value: 'JSON' },
  { label: 'Excel', value: 'EXCEL' },
  { label: 'PDF', value: 'PDF' },
  { label: 'Word', value: 'WORD' },
];

const RESOURCE_TYPE_EXTENSIONS: Record<Exclude<ResourceTypeFilter, 'ALL' | 'DIRECTORY'>, string[]> = {
  TXT: ['txt', 'text'],
  CSV: ['csv'],
  JSON: ['json'],
  EXCEL: ['xls', 'xlsx'],
  PDF: ['pdf'],
  WORD: ['doc', 'docx'],
};

export interface FileResourceBrowserProps {
  path: string;
  onPathChange: (path: string) => void;
  refreshToken?: number;
  selectable?: boolean;
  selectedId?: FileResourceId;
  onSelect?: (resource: FileResourceEntry) => void;
  canSelect?: (resource: FileResourceEntry) => boolean;
  onDownload?: (resource: FileResourceEntry) => void | Promise<void>;
  onPreview?: (resource: FileResourceEntry) => void | Promise<void>;
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
  onPreview,
  onDelete,
  notice,
  compact = false,
  pageSize = 20,
}) => {
  const [searchText, setSearchText] = useState('');
  const [keyword, setKeyword] = useState('');
  const [resourceTypeFilter, setResourceTypeFilter] = useState<ResourceTypeFilter>('ALL');
  const [resources, setResources] = useState<FileResourceEntry[]>([]);
  const [pagination, setPagination] = useState<FileResourcePagination>({ pageNo: 1, pageSize, total: 0 });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [reloadVersion, setReloadVersion] = useState(0);

  useEffect(() => {
    setSearchText('');
    setKeyword('');
    setResourceTypeFilter('ALL');
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
    return resources.filter((resource) => {
      if (resourceTypeFilter === 'DIRECTORY') {
        if (!isDirectoryResource(resource)) return false;
      } else if (resourceTypeFilter !== 'ALL') {
        if (!resourceMatchesExtensionGroup(resource, RESOURCE_TYPE_EXTENSIONS[resourceTypeFilter])) {
          return false;
        }
      }
      if (!normalizedKeyword) return true;
      return resourceName(resource).toLocaleLowerCase().includes(normalizedKeyword);
    });
  }, [keyword, resourceTypeFilter, resources]);

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
          const canActivate = directory || (selectable && (canSelect?.(record) ?? true));
          const labelNode = canActivate ? (
            <Button
              type="link"
              size="small"
              className="file-resource-browser__name-button"
              onClick={handleClick}
              title={label}
              aria-label={directory ? `进入目录 ${label}` : `选择文件 ${label}`}
            >
              {label}
            </Button>
          ) : (
            <span className="file-resource-browser__name-label" title={label}>
              {label}
            </span>
          );

          return (
            <Space size={8} className="file-resource-browser__name">
              {directory ? <FolderOpenOutlined className="file-resource-browser__folder-icon" /> : <FileOutlined />}
              {labelNode}
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
        width: 70,
        render: (_, record) => {
          const status = String(record.status || 'READY').toUpperCase();
          return <Tag color={statusColor[status]}>{statusLabel[status] || status}</Tag>;
        },
      },
    ];

    if (selectable || onDownload || onPreview || onDelete) {
      result.push({
        title: '操作',
        key: 'option',
        valueType: 'option',
        width: onPreview ? 240 : 180,
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
          if (!directory && onPreview && isDocumentPreviewable(record)) {
            actions.push(
              <Button
                key="preview"
                type="link"
                size="small"
                icon={<EyeOutlined />}
                onClick={() => void onPreview(record)}
              >
                预览
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
                description="被任务引用的资源无法删除；未被引用的资源删除后不可恢复。"
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
  }, [canSelect, compact, onDelete, onDownload, onPathChange, onPreview, onSelect, path, selectable, selectedId]);

  const applySearch = (value: string) => {
    setPagination((current) => ({ ...current, pageNo: 1 }));
    setKeyword(value.trim());
  };

  const hasActiveFilter = Boolean(keyword) || resourceTypeFilter !== 'ALL';
  const emptyDescription = hasActiveFilter
    ? '当前目录没有匹配的资源'
    : '当前目录为空，先上传文件或新建目录';

  return (
    <div className={`file-resource-browser${compact ? ' file-resource-browser--compact' : ''}`}>
      {notice}
      <div className="file-resource-browser__search">
        <Input
          allowClear
          value={searchText}
          prefix={<SearchOutlined />}
          placeholder="搜索当前目录"
          onChange={(event) => setSearchText(event.target.value)}
          onPressEnter={() => applySearch(searchText)}
        />
        <Select
          className="file-resource-browser__type-filter"
          value={resourceTypeFilter}
          options={RESOURCE_TYPE_OPTIONS}
          popupMatchSelectWidth={false}
          aria-label="按文件类型筛选"
          onChange={(value: ResourceTypeFilter) => {
            setPagination((current) => ({ ...current, pageNo: 1 }));
            setResourceTypeFilter(value);
          }}
        />
        <Button type="primary" onClick={() => applySearch(searchText)}>
          搜索
        </Button>
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
        scroll={{ x: 'max-content' }}
        pagination={{
          current: pagination.pageNo,
          pageSize: pagination.pageSize,
          total: hasActiveFilter ? visibleResources.length : pagination.total,
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
