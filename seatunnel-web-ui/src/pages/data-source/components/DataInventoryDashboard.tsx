import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Card, Col, Progress, Row, Spin, Statistic, Tag, message } from 'antd';
import React, { useCallback, useEffect, useState } from 'react';
import {
  downloadDataExplorationExport,
  fetchDataInventoryBusinessSystems,
  fetchDataInventoryProfileCoverage,
  fetchDataInventorySourceTypes,
  fetchDataInventorySummary,
  fetchDataInventoryUnits,
} from '../service';
import type {
  DataInventoryDistributionItem,
  DataInventoryProfileCoverage,
  DataInventorySummary,
} from '../types';

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

const DataInventoryDashboard: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [summary, setSummary] = useState<DataInventorySummary>(EMPTY_SUMMARY);
  const [coverage, setCoverage] = useState<DataInventoryProfileCoverage>(EMPTY_COVERAGE);
  const [sourceTypes, setSourceTypes] = useState<DataInventoryDistributionItem[]>([]);
  const [units, setUnits] = useState<DataInventoryDistributionItem[]>([]);
  const [systems, setSystems] = useState<DataInventoryDistributionItem[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [summaryResponse, sourceResponse, unitResponse, systemResponse, coverageResponse] = await Promise.all([
        fetchDataInventorySummary(),
        fetchDataInventorySourceTypes(),
        fetchDataInventoryUnits(),
        fetchDataInventoryBusinessSystems(),
        fetchDataInventoryProfileCoverage(),
      ]);
      if (summaryResponse.code === 0 && summaryResponse.data) setSummary(summaryResponse.data);
      if (sourceResponse.code === 0) setSourceTypes(sourceResponse.data || []);
      if (unitResponse.code === 0) setUnits(unitResponse.data || []);
      if (systemResponse.code === 0) setSystems(systemResponse.data || []);
      if (coverageResponse.code === 0 && coverageResponse.data) setCoverage(coverageResponse.data);
    } catch (_) {
      message.warning('数据清查暂不可用，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const exportWorkbook = async () => {
    try {
      const result: any = await downloadDataExplorationExport();
      const blob = result instanceof Blob ? result : result?.data instanceof Blob ? result.data : result?.response?.data;
      if (!(blob instanceof Blob)) {
        message.error('导出响应不可用');
        return;
      }
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = 'data-exploration.xlsx';
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (_) {
      message.error('数据清查导出失败');
    }
  };

  const bucketList = (items: DataInventoryDistributionItem[]) => (
    <div className="space-y-2">
      {items.slice(0, 6).map((item) => (
        <div key={item.key} className="flex items-center justify-between text-sm">
          <span className="truncate text-[var(--st-color-text-muted)]">{item.name || item.key}</span>
          <Tag color="cyan">{item.count}</Tag>
        </div>
      ))}
      {items.length === 0 && <span className="text-sm text-[var(--st-color-text-muted)]">暂无数据</span>}
    </div>
  );

  return (
    <Card
      className="mb-6"
      title="数据清查"
      extra={
        <div className="flex gap-2">
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>刷新</Button>
          <Button icon={<DownloadOutlined />} onClick={exportWorkbook}>导出 XLSX</Button>
        </div>
      }
    >
      <Spin spinning={loading}>
        <Row gutter={[12, 12]}>
          <Col xs={24} xl={8}>
            <Card size="small" title="数据底数">
              <Row gutter={[8, 16]}>
                <Col span={8}><Statistic title="数据源" value={summary.dataSourceCount} /></Col>
                <Col span={8}><Statistic title="Database" value={summary.databaseCount} /></Col>
                <Col span={8}><Statistic title="Schema" value={summary.schemaCount} /></Col>
                <Col span={8}><Statistic title="Table" value={summary.tableCount} /></Col>
                <Col span={8}><Statistic title="Column" value={summary.columnCount} /></Col>
                <Col span={8}><Statistic title="单位" value={summary.unitCount} /></Col>
              </Row>
            </Card>
          </Col>
          <Col xs={24} md={12} xl={5}>
            <Card size="small" title="数据源类型分布">{bucketList(sourceTypes)}</Card>
          </Col>
          <Col xs={24} md={12} xl={5}>
            <Card size="small" title="单位 / 业务系统">
              <div className="mb-2 text-xs text-[var(--st-color-text-muted)]">单位</div>
              {bucketList(units)}
              <div className="mb-2 mt-3 text-xs text-[var(--st-color-text-muted)]">业务系统</div>
              {bucketList(systems)}
            </Card>
          </Col>
          <Col xs={24} xl={6}>
            <Card size="small" title="探查覆盖情况">
              <Progress type="circle" percent={Number(coverage.tableCoveragePercent.toFixed(1))} />
              <div className="mt-2 text-sm text-[var(--st-color-text-muted)]">
                已探查表 {coverage.profiledTableCount} / {coverage.tableCount}
              </div>
              <div className="text-sm text-[var(--st-color-text-muted)]">
                已探查数据量：{coverage.knownRowCount}
              </div>
            </Card>
          </Col>
        </Row>
      </Spin>
    </Card>
  );
};

export default DataInventoryDashboard;
