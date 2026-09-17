import { DownloadOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import {
  Button,
  Card,
  Col,
  DatePicker,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import React, { useMemo, useState } from 'react';
import {
  DATA_SOURCES,
  REPORT_ROWS,
  SYSTEMS,
  TASK_TYPE_LABEL,
  UNITS,
} from './mock';
import type { IngestionReportRow, IngestionTaskType, ReportFilters } from './types';
import {
  buildDailyTrend,
  buildSummary,
  buildTaskTypeBreakdown,
  downloadCsv,
  filterRows,
  formatBytes,
  formatCount,
  formatPercent,
  rowsToCsv,
} from './utils';
import './index.less';

const { RangePicker } = DatePicker;

const defaultRange: [Dayjs, Dayjs] = [dayjs().subtract(6, 'day'), dayjs()];

const taskTypeOptions = (Object.keys(TASK_TYPE_LABEL) as IngestionTaskType[]).map((value) => ({
  value,
  label: TASK_TYPE_LABEL[value],
}));

const taskTypeColor: Record<IngestionTaskType, string> = {
  BATCH: 'blue',
  STREAM: 'green',
  FILE: 'orange',
};

const IngestionReportPage: React.FC = () => {
  const [unitId, setUnitId] = useState<string>();
  const [systemId, setSystemId] = useState<string>();
  const [dataSourceId, setDataSourceId] = useState<string>();
  const [taskType, setTaskType] = useState<IngestionTaskType>();
  const [dateRange, setDateRange] = useState<[Dayjs, Dayjs]>(defaultRange);
  const [applied, setApplied] = useState<ReportFilters>({
    dateRange: [defaultRange[0].format('YYYY-MM-DD'), defaultRange[1].format('YYYY-MM-DD')],
  });

  const systemOptions = useMemo(
    () => SYSTEMS.map((item) => ({ value: item.id, label: item.name })),
    [],
  );

  const dataSourceOptions = useMemo(() => {
    const list = systemId
      ? DATA_SOURCES.filter((item) => item.systemId === systemId)
      : DATA_SOURCES;
    return list.map((item) => ({ value: item.id, label: item.name }));
  }, [systemId]);

  const filteredRows = useMemo(() => filterRows(REPORT_ROWS, applied), [applied]);
  const summary = useMemo(() => buildSummary(filteredRows), [filteredRows]);
  const dailyTrend = useMemo(() => buildDailyTrend(filteredRows), [filteredRows]);
  const breakdown = useMemo(() => buildTaskTypeBreakdown(filteredRows), [filteredRows]);
  const maxDailyRecords = useMemo(
    () => Math.max(1, ...dailyTrend.map((item) => item.recordCount)),
    [dailyTrend],
  );
  const maxBreakdownBytes = useMemo(
    () => Math.max(1, ...breakdown.map((item) => item.byteCount)),
    [breakdown],
  );

  const applyFilters = () => {
    if (!dateRange?.[0] || !dateRange?.[1]) {
      message.warning('请选择日期范围');
      return;
    }
    setApplied({
      unitId,
      systemId,
      dataSourceId,
      taskType,
      dateRange: [dateRange[0].format('YYYY-MM-DD'), dateRange[1].format('YYYY-MM-DD')],
    });
  };

  const resetFilters = () => {
    setUnitId(undefined);
    setSystemId(undefined);
    setDataSourceId(undefined);
    setTaskType(undefined);
    setDateRange(defaultRange);
    setApplied({
      dateRange: [defaultRange[0].format('YYYY-MM-DD'), defaultRange[1].format('YYYY-MM-DD')],
    });
  };

  const handleExport = () => {
    if (filteredRows.length === 0) {
      message.warning('当前筛选条件下没有可导出的数据');
      return;
    }
    const [start, end] = applied.dateRange;
    const filename = `数据引接报告_${start.replace(/-/g, '')}-${end.replace(/-/g, '')}.csv`;
    downloadCsv(filename, rowsToCsv(filteredRows));
    message.success(`已导出 ${filteredRows.length} 条明细`);
  };

  const columns: ColumnsType<IngestionReportRow> = [
    { title: '日期', dataIndex: 'date', width: 100 },
    { title: '单位', dataIndex: 'unitName', width: 100 },
    { title: '系统', dataIndex: 'systemName', width: 160 },
    { title: '数据源', dataIndex: 'dataSourceName', width: 160 },
    {
      title: '引接任务类型',
      dataIndex: 'taskType',
      width: 110,
      render: (value: IngestionTaskType) => (
        <Tag color={taskTypeColor[value]}>{TASK_TYPE_LABEL[value]}</Tag>
      ),
    },
    { title: '任务数', dataIndex: 'taskCount', width: 72, align: 'right' },
    {
      title: '引接记录数',
      dataIndex: 'recordCount',
      width: 100,
      align: 'right',
      render: (value: number) => formatCount(value),
    },
    {
      title: '引接数据量',
      dataIndex: 'byteCount',
      width: 100,
      align: 'right',
      render: (value: number) => formatBytes(value),
    },
    {
      title: '成功率',
      width: 80,
      align: 'right',
      render: (_, row) =>
        formatPercent(row.taskCount === 0 ? 0 : row.successCount / row.taskCount),
    },
  ];

  return (
    <PageContainer className="ingestion-report-page" title={false}>
      <div className="ingestion-report-body">
        <Card className="ingestion-report-filters" size="small">
          <Space wrap size={[8, 8]} className="ingestion-report-filters-row">
            <Select
              allowClear
              placeholder="单位"
              style={{ width: 140 }}
              value={unitId}
              options={UNITS.map((item) => ({ value: item.id, label: item.name }))}
              onChange={(value) => {
                setUnitId(value);
                setSystemId(undefined);
                setDataSourceId(undefined);
              }}
            />
            <Select
              allowClear
              placeholder="系统"
              style={{ width: 200 }}
              value={systemId}
              options={systemOptions}
              onChange={(value) => {
                setSystemId(value);
                setDataSourceId(undefined);
              }}
            />
            <Select
              allowClear
              placeholder="数据源"
              style={{ width: 180 }}
              value={dataSourceId}
              options={dataSourceOptions}
              onChange={setDataSourceId}
            />
            <RangePicker
              value={dateRange}
              allowClear={false}
              onChange={(value) => {
                if (value?.[0] && value?.[1]) {
                  setDateRange([value[0], value[1]]);
                }
              }}
            />
            <Select
              allowClear
              placeholder="引接任务类型"
              style={{ width: 140 }}
              value={taskType}
              options={taskTypeOptions}
              onChange={setTaskType}
            />
            <Button type="primary" icon={<SearchOutlined />} onClick={applyFilters}>
              查询
            </Button>
            <Button icon={<ReloadOutlined />} onClick={resetFilters}>
              重置
            </Button>
          </Space>
        </Card>

        <Row gutter={[8, 8]} className="ingestion-report-kpi">
          <Col xs={12} sm={8} lg={4}>
            <Card size="small">
              <Statistic title="引接数据量" value={formatBytes(summary.totalBytes)} />
            </Card>
          </Col>
          <Col xs={12} sm={8} lg={4}>
            <Card size="small">
              <Statistic title="引接记录数" value={formatCount(summary.totalRecords)} />
            </Card>
          </Col>
          <Col xs={12} sm={8} lg={4}>
            <Card size="small">
              <Statistic title="引接任务数" value={summary.totalTasks} />
            </Card>
          </Col>
          <Col xs={12} sm={8} lg={4}>
            <Card size="small" className="ingestion-report-kpi-success">
              <Statistic title="任务成功率" value={formatPercent(summary.successRate)} />
            </Card>
          </Col>
          <Col xs={12} sm={8} lg={4}>
            <Card size="small">
              <Statistic title="活跃数据源" value={summary.activeDataSources} suffix="个" />
            </Card>
          </Col>
          <Col xs={12} sm={8} lg={4}>
            <Card size="small">
              <Statistic title="覆盖单位" value={summary.coveredUnits} suffix="个" />
            </Card>
          </Col>
        </Row>

        <Row gutter={[8, 8]} className="ingestion-report-charts">
          <Col xs={24} lg={14}>
            <Card title="按日引接记录数趋势" size="small">
              {dailyTrend.length === 0 ? (
                <div className="ingestion-report-empty">暂无数据</div>
              ) : (
                <div className="ingestion-report-trend">
                  {dailyTrend.map((item) => (
                    <div key={item.date} className="ingestion-report-trend-row">
                      <span className="ingestion-report-trend-date">{item.date.slice(5)}</span>
                      <div className="ingestion-report-trend-track">
                        <div
                          className="ingestion-report-trend-fill"
                          style={{ width: `${(item.recordCount / maxDailyRecords) * 100}%` }}
                        />
                      </div>
                      <span className="ingestion-report-trend-value">
                        {formatCount(item.recordCount)}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </Card>
          </Col>
          <Col xs={24} lg={10}>
            <Card title="按任务类型构成（数据量）" size="small">
              {breakdown.length === 0 ? (
                <div className="ingestion-report-empty">暂无数据</div>
              ) : (
                <div className="ingestion-report-breakdown">
                  {breakdown.map((item) => (
                    <div key={item.taskType} className="ingestion-report-breakdown-item">
                      <div className="ingestion-report-breakdown-head">
                        <span>
                          <Tag color={taskTypeColor[item.taskType]}>{item.label}</Tag>
                          {formatBytes(item.byteCount)}
                        </span>
                        <span className="ingestion-report-breakdown-meta">
                          任务 {item.taskCount} · 成功率 {formatPercent(item.successRate)}
                        </span>
                      </div>
                      <div className="ingestion-report-breakdown-track">
                        <div
                          className={`ingestion-report-breakdown-fill ingestion-report-breakdown-fill--${item.taskType}`}
                          style={{ width: `${(item.byteCount / maxBreakdownBytes) * 100}%` }}
                        />
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </Card>
          </Col>
        </Row>

        <Card
          className="ingestion-report-table-card"
          title="引接明细"
          size="small"
          extra={
            <Space size={12}>
              <span className="ingestion-report-table-extra">共 {filteredRows.length} 条</span>
              <Button type="primary" icon={<DownloadOutlined />} onClick={handleExport}>
                导出 CSV
              </Button>
            </Space>
          }
        >
          <Table
            rowKey="id"
            size="small"
            columns={columns}
            dataSource={filteredRows}
            pagination={{
              pageSize: 5,
              size: 'small',
              showTotal: (total) => `共 ${total} 条`,
            }}
            scroll={{ x: 980, y: 200 }}
          />
        </Card>
      </div>
    </PageContainer>
  );
};

export default IngestionReportPage;
