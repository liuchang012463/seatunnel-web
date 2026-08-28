import {
  ApiOutlined,
  ApartmentOutlined,
  DatabaseOutlined,
  DownloadOutlined,
  FolderOpenOutlined,
  MessageOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import { Button, Empty, Input, Select, Spin, Tag, message } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import DataExplorationDrawer from '../components/DataExplorationDrawer';
import {
  downloadDataExplorationExport,
  fetchBusinessSystemOptions,
  fetchDataInventoryOverview,
  fetchDataSourceAll,
  fetchDataSourceUnitOptions,
  unwrapMasterDataList,
} from '@/pages/data-source/service';
import type {
  BusinessSystemOption,
  DataInventoryFilter,
  DataInventorySummary,
  DataSourceRecord,
  DataSourceUnitOption,
} from '@/pages/data-source/types';
import { getDataSourceCategory } from '@/pages/data-source/dataSourceRegistry';
import { explorationStatus, normalizeDataSourceList } from '../shared';
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

const EXPLORATION_GROUPS = [
  {
    key: 'DATABASE',
    label: '数据库',
    description: '关系型 / OLAP / ES',
    icon: <DatabaseOutlined />,
  },
  {
    key: 'MESSAGE_QUEUE',
    label: '消息队列',
    description: 'Kafka 主题与消息',
    icon: <MessageOutlined />,
  },
  {
    key: 'API',
    label: 'API 服务',
    description: 'HTTP 接口目录',
    icon: <ApiOutlined />,
  },
  {
    key: 'FILE_TRANSFER',
    label: '文件传输',
    description: 'FTP / SFTP / 对象存储',
    icon: <FolderOpenOutlined />,
  },
] as const;

type ExplorationGroupKey = (typeof EXPLORATION_GROUPS)[number]['key'];
type SelectedGroupKey = 'ALL' | ExplorationGroupKey;

interface ResultFilters {
  unitId?: string;
  businessSystemId?: string;
}

interface InitialRouteState extends ResultFilters {
  dataSourceId?: string;
  dbType?: string;
}

const GROUP_LABELS: Record<SelectedGroupKey, string> = {
  ALL: '全部类型',
  DATABASE: '数据库',
  MESSAGE_QUEUE: '消息队列',
  API: 'API 服务',
  FILE_TRANSFER: '文件传输',
};

const GROUP_DESCRIPTIONS: Record<SelectedGroupKey, string> = {
  ALL: '查看当前范围内的全部数据源探查结果',
  DATABASE: '关系型、OLAP 数据库与 Elasticsearch',
  MESSAGE_QUEUE: 'Kafka 主题与消息元数据',
  API: 'HTTP 接口与 OpenAPI 目录',
  FILE_TRANSFER: 'FTP、SFTP、S3 与 MinIO 文件对象',
};

function readInitialRouteState(): InitialRouteState {
  if (typeof window === 'undefined') return {};
  const params = new URLSearchParams(window.location.search);
  return {
    unitId: params.get('unitId') || undefined,
    businessSystemId: params.get('businessSystemId') || undefined,
    dataSourceId: params.get('dataSourceId') || undefined,
    dbType: params.get('dbType') || undefined,
  };
}

function sourceGroupKey(dbType?: string): ExplorationGroupKey {
  switch (getDataSourceCategory(dbType).key) {
    case 'MESSAGE_QUEUE':
      return 'MESSAGE_QUEUE';
    case 'API':
      return 'API';
    case 'FILE_TRANSFER':
      return 'FILE_TRANSFER';
    case 'RELATIONAL':
    case 'OLAP':
    case 'OTHER':
    default:
      return 'DATABASE';
  }
}

function sourceTypeLabel(dbType?: string): string {
  if (!dbType) return '数据库';
  const category = getDataSourceCategory(dbType);
  if (category.key === 'OTHER') return '数据库';
  return category.label;
}

function sourceOwner(record: DataSourceRecord): { unit: string; system: string } {
  return {
    unit: record.unitName || record.dataSourceUnit || '未分配单位',
    system: record.businessSystemName || record.systemName || '未分配系统',
  };
}

function sourceEnvironment(record: DataSourceRecord): string {
  return record.environmentName || record.environment || '未标注环境';
}

function formatMetric(value?: number): string {
  return Number(value || 0).toLocaleString('zh-CN');
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
  const [route] = useState<InitialRouteState>(readInitialRouteState);
  const [sourcesLoading, setSourcesLoading] = useState(false);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [unitLoading, setUnitLoading] = useState(false);
  const [businessSystemLoading, setBusinessSystemLoading] = useState(false);
  const [exportLoading, setExportLoading] = useState(false);
  const [summary, setSummary] = useState<DataInventorySummary>(EMPTY_SUMMARY);
  const [dataSources, setDataSources] = useState<DataSourceRecord[]>([]);
  const [unitOptions, setUnitOptions] = useState<DataSourceUnitOption[]>([]);
  const [businessSystemOptions, setBusinessSystemOptions] = useState<BusinessSystemOption[]>([]);
  const [filters, setFilters] = useState<ResultFilters>({
    unitId: route.unitId,
    businessSystemId: route.unitId ? route.businessSystemId : undefined,
  });
  const [selectedGroup, setSelectedGroup] = useState<SelectedGroupKey>('ALL');
  const [search, setSearch] = useState('');
  const [selectedSourceId, setSelectedSourceId] = useState<string>(route.dataSourceId || '');
  const [selectedSourceDbType, setSelectedSourceDbType] = useState<string | undefined>(route.dbType);
  const [drawerOpen, setDrawerOpen] = useState(Boolean(route.dataSourceId));

  const loadUnits = useCallback(async () => {
    setUnitLoading(true);
    try {
      const response = await fetchDataSourceUnitOptions();
      if (response.code === 0) setUnitOptions(unwrapMasterDataList(response));
    } catch (_) {
      setUnitOptions([]);
      message.warning('单位列表暂不可用');
    } finally {
      setUnitLoading(false);
    }
  }, []);

  const loadBusinessSystems = useCallback(async (unitId?: string) => {
    if (!unitId) {
      setBusinessSystemOptions([]);
      return;
    }
    setBusinessSystemLoading(true);
    try {
      const response = await fetchBusinessSystemOptions(unitId);
      if (response.code === 0) setBusinessSystemOptions(unwrapMasterDataList(response));
    } catch (_) {
      setBusinessSystemOptions([]);
      message.warning('业务系统列表暂不可用');
    } finally {
      setBusinessSystemLoading(false);
    }
  }, []);

  const loadSources = useCallback(async () => {
    setSourcesLoading(true);
    try {
      const response = await fetchDataSourceAll();
      if (response.code !== 0) throw new Error(response.message || 'data source list unavailable');
      setDataSources(normalizeDataSourceList(response.data));
    } catch (error: any) {
      setDataSources([]);
      message.warning(error?.response?.data?.message || '数据源列表暂不可用');
    } finally {
      setSourcesLoading(false);
    }
  }, []);

  const loadSummary = useCallback(async () => {
    setSummaryLoading(true);
    const filter: DataInventoryFilter = {
      unitId: filters.unitId,
      businessSystemId: filters.businessSystemId,
    };
    try {
      const response = await fetchDataInventoryOverview(filter);
      if (response.code !== 0 || !response.data) {
        throw new Error(response.message || 'inventory overview unavailable');
      }
      setSummary(response.data.summary || EMPTY_SUMMARY);
    } catch (error: any) {
      message.warning(error?.response?.data?.message || '探查统计暂不可用');
    } finally {
      setSummaryLoading(false);
    }
  }, [filters.businessSystemId, filters.unitId]);

  const load = useCallback(async () => {
    await Promise.all([loadSources(), loadSummary()]);
  }, [loadSources, loadSummary]);

  useEffect(() => {
    void loadUnits();
    void loadSources();
  }, [loadSources, loadUnits]);

  useEffect(() => {
    if (!filters.unitId) {
      setBusinessSystemOptions([]);
      if (filters.businessSystemId) {
        setFilters((current) => ({ ...current, businessSystemId: undefined }));
      }
      return;
    }
    void loadBusinessSystems(filters.unitId);
  }, [filters.businessSystemId, filters.unitId, loadBusinessSystems]);

  useEffect(() => {
    void loadSummary();
  }, [loadSummary]);

  const selectedSource = useMemo(
    () => dataSources.find((item) => String(item.id) === selectedSourceId),
    [dataSources, selectedSourceId],
  );

  const scopedSources = useMemo(
    () => dataSources.filter((source) => {
      if (filters.unitId && String(source.unitId ?? '') !== filters.unitId) return false;
      if (filters.businessSystemId && String(source.businessSystemId ?? '') !== filters.businessSystemId) return false;
      return true;
    }),
    [dataSources, filters.businessSystemId, filters.unitId],
  );

  const groupCounts = useMemo(() => {
    const counts: Record<ExplorationGroupKey, number> = {
      DATABASE: 0,
      MESSAGE_QUEUE: 0,
      API: 0,
      FILE_TRANSFER: 0,
    };
    scopedSources.forEach((source) => {
      counts[sourceGroupKey(source.dbType)] += 1;
    });
    return counts;
  }, [scopedSources]);

  const visibleSources = useMemo(() => {
    const normalizedSearch = search.trim().toLowerCase();
    return scopedSources.filter((source) => {
      if (selectedGroup !== 'ALL' && sourceGroupKey(source.dbType) !== selectedGroup) return false;
      if (!normalizedSearch) return true;
      const owner = sourceOwner(source);
      return [
        source.name,
        source.dbType,
        source.jdbcUrl,
        sourceEnvironment(source),
        owner.unit,
        owner.system,
      ].some((value) => String(value || '').toLowerCase().includes(normalizedSearch));
    });
  }, [scopedSources, search, selectedGroup]);

  const selectedSourceType = selectedSource?.dbType || selectedSourceDbType;
  const selectedSourceGroup = selectedSourceType ? sourceGroupKey(selectedSourceType) : undefined;
  const selectedSourceOwner = selectedSource ? sourceOwner(selectedSource) : undefined;
  const pageLoading = sourcesLoading || summaryLoading;

  const clearSelection = () => {
    setSelectedSourceId('');
    setSelectedSourceDbType(undefined);
    setDrawerOpen(false);
  };

  const handleUnitChange = (value?: string) => {
    clearSelection();
    setFilters({ unitId: value || undefined, businessSystemId: undefined });
  };

  const handleBusinessSystemChange = (value?: string) => {
    clearSelection();
    setFilters((current) => ({ ...current, businessSystemId: value || undefined }));
  };

  const handleGroupChange = (group: SelectedGroupKey) => {
    clearSelection();
    setSelectedGroup(group);
  };

  const openSource = (source: DataSourceRecord) => {
    const sourceId = String(source.id || '');
    if (!sourceId) return;
    setSelectedSourceId(sourceId);
    setSelectedSourceDbType(source.dbType);
    setDrawerOpen(true);
  };

  const exportAllResults = async () => {
    setExportLoading(true);
    try {
      const result = await downloadDataExplorationExport({
        unitId: filters.unitId,
        businessSystemId: filters.businessSystemId,
      });
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

  const selectedUnit = unitOptions.find((item) => String(item.id) === filters.unitId);
  const selectedBusinessSystem = businessSystemOptions.find(
    (item) => String(item.id) === filters.businessSystemId,
  );

  return (
    <div className="data-exploration-page data-exploration-results">
      <header className="results-toolbar">
        <div className="results-toolbar__heading">
          <span className="results-kicker">数据探查 / EXPLORER</span>
          <h1>探查结果</h1>
          <p>按数据源类型浏览元数据探查结果，进入数据源查看结构、样本与画像。</p>
        </div>
        <div className="results-toolbar__actions">
          <div className="results-scope-selects" aria-label="探查范围筛选">
            <label>
              <span>单位</span>
              <Select
                allowClear
                showSearch
                optionFilterProp="label"
                value={filters.unitId}
                loading={unitLoading}
                options={unitOptions.map((item) => ({ label: item.unitName, value: String(item.id) }))}
                placeholder="全部单位"
                onChange={handleUnitChange}
              />
            </label>
            <label>
              <span>业务系统</span>
              <Select
                allowClear
                showSearch
                optionFilterProp="label"
                value={filters.businessSystemId}
                loading={businessSystemLoading}
                disabled={!filters.unitId}
                options={businessSystemOptions.map((item) => ({ label: item.systemName, value: String(item.id) }))}
                placeholder={filters.unitId ? '全部业务系统' : '先选择单位'}
                onChange={handleBusinessSystemChange}
              />
            </label>
          </div>
          <div className="results-action-buttons">
            <Button icon={<PlayCircleOutlined />} onClick={() => history.push('/data-exploration/tasks')}>
              任务配置
            </Button>
            <Button icon={<ReloadOutlined />} loading={pageLoading} onClick={() => void load()}>
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
        </div>
      </header>

      <section className="results-workspace">
        <aside className="results-catalog" aria-label="探查类型导航">
          <div className="results-column-heading results-catalog__heading">
            <div>
              <span className="results-column-kicker">EXPLORATION TYPES</span>
              <h2>探查导航</h2>
            </div>
            <span className="results-column-count">{scopedSources.length} 个数据源</span>
          </div>

          <button
            type="button"
            className={`results-all-entry${selectedGroup === 'ALL' ? ' is-selected' : ''}`}
            onClick={() => handleGroupChange('ALL')}
          >
            <span className="results-all-entry__icon"><ApartmentOutlined /></span>
            <span className="results-all-entry__copy">
              <strong>全部类型</strong>
              <small>查看当前范围的全部结果</small>
            </span>
            <b>{scopedSources.length}</b>
          </button>

          <div className="results-catalog__divider" />
          <nav className="results-catalog__nav">
            {EXPLORATION_GROUPS.map((group) => (
              <button
                type="button"
                className={`results-type-entry${selectedGroup === group.key ? ' is-selected' : ''}`}
                key={group.key}
                onClick={() => handleGroupChange(group.key)}
              >
                <span className="results-type-entry__icon">{group.icon}</span>
                <span className="results-type-entry__copy">
                  <strong>{group.label}</strong>
                  <small>{group.description}</small>
                </span>
                <b>{groupCounts[group.key]}</b>
              </button>
            ))}
          </nav>

          <div className="results-catalog__footer">
            <span className="results-catalog__footer-icon"><DatabaseOutlined /></span>
            <span>类型导航固定展示，单位和业务系统在右上角筛选。</span>
          </div>
        </aside>

        <main className="results-browser" aria-label="探查结果列表">
          <div className="results-browser__header">
            <div className="results-browser__heading-copy">
              <span className="results-column-kicker">METADATA RESULTS</span>
              <h2>{GROUP_LABELS[selectedGroup]}</h2>
              <p>{GROUP_DESCRIPTIONS[selectedGroup]}</p>
            </div>
            <div className="results-browser__tools">
              <Input
                allowClear
                value={search}
                prefix={<SearchOutlined />}
                placeholder="搜索数据源、类型或环境"
                onChange={(event) => setSearch(event.target.value)}
              />
              <span className="results-browser__count">{visibleSources.length} 个结果</span>
            </div>
          </div>

          <div className="results-browser__scope">
            <span><ApartmentOutlined /> 单位：{selectedUnit?.unitName || '全部'}</span>
            <span><ApartmentOutlined /> 业务系统：{selectedBusinessSystem?.systemName || '全部'}</span>
            {selectedGroup !== 'ALL' && <Tag color="blue">{GROUP_LABELS[selectedGroup]}</Tag>}
          </div>

          <div className="results-browser__list">
            {sourcesLoading && dataSources.length === 0 ? (
              <div className="results-inline-loading"><Spin size="small" /> 正在加载探查结果</div>
            ) : visibleSources.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={search ? '没有匹配的探查结果' : '当前范围暂无探查结果'}
              />
            ) : (
              visibleSources.map((source) => {
                const owner = sourceOwner(source);
                const status = explorationStatus(source.profileStatus);
                const sourceId = String(source.id || source.name || '');
                const isSelected = sourceId === selectedSourceId;
                return (
                  <button
                    type="button"
                    className={`results-source-row${isSelected ? ' is-selected' : ''}`}
                    key={sourceId}
                    onClick={() => openSource(source)}
                  >
                    <span className="results-source-row__icon">
                      <DatabaseIcons dbType={source.dbType} width="23" height="23" />
                    </span>
                    <span className="results-source-row__body">
                      <span className="results-source-row__title-row">
                        <strong title={source.name}>{source.name || sourceId}</strong>
                        <Tag>{source.dbType || '数据库'}</Tag>
                      </span>
                      <span className="results-source-row__meta">
                        <span>{sourceTypeLabel(source.dbType)}</span>
                        <i>·</i>
                        <span>{sourceEnvironment(source)}</span>
                        {source.jdbcUrl && <><i>·</i><span title={source.jdbcUrl}>{source.jdbcUrl}</span></>}
                      </span>
                      <span className="results-source-row__footer">
                        <span className="results-source-row__owners">
                          <span><ApartmentOutlined /> {owner.unit}</span>
                          <span><ApartmentOutlined /> {owner.system}</span>
                        </span>
                        <Tag color={status.color}>{status.label}</Tag>
                      </span>
                    </span>
                    <span className="results-source-row__arrow" aria-hidden="true">→</span>
                  </button>
                );
              })
            )}
          </div>

          <div className="results-browser__footer">
            <span><DatabaseOutlined /> 共 {scopedSources.length} 个数据源</span>
            <span>点击数据源进入结构、样本与画像探查</span>
          </div>
        </main>

        <aside className="results-inspector" aria-label="当前结果摘要">
          <div className="results-column-heading">
            <div>
              <span className="results-column-kicker">INSPECTOR</span>
              <h2>当前结果摘要</h2>
            </div>
          </div>
          <div className="results-inspector__body">
            <div className="results-inspector__hero">
              <span className="results-inspector__hero-icon">
                {selectedSourceType
                  ? <DatabaseIcons dbType={selectedSourceType} width="23" height="23" />
                  : <DatabaseOutlined />}
              </span>
              <div>
                <span className="results-inspector__type">
                  {selectedSourceType ? sourceTypeLabel(selectedSourceType) : GROUP_LABELS[selectedGroup]}
                </span>
                <h3 title={selectedSource?.name || undefined}>{selectedSource?.name || '全部探查结果'}</h3>
                <p>{selectedSourceType || GROUP_DESCRIPTIONS[selectedGroup]}</p>
              </div>
            </div>

            <section className="results-inspector__section">
              <div className="results-inspector__section-title">当前范围</div>
              <div className="results-inspector__scope-copy">
                <span>单位 <b>{selectedUnit?.unitName || '全部'}</b></span>
                <span>业务系统 <b>{selectedBusinessSystem?.systemName || '全部'}</b></span>
                <span>结果类型 <b>{GROUP_LABELS[selectedGroup]}</b></span>
              </div>
            </section>

            <section className="results-inspector__section">
              <div className="results-inspector__section-title">结果统计</div>
              <div className="results-inspector__stats">
                <span><small>数据源</small><b>{formatMetric(scopedSources.length)}</b></span>
                <span><small>数据表</small><b>{formatMetric(summary.tableCount)}</b></span>
                <span><small>字段</small><b>{formatMetric(summary.columnCount)}</b></span>
                <span><small>已统计行数</small><b>{formatMetric(summary.knownRowCount)}</b></span>
              </div>
            </section>

            {!selectedSource && (
              <section className="results-inspector__section">
                <div className="results-inspector__section-title">类型分布</div>
                <div className="results-inspector__distribution">
                  {EXPLORATION_GROUPS.map((group) => (
                    <button type="button" key={group.key} onClick={() => handleGroupChange(group.key)}>
                      <span>{group.label}</span><b>{groupCounts[group.key]}</b>
                    </button>
                  ))}
                </div>
              </section>
            )}

            {selectedSource && (
              <section className="results-inspector__section">
                <div className="results-inspector__section-title">数据源信息</div>
                <div className="results-inspector__details">
                  <span><small>类型</small><b>{selectedSourceType || '—'}</b></span>
                  <span><small>单位</small><b>{selectedSourceOwner?.unit || '—'}</b></span>
                  <span><small>业务系统</small><b>{selectedSourceOwner?.system || '—'}</b></span>
                  <span><small>探查状态</small><b>{explorationStatus(selectedSource.profileStatus).label}</b></span>
                  <span><small>最近成功</small><b>{selectedSource.profileLastSuccessTime || '—'}</b></span>
                </div>
                {selectedSourceGroup && (
                  <Tag className="results-inspector__source-tag" color="blue">
                    {GROUP_LABELS[selectedSourceGroup]}
                  </Tag>
                )}
              </section>
            )}
          </div>
        </aside>
      </section>

      <DataExplorationDrawer
        open={drawerOpen && Boolean(selectedSourceId)}
        dataSourceId={selectedSourceId || undefined}
        dataSourceName={selectedSource?.name}
        dbType={selectedSource?.dbType || selectedSourceDbType}
        onClose={() => setDrawerOpen(false)}
      />
    </div>
  );
};

export default DataExplorationResultsPage;
