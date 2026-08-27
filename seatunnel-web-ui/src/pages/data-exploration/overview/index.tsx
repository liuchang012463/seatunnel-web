import {
  ApartmentOutlined,
  DatabaseOutlined,
  DownloadOutlined,
  ReloadOutlined,
  TableOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import {
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Progress,
  Row,
  Select,
  Statistic,
  Table,
  Tag,
  message,
} from 'antd';
import type { TableColumnsType } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import TaskListPageHeader from '@/components/TaskListPageHeader';
import {
  downloadDataExplorationExport,
  fetchBusinessSystemOptions,
  fetchDataInventoryProfileCoverage,
  fetchDataInventorySummary,
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

function downloadBlob(result: any, filename: string) {
  const blob = result instanceof Blob ? result : result?.data instanceof Blob ? result.data : result?.response?.data;
  if (!(blob instanceof Blob)) return false;
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
  return true;
}

const DataExplorationOverviewPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
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
      const [summaryResponse, coverageResponse, sourceResponse] = await Promise.all([
        fetchDataInventorySummary(requestFilter),
        fetchDataInventoryProfileCoverage(requestFilter),
        fetchDataSourceAll(),
      ]);
      if (summaryResponse.code === 0 && summaryResponse.data) setSummary(summaryResponse.data);
      if (coverageResponse.code === 0 && coverageResponse.data) setCoverage(coverageResponse.data);
      if (sourceResponse.code === 0) setDataSources(normalizeDataSourceList(sourceResponse.data));
    } catch (_) {
      message.warning('探查概览暂不可用，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [appliedFilter]);

  useEffect(() => {
    void loadUnits();
  }, [loadUnits]);

  useEffect(() => {
    void loadBusinessSystems(appliedFilter.unitId);
  }, [appliedFilter.unitId, loadBusinessSystems]);

  useEffect(() => {
    void load();
  }, [load]);

  const filteredSources = useMemo(
    () => dataSources.filter((record) => sourceMatches(record, appliedFilter)),
    [dataSources, appliedFilter],
  );

  const tableColumns: TableColumnsType<DataSourceRecord> = [
    {
      title: '数据源',
      key: 'name',
      fixed: 'left',
      width: 210,
      render: (_, record) => (
        <div className="min-w-0">
          <div className="truncate font-medium" title={record.name}>{record.name || '-'}</div>
          <div className="truncate text-xs text-[var(--st-color-text-muted)]" title={record.jdbcUrl}>
            {record.jdbcUrl || '-'}
          </div>
        </div>
      ),
    },
    { title: '单位', key: 'unit', width: 150, render: (_, record) => displayOwner(record).unit },
    { title: '业务系统', key: 'system', width: 170, render: (_, record) => displayOwner(record).system },
    { title: '数据源类型', dataIndex: 'dbType', key: 'dbType', width: 120, render: (value) => value || '-' },
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
      render: (_, record) => record.profileLastSuccessTime || record.profileLastRunTime || '-',
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 120,
      render: (_, record) => (
        <Button
          type="link"
          icon={<ApartmentOutlined />}
          onClick={() => history.push(`/data-exploration/results?dataSourceId=${encodeURIComponent(String(record.id))}`)}
        >
          查看结果
        </Button>
      ),
    },
  ];

  const exportWorkbook = async () => {
    try {
      const result = await downloadDataExplorationExport(filterToRequest(appliedFilter));
      if (!downloadBlob(result, 'data-exploration-overview.xlsx')) message.error('导出响应不可用');
    } catch (_) {
      message.error('数据清查导出失败');
    }
  };

  const selectedUnit = unitOptions.find((item) => String(item.id) === appliedFilter.unitId);
  const selectedSystem = businessSystemOptions.find((item) => String(item.id) === appliedFilter.businessSystemId);
  const selectedSource = dataSources.find((item) => String(item.id) === appliedFilter.dataSourceId);

  return (
    <div className="data-exploration-page data-exploration-overview min-h-full px-6 py-5">
      <TaskListPageHeader
        icon={<TableOutlined />}
        title="探查概览"
        subtitle="汇总展示所有数据源的清查底数与探查覆盖情况；可在右上角按单位、业务系统或数据源缩小范围。"
        actions={(
          <div className="exploration-overview-actions">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              className="exploration-overview-filter exploration-overview-filter--unit"
              placeholder="全部单位"
              value={appliedFilter.unitId}
              options={unitOptions.map((item) => ({ label: item.unitName, value: String(item.id) }))}
              onChange={(value) => setAppliedFilter((current) => ({
                ...current,
                unitId: value,
                businessSystemId: undefined,
              }))}
            />
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              className="exploration-overview-filter exploration-overview-filter--system"
              placeholder={appliedFilter.unitId ? '全部业务系统' : '先选单位'}
              disabled={!appliedFilter.unitId}
              value={appliedFilter.businessSystemId}
              options={businessSystemOptions.map((item) => ({ label: item.systemName, value: String(item.id) }))}
              onChange={(value) => setAppliedFilter((current) => ({ ...current, businessSystemId: value }))}
            />
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              className="exploration-overview-filter exploration-overview-filter--source"
              placeholder="全部数据源"
              value={appliedFilter.dataSourceId}
              options={dataSources.map((item) => ({ label: item.name || String(item.id), value: String(item.id) }))}
              onChange={(value) => setAppliedFilter((current) => ({ ...current, dataSourceId: value }))}
            />
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新</Button>
            <Button icon={<DownloadOutlined />} onClick={() => void exportWorkbook()}>导出结果</Button>
          </div>
        )}
      />

      <div className="exploration-overview-body mt-5">
        <Row gutter={[12, 12]} className="exploration-overview-metrics">
          {[
            ['数据源', summary.dataSourceCount, <DatabaseOutlined key="source" />],
            ['Database', summary.databaseCount, <DatabaseOutlined key="database" />],
            ['Schema', summary.schemaCount, <ApartmentOutlined key="schema" />],
            ['Table', summary.tableCount, <TableOutlined key="table" />],
            ['字段', summary.columnCount, <TableOutlined key="column" />],
            ['已知数据量', summary.knownRowCount, <TableOutlined key="rows" />],
          ].map(([title, value, prefix]) => (
            <Col key={String(title)} xs={12} sm={8} lg={4}>
              <Card size="small" className="exploration-panel exploration-metric-card h-full">
                <Statistic title={title as string} value={value as number} prefix={prefix as React.ReactNode} />
              </Card>
            </Col>
          ))}
        </Row>

        <Row gutter={[12, 12]} className="exploration-overview-secondary mt-3">
          <Col xs={24} lg={8}>
            <Card className="exploration-panel exploration-coverage-card h-full" title="探查覆盖情况" size="small" loading={loading}>
              <div className="exploration-coverage-content flex items-center gap-5">
                <Progress type="circle" percent={Number(coverage.tableCoveragePercent.toFixed(1))} />
                <Descriptions size="small" column={1}>
                  <Descriptions.Item label="已探查 Database">
                    {coverage.profiledDatabaseCount} / {coverage.databaseCount}
                  </Descriptions.Item>
                  <Descriptions.Item label="已探查 Table">
                    {coverage.profiledTableCount} / {coverage.tableCount}
                  </Descriptions.Item>
                  <Descriptions.Item label="已知数据量">{coverage.knownRowCount}</Descriptions.Item>
                </Descriptions>
              </div>
            </Card>
          </Col>
          <Col xs={24} lg={16}>
            <Card className="exploration-panel exploration-scope-card h-full" title="当前范围" size="small" loading={loading}>
              <div className="exploration-scope-content flex flex-wrap gap-2">
                <Tag color={selectedUnit ? 'blue' : 'default'}>单位：{selectedUnit?.unitName || '全部'}</Tag>
                <Tag color={selectedSystem ? 'blue' : 'default'}>业务系统：{selectedSystem?.systemName || '全部'}</Tag>
                <Tag color={selectedSource ? 'blue' : 'default'}>数据源：{selectedSource?.name || '全部'}</Tag>
                <span className="text-xs text-[var(--st-color-text-muted)]">
                  未设置筛选时展示全部数据源；筛选仅影响本页概览与导出结果。
                </span>
              </div>
            </Card>
          </Col>
        </Row>

        <Card
          className="exploration-panel exploration-source-card mt-3"
          title={<span className="inline-flex items-center gap-2"><ApartmentOutlined />数据源探查情况</span>}
          extra={<span className="text-xs text-[var(--st-color-text-muted)]">共 {filteredSources.length} 个数据源</span>}
        >
          <Table<DataSourceRecord>
            rowKey={(record) => String(record.id || record.name)}
            loading={loading}
            columns={tableColumns}
            dataSource={filteredSources}
            pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }}
            scroll={{ x: 1050 }}
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无符合条件的数据源" /> }}
          />
        </Card>
      </div>

    </div>
  );
};

export default DataExplorationOverviewPage;
