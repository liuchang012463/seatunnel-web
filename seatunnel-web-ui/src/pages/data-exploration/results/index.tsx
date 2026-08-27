import {
  ApartmentOutlined,
  FilterOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  TableOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import { Button, Card, Col, Empty, Row, Select, Space, Spin, Statistic, Tag, Tree, message } from 'antd';
import type { TreeDataNode } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import TaskListPageHeader from '@/components/TaskListPageHeader';
import DataExplorationDrawer from '../components/DataExplorationDrawer';
import {
  fetchBusinessSystemOptions,
  fetchDataInventorySummary,
  fetchDataSourceAll,
  fetchDataSourceTopologyChildren,
  fetchDataSourceTopologyTree,
  fetchDataSourceUnitOptions,
  unwrapMasterDataList,
} from '@/pages/data-source/service';
import type {
  BusinessSystemOption,
  DataInventoryFilter,
  DataInventorySummary,
  DataSourceRecord,
  DataSourceTopologyNode,
  DataSourceTopologyNodeType,
  DataSourceUnitOption,
} from '@/pages/data-source/types';
import {
  normalizeDataSourceList,
  replaceTopologyChildren,
  topologyTreeData,
} from '../shared';
import '../index.less';

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

type ResultFilter = {
  unitId?: string;
  businessSystemId?: string;
  dataSourceId?: string;
};

const toRequestFilter = (filter: ResultFilter): DataInventoryFilter => ({
  unitId: filter.unitId || undefined,
  businessSystemId: filter.businessSystemId || undefined,
  dataSourceId: filter.dataSourceId || undefined,
});

const DataExplorationResultsPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [topologyNodes, setTopologyNodes] = useState<DataSourceTopologyNode[]>([]);
  const [summary, setSummary] = useState<DataInventorySummary>(EMPTY_SUMMARY);
  const [dataSources, setDataSources] = useState<DataSourceRecord[]>([]);
  const [unitOptions, setUnitOptions] = useState<DataSourceUnitOption[]>([]);
  const [businessSystemOptions, setBusinessSystemOptions] = useState<BusinessSystemOption[]>([]);
  const [filter, setFilter] = useState<ResultFilter>({});
  const [selectedSourceId, setSelectedSourceId] = useState<string>();
  const [drawerOpen, setDrawerOpen] = useState(false);

  const loadOptions = useCallback(async () => {
    try {
      const [unitResponse, sourceResponse] = await Promise.all([
        fetchDataSourceUnitOptions(),
        fetchDataSourceAll(),
      ]);
      if (unitResponse.code === 0) setUnitOptions(unwrapMasterDataList(unitResponse));
      if (sourceResponse.code === 0) setDataSources(normalizeDataSourceList(sourceResponse.data));
    } catch (_) {
      message.warning('探查结果筛选项暂不可用');
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
    const requestFilter = toRequestFilter(filter);
    try {
      const [treeResponse, summaryResponse] = await Promise.all([
        fetchDataSourceTopologyTree(requestFilter),
        fetchDataInventorySummary(requestFilter),
      ]);
      if (treeResponse.code !== 0) {
        message.warning(treeResponse.message || '拓扑暂不可用');
        setTopologyNodes([]);
      } else {
        setTopologyNodes(treeResponse.data || []);
      }
      if (summaryResponse.code === 0 && summaryResponse.data) setSummary(summaryResponse.data);
    } catch (error: any) {
      message.warning(error?.response?.data?.message || '探查结果暂不可用，请稍后重试');
      setTopologyNodes([]);
    } finally {
      setLoading(false);
    }
  }, [filter]);

  useEffect(() => {
    void loadOptions();
    const initialDataSourceId = new URLSearchParams(window.location.search).get('dataSourceId') || undefined;
    if (initialDataSourceId) {
      setFilter({ dataSourceId: initialDataSourceId });
      setSelectedSourceId(initialDataSourceId);
      setDrawerOpen(true);
    }
  }, [loadOptions]);

  useEffect(() => {
    void loadBusinessSystems(filter.unitId);
  }, [filter.unitId, loadBusinessSystems]);

  useEffect(() => {
    void load();
  }, [load]);

  const onSelect = (keys: React.Key[]) => {
    if (keys.length === 0) return;
    const key = String(keys[0]);
    const separator = key.indexOf(':');
    if (separator <= 0) return;
    const nodeType = key.substring(0, separator) as DataSourceTopologyNodeType;
    const nodeId = key.substring(separator + 1);
    if (nodeType === 'DATA_SOURCE') {
      setSelectedSourceId(nodeId);
      setDrawerOpen(true);
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
        message.warning(response.message || '拓扑节点暂不可用');
        return;
      }
      setTopologyNodes((current) => replaceTopologyChildren(current, key, response.data || []));
    } catch (error: any) {
      message.warning(error?.response?.data?.message || '拓扑节点暂不可用');
    }
  };

  const sourceOptions = useMemo(
    () => dataSources.map((source) => ({
      label: `${source.name || '-'}${source.dbType ? ` · ${source.dbType}` : ''}`,
      value: String(source.id),
    })),
    [dataSources],
  );

  const selectedSource = dataSources.find((item) => String(item.id) === selectedSourceId);

  const clearFilter = () => {
    setFilter({});
    setSelectedSourceId(undefined);
    setDrawerOpen(false);
  };

  return (
    <div className="data-exploration-page data-exploration-results min-h-full px-6 py-5">
      <TaskListPageHeader
        icon={<ApartmentOutlined />}
        title="探查结果展示"
        subtitle="以单位、业务系统、数据源、Database、Schema、Table 的拓扑层级展示探查结果，点击数据源节点查看结构与画像。"
        actions={(
          <Space wrap>
            <Button icon={<PlayCircleOutlined />} onClick={() => history.push('/data-exploration/tasks')}>任务配置</Button>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新</Button>
          </Space>
        )}
      />

      <Card className="exploration-panel exploration-filter-panel mt-5" size="small" title={<span className="inline-flex items-center gap-2"><FilterOutlined />结果范围</span>}>
        <div className="flex flex-wrap items-center gap-3">
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            className="w-[190px]"
            placeholder="全部单位"
            value={filter.unitId}
            options={unitOptions.map((item) => ({
              label: item.unitCode ? `${item.unitName}（${item.unitCode}）` : item.unitName,
              value: String(item.id),
            }))}
            onChange={(value) => {
              setFilter((current) => ({ ...current, unitId: value, businessSystemId: undefined, dataSourceId: undefined }));
              setSelectedSourceId(undefined);
              setDrawerOpen(false);
            }}
          />
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            className="w-[210px]"
            placeholder={filter.unitId ? '全部业务系统' : '请先选择单位'}
            disabled={!filter.unitId}
            value={filter.businessSystemId}
            options={businessSystemOptions.map((item) => ({
              label: item.systemCode ? `${item.systemName}（${item.systemCode}）` : item.systemName,
              value: String(item.id),
            }))}
            onChange={(value) => {
              setFilter((current) => ({ ...current, businessSystemId: value, dataSourceId: undefined }));
              setSelectedSourceId(undefined);
              setDrawerOpen(false);
            }}
          />
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            className="w-[260px]"
            placeholder="全部数据源"
            value={filter.dataSourceId}
            options={sourceOptions}
            onChange={(value) => {
              setFilter((current) => ({ ...current, dataSourceId: value }));
              setSelectedSourceId(value);
              setDrawerOpen(Boolean(value));
            }}
          />
          <Button onClick={clearFilter}>清除筛选</Button>
          <span className="ml-auto text-xs text-[var(--st-color-text-muted)]">未筛选时展示全部拓扑</span>
        </div>
      </Card>

      <Row gutter={[12, 12]} className="exploration-stat-strip mt-3">
        <Col xs={12} sm={8} lg={4}><Card className="exploration-metric-card" size="small"><Statistic title="数据源" value={summary.dataSourceCount} /></Card></Col>
        <Col xs={12} sm={8} lg={4}><Card className="exploration-metric-card" size="small"><Statistic title="Database" value={summary.databaseCount} /></Card></Col>
        <Col xs={12} sm={8} lg={4}><Card className="exploration-metric-card" size="small"><Statistic title="Schema" value={summary.schemaCount} /></Card></Col>
        <Col xs={12} sm={8} lg={4}><Card className="exploration-metric-card" size="small"><Statistic title="Table" value={summary.tableCount} /></Card></Col>
        <Col xs={12} sm={8} lg={4}><Card className="exploration-metric-card" size="small"><Statistic title="字段" value={summary.columnCount} /></Card></Col>
        <Col xs={12} sm={8} lg={4}><Card className="exploration-metric-card" size="small"><Statistic title="已探查表" value={summary.profiledTableCount} /></Card></Col>
      </Row>

      <Card
        className="exploration-panel exploration-topology-panel mt-3"
        title={<span className="inline-flex items-center gap-2"><ApartmentOutlined />数据资产拓扑</span>}
        extra={selectedSource ? <Tag color="blue">当前：{selectedSource.name || selectedSource.id}</Tag> : undefined}
      >
        <Spin spinning={loading}>
          {topologyNodes.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无符合条件的探查结果" />
          ) : (
            <div className="min-h-[520px] rounded-lg border border-[var(--st-color-border)] bg-[rgba(77,210,255,0.03)] p-4">
              <Tree
                blockNode
                showLine
                selectable
                treeData={topologyTreeData(topologyNodes)}
                loadData={loadTopologyData}
                onSelect={onSelect}
              />
            </div>
          )}
        </Spin>
      </Card>

      <DataExplorationDrawer
        open={drawerOpen && Boolean(selectedSourceId)}
        dataSourceId={selectedSourceId}
        dataSourceName={selectedSource?.name}
        onClose={() => setDrawerOpen(false)}
      />
    </div>
  );
};

export default DataExplorationResultsPage;
