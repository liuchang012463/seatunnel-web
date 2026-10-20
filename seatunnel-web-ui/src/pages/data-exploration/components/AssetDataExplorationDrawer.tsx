import {
  ApiOutlined,
  ApartmentOutlined,
  ArrowLeftOutlined,
  CloudOutlined,
  DatabaseOutlined,
  EyeOutlined,
  FileOutlined,
  FolderOpenOutlined,
  InboxOutlined,
  InfoCircleOutlined,
  MessageOutlined,
  ReloadOutlined,
  RightOutlined,
  SearchOutlined,
  TableOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Drawer,
  Empty,
  Input,
  Modal,
  Pagination,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Tree,
  message,
} from 'antd';
import type { TableColumnsType } from 'antd';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import {
  fetchDataExplorationAsset,
  fetchDataExplorationAssets,
  fetchDataSourceMetadataStatus,
  retryDataSourceMetadataSync,
  triggerDataSourceScan,
} from '@/pages/data-source/service';
import type {
  DataExplorationAsset,
  DataExplorationAssetDetail,
  DataExplorationAssetField,
  DataExplorationAssetPage,
  DataExplorationAssetRef,
  DataSourceMetadataStatus,
} from '@/pages/data-source/types';
import {
  assetDisplayName,
  assetTypeEyebrow,
  decodeHtmlEntities,
  assetTypeLabel,
  firstTabKey,
  formatAssetBytes,
  formatAssetTime,
  formatPropertyValue,
  normalizeAssetType,
  sampleStatusCopy,
  schemaStatusCopy,
  tabsForAsset,
  type AssetTabKey,
} from './assetExploration';
import AssetExplorationSettingsModal from './AssetExplorationSettingsModal';
import { assetExplorationStatus } from '@/utils/assetExplorationStatus';
import './AssetDataExplorationDrawer.less';

export interface AssetDataExplorationDrawerProps {
  dataSourceId?: string;
  dataSourceName?: string;
  dbType?: string;
  open: boolean;
  inline?: boolean;
  backLabel?: string;
  /** Deep-link target; breadcrumbs come from the detail response. */
  initialAssetId?: string;
  initialAssetType?: string;
  onClose: () => void;
}

interface LevelState {
  parentId?: string;
  parentType?: string;
  breadcrumbs: DataExplorationAssetRef[];
}

/** One node of the API catalog tree: a collection with its endpoints below it. */
interface ApiTreeNode {
  key: string;
  title: string;
  asset: DataExplorationAsset;
  isLeaf: boolean;
  children?: ApiTreeNode[];
}

const PAGE_SIZE = 50;

function rootTerm(dbType?: string): string {
  switch (String(dbType || '').toUpperCase()) {
    case 'KAFKA':
      return '主题';
    case 'ELASTICSEARCH':
      return '索引';
    case 'MINIO':
    case 'S3':
      return '容器';
    case 'FTP':
    case 'SFTP':
      return '目录或文件';
    case 'HTTP':
      return '接口分组';
    default:
      return '元数据';
  }
}

function childTerm(parentType?: string): string {
  switch (normalizeAssetType(parentType)) {
    case 'CONTAINER':
      return '子容器';
    case 'DIRECTORY':
      return '子目录或文件';
    case 'API_COLLECTION':
      return '接口';
    default:
      return '子项';
  }
}

function assetIcon(type?: string) {
  switch (normalizeAssetType(type)) {
    case 'TOPIC':
      return <MessageOutlined />;
    case 'SEARCH_INDEX':
      return <DatabaseOutlined />;
    case 'CONTAINER':
      return <InboxOutlined />;
    case 'DIRECTORY':
      return <FolderOpenOutlined />;
    case 'FILE':
      return <FileOutlined />;
    case 'API_COLLECTION':
    case 'API_ENDPOINT':
      return <ApiOutlined />;
    default:
      return <ApartmentOutlined />;
  }
}

function toRef(asset: DataExplorationAsset): DataExplorationAssetRef {
  return {
    id: asset.id,
    name: assetDisplayName(asset),
    assetType: asset.assetType,
    fullyQualifiedName: asset.fullyQualifiedName,
  };
}

function toApiTreeNode(asset: DataExplorationAsset, isLeaf: boolean): ApiTreeNode {
  return {
    key: String(asset.id || asset.fullyQualifiedName || asset.name),
    title: assetDisplayName(asset),
    asset,
    isLeaf,
  };
}

/** Replaces the children of one tree node, keeping the rest of the tree untouched. */
function withApiTreeChildren(
  nodes: ApiTreeNode[],
  key: string,
  children: ApiTreeNode[],
): ApiTreeNode[] {
  return nodes.map((node) => {
    if (node.key === key) return { ...node, children, isLeaf: children.length === 0 };
    if (node.children && node.children.length > 0) {
      return { ...node, children: withApiTreeChildren(node.children, key, children) };
    }
    return node;
  });
}

function sampleCollectionCopy(status?: DataSourceMetadataStatus) {
  if (!status) return { label: '样本状态加载中', color: 'default' as const };
  if (!status.sampleDataSupported) return { label: '样本不支持', color: 'default' as const };
  if (status?.sampleDataEnabled) return { label: '样本采集已开启', color: 'success' as const };
  return { label: '样本采集未开启', color: 'default' as const };
}

function schemaTag(status?: string) {
  switch (status) {
    case 'AVAILABLE':
      return <Tag color="success">结构已读取</Tag>;
    case 'NEEDS_DATA_MODEL':
      return <Tag color="warning">需配置清单</Tag>;
    case 'NO_SCHEMA':
      return <Tag>无结构</Tag>;
    case 'NOT_APPLICABLE':
      return <Tag>结构不适用</Tag>;
    case 'NOT_SUPPORTED':
      return <Tag>不支持结构</Tag>;
    default:
      return <Tag>结构未知</Tag>;
  }
}

function sampleTag(status?: string) {
  switch (status) {
    case 'AVAILABLE':
      return <Tag color="success">样本已采集</Tag>;
    case 'DISABLED':
      return <Tag>样本未开启</Tag>;
    case 'NOT_COLLECTED':
      return <Tag color="warning">样本未采集</Tag>;
    case 'NEEDS_DATA_MODEL':
      return <Tag color="warning">样本需清单</Tag>;
    case 'NOT_APPLICABLE':
      return <Tag>样本不适用</Tag>;
    case 'NOT_SUPPORTED':
      return <Tag>不支持样本</Tag>;
    case 'PERMISSION_DENIED':
      return <Tag color="error">无样本权限</Tag>;
    case 'ERROR':
      return <Tag color="error">样本读取失败</Tag>;
    default:
      return <Tag>样本未知</Tag>;
  }
}

function flattenFields(
  fields: DataExplorationAssetField[] | undefined,
  depth = 0,
  parentPath = '',
): Array<DataExplorationAssetField & { __key: string; __depth: number }> {
  const result: Array<DataExplorationAssetField & { __key: string; __depth: number }> = [];
  (fields || []).forEach((field, index) => {
    // A field name is only unique among its siblings; the full path keeps React keys
    // unique when different parents expose same-named children.
    const path = `${parentPath}/${index}:${field.name || ''}`;
    const { children, ...row } = field;
    result.push({ ...row, __key: path, __depth: depth });
    result.push(...flattenFields(children, depth + 1, path));
  });
  return result;
}

const fieldColumns: TableColumnsType<DataExplorationAssetField & { __key: string; __depth: number }> = [
  {
    title: '字段',
    dataIndex: 'name',
    key: 'name',
    width: 190,
    render: (value: string, row) => (
      <span style={{ paddingLeft: row.__depth * 14 }}>{value || '—'}</span>
    ),
  },
  { title: '类型', dataIndex: 'dataType', key: 'dataType', width: 160, render: (value) => value || '—' },
  { title: '说明', dataIndex: 'description', key: 'description', ellipsis: true, render: (value) => (value ? decodeHtmlEntities(value) : '—') },
  {
    title: '标签',
    dataIndex: 'tags',
    key: 'tags',
    width: 160,
    render: (tags: string[]) => (tags && tags.length > 0
      ? <span className="asset-exploration__tag-list">{tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}</span>
      : '—'),
  },
];

const AssetDataExplorationDrawer: React.FC<AssetDataExplorationDrawerProps> = ({
  dataSourceId,
  dataSourceName,
  dbType,
  open,
  inline = false,
  backLabel = '返回探查结果',
  initialAssetId,
  initialAssetType,
  onClose,
}) => {
  const [metadataStatus, setMetadataStatus] = useState<DataSourceMetadataStatus>();
  const [metadataLoading, setMetadataLoading] = useState(false);
  const [level, setLevel] = useState<LevelState>({ breadcrumbs: [] });
  const [pageNo, setPageNo] = useState(1);
  const [search, setSearch] = useState('');
  const [page, setPage] = useState<DataExplorationAssetPage>();
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState<string>();
  const [selected, setSelected] = useState<{ id: string; type: string }>();
  const [detail, setDetail] = useState<DataExplorationAssetDetail>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string>();
  const [activeTab, setActiveTab] = useState<AssetTabKey>();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [apiTree, setApiTree] = useState<ApiTreeNode[]>([]);
  const pageRequest = useRef(0);
  const detailRequest = useRef(0);
  const refreshTimer = useRef<number>();
  // API services are browsed as one tree: the groups carry their endpoints, so the
  // request and response structure of an interface is one click away.
  const isApiCatalog = String(dbType || '').trim().toUpperCase() === 'HTTP';

  useEffect(() => () => {
    if (refreshTimer.current) {
      window.clearTimeout(refreshTimer.current);
    }
  }, []);

  const loadMetadataStatus = useCallback(async () => {
    if (!dataSourceId) return;
    setMetadataLoading(true);
    try {
      const response = await fetchDataSourceMetadataStatus(dataSourceId);
      if (response.code === 0) {
        setMetadataStatus(response.data);
      }
    } catch {
      // The asset list still works; only the header status is unavailable.
    } finally {
      setMetadataLoading(false);
    }
  }, [dataSourceId]);

  const loadPage = useCallback(async (
    requestedLevel: LevelState,
    requestedPageNo: number,
    requestedSearch: string,
  ) => {
    if (!dataSourceId) return;
    const requestId = ++pageRequest.current;
    setListLoading(true);
    setListError(undefined);
    try {
      const response = await fetchDataExplorationAssets({
        dataSourceId,
        parentId: requestedLevel.parentId,
        parentType: requestedLevel.parentType,
        pageNo: requestedPageNo,
        pageSize: PAGE_SIZE,
        search: requestedSearch,
      });
      if (requestId !== pageRequest.current) return;
      if (response.code !== 0 || !response.data) {
        setListError(response.message || '无法读取元数据列表');
        return;
      }
      setPage(response.data);
      const nextBreadcrumbs = response.data.breadcrumbs || [];
      setLevel((current) => (
        JSON.stringify(current.breadcrumbs) === JSON.stringify(nextBreadcrumbs)
          ? current
          : { ...current, breadcrumbs: nextBreadcrumbs }
      ));
    } catch (error: any) {
      if (requestId !== pageRequest.current) return;
      setListError(error?.response?.data?.message || error?.message || '无法读取元数据列表');
    } finally {
      if (requestId === pageRequest.current) {
        setListLoading(false);
      }
    }
  }, [dataSourceId]);

  /**
   * Reads the API catalog root (the interface groups). The endpoints below a group are
   * loaded when the group is expanded, so the request and response structure of an
   * interface is reachable without walking into the group first.
   */
  const loadApiTree = useCallback(async (requestedSearch: string) => {
    if (!dataSourceId) return;
    const requestId = ++pageRequest.current;
    setListLoading(true);
    setListError(undefined);
    try {
      const response = await fetchDataExplorationAssets({
        dataSourceId,
        pageNo: 1,
        pageSize: PAGE_SIZE,
        search: requestedSearch,
      });
      if (requestId !== pageRequest.current) return;
      if (response.code !== 0 || !response.data) {
        setListError(response.message || '无法读取接口目录');
        return;
      }
      setApiTree((response.data.records || []).map((asset) => toApiTreeNode(asset, false)));
    } catch (error: any) {
      if (requestId !== pageRequest.current) return;
      setListError(error?.response?.data?.message || error?.message || '无法读取接口目录');
    } finally {
      if (requestId === pageRequest.current) {
        setListLoading(false);
      }
    }
  }, [dataSourceId]);

  /** Loads the endpoints of one interface group. */
  const loadApiChildren = useCallback(async (node: ApiTreeNode) => {
    if (!dataSourceId || !node.asset.id) return;
    try {
      const response = await fetchDataExplorationAssets({
        dataSourceId,
        parentId: node.asset.id,
        parentType: 'API_COLLECTION',
        pageNo: 1,
        pageSize: PAGE_SIZE,
      });
      if (response.code !== 0 || !response.data) {
        setListError(response.message || '无法读取接口列表');
        return;
      }
      const children = (response.data.records || []).map((asset) => toApiTreeNode(asset, true));
      setApiTree((current) => withApiTreeChildren(current, node.key, children));
    } catch (error: any) {
      setListError(error?.response?.data?.message || error?.message || '无法读取接口列表');
    }
  }, [dataSourceId]);

  const loadDetail = useCallback(async (
    assetId: string,
    assetType: string,
    keepTab = false,
  ) => {    if (!dataSourceId) return;
    const requestId = ++detailRequest.current;
    setDetailLoading(true);
    setDetailError(undefined);
    try {
      const response = await fetchDataExplorationAsset(dataSourceId, assetId, assetType);
      if (requestId !== detailRequest.current) return;
      if (response.code !== 0 || !response.data) {
        setDetailError(response.message || '无法读取元数据详情');
        return;
      }
      setDetail(response.data);
      if (!keepTab) {
        setActiveTab(firstTabKey(response.data.asset?.assetType || assetType));
      }
    } catch (error: any) {
      if (requestId !== detailRequest.current) return;
      setDetailError(error?.response?.data?.message || error?.message || '无法读取元数据详情');
    } finally {
      if (requestId === detailRequest.current) {
        setDetailLoading(false);
      }
    }
  }, [dataSourceId]);

  const loadDeepLink = useCallback(async (assetId: string, assetType: string) => {
    if (!dataSourceId) return;
    try {
      const response = await fetchDataExplorationAsset(dataSourceId, assetId, assetType);
      if (response.code !== 0 || !response.data?.asset) {
        message.warning('无法定位到指定元数据，已返回根层级');
        return;
      }
      const breadcrumbs = response.data.breadcrumbs || [];
      const parent = breadcrumbs[breadcrumbs.length - 1];
      setLevel({
        parentId: parent?.id,
        parentType: parent?.assetType as string | undefined,
        breadcrumbs,
      });
      setPageNo(1);
      setSelected({ id: assetId, type: response.data.asset.assetType || assetType });
      setDetail(response.data);
      setActiveTab(firstTabKey(response.data.asset.assetType || assetType));
    } catch {
      message.warning('无法定位到指定元数据，已返回根层级');
    }
  }, [dataSourceId]);

  useEffect(() => {
    if (!open || !dataSourceId) return;
    setLevel({ breadcrumbs: [] });
    setPageNo(1);
    setSearch('');
    setPage(undefined);
    setSelected(undefined);
    setDetail(undefined);
    setActiveTab(undefined);
    setSettingsOpen(false);
    setListError(undefined);
    setDetailError(undefined);
    setApiTree([]);
    void loadMetadataStatus();
    if (initialAssetId && initialAssetType) {
      void loadDeepLink(initialAssetId, initialAssetType);
    }
  }, [dataSourceId, initialAssetId, initialAssetType, loadDeepLink, loadMetadataStatus, open]);

  useEffect(() => {
    if (!open || !dataSourceId) return;
    const timer = window.setTimeout(() => {
      if (isApiCatalog) {
        void loadApiTree(search);
        return;
      }
      void loadPage(level, pageNo, search);
    }, search ? 300 : 0);
    return () => window.clearTimeout(timer);
  }, [dataSourceId, isApiCatalog, level, loadApiTree, loadPage, open, pageNo, search]);

  useEffect(() => {
    if (!open || !selected) return;
    void loadDetail(selected.id, selected.type);
  }, [loadDetail, open, selected]);

  // Open the first item by default so the workspace shows its metadata and
  // sample data without an extra click.
  useEffect(() => {
    if (!open || selected || isApiCatalog || !page?.records?.length) return;
    const first = page.records[0];
    if (!first?.id) return;
    setSelected({ id: first.id, type: String(first.assetType || '') });
    setActiveTab(firstTabKey(first.assetType));
  }, [isApiCatalog, open, page, selected]);

  const currentTerm = level.parentType ? childTerm(level.parentType) : rootTerm(dbType);
  const rootCrumbLabel = ['FTP', 'SFTP'].includes(String(dbType || '').toUpperCase())
    ? '根目录' : '全部';
  const records = page?.records || [];
  const currentPageNo = page?.pageNo || pageNo;
  const total = page?.total || 0;
  const totalLabel = page?.truncated ? `${total}（前 5000 项内）` : String(total);
  const metadataTag = assetExplorationStatus({
    metadataSyncStatus: metadataStatus?.syncStatus,
    scanStatus: metadataStatus?.scan?.status,
  });
  const syncFailed = metadataStatus?.syncStatus === 'ERROR';
  const sampleCollectionTag = sampleCollectionCopy(metadataStatus);
  const selectedAsset = detail?.asset;
  const tabs = tabsForAsset(selectedAsset?.assetType || selected?.type);
  const showTabs = tabs.length > 0;

  const selectAsset = (asset: DataExplorationAsset) => {
    if (!asset.id) return;
    setSelected({ id: asset.id, type: String(asset.assetType || '') });
    setActiveTab(firstTabKey(asset.assetType));
  };

  const enterAsset = (asset: DataExplorationAsset) => {
    if (!asset.id) return;
    setLevel((current) => ({
      parentId: asset.id,
      parentType: String(asset.assetType || ''),
      breadcrumbs: [...current.breadcrumbs, toRef(asset)],
    }));
    setPageNo(1);
    setSearch('');
    setSelected(undefined);
    setDetail(undefined);
    setActiveTab(undefined);
  };

  const goToBreadcrumb = (index: number) => {
    if (index < 0) {
      setLevel({ breadcrumbs: [] });
    } else {
      const ref = level.breadcrumbs[index];
      setLevel({
        parentId: ref.id,
        parentType: ref.assetType as string | undefined,
        breadcrumbs: level.breadcrumbs.slice(0, index + 1),
      });
    }
    setPageNo(1);
    setSearch('');
    setSelected(undefined);
    setDetail(undefined);
    setActiveTab(undefined);
  };

  const refresh = () => {
    void loadMetadataStatus();
    if (isApiCatalog) {
      void loadApiTree(search);
    } else {
      void loadPage(level, pageNo, search);
    }
    if (selected) {
      void loadDetail(selected.id, selected.type, true);
    }
  };

  const triggerScan = () => {
    if (!dataSourceId) return;
    const retrying = metadataStatus?.syncStatus === 'ERROR';
    Modal.confirm({
      title: retrying ? '重试元数据同步？' : '重新扫描数据源？',
      content: retrying
        ? '上次同步失败，将重新建立 OpenMetadata 绑定并继续元数据抽取。'
        : '扫描会在 OpenMetadata 中重新抽取元数据，并在样本采集开启时采集样本。',
      okText: retrying ? '重试同步' : '开始扫描',
      cancelText: '取消',
      onOk: async () => {
        try {
          const response = retrying
            ? await retryDataSourceMetadataSync(dataSourceId)
            : await triggerDataSourceScan(dataSourceId);
          if (response.code !== 0) {
            message.error(response.message || (retrying ? '重试同步失败' : '扫描提交失败'));
            return;
          }
          message.success(retrying
            ? '已提交同步重试，完成后刷新查看结果'
            : '已提交扫描，完成后刷新查看结果');
          refreshTimer.current = window.setTimeout(() => {
            void loadMetadataStatus();
            void loadPage(level, pageNo, search);
            if (selected) {
              void loadDetail(selected.id, selected.type, true);
            }
          }, 3000);
        } catch (error: any) {
          message.error(error?.response?.data?.message || error?.message
            || (retrying ? '重试同步失败' : '扫描提交失败'));
        }
      },
    });
  };

  const explorationTitle = (
    <div className="exploration-drawer__title">
      <div className="exploration-drawer__title-icon">
        <DatabaseIcons dbType={dbType} width="22" height="22" />
      </div>
      <div className="exploration-drawer__title-copy">
        <div className="exploration-drawer__title-name">数据探查</div>
        <div className="exploration-drawer__title-path" title={dataSourceName}>
          {dataSourceName || '正在连接数据源'} · {currentTerm}
        </div>
      </div>
      <div className="exploration-drawer__title-status asset-exploration__title-status">
        {metadataLoading ? <Spin size="small" /> : <Tag color={metadataTag.color}>{metadataTag.label}</Tag>}
        <Tag color={sampleCollectionTag.color}>{sampleCollectionTag.label}</Tag>
        <span>{totalLabel} 项</span>
      </div>
    </div>
  );

  const renderFieldTable = (
    fields?: DataExplorationAssetField[],
    emptyText = '暂无字段',
  ) => {
    const rows = flattenFields(fields);
    return (
      <Table
        className="exploration-drawer__detail-table"
        size="small"
        rowKey="__key"
        columns={fieldColumns}
        dataSource={rows}
        pagination={false}
        scroll={{ x: 720, y: 360 }}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyText} /> }}
      />
    );
  };

  const renderState = (
    title: string,
    description: string,
    action?: React.ReactNode,
    icon: React.ReactNode = <InfoCircleOutlined />,
  ) => (
    <div className="asset-exploration__state">
      <div className="asset-exploration__state-icon">{icon}</div>
      <strong>{title}</strong>
      <span>{description}</span>
      {action}
    </div>
  );

  const schemaPanel = () => {
    if (!detail) return null;
    const schema = detail.schema;
    const status = schema?.status;
    if (status === 'NEEDS_DATA_MODEL') {
      return renderState(
        schemaStatusCopy(status).title,
        schemaStatusCopy(status).description,
        <Button size="small" onClick={() => setSettingsOpen(true)}>配置结构化容器清单</Button>,
      );
    }
    if (status === 'NOT_APPLICABLE' || status === 'NOT_SUPPORTED') {
      return renderState(
        schemaStatusCopy(status).title,
        schemaStatusCopy(status).description,
      );
    }
    if (status === 'NO_SCHEMA') {
      return renderState(
        schemaStatusCopy(status).title,
        schemaStatusCopy(status).description,
      );
    }
    return (
      <div className="exploration-drawer__tab-pane">
        <div className="exploration-drawer__tab-toolbar">
          <div>
            <div className="exploration-drawer__eyebrow">SCHEMA</div>
            <strong>{schema?.kind === 'MESSAGE' ? '消息结构' : '列定义'}</strong>
          </div>
          <span>{schema?.fields?.length || 0} 个字段</span>
        </div>
        {renderFieldTable(schema?.fields)}
      </div>
    );
  };

  const samplePanel = () => {
    if (!detail) return null;
    const sample = detail.sample;
    if (!sample) {
      return renderState('暂无样本', '当前元数据没有可展示的样本数据。', undefined, <EyeOutlined />);
    }
    const status = sample.status;
    if (status === 'AVAILABLE') {
      return (
        <div className="exploration-drawer__tab-pane">
          <div className="exploration-drawer__tab-toolbar">
            <div>
              <div className="exploration-drawer__eyebrow">SAMPLE DATA</div>
              <strong>{sample?.kind === 'MESSAGES' ? '样本消息' : '样本行'}</strong>
            </div>
            <span>
              共 {sample?.kind === 'MESSAGES'
                ? (sample?.messages?.length || 0)
                : (sample?.rows?.length || 0)} 条 · 样本为最近一次采集结果
            </span>
          </div>
          {sample?.kind === 'MESSAGES' ? (
            <div className="asset-exploration__message-list">
              {(sample.messages || []).map((item, index) => (
                <pre className="asset-exploration__message" key={`message-${index}`}>{item}</pre>
              ))}
            </div>
          ) : (
            <SampleRowsTable sample={sample} />
          )}
        </div>
      );
    }
    const copy = sampleStatusCopy(status);
    let action: React.ReactNode;
    if (status === 'DISABLED' || status === 'NEEDS_DATA_MODEL') {
      action = <Button size="small" onClick={() => setSettingsOpen(true)}>打开探查设置</Button>;
    } else if (status === 'ERROR') {
      action = (
        <Button size="small" onClick={() => selected && void loadDetail(selected.id, selected.type, true)}>
          重试
        </Button>
      );
    }
    return renderState(copy.title, copy.description, action, <EyeOutlined />);
  };

  const metricsPanel = () => (
    <div className="exploration-drawer__tab-pane">
      <div className="exploration-drawer__tab-toolbar">
        <div>
          <div className="exploration-drawer__eyebrow">PROPERTIES</div>
          <strong>元数据指标</strong>
        </div>
      </div>
      {renderProperties()}
    </div>
  );

  const renderProperties = () => {
    const properties = detail?.properties || [];
    if (properties.length === 0) {
      return renderState('暂无指标', 'OpenMetadata 没有返回可展示的指标。');
    }
    return (
      <dl className="asset-exploration__property-list">
        {properties.map((property) => (
          <div key={property.key || property.label}>
            <dt>{property.label || property.key}</dt>
            <dd className={property.displayType === 'CODE' ? 'is-code' : undefined}>
              {formatPropertyValue(property)}
            </dd>
          </div>
        ))}
      </dl>
    );
  };

  const overviewPanel = () => (
    <div className="exploration-drawer__tab-pane">
      <div className="exploration-drawer__tab-toolbar">
        <div>
          <div className="exploration-drawer__eyebrow">OVERVIEW</div>
          <strong>{assetTypeLabel(selectedAsset?.assetType)}概览</strong>
        </div>
        {selectedAsset?.hasChildren && selectedAsset.id && (
          <Button size="small" icon={<RightOutlined />} onClick={() => enterAsset(selectedAsset)}>
            进入{childTerm(selectedAsset.assetType)}
          </Button>
        )}
      </div>
      {renderProperties()}
    </div>
  );

  const detailBody = () => {
    if (detailLoading) {
      return <div className="asset-exploration__state"><Spin /></div>;
    }
    if (detailError) {
      return (
        <Alert
          type="warning"
          showIcon
          message="元数据详情读取失败"
          description={detailError}
          action={selected
            ? <Button size="small" onClick={() => void loadDetail(selected.id, selected.type, true)}>重试</Button>
            : undefined}
        />
      );
    }
    if (!detail || !selectedAsset) {
      return renderState(
        `选择一个${currentTerm}查看详情`,
        '结构、样本与指标会显示在这里。',
        undefined,
        assetIcon(level.parentType),
      );
    }
    return (
      <>
        <header className="exploration-drawer__asset-header">
          <div className="exploration-drawer__asset-copy">
            <div className="exploration-drawer__eyebrow">
              {assetTypeEyebrow(selectedAsset.assetType)}
            </div>
            <h1 title={selectedAsset.fullyQualifiedName}>{assetDisplayName(selectedAsset)}</h1>
            <div className="exploration-drawer__fqn" title={selectedAsset.fullyQualifiedName}>
              <span>FQN</span>
              <code>{selectedAsset.fullyQualifiedName || '—'}</code>
            </div>
          </div>
          <div className="exploration-drawer__asset-badges">
            <Tag color="blue">{assetTypeLabel(selectedAsset.assetType)}</Tag>
            {schemaTag(detail.schema?.status)}
            {sampleTag(detail.sample?.status)}
            {selectedAsset.hasChildren && selectedAsset.id && (
              <Button size="small" icon={<RightOutlined />} onClick={() => enterAsset(selectedAsset)}>
                进入
              </Button>
            )}
          </div>
        </header>
        <div className="exploration-drawer__asset-summary asset-exploration__summary">
          <span>
            <TableOutlined />{' '}
            {typeof selectedAsset.fieldCount === 'number' && selectedAsset.fieldCount > 0
              ? `${selectedAsset.fieldCount} 列`
              : '无结构'}
          </span>
          {typeof selectedAsset.childCount === 'number' && (
            <span><ApartmentOutlined /> {selectedAsset.childCount} 个子项</span>
          )}
          <span><CloudOutlined /> {formatAssetBytes(selectedAsset.sizeInByte)}</span>
          <span><InfoCircleOutlined /> 更新于 {formatAssetTime(selectedAsset.updatedAt)}</span>
        </div>
        {showTabs ? (
          <Tabs
            className="exploration-drawer__tabs"
            activeKey={activeTab}
            onChange={(key) => setActiveTab(key as AssetTabKey)}
            items={tabs.map((tab) => ({
              key: tab.key,
              label: tab.label,
              children: tab.key === 'schema'
                ? schemaPanel()
                : tab.key === 'sample'
                  ? samplePanel()
                  : tab.key === 'metrics'
                    ? metricsPanel()
                    : tab.key === 'request'
                      ? renderFieldTable(detail.schema?.requestFields, 'OpenAPI 文档未定义请求结构')
                      : renderFieldTable(detail.schema?.responseFields, 'OpenAPI 文档未定义响应结构'),
            }))}
          />
        ) : (
          overviewPanel()
        )}
        <div className="asset-exploration__mobile-meta">
          <section>
            <strong>标签</strong>
            {(detail.tags || []).length > 0
              ? (detail.tags || []).map((tag) => <Tag key={tag} color="purple">{tag}</Tag>)
              : <span>暂无标签</span>}
          </section>
          <section>
            <strong>描述</strong>
            <p>{detail.description ? decodeHtmlEntities(detail.description) : '尚未补充描述'}</p>
          </section>
          <section>
            <strong>样本状态</strong>
            <div>{sampleTag(detail.sample?.status)}</div>
            <p>{sampleStatusCopy(detail.sample?.status).description}</p>
          </section>
        </div>
      </>
    );
  };

  const workspace = (
    <div className="exploration-drawer__workspace asset-exploration__workspace">
      <aside className="exploration-drawer__nav" aria-label={`${currentTerm}目录`}>
        <div className="exploration-drawer__nav-context">
          <div className="exploration-drawer__eyebrow">CATALOG</div>
          <div className="asset-exploration__nav-title">
            <strong>{currentTerm}</strong>
            <span>{totalLabel} 项</span>
          </div>
          <div className="asset-exploration__search-row">
            <Input
              allowClear
              value={search}
              prefix={<SearchOutlined />}
              aria-label={`搜索${currentTerm}`}
              placeholder={`搜索${currentTerm}`}
              onChange={(event) => {
                setSearch(event.target.value);
                setPageNo(1);
              }}
            />
            <Button
              type="text"
              size="small"
              aria-label="刷新元数据列表"
              icon={<ReloadOutlined />}
              loading={listLoading}
              onClick={refresh}
            />
          </div>
          {!isApiCatalog && (
            <div className="asset-exploration__breadcrumb" aria-label="层级路径">
              <button type="button" onClick={() => goToBreadcrumb(-1)}>{rootCrumbLabel}</button>
              {level.breadcrumbs.map((ref, index) => (
                <React.Fragment key={`${ref.id || ref.name}-${index}`}>
                  <span aria-hidden>/</span>
                  <button type="button" onClick={() => goToBreadcrumb(index)} title={ref.fullyQualifiedName}>
                    {ref.name || ref.fullyQualifiedName}
                  </button>
                </React.Fragment>
              ))}
            </div>
          )}
        </div>

        <div className="exploration-drawer__nav-divider" />

        <div className="exploration-drawer__tables">
          <div className="exploration-drawer__section-heading">
            <div className="exploration-drawer__tables-title">
              {assetIcon(level.parentType)}
              <strong>{currentTerm}</strong>
              <span>{totalLabel}</span>
            </div>
          </div>
          <div className="asset-exploration__list">
            <Spin spinning={listLoading} size="small">
              {listError ? (
                <Alert
                  type="warning"
                  showIcon
                  message="目录读取失败"
                  description={listError}
                  action={(
                    <Space size="small">
                      <Button size="small" onClick={() => void (isApiCatalog ? loadApiTree(search) : loadPage(level, pageNo, search))}>
                        刷新
                      </Button>
                      {syncFailed && (
                        <Button size="small" type="primary" onClick={triggerScan}>
                          重试同步
                        </Button>
                      )}
                    </Space>
                  )}
                />
              ) : isApiCatalog ? (
                apiTree.length > 0 ? (
                  <Tree
                    className="asset-exploration__tree"
                    showIcon
                    blockNode
                    treeData={apiTree}
                    loadData={(node) => loadApiChildren(node as ApiTreeNode)}
                    selectedKeys={selected ? [selected.id] : []}
                    onSelect={(_keys, info) => {
                      const asset = (info.node as ApiTreeNode).asset;
                      if (asset) selectAsset(asset);
                    }}
                    titleRender={(node) => {
                      const item = node as ApiTreeNode;
                      return (
                        <span className="asset-exploration__tree-title" title={item.asset.fullyQualifiedName}>
                          {assetIcon(item.asset.assetType)}
                          <span className="asset-exploration__tree-label">{item.title}</span>
                        </span>
                      );
                    }}
                  />
                ) : (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={search ? `没有匹配的${currentTerm}` : `OpenMetadata 中暂无${currentTerm}`}
                  />
                )
              ) : records.length > 0 ? records.map((asset) => {
                const key = String(asset.id || asset.fullyQualifiedName || asset.name);
                const isSelected = selected?.id === asset.id;
                return (
                  <div
                    className={`asset-exploration__row${isSelected ? ' is-selected' : ''}`}
                    key={key}
                  >
                    <button
                      type="button"
                      className="asset-exploration__row-main"
                      aria-current={isSelected ? 'true' : undefined}
                      aria-label={`查看 ${assetDisplayName(asset)} 详情`}
                      onClick={() => selectAsset(asset)}
                    >
                      <span className="asset-exploration__row-icon">{assetIcon(asset.assetType)}</span>
                      <span className="asset-exploration__row-copy">
                        <strong title={assetDisplayName(asset)}>{assetDisplayName(asset)}</strong>
                        <small title={asset.fullyQualifiedName}>{asset.fullyQualifiedName || (asset.description ? decodeHtmlEntities(asset.description) : assetTypeLabel(asset.assetType))}</small>
                      </span>
                      <span className="asset-exploration__row-meta">
                        {typeof asset.fieldCount === 'number' && asset.fieldCount > 0 && (
                          <span>{asset.fieldCount} 列</span>
                        )}
                        {typeof asset.childCount === 'number' && <span>{asset.childCount} 项</span>}
                        {asset.sizeInByte !== null && asset.sizeInByte !== undefined && (
                          <span>{formatAssetBytes(asset.sizeInByte)}</span>
                        )}
                      </span>
                    </button>
                    {asset.hasChildren && (
                      <button
                        type="button"
                        className="asset-exploration__row-enter"
                        aria-label={`进入 ${assetDisplayName(asset)}`}
                        onClick={() => enterAsset(asset)}
                      >
                        <RightOutlined />
                      </button>
                    )}
                  </div>
                );
              }) : (
                <>
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={search ? `没有匹配的${currentTerm}` : `OpenMetadata 中暂无${currentTerm}`}
                  />
                  <div className="asset-exploration__empty-hint">
                    {syncFailed
                      ? '元数据同步失败，请点击右侧“重试同步”。'
                      : metadataStatus?.syncStatus === 'READY'
                        ? '如果刚完成扫描，请刷新列表；仍未出现时可在右侧重新扫描。'
                        : '元数据尚未同步，请先在探查设置中完成同步。'}
                  </div>
                </>
              )}
            </Spin>
          </div>
          {page?.truncated && (
            <div className="asset-exploration__truncated">仅扫描前 5000 项，超出部分未纳入结果</div>
          )}
          {!isApiCatalog && (
            <Pagination
              size="small"
              current={currentPageNo}
              pageSize={page?.pageSize || PAGE_SIZE}
              total={total}
              showSizeChanger={false}
              showLessItems
              onChange={(nextPage) => setPageNo(nextPage)}
            />
          )}
        </div>
      </aside>

      <main className="exploration-drawer__main" aria-label={`${currentTerm}详情`}>
        {detailBody()}
      </main>

      <aside className="exploration-drawer__inspector" aria-label="元数据检查器">
        <div className="exploration-drawer__inspector-header">
          <div className="exploration-drawer__eyebrow">INSPECTOR</div>
          <strong>元数据摘要</strong>
        </div>
        <div className="exploration-drawer__inspector-scroll">
          <section className="exploration-drawer__inspector-section">
            <div className="exploration-drawer__property-label">元数据身份</div>
            <div className="exploration-drawer__property">
              <span>类型</span>
              <strong>{assetTypeLabel(selectedAsset?.assetType)}</strong>
            </div>
          </section>

          <section className="exploration-drawer__inspector-section">
            <div className="exploration-drawer__property-label">标签</div>
            {(detail?.tags || []).length > 0 ? (
              <div className="asset-exploration__tag-list">
                {(detail?.tags || []).map((tag) => <Tag key={tag} color="purple">{tag}</Tag>)}
              </div>
            ) : <em>暂无标签</em>}
          </section>

          <section className="exploration-drawer__inspector-section">
            <div className="exploration-drawer__property-label">描述</div>
            <p className={detail?.description ? '' : 'is-empty'}>
              {detail?.description ? decodeHtmlEntities(detail.description) : '尚未补充描述'}
            </p>
          </section>

          <section className="exploration-drawer__inspector-section">
            <div className="exploration-drawer__property-label">样本状态</div>
            <div className="asset-exploration__sample-status">
              {sampleTag(detail?.sample?.status)}
              <p>{sampleStatusCopy(detail?.sample?.status).description}</p>
            </div>
          </section>
        </div>
      </aside>
    </div>
  );

  return (
    <>
      {inline ? (
        <section className="exploration-drawer exploration-drawer--inline asset-exploration" aria-label="数据探查详情">
          <div className="exploration-drawer__inline-header">
            {explorationTitle}
            <Button type="text" icon={<ArrowLeftOutlined />} onClick={onClose}>{backLabel}</Button>
          </div>
          {workspace}
        </section>
      ) : (
        <Drawer
          className="exploration-drawer asset-exploration"
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
      <AssetExplorationSettingsModal
        open={settingsOpen}
        dataSourceId={dataSourceId}
        dataSourceName={dataSourceName}
        dbType={dbType}
        onClose={() => setSettingsOpen(false)}
        onSaved={() => {
          void loadMetadataStatus();
          if (selected) {
            void loadDetail(selected.id, selected.type, true);
          }
        }}
      />
    </>
  );
};

const SampleRowsTable: React.FC<{ sample: NonNullable<DataExplorationAssetDetail['sample']> }> = ({ sample }) => {
  const columns = (sample.columns || []).length > 0
    ? (sample.columns || [])
    : Array.from({ length: Math.max(...(sample.rows || []).map((row) => row.length), 0) }, (_, index) => `列 ${index + 1}`);
  const tableColumns: TableColumnsType<Record<string, unknown>> = columns.map((column, index) => ({
    title: column,
    dataIndex: `c${index}`,
    key: `c${index}`,
    ellipsis: true,
    render: (value) => (value === null || value === undefined || value === '' ? '—' : String(value)),
  }));
  const rows = (sample.rows || []).map((row, rowIndex) => {
    const record: Record<string, unknown> = { key: rowIndex };
    row.forEach((cell, cellIndex) => {
      record[`c${cellIndex}`] = cell;
    });
    return record;
  });
  return (
    <Table
      className="exploration-drawer__detail-table"
      size="small"
      rowKey={(row) => String(row.key)}
      columns={tableColumns}
      dataSource={rows}
      pagination={false}
      scroll={{ x: 'max-content', y: 360 }}
      locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无样本行" /> }}
    />
  );
};

export default AssetDataExplorationDrawer;
