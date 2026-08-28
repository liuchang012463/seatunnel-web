import {
  ApartmentOutlined,
  DownloadOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SearchOutlined,
  TableOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import { Button, Empty, Input, Spin, Tag, Tree, message } from 'antd';
import type { TreeDataNode } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import DataExplorationDrawer from '../components/DataExplorationDrawer';
import {
  downloadDataExplorationExport,
  fetchDataInventoryOverview,
  fetchDataSourceAll,
  fetchDataSourceTopologyChildren,
  fetchDataSourceTopologyTree,
} from '@/pages/data-source/service';
import type {
  DataInventorySummary,
  DataSourceRecord,
  DataSourceTopologyNode,
  DataSourceTopologyNodeType,
} from '@/pages/data-source/types';
import {
  explorationStatus,
  normalizeDataSourceList,
  replaceTopologyChildren,
  topologyKey,
  topologyTreeData,
} from '../shared';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import '../index.less';
import './results.less';

const EMPTY_SUMMARY: DataInventorySummary = {
  unitCount: 0,
  businessSystemCount: 0,
  dataSourceCount: 0,
  databaseCount: 0,
  schemaCount: 0,
  tableCount: 0,
  columnCount: 0,
  profiledDatabaseCount: 0,
  profiledTableCount: 0,
  knownRowCount: 0,
};

const NODE_LABEL: Record<DataSourceTopologyNodeType, string> = {
  UNIT: '单位',
  BUSINESS_SYSTEM: '业务系统',
  DATA_SOURCE: '数据源',
  DATABASE: 'Database',
  SCHEMA: 'Schema',
  TABLE: '数据表',
};

function filterTopologyNodes(nodes: DataSourceTopologyNode[], keyword: string): DataSourceTopologyNode[] {
  const normalized = keyword.trim().toLowerCase();
  if (!normalized) return nodes;
  return nodes.reduce<DataSourceTopologyNode[]>((result, node) => {
    const children = node.children ? filterTopologyNodes(node.children, keyword) : [];
    if (node.name.toLowerCase().includes(normalized) || children.length > 0) {
      result.push(children.length > 0 ? { ...node, children } : node);
    }
    return result;
  }, []);
}

function findTopologyNode(nodes: DataSourceTopologyNode[], key: string): DataSourceTopologyNode | undefined {
  for (const node of nodes) {
    if (topologyKey(node) === key) return node;
    const child = node.children ? findTopologyNode(node.children, key) : undefined;
    if (child) return child;
  }
  return undefined;
}

function findTopologyPath(
  nodes: DataSourceTopologyNode[],
  key: string,
  parents: string[] = [],
): string[] | undefined {
  for (const node of nodes) {
    const nextParents = [...parents, node.name];
    if (topologyKey(node) === key) return nextParents;
    const childPath = node.children ? findTopologyPath(node.children, key, nextParents) : undefined;
    if (childPath) return childPath;
  }
  return undefined;
}

function extractBlob(value: unknown, depth = 0): Blob | undefined {
  if (depth > 4 || value === undefined || value === null) return undefined;
  if (typeof Blob !== 'undefined' && value instanceof Blob) return value;
  if (typeof ArrayBuffer !== 'undefined' && value instanceof ArrayBuffer) return new Blob([value]);
  if (typeof value !== 'object') return undefined;
  const response = value as { data?: unknown; response?: unknown };
  return extractBlob(response.data, depth + 1) || extractBlob(response.response, depth + 1);
}

function saveBlob(result: unknown, filename: string): boolean {
  const blob = extractBlob(result);
  if (!blob || blob.size === 0 || typeof document === 'undefined') return false;
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
  return true;
}

const DataExplorationResultsPage: React.FC = () => {
  const [topologyLoading, setTopologyLoading] = useState(false);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [exportLoading, setExportLoading] = useState(false);
  const [topologyNodes, setTopologyNodes] = useState<DataSourceTopologyNode[]>([]);
  const [summary, setSummary] = useState<DataInventorySummary>(EMPTY_SUMMARY);
  const [dataSources, setDataSources] = useState<DataSourceRecord[]>([]);
  const [selectedSourceId, setSelectedSourceId] = useState<string>();
  const [selectedSourceDbType, setSelectedSourceDbType] = useState<string>();
  const [selectedTopologyNode, setSelectedTopologyNode] = useState<DataSourceTopologyNode>();
  const [topologySearch, setTopologySearch] = useState('');
  const [drawerOpen, setDrawerOpen] = useState(false);

  const loadOptions = useCallback(async () => {
    try {
      const sourceResponse = await fetchDataSourceAll();
      if (sourceResponse.code === 0) setDataSources(normalizeDataSourceList(sourceResponse.data));
    } catch (_) {
      message.warning('数据源资产暂不可用');
    }
  }, []);

  const load = useCallback(async () => {
    setTopologyLoading(true);
    setSummaryLoading(true);
    const topologyRequest = fetchDataSourceTopologyTree()
      .then((treeResponse) => {
        if (treeResponse.code !== 0) {
          message.warning(treeResponse.message || '资产目录暂不可用');
          setTopologyNodes([]);
        } else {
          setTopologyNodes(treeResponse.data || []);
        }
      })
      .catch((error: any) => {
        message.warning(error?.response?.data?.message || '资产目录暂不可用');
        setTopologyNodes([]);
      })
      .finally(() => setTopologyLoading(false));
    const summaryRequest = fetchDataInventoryOverview()
      .then((response) => {
        if (response.code !== 0 || !response.data) {
          throw new Error(response.message || 'inventory overview unavailable');
        }
        setSummary(response.data.summary || EMPTY_SUMMARY);
      })
      .catch((error: any) => {
        message.warning(error?.response?.data?.message || '探查统计暂不可用');
      })
      .finally(() => setSummaryLoading(false));
    await Promise.all([topologyRequest, summaryRequest]);
  }, []);

  useEffect(() => {
    void loadOptions();
    const params = new URLSearchParams(window.location.search);
    const initialDataSourceId = params.get('dataSourceId') || undefined;
    const initialDbType = params.get('dbType') || undefined;
    if (initialDataSourceId) {
      setSelectedSourceId(initialDataSourceId);
      setSelectedSourceDbType(initialDbType);
      setDrawerOpen(true);
    }
  }, [loadOptions]);

  useEffect(() => {
    void load();
  }, [load]);

  const selectedSource = useMemo(
    () => dataSources.find((item) => String(item.id) === selectedSourceId),
    [dataSources, selectedSourceId],
  );

  const filteredTopologyNodes = useMemo(
    () => filterTopologyNodes(topologyNodes, topologySearch),
    [topologyNodes, topologySearch],
  );

  const selectedNodeKey = selectedTopologyNode ? topologyKey(selectedTopologyNode) : undefined;
  const selectedNodePath = selectedNodeKey ? findTopologyPath(topologyNodes, selectedNodeKey) : undefined;

  const browserNodes = useMemo<DataSourceTopologyNode[]>(() => {
    if (selectedTopologyNode) {
      if (selectedTopologyNode.children && selectedTopologyNode.children.length > 0) {
        return selectedTopologyNode.children;
      }
      return [selectedTopologyNode];
    }
    return dataSources.map((source) => ({
      id: String(source.id),
      nodeType: 'DATA_SOURCE' as const,
      name: source.name || String(source.id),
      children: undefined,
    }));
  }, [dataSources, selectedTopologyNode]);

  const loading = topologyLoading || summaryLoading;

  const onSelect = (keys: React.Key[]) => {
    if (keys.length === 0) return;
    const key = String(keys[0]);
    const separator = key.indexOf(':');
    if (separator <= 0) return;
    const nodeType = key.substring(0, separator) as DataSourceTopologyNodeType;
    const nodeId = key.substring(separator + 1);
    const node = findTopologyNode(topologyNodes, key);
    setSelectedTopologyNode(node);
    if (nodeType === 'DATA_SOURCE') {
      setSelectedSourceId(nodeId);
      setSelectedSourceDbType(dataSources.find((source) => String(source.id) === nodeId)?.dbType);
      setDrawerOpen(true);
    } else {
      setSelectedSourceDbType(undefined);
      setDrawerOpen(false);
    }
  };

  const onBrowserNodeClick = (node: DataSourceTopologyNode) => {
    setSelectedTopologyNode(node);
    if (node.nodeType === 'DATA_SOURCE') {
      setSelectedSourceId(node.id);
      setSelectedSourceDbType(dataSources.find((source) => String(source.id) === node.id)?.dbType);
      setDrawerOpen(true);
    } else {
      setSelectedSourceDbType(undefined);
      setDrawerOpen(false);
    }
  };

  const loadTopologyData = async (node: TreeDataNode) => {
    const key = String(node.key);
    const separator = key.indexOf(':');
    if (separator <= 0) return;
    const nodeType = key.substring(0, separator) as DataSourceTopologyNodeType;
    if (nodeType === 'TABLE' || node.children) return;
    const nodeId = key.substring(separator + 1);
    try {
      const response = await fetchDataSourceTopologyChildren(nodeType, nodeId);
      if (response.code !== 0) {
        message.warning(response.message || '资产节点暂不可用');
        return;
      }
      setTopologyNodes((current) => replaceTopologyChildren(current, key, response.data || []));
      if (selectedTopologyNode && topologyKey(selectedTopologyNode) === key) {
        setSelectedTopologyNode({ ...selectedTopologyNode, children: response.data || [] });
      }
    } catch (error: any) {
      message.warning(error?.response?.data?.message || '资产节点暂不可用');
    }
  };

  const exportAllResults = async () => {
    setExportLoading(true);
    try {
      const result = await downloadDataExplorationExport();
      if (saveBlob(result, `data-exploration-results-${Date.now()}.xlsx`)) {
        message.success('探查结果已导出');
      } else {
        message.error('导出响应不是有效文件');
      }
    } catch (error: any) {
      message.error(error?.response?.data?.message || '探查结果导出失败');
    } finally {
      setExportLoading(false);
    }
  };

  const selectedNodeSource = selectedTopologyNode?.nodeType === 'DATA_SOURCE'
    ? dataSources.find((source) => String(source.id) === selectedTopologyNode.id)
    : selectedSource;
  const selectedNodeType = selectedTopologyNode?.nodeType || (selectedNodeSource ? 'DATA_SOURCE' : undefined);
  const selectedNodeName = selectedTopologyNode?.name || selectedNodeSource?.name;

  return (
    <div className="data-exploration-page data-exploration-results">
      <header className="results-toolbar">
        <div className="results-toolbar__heading">
          <span className="results-kicker">数据探查 / EXPLORER</span>
          <h1>探查结果</h1>
          <p>浏览资产层级并进入数据源的结构、样本与画像。</p>
        </div>
        <div className="results-toolbar__actions">
          <Button icon={<PlayCircleOutlined />} onClick={() => history.push('/data-exploration/tasks')}>
            任务配置
          </Button>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>
            刷新
          </Button>
          <Button
            className="results-export-button"
            icon={<DownloadOutlined />}
            loading={exportLoading}
            onClick={() => void exportAllResults()}
          >
            导出探查结果
          </Button>
        </div>
      </header>

      <section className="results-workspace">
        <aside className="results-catalog" aria-label="资产目录">
          <div className="results-column-heading">
            <div>
              <span className="results-column-kicker">CATALOG</span>
              <h2>资产目录</h2>
            </div>
            <span className="results-column-count">{topologyNodes.length} 个根节点</span>
          </div>
          <Input
            allowClear
            value={topologySearch}
            prefix={<SearchOutlined />}
            placeholder="搜索资产名称"
            onChange={(event) => setTopologySearch(event.target.value)}
          />
          <div className="results-catalog__tree">
            <Spin spinning={topologyLoading}>
              {topologyNodes.length === 0 ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前范围暂无资产" />
              ) : filteredTopologyNodes.length === 0 ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有匹配的资产" />
              ) : (
                <Tree
                  blockNode
                  showLine
                  selectable
                  selectedKeys={selectedNodeKey ? [selectedNodeKey] : selectedSourceId ? [`DATA_SOURCE:${selectedSourceId}`] : []}
                  treeData={topologyTreeData(filteredTopologyNodes)}
                  loadData={loadTopologyData}
                  onSelect={onSelect}
                />
              )}
            </Spin>
          </div>
          <div className="results-catalog__note">展开节点后按需加载下一级元数据。</div>
        </aside>

        <main className="results-browser" aria-label="资产浏览">
          <div className="results-column-heading results-browser__heading">
            <div>
              <span className="results-column-kicker">ASSET BROWSER</span>
              <h2>{selectedTopologyNode ? `${NODE_LABEL[selectedTopologyNode.nodeType]} · ${selectedTopologyNode.name}` : '当前范围资产'}</h2>
            </div>
            <span className="results-column-count">{browserNodes.length} 项</span>
          </div>
          <div className="results-browser__scope">
            <span className="results-browser__breadcrumb" title={selectedNodePath?.join(' / ')}>
              <ApartmentOutlined />
              {selectedNodePath?.join(' / ') || '全部资产 / 选择目录节点继续浏览'}
            </span>
          </div>
          <div className="results-browser__list">
            {topologyLoading && topologyNodes.length === 0 ? (
              <div className="results-inline-loading"><Spin size="small" /> 正在加载资产</div>
            ) : browserNodes.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前范围暂无可浏览资产" />
            ) : (
              browserNodes.map((node) => {
                const source = node.nodeType === 'DATA_SOURCE'
                  ? dataSources.find((item) => String(item.id) === node.id)
                  : undefined;
                const status = source ? explorationStatus(source.profileStatus) : undefined;
                const isSelected = topologyKey(node) === selectedNodeKey;
                return (
                  <button
                    type="button"
                    className={`results-asset-card${isSelected ? ' is-selected' : ''}`}
                    key={topologyKey(node)}
                    onClick={() => onBrowserNodeClick(node)}
                  >
                    <span className="results-asset-card__icon">
                      {node.nodeType === 'DATA_SOURCE' && source
                        ? <DatabaseIcons dbType={source.dbType} width="20" height="20" />
                        : node.nodeType === 'TABLE' ? <TableOutlined /> : <ApartmentOutlined />}
                    </span>
                    <span className="results-asset-card__body">
                      <span className="results-asset-card__title-row">
                        <strong title={node.name}>{node.name}</strong>
                        <Tag>{NODE_LABEL[node.nodeType]}</Tag>
                      </span>
                      <span className="results-asset-card__meta">
                        {source
                          ? `${source.dbType || '数据库'} · ${source.environmentName || source.environment || '未标注环境'}`
                          : node.nodeType === 'TABLE'
                            ? '结构与画像数据可在数据源工作区查看'
                            : `${node.children?.length || 0} 个下级资产`}
                      </span>
                      <span className="results-asset-card__footer">
                        <span>{source ? `${source.unitName || source.dataSourceUnit || '待归属'} / ${source.businessSystemName || source.systemName || '待归属'}` : '当前资产目录'}</span>
                        {status ? <Tag color={status.color}>{status.label}</Tag> : <Tag color="default">已纳入范围</Tag>}
                      </span>
                    </span>
                  </button>
                );
              })
            )}
          </div>
          <div className="results-browser__footer">
            <span><ApartmentOutlined /> 点击数据源打开探查工作区</span>
            <span><TableOutlined /> 目录节点显示其下级资产摘要</span>
          </div>
        </main>

        <aside className="results-inspector" aria-label="资产详情">
          <div className="results-column-heading">
            <div>
              <span className="results-column-kicker">INSPECTOR</span>
              <h2>资产详情</h2>
            </div>
          </div>
          <div className="results-inspector__body">
            <div className="results-inspector__asset">
              <span className="results-inspector__asset-icon">
                {selectedNodeType === 'DATA_SOURCE' && selectedNodeSource
                  ? <DatabaseIcons dbType={selectedNodeSource.dbType} width="22" height="22" />
                  : selectedNodeType === 'TABLE' ? <TableOutlined /> : <ApartmentOutlined />}
              </span>
              <div className="min-w-0">
                <span className="results-inspector__type">{selectedNodeType ? NODE_LABEL[selectedNodeType] : '资产目录'}</span>
                <h3 title={selectedNodeName}>{selectedNodeName || '全部探查资产'}</h3>
                <p title={selectedNodePath?.join(' / ')}>{selectedNodePath?.join(' / ') || '未选择具体目录节点'}</p>
              </div>
            </div>

            <section className="results-inspector__section">
              <div className="results-inspector__section-title">当前路径</div>
              <p className="results-inspector__path-copy">
                {selectedNodePath?.join(' / ') || '全部资产目录'}
              </p>
            </section>

            <section className="results-inspector__section">
              <div className="results-inspector__section-title">范围统计</div>
              <div className="results-inspector__stats">
                <span><small>数据源</small><b>{summary.dataSourceCount}</b></span>
                <span><small>数据表</small><b>{summary.tableCount}</b></span>
                <span><small>字段</small><b>{summary.columnCount}</b></span>
                <span><small>已统计行数</small><b>{summary.knownRowCount}</b></span>
              </div>
            </section>

            {selectedNodeSource && (
              <section className="results-inspector__section">
                <div className="results-inspector__section-title">数据源信息</div>
                <div className="results-inspector__details">
                  <span><small>类型</small><b>{selectedNodeSource.dbType || '—'}</b></span>
                  <span><small>归属</small><b>{selectedNodeSource.unitName || selectedNodeSource.dataSourceUnit || '待归属'}</b></span>
                  <span><small>探查状态</small><b>{explorationStatus(selectedNodeSource.profileStatus).label}</b></span>
                  <span><small>最近成功</small><b>{selectedNodeSource.profileLastSuccessTime || '—'}</b></span>
                </div>
              </section>
            )}

            {selectedTopologyNode && selectedTopologyNode.nodeType !== 'DATA_SOURCE' && (
              <section className="results-inspector__section results-inspector__section--note">
                <div className="results-inspector__section-title">节点摘要</div>
                <p>{selectedTopologyNode.children?.length
                  ? `已加载 ${selectedTopologyNode.children.length} 个${NODE_LABEL[selectedTopologyNode.nodeType]}下级资产。`
                  : selectedTopologyNode.nodeType === 'TABLE'
                    ? '这是一个数据表节点，可在所属数据源工作区查看字段、样本和画像。'
                    : '下级资产尚未加载，展开左侧目录即可读取。'}</p>
              </section>
            )}
          </div>
        </aside>
      </section>

      <DataExplorationDrawer
        open={drawerOpen && Boolean(selectedSourceId)}
        dataSourceId={selectedSourceId}
        dataSourceName={selectedSource?.name}
        dbType={selectedSource?.dbType || selectedSourceDbType}
        onClose={() => setDrawerOpen(false)}
      />
    </div>
  );
};

export default DataExplorationResultsPage;
