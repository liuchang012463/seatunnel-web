import {
  ApartmentOutlined,
  DatabaseOutlined,
  ReloadOutlined,
  TableOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import {
  Button,
  Card,
  Empty,
  Progress,
  Select,
  Statistic,
  Table,
  Tag,
  message,
} from 'antd';
import type { TableColumnsType } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  fetchBusinessSystemOptions,
  fetchDataInventoryOverview,
  fetchDataSourceAll,
  fetchDataSourceUnitOptions,
  unwrapMasterDataList,
} from '@/pages/data-source/service';
import type {
  BusinessSystemOption,
  DataInventoryFilter,
  DataInventoryProfileCoverage,
  DataInventorySummary,
  DataSourceRecord,
  DataSourceUnitOption,
} from '@/pages/data-source/types';
import { displayOwner, explorationStatus, normalizeDataSourceList, sourceMatches } from '../shared';
import '../index.less';
import './overview.less';

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

const EMPTY_COVERAGE: DataInventoryProfileCoverage = {
  databaseCount: 0,
  profiledDatabaseCount: 0,
  tableCount: 0,
  profiledTableCount: 0,
  knownRowCount: 0,
  tableCoveragePercent: 0,
};

type FilterValues = {
  unitId?: string;
  businessSystemId?: string;
  dataSourceId?: string;
};

const filterToRequest = (filter: FilterValues): DataInventoryFilter => ({
  unitId: filter.unitId || undefined,
  businessSystemId: filter.businessSystemId || undefined,
  dataSourceId: filter.dataSourceId || undefined,
});

const DataExplorationOverviewPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [sourcesLoading, setSourcesLoading] = useState(false);
  const [summary, setSummary] = useState<DataInventorySummary>(EMPTY_SUMMARY);
  const [coverage, setCoverage] = useState<DataInventoryProfileCoverage>(EMPTY_COVERAGE);
  const [dataSources, setDataSources] = useState<DataSourceRecord[]>([]);
  const [unitOptions, setUnitOptions] = useState<DataSourceUnitOption[]>([]);
  const [businessSystemOptions, setBusinessSystemOptions] = useState<BusinessSystemOption[]>([]);
  const [appliedFilter, setAppliedFilter] = useState<FilterValues>({});

  const loadUnits = useCallback(async () => {
    try {
      const response = await fetchDataSourceUnitOptions();
      if (response.code === 0) setUnitOptions(unwrapMasterDataList(response));
    } catch (_) {
      setUnitOptions([]);
    }
  }, []);

  const loadBusinessSystems = useCallback(async (unitId?: string) => {
    if (!unitId) {
      setBusinessSystemOptions([]);
      return;
    }
    try {
      const response = await fetchBusinessSystemOptions(unitId);
      if (response.code === 0) setBusinessSystemOptions(unwrapMasterDataList(response));
    } catch (_) {
      setBusinessSystemOptions([]);
    }
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    const requestFilter = filterToRequest(appliedFilter);
    try {
      const response = await fetchDataInventoryOverview(requestFilter);
      if (response.code !== 0 || !response.data) {
        throw new Error(response.message || 'overview unavailable');
      }
      setSummary(response.data.summary || EMPTY_SUMMARY);
      setCoverage(response.data.coverage || EMPTY_COVERAGE);
    } catch (_) {
      message.warning('探查概览暂不可用，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [appliedFilter]);

  const loadSources = useCallback(async () => {
    setSourcesLoading(true);
    try {
      const response = await fetchDataSourceAll();
      if (response.code === 0) setDataSources(normalizeDataSourceList(response.data));
    } catch (_) {
      message.warning('数据源列表暂不可用，筛选项可能不完整');
    } finally {
      setSourcesLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadUnits();
    void loadSources();
  }, [loadSources, loadUnits]);

  useEffect(() => {
    void loadBusinessSystems(appliedFilter.unitId);
  }, [appliedFilter.unitId, loadBusinessSystems]);

  useEffect(() => {
    void load();
  }, [load]);

  const availableSources = useMemo(
    () => dataSources.filter((record) => sourceMatches(record, {
      unitId: appliedFilter.unitId,
      businessSystemId: appliedFilter.businessSystemId,
    })),
    [appliedFilter.businessSystemId, appliedFilter.unitId, dataSources],
  );

  const filteredSources = useMemo(
    () => availableSources.filter((record) => sourceMatches(record, appliedFilter)),
    [availableSources, appliedFilter],
  );

  const tableColumns: TableColumnsType<DataSourceRecord> = [
    {
      title: '数据源',
      key: 'name',
      fixed: 'left',
      width: 230,
      render: (_, record) => (
        <div className="overview-source-name">
          <div className="overview-source-name__title" title={record.name}>{record.name || '-'}</div>
          <div className="overview-source-name__url" title={record.jdbcUrl}>{record.jdbcUrl || '未提供连接地址'}</div>
        </div>
      ),
    },
    { title: '单位', key: 'unit', width: 150, render: (_, record) => displayOwner(record).unit },
    { title: '业务系统', key: 'system', width: 170, render: (_, record) => displayOwner(record).system },
    { title: '类型', dataIndex: 'dbType', key: 'dbType', width: 120, render: (value) => value || '-' },
    {
      title: '探查状态',
      dataIndex: 'profileStatus',
      key: 'profileStatus',
      width: 110,
      render: (value: string) => {
        const status = explorationStatus(value);
        return <Tag color={status.color}>{status.label}</Tag>;
      },
    },
    {
      title: '最近成功探查',
      key: 'profileTime',
      width: 170,
      render: (_, record) => record.profileLastSuccessTime || record.profileLastRunTime || '—',
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 110,
      render: (_, record) => (
        <Button
          type="link"
          icon={<ApartmentOutlined />}
          onClick={() => {
            const query = new URLSearchParams({ dataSourceId: String(record.id) });
            if (record.dbType) query.set('dbType', record.dbType);
            history.push(`/data-exploration/results?${query.toString()}`);
          }}
        >
          查看结果
        </Button>
      ),
    },
  ];

  const selectedUnit = unitOptions.find((item) => String(item.id) === appliedFilter.unitId);
  const selectedSystem = businessSystemOptions.find((item) => String(item.id) === appliedFilter.businessSystemId);
  const selectedSource = dataSources.find((item) => String(item.id) === appliedFilter.dataSourceId);

  return (
    <div className="data-exploration-page data-exploration-overview">
      <header className="overview-page-header">
        <div className="overview-page-header__copy">
          <span className="overview-kicker">数据探查 / OVERVIEW</span>
          <h1>探查概览</h1>
          <p>查看当前数据探查规模、元数据与数据画像的探查覆盖情况。</p>
        </div>
        <Button
          className="overview-refresh"
          icon={<ReloadOutlined />}
          loading={loading}
          onClick={() => void load()}
        >
          刷新数据
        </Button>
      </header>

      <section className="overview-filter-bar" aria-label="探查范围筛选">
        <div className="overview-filter-bar__intro">
          <span className="overview-filter-bar__label">筛选范围</span>
          <span className="overview-filter-bar__hint">选择范围后，指标与数据源列表会同步更新</span>
        </div>
        <div className="overview-filter-bar__fields">
          <label className="overview-filter-field">
            <span>单位</span>
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="全部单位"
              value={appliedFilter.unitId}
              options={unitOptions.map((item) => ({ label: item.unitName, value: String(item.id) }))}
              onChange={(value) => setAppliedFilter((current) => ({
                ...current,
                unitId: value,
                businessSystemId: undefined,
                dataSourceId: undefined,
              }))}
            />
          </label>
          <label className="overview-filter-field">
            <span>业务系统</span>
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder={appliedFilter.unitId ? '全部业务系统' : '选择单位后可筛选'}
              disabled={!appliedFilter.unitId}
              value={appliedFilter.businessSystemId}
              options={businessSystemOptions.map((item) => ({ label: item.systemName, value: String(item.id) }))}
              onChange={(value) => setAppliedFilter((current) => ({
                ...current,
                businessSystemId: value,
                dataSourceId: undefined,
              }))}
            />
          </label>
          <label className="overview-filter-field overview-filter-field--source">
            <span>数据源</span>
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="全部数据源"
              value={appliedFilter.dataSourceId}
              loading={sourcesLoading}
              options={availableSources.map((item) => ({
                label: `${item.name || String(item.id)}${item.dbType ? ` · ${item.dbType}` : ''}`,
                value: String(item.id),
              }))}
              onChange={(value) => setAppliedFilter((current) => ({ ...current, dataSourceId: value }))}
            />
          </label>
        </div>
      </section>

      <main className="overview-content">
        <section className="overview-panel overview-assets-panel">
          <div className="overview-panel-heading">
            <div>
              <span className="overview-panel-heading__eyebrow">INVENTORY</span>
              <h2>资产规模</h2>
            </div>
            <span className="overview-panel-heading__meta">已纳入当前范围</span>
          </div>
          <div className="overview-primary-metrics">
            <div className="overview-primary-metric overview-primary-metric--accent">
              <DatabaseOutlined />
              <Statistic title="数据源" value={summary.dataSourceCount} />
            </div>
            <div className="overview-primary-metric">
              <TableOutlined />
              <Statistic title="数据表" value={summary.tableCount} />
            </div>
            <div className="overview-primary-metric">
              <TableOutlined />
              <Statistic title="字段" value={summary.columnCount} />
            </div>
            <div className="overview-primary-metric overview-primary-metric--rows">
              <TableOutlined />
              <Statistic title="已统计行数" value={summary.knownRowCount} />
            </div>
          </div>
          <div className="overview-secondary-metrics" aria-label="次级资产指标">
            <span><small>Database</small><strong>{summary.databaseCount}</strong></span>
            <span><small>Schema</small><strong>{summary.schemaCount}</strong></span>
            <span><small>单位</small><strong>{summary.unitCount}</strong></span>
            <span><small>业务系统</small><strong>{summary.businessSystemCount}</strong></span>
          </div>
        </section>

        <div className="overview-insight-grid">
          <section className="overview-panel overview-coverage-panel">
            <div className="overview-panel-heading">
              <div>
                <span className="overview-panel-heading__eyebrow">COVERAGE</span>
                <h2>探查覆盖率</h2>
              </div>
              <span className="overview-panel-heading__meta">数据表</span>
            </div>
            <div className="overview-coverage-body">
              <Progress
                type="circle"
                size={92}
                percent={Number(coverage.tableCoveragePercent.toFixed(1))}
                strokeColor="var(--st-color-accent)"
                trailColor="var(--st-color-bg-control)"
              />
              <div className="overview-coverage-copy">
                <strong>{coverage.profiledTableCount} <small>/ {coverage.tableCount}</small></strong>
                <span>数据表已完成探查</span>
                <div className="overview-coverage-details">
                  <span>Database <b>{coverage.profiledDatabaseCount} / {coverage.databaseCount}</b></span>
                  <span>已统计行数 <b>{coverage.knownRowCount}</b></span>
                </div>
              </div>
            </div>
          </section>

          <section className="overview-panel overview-scope-panel">
            <div className="overview-panel-heading">
              <div>
                <span className="overview-panel-heading__eyebrow">SCOPE</span>
                <h2>当前范围</h2>
              </div>
              <span className="overview-panel-heading__meta">指标与列表同步</span>
            </div>
            <div className="overview-scope-body">
              <div className="overview-scope-tags">
                <Tag color={selectedUnit ? 'blue' : undefined}>单位：{selectedUnit?.unitName || '全部'}</Tag>
                <Tag color={selectedSystem ? 'blue' : undefined}>业务系统：{selectedSystem?.systemName || '全部'}</Tag>
                <Tag color={selectedSource ? 'blue' : undefined}>数据源：{selectedSource?.name || '全部'}</Tag>
              </div>
              <p>当前范围用于查看概览指标和下方数据源状态；需要深入查看结构、样本或关系时，请进入探查结果。</p>
            </div>
          </section>
        </div>

        <section className="overview-panel overview-source-panel">
          <div className="overview-panel-heading overview-source-panel__heading">
            <div>
              <span className="overview-panel-heading__eyebrow">DATA SOURCES</span>
              <h2>数据源探查状态</h2>
            </div>
            <span className="overview-panel-heading__meta">共 {filteredSources.length} 个数据源</span>
          </div>
          <Table<DataSourceRecord>
            rowKey={(record) => String(record.id || record.name)}
            loading={sourcesLoading}
            columns={tableColumns}
            dataSource={filteredSources}
            pagination={{ pageSize: 8, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }}
            scroll={{ x: 1050 }}
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无符合条件的数据源" /> }}
          />
        </section>
      </main>
    </div>
  );
};

export default DataExplorationOverviewPage;
