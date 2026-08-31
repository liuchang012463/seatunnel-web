import {
  ApiOutlined,
  ArrowLeftOutlined,
  CloudOutlined,
  CodeOutlined,
  FileOutlined,
  FolderOpenOutlined,
  GlobalOutlined,
  InboxOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { Alert, Breadcrumb, Button, Drawer, Empty, Input, Spin, Tabs, Tag } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import DatabaseIcons from '../icon/DatabaseIcons';
import {
  fetchDataSourceCatalogFiles,
  fetchDataSourceCatalogOptions,
} from '../service';
import type {
  DataSourceCatalogFileEntry,
  DataSourceCatalogOption,
} from '../types';
import './GenericDataExplorationDrawer.less';

export const GENERIC_EXPLORATION_DB_TYPES = [
  'KAFKA',
  'ELASTICSEARCH',
  'HTTP',
  'MINIO',
  'S3',
  'FTP',
  'SFTP',
] as const;

export type GenericExplorationDbType = (typeof GENERIC_EXPLORATION_DB_TYPES)[number];

export function normalizeExplorationDbType(dbType?: string): string {
  return String(dbType || '').trim().toUpperCase().replace(/-/g, '_');
}

export function isGenericExplorationDbType(dbType?: string): dbType is GenericExplorationDbType {
  return (GENERIC_EXPLORATION_DB_TYPES as readonly string[]).includes(normalizeExplorationDbType(dbType));
}

export function genericMetadataTerm(dbType?: string): string {
  switch (normalizeExplorationDbType(dbType)) {
    case 'KAFKA':
      return 'Kafka 主题';
    case 'ELASTICSEARCH':
      return 'ES 索引';
    case 'HTTP':
      return 'HTTP 接口';
    case 'MINIO':
      return 'MinIO 对象';
    case 'S3':
      return 'S3 对象';
    case 'FTP':
    case 'SFTP':
      return '文件';
    default:
      return '资源';
  }
}

/**
 * Compatibility export for callers that still use the pre-Metadata naming.
 * The returned label is intentionally resource-oriented and never exposes the
 * retired “asset” terminology in the UI.
 */
export const genericAssetTerm = genericMetadataTerm;

function genericMetadataEnglishTerm(dbType?: string): string {
  switch (normalizeExplorationDbType(dbType)) {
    case 'KAFKA':
      return 'KAFKA TOPIC';
    case 'ELASTICSEARCH':
      return 'ELASTICSEARCH INDEX';
    case 'HTTP':
      return 'HTTP ENDPOINT';
    case 'MINIO':
    case 'S3':
      return 'OBJECT STORAGE';
    case 'FTP':
    case 'SFTP':
      return 'REMOTE FILE';
    default:
      return 'RESOURCE';
  }
}

function isFileType(dbType?: string): boolean {
  const normalized = normalizeExplorationDbType(dbType);
  return normalized === 'MINIO' || normalized === 'S3' || normalized === 'FTP' || normalized === 'SFTP';
}

function isDirectory(entry: DataSourceCatalogFileEntry): boolean {
  return String(entry.type || '').toUpperCase() === 'DIRECTORY'
    || String(entry.path || '').endsWith('/');
}

function formatBytes(size?: number): string {
  if (size === undefined || size === null || Number.isNaN(Number(size))) return '—';
  const value = Number(size);
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`;
  return `${(value / 1024 / 1024 / 1024).toFixed(1)} GB`;
}

function formatTime(timestamp?: number): string {
  if (!timestamp) return '—';
  const date = new Date(timestamp);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString();
}

function assetKey(value: unknown, fallback?: string): string {
  return String(value ?? fallback ?? '');
}

function normalizeOptions(data?: DataSourceCatalogOption[]): DataSourceCatalogOption[] {
  return Array.isArray(data) ? data.filter((item) => item && (item.label || item.value !== undefined)) : [];
}

function normalizeFiles(data?: DataSourceCatalogFileEntry[]): DataSourceCatalogFileEntry[] {
  return Array.isArray(data) ? data.filter((item) => item && (item.name || item.path)) : [];
}

export interface GenericDataExplorationDrawerProps {
  dataSourceId?: string;
  dataSourceName?: string;
  dbType?: string;
  open: boolean;
  /** Render the catalog workspace in the current page instead of an Ant Drawer. */
  inline?: boolean;
  onClose: () => void;
}

const GenericDataExplorationDrawer: React.FC<GenericDataExplorationDrawerProps> = ({
  dataSourceId,
  dataSourceName,
  dbType,
  open,
  inline = false,
  onClose,
}) => {
  const normalizedDbType = normalizeExplorationDbType(dbType);
  const fileSource = isFileType(normalizedDbType);
  const term = genericMetadataTerm(normalizedDbType);
  const [loading, setLoading] = useState(false);
  const [catalogError, setCatalogError] = useState<string>();
  const [options, setOptions] = useState<DataSourceCatalogOption[]>([]);
  const [files, setFiles] = useState<DataSourceCatalogFileEntry[]>([]);
  const [path, setPath] = useState('');
  const [search, setSearch] = useState('');
  const [selectedKey, setSelectedKey] = useState<string>();

  useEffect(() => {
    if (!open || !dataSourceId) return;
    setCatalogError(undefined);
    setOptions([]);
    setFiles([]);
    setPath('');
    setSearch('');
    setSelectedKey(undefined);
  }, [dataSourceId, dbType, open]);

  const loadCatalog = useCallback(async () => {
    if (!open || !dataSourceId) return;
    setLoading(true);
    setCatalogError(undefined);
    try {
      if (fileSource) {
        const response = await fetchDataSourceCatalogFiles(dataSourceId, path || undefined);
        if (response.code !== 0) {
          setFiles([]);
          setCatalogError(response.message || `无法读取${term}目录`);
          return;
        }
        const nextFiles = normalizeFiles(response.data);
        setFiles(nextFiles);
        setSelectedKey((current) => {
          if (current && nextFiles.some((entry) => assetKey(entry.path, entry.name) === current)) return current;
          return nextFiles[0] ? assetKey(nextFiles[0].path, nextFiles[0].name) : undefined;
        });
      } else {
        const response = await fetchDataSourceCatalogOptions(dataSourceId);
        const nextOptions = normalizeOptions(response.data);
        if (response.code !== 0) {
          setCatalogError(response.message || `无法读取${term}目录`);
          // Keep a selectable interface identity when the catalog request
          // fails so the detail surface still explains the connection issue.
          if (normalizedDbType === 'HTTP') {
            const fallback = {
              value: dataSourceName || 'http-root',
              label: dataSourceName || 'HTTP 基础接口',
              description: '当前 HTTP 数据源未配置可浏览的接口路径',
            };
            setOptions([fallback]);
            setSelectedKey(assetKey(fallback.value, fallback.label));
          } else {
            setOptions([]);
            setSelectedKey(undefined);
          }
          return;
        }
        const resolvedOptions = normalizedDbType === 'HTTP' && nextOptions.length === 0
          ? [{
            value: dataSourceName || 'http-root',
            label: dataSourceName || 'HTTP 基础接口',
            description: '请在数据源连接中配置 OpenAPI 文档地址',
          }]
          : nextOptions;
        setOptions(resolvedOptions);
        setSelectedKey((current) => {
          if (current && resolvedOptions.some((item) => assetKey(item.value, item.label) === current)) return current;
          return resolvedOptions[0] ? assetKey(resolvedOptions[0].value, resolvedOptions[0].label) : undefined;
        });
      }
    } catch (error: any) {
      setCatalogError(error?.response?.data?.message || error?.message || `无法读取${term}目录`);
      if (normalizedDbType === 'HTTP') {
        const fallback = {
          value: dataSourceName || 'http-root',
          label: dataSourceName || 'HTTP 基础接口',
          description: '当前 HTTP 数据源未配置可浏览的接口路径',
        };
        setOptions([fallback]);
        setSelectedKey(assetKey(fallback.value, fallback.label));
      } else if (fileSource) {
        setFiles([]);
      } else {
        setOptions([]);
        setSelectedKey(undefined);
      }
    } finally {
      setLoading(false);
    }
  }, [dataSourceId, dataSourceName, fileSource, normalizedDbType, open, path, term]);

  useEffect(() => {
    void loadCatalog();
  }, [loadCatalog]);

  const visibleOptions = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    if (!keyword) return options;
    return options.filter((item) => [item.label, item.value, item.description]
      .some((value) => String(value ?? '').toLowerCase().includes(keyword)));
  }, [options, search]);

  const visibleFiles = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    if (!keyword) return files;
    return files.filter((item) => [item.name, item.path, item.type]
      .some((value) => String(value ?? '').toLowerCase().includes(keyword)));
  }, [files, search]);

  const selectedOption = useMemo(
    () => options.find((item) => assetKey(item.value, item.label) === selectedKey),
    [options, selectedKey],
  );
  const selectedFile = useMemo(
    () => files.find((item) => assetKey(item.path, item.name) === selectedKey),
    [files, selectedKey],
  );
  const selectedName = selectedFile?.name || selectedOption?.label;
  const selectedValue = selectedFile?.path || selectedOption?.value || selectedName || '';
  const catalogReadFailed = Boolean(catalogError);
  const pathParts = path.split('/').filter(Boolean);
  const rootPath = path.startsWith('/') ? '/' : '';
  const breadcrumbItems = [
    {
      title: <button type="button" className="generic-exploration__breadcrumb-button" onClick={() => setPath(rootPath)}>根目录</button>,
    },
    ...pathParts.map((part, index) => {
      const nextPath = `${rootPath}${pathParts.slice(0, index + 1).join('/')}`;
      return {
        title: <button type="button" className="generic-exploration__breadcrumb-button" onClick={() => setPath(nextPath)}>{part}</button>,
      };
    }),
  ];

  const listCount = fileSource ? visibleFiles.length : visibleOptions.length;
  const sourceIcon = fileSource ? <FolderOpenOutlined /> : normalizedDbType === 'HTTP' ? <ApiOutlined /> : <InboxOutlined />;

  const explorationTitle = (
    <div className="generic-exploration__title">
      <div className="generic-exploration__title-icon">
        <DatabaseIcons dbType={normalizedDbType} width="22" height="22" />
      </div>
      <div className="generic-exploration__title-copy">
        <strong>数据探查</strong>
        <span>{dataSourceName || '正在连接数据源'} · {term}</span>
      </div>
      <Tag color="blue">{normalizedDbType || '未知类型'}</Tag>
    </div>
  );

  const workspace = (
      <div className="generic-exploration__workspace">
        <aside className="generic-exploration__nav" aria-label={`${term}目录`}>
          <div className="generic-exploration__nav-head">
            <div className="generic-exploration__eyebrow">CATALOG</div>
            <strong>{term}目录</strong>
            <span>浏览连接器返回的一级资源</span>
          </div>
          {fileSource && path && (
            <Button
              type="text"
              size="small"
              icon={<ArrowLeftOutlined />}
              className="generic-exploration__back"
              onClick={() => {
                const parent = path.replace(/\/$/, '').split('/').slice(0, -1).join('/');
                setPath(path.startsWith('/') ? (parent ? `/${parent}` : '/') : parent);
              }}
            >
              返回上级目录
            </Button>
          )}
          <div className="generic-exploration__search-row">
            <Input
              allowClear
              value={search}
              prefix={<SearchOutlined />}
              placeholder={`搜索${term}`}
              onChange={(event) => setSearch(event.target.value)}
            />
            <Button
              type="text"
              size="small"
              aria-label={`刷新${term}目录`}
              icon={<ReloadOutlined />}
              loading={loading}
              onClick={() => void loadCatalog()}
            />
          </div>
          {fileSource && (
            <Breadcrumb className="generic-exploration__breadcrumb" items={breadcrumbItems} />
          )}
          <div className="generic-exploration__nav-list">
            <Spin spinning={loading} size="small">
              {fileSource ? visibleFiles.map((entry) => {
                const key = assetKey(entry.path, entry.name);
                const directory = isDirectory(entry);
                return (
                  <button
                    type="button"
                    className={`generic-exploration__asset-item${key === selectedKey ? ' is-selected' : ''}`}
                    key={key}
                    onClick={() => {
                      if (directory) {
                        setPath(entry.path || entry.name || '');
                        setSearch('');
                      } else {
                        setSelectedKey(key);
                      }
                    }}
                  >
                    <span className="generic-exploration__asset-item-icon">{directory ? <FolderOpenOutlined /> : <FileOutlined />}</span>
                    <span className="generic-exploration__asset-item-copy">
                      <strong title={entry.name}>{entry.name || entry.path}</strong>
                      <small title={entry.path}>{directory ? '目录' : `${formatBytes(entry.size)} · ${entry.type || '文件'}`}</small>
                    </span>
                    {directory && <span className="generic-exploration__asset-item-arrow">›</span>}
                  </button>
                );
              }) : visibleOptions.map((item) => {
                const key = assetKey(item.value, item.label);
                return (
                  <button
                    type="button"
                    className={`generic-exploration__asset-item${key === selectedKey ? ' is-selected' : ''}`}
                    key={key}
                    onClick={() => setSelectedKey(key)}
                  >
                    <span className="generic-exploration__asset-item-icon">{sourceIcon}</span>
                    <span className="generic-exploration__asset-item-copy">
                      <strong title={item.label}>{item.label || item.value}</strong>
                      <small title={String(item.value ?? '')}>{item.description || term}</small>
                    </span>
                  </button>
                );
              })}
              {!loading && listCount === 0 && (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={`暂无${term}`} />
              )}
            </Spin>
          </div>
          <div className="generic-exploration__nav-footer">{listCount} 项资源</div>
        </aside>

        <main className="generic-exploration__main" aria-label={`${term}详情`}>
          {catalogError && (
            <Alert
              className="generic-exploration__alert"
              type="warning"
              showIcon
              message="目录读取提示"
              description={catalogError}
            />
          )}
          {loading && !selectedOption && !selectedFile ? (
            <div className="generic-exploration__state"><Spin /></div>
          ) : !selectedOption && !selectedFile ? (
            <div className="generic-exploration__state">
              <div className="generic-exploration__state-icon">{sourceIcon}</div>
              <strong>选择一个{term}开始查看</strong>
              <span>从左侧目录选择资源，查看身份、概览和连接信息。</span>
            </div>
          ) : (
            <>
              <header className="generic-exploration__asset-header">
                <div className="generic-exploration__asset-copy">
                  <div className="generic-exploration__eyebrow">{genericMetadataEnglishTerm(normalizedDbType)}</div>
                  <h1 title={selectedName}>{selectedName}</h1>
                  <div className="generic-exploration__asset-value" title={String(selectedValue)}>
                    <span>IDENTIFIER</span>
                    <code>{String(selectedValue)}</code>
                  </div>
                </div>
                <div className="generic-exploration__asset-badges">
                  <Tag color={catalogReadFailed ? 'warning' : 'blue'}>{term}</Tag>
                  <span>
                    <span className={`generic-exploration__status-dot${catalogReadFailed ? ' is-error' : ''}`} />
                    {catalogReadFailed ? 'Catalog 读取失败' : 'Catalog 已读取'}
                  </span>
                </div>
              </header>
              <div className="generic-exploration__summary">
                <span>{sourceIcon} {fileSource ? (isDirectory(selectedFile || {}) ? '目录' : selectedFile?.type || '文件') : normalizedDbType}</span>
                <span><CodeOutlined /> {String(selectedValue)}</span>
                {selectedFile && <span><CloudOutlined /> {formatBytes(selectedFile.size)}</span>}
              </div>
              <Tabs
                className="generic-exploration__tabs"
                items={[
                  {
                    key: 'overview',
                    label: '概览',
                    children: (
                      <div className="generic-exploration__tab-pane">
                        <div className="generic-exploration__tab-title">
                          <div><div className="generic-exploration__eyebrow">RESOURCE OVERVIEW</div><strong>资源身份</strong></div>
                          <Tag>{term}</Tag>
                        </div>
                        <div className="generic-exploration__overview-grid">
                          <div><span>资源名称</span><strong>{selectedName}</strong></div>
                          <div><span>数据源类型</span><strong>{normalizedDbType || '—'}</strong></div>
                          <div><span>资源标识</span><code>{String(selectedValue)}</code></div>
                          <div><span>目录位置</span><strong>{fileSource ? (path || '根目录') : '数据源 catalog'}</strong></div>
                        </div>
                        <div className="generic-exploration__description">
                          <span>描述</span>
                          <p>{selectedFile?.type || selectedOption?.description || `这是一个${term}，当前展示连接器可读取的元数据。`}</p>
                        </div>
                      </div>
                    ),
                  },
                  {
                    key: 'details',
                    label: '详情',
                    children: (
                      <div className="generic-exploration__tab-pane">
                        <div className="generic-exploration__tab-title">
                          <div><div className="generic-exploration__eyebrow">METADATA</div><strong>原始目录属性</strong></div>
                        </div>
                        <dl className="generic-exploration__property-list">
                          <div><dt>名称</dt><dd>{selectedName}</dd></div>
                          <div><dt>路径 / 标识</dt><dd><code>{String(selectedValue)}</code></dd></div>
                          <div><dt>类型</dt><dd>{selectedFile?.type || selectedOption?.description || term}</dd></div>
                          {selectedFile && <div><dt>大小</dt><dd>{formatBytes(selectedFile.size)}</dd></div>}
                          {selectedFile && <div><dt>修改时间</dt><dd>{formatTime(selectedFile.modifiedTime)}</dd></div>}
                        </dl>
                      </div>
                    ),
                  },
                ]}
              />
            </>
          )}
        </main>

        <aside className="generic-exploration__inspector" aria-label="连接与探查信息">
          <div className="generic-exploration__inspector-head">
            <div className="generic-exploration__eyebrow">INSPECTOR</div>
            <strong>连接 / 探查信息</strong>
          </div>
          <div className="generic-exploration__inspector-body">
            <section>
              <div className="generic-exploration__section-title">连接器</div>
              <dl className="generic-exploration__property-list">
                <div><dt>数据源</dt><dd>{dataSourceName || '—'}</dd></div>
                <div><dt>类型</dt><dd>{normalizedDbType || '—'}</dd></div>
                <div><dt>资源类型</dt><dd>{term}</dd></div>
              </dl>
            </section>
            <section>
              <div className="generic-exploration__section-title">当前探查</div>
              <div className="generic-exploration__agent-status">
                <span className={`generic-exploration__status-dot${catalogReadFailed ? ' is-error' : ''}`} />
                <strong>{catalogReadFailed ? '目录读取失败' : '目录已加载'}</strong>
              </div>
              <p className="generic-exploration__muted">
                {catalogReadFailed
                  ? `暂时无法读取${term}目录，请检查连接器配置后重试。`
                  : '结果来自 SeaTunnel 数据源 catalog。目录刷新为一次性读取，不会创建定时任务。'}
              </p>
            </section>
            <section>
              <div className="generic-exploration__section-title">选中资源</div>
              <p className="generic-exploration__selected-path" title={String(selectedValue)}>{selectedName || '未选择资源'}</p>
              <Tag color={selectedOption || selectedFile ? 'blue' : 'default'}>{selectedOption || selectedFile ? '已选择' : '等待选择'}</Tag>
            </section>
          </div>
        </aside>
      </div>
  );

  return (
    <>
      {inline ? (
        <section className="generic-exploration generic-exploration--inline" aria-label="数据探查详情">
          <div className="generic-exploration__inline-header">
            {explorationTitle}
            <Button type="text" icon={<ArrowLeftOutlined />} onClick={onClose}>返回探查结果</Button>
          </div>
          {workspace}
        </section>
      ) : (
        <Drawer
          className="generic-exploration"
          open={open}
          onClose={onClose}
          width="min(1500px, calc(100vw - 40px))"
          destroyOnHidden
          title={explorationTitle}
          styles={{ body: { padding: 0 } }}
        >
          {workspace}
        </Drawer>
      )}
    </>
  );
};

export default GenericDataExplorationDrawer;
