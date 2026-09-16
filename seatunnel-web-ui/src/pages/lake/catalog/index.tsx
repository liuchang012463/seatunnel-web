import {
  ApartmentOutlined,
  DatabaseOutlined,
  InfoCircleOutlined,
  ReloadOutlined,
  TableOutlined,
} from '@ant-design/icons';
import { Button, Empty, Input, Spin, Table, Tabs, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { history } from '@umijs/max';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { fetchLakeWarehouse, lakeCatalogApi } from '@/services/lake';
import type { LakeWarehouseConfig } from '@/services/lake';
import './index.less';

const { Paragraph, Text, Title } = Typography;

interface CatalogOption {
  value?: string | number;
  label?: string;
  description?: string;
}

interface CatalogColumn {
  key?: string | number;
  fieldName?: string;
  fieldType?: string;
  isNullable?: string;
  fieldComment?: string;
  fieldKey?: string;
}

interface PreviewColumn {
  title?: string;
  dataIndex?: string;
  key?: string;
  ellipsis?: boolean;
}

interface PreviewResult {
  columns?: PreviewColumn[];
  data?: Array<Record<string, unknown>>;
  total?: number;
}

const optionValue = (option?: CatalogOption) => String(option?.value ?? '');
const optionLabel = (option?: CatalogOption) => String(option?.label || option?.value || '');

const DataLakeCatalogPage: React.FC = () => {
  const [warehouse, setWarehouse] = useState<LakeWarehouseConfig>();
  const [warehouseLoading, setWarehouseLoading] = useState(true);
  const [catalogLoading, setCatalogLoading] = useState(false);
  const [tableLoading, setTableLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [catalogError, setCatalogError] = useState('');
  const [databases, setDatabases] = useState<CatalogOption[]>([]);
  const [tables, setTables] = useState<CatalogOption[]>([]);
  const [columns, setColumns] = useState<CatalogColumn[]>([]);
  const [preview, setPreview] = useState<PreviewResult>({});
  const [selectedDatabase, setSelectedDatabase] = useState('');
  const [selectedTable, setSelectedTable] = useState('');
  const [databaseSearch, setDatabaseSearch] = useState('');
  const [tableSearch, setTableSearch] = useState('');
  const [activeTab, setActiveTab] = useState('columns');

  const selectedTableOption = tables.find((item) => optionValue(item) === selectedTable);
  const configured = Boolean(warehouse?.configured);
  const catalogReady = warehouse?.catalogReady !== false;

  const loadDatabases = useCallback(async () => {
    if (!configured || !catalogReady) {
      setDatabases([]);
      setSelectedDatabase('');
      return;
    }

    setCatalogLoading(true);
    try {
      const response = await lakeCatalogApi.listDatabases();
      if (response.code !== 0) throw new Error(response.message || '数据库列表读取失败');
      const nextDatabases = Array.isArray(response.data) ? response.data : [];
      setCatalogError('');
      setDatabases(nextDatabases);
      setSelectedDatabase((current) =>
        nextDatabases.some((item) => optionValue(item) === current)
          ? current
          : optionValue(nextDatabases[0]),
      );
    } catch (error) {
      setDatabases([]);
      setSelectedDatabase('');
      setCatalogError(error instanceof Error ? error.message : '数据库列表读取失败');
    } finally {
      setCatalogLoading(false);
    }
  }, [catalogReady, configured]);

  const loadTables = useCallback(async () => {
    if (!configured || !catalogReady || !selectedDatabase) {
      setTables([]);
      setSelectedTable('');
      return;
    }

    setTableLoading(true);
    try {
      const response = await lakeCatalogApi.listTablesByDatabase(selectedDatabase);
      if (response.code !== 0) throw new Error(response.message || '表列表读取失败');
      const nextTables = Array.isArray(response.data) ? response.data : [];
      setCatalogError('');
      setTables(nextTables);
      setSelectedTable((current) =>
        nextTables.some((item) => optionValue(item) === current)
          ? current
          : optionValue(nextTables[0]),
      );
    } catch (error) {
      setTables([]);
      setSelectedTable('');
      setCatalogError(error instanceof Error ? error.message : '表列表读取失败');
    } finally {
      setTableLoading(false);
    }
  }, [catalogReady, configured, selectedDatabase]);

  const loadDetail = useCallback(async () => {
    if (!configured || !catalogReady || !selectedDatabase || !selectedTable) {
      setColumns([]);
      setPreview({});
      return;
    }

    setDetailLoading(true);
    try {
      const request = {
        read_mode: 'table',
        table_path: selectedTable,
        database: selectedDatabase,
      };
      const [columnResponse, previewResponse] = await Promise.all([
        lakeCatalogApi.listColumn(request),
        lakeCatalogApi.getTop20Data(request),
      ]);
      if (columnResponse.code !== 0) throw new Error(columnResponse.message || '字段读取失败');
      if (previewResponse.code !== 0) throw new Error(previewResponse.message || '样本数据读取失败');
      setColumns(Array.isArray(columnResponse.data) ? columnResponse.data : []);
      setPreview((previewResponse.data || {}) as PreviewResult);
    } catch (error) {
      setColumns([]);
      setPreview({});
      setCatalogError(error instanceof Error ? error.message : '表详情读取失败');
    } finally {
      setDetailLoading(false);
    }
  }, [catalogReady, configured, selectedDatabase, selectedTable]);

  useEffect(() => {
    let active = true;
    const loadWarehouse = async () => {
      setWarehouseLoading(true);
      try {
        const response = await fetchLakeWarehouse();
        if (!active) return;
        if (response.code !== 0) throw new Error(response.message || '读取数据湖配置失败');
        setCatalogError('');
        setWarehouse(response.data || undefined);
      } catch (error) {
        if (active) setCatalogError(error instanceof Error ? error.message : '读取数据湖配置失败');
      } finally {
        if (active) setWarehouseLoading(false);
      }
    };
    void loadWarehouse();
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    void loadDatabases();
  }, [loadDatabases]);

  useEffect(() => {
    void loadTables();
  }, [loadTables]);

  useEffect(() => {
    void loadDetail();
  }, [loadDetail]);

  const visibleDatabases = useMemo(() => {
    const keyword = databaseSearch.trim().toLowerCase();
    if (!keyword) return databases;
    return databases.filter((item) => optionLabel(item).toLowerCase().includes(keyword));
  }, [databaseSearch, databases]);

  const visibleTables = useMemo(() => {
    const keyword = tableSearch.trim().toLowerCase();
    if (!keyword) return tables;
    return tables.filter((item) => {
      const name = `${optionValue(item)} ${optionLabel(item)}`.toLowerCase();
      return name.includes(keyword);
    });
  }, [tableSearch, tables]);

  const previewColumns = useMemo<ColumnsType<Record<string, unknown>>>(() => {
    const resultColumns = Array.isArray(preview.columns) ? preview.columns : [];
    if (resultColumns.length > 0) {
      return resultColumns.map((column) => ({
        title: column.title || column.dataIndex || column.key,
        dataIndex: column.dataIndex || column.key,
        key: column.key || column.dataIndex,
        ellipsis: true,
      }));
    }
    const firstRow = preview.data?.[0];
    return Object.keys(firstRow || {}).map((key) => ({ title: key, dataIndex: key, key, ellipsis: true }));
  }, [preview]);

  const previewRows = useMemo(
    () =>
      (preview.data || []).map((row, index) => ({
        ...row,
        __lakeCatalogRowKey: `${selectedDatabase}.${selectedTable}.${index}`,
      })),
    [preview.data, selectedDatabase, selectedTable],
  );

  const columnTableColumns: ColumnsType<CatalogColumn> = [
    { title: '字段名', dataIndex: 'fieldName', key: 'fieldName', width: 180, ellipsis: true },
    { title: '类型', dataIndex: 'fieldType', key: 'fieldType', width: 140, ellipsis: true },
    { title: '可空', dataIndex: 'isNullable', key: 'isNullable', width: 90 },
    { title: '键', dataIndex: 'fieldKey', key: 'fieldKey', width: 80 },
    { title: '说明', dataIndex: 'fieldComment', key: 'fieldComment', ellipsis: true },
  ];

  const refresh = async () => {
    await loadDatabases();
    if (selectedDatabase) await loadTables();
    if (selectedTable) await loadDetail();
  };

  return (
    <div className="lake-catalog-page">
      <header className="lake-catalog-header">
        <div className="lake-catalog-heading">
          <div className="lake-catalog-heading__mark" aria-hidden="true">
            <DatabaseOutlined />
          </div>
          <div>
            <span className="lake-catalog-kicker">数据湖 / 实时目录</span>
            <Title level={1}>数据湖管理</Title>
            <Paragraph>直接读取 Doris 当前可见的数据库、表与样本数据。Doris 没有独立 Schema，数据库即目录层级。</Paragraph>
          </div>
        </div>
        <div className="lake-catalog-actions">
          {configured ? <Tag color="cyan">{warehouse?.name || 'Doris 数据湖'}</Tag> : null}
          <Button icon={<ReloadOutlined />} loading={warehouseLoading || catalogLoading} onClick={() => void refresh()}>
            刷新目录
          </Button>
        </div>
      </header>

      {warehouseLoading ? (
        <div className="lake-catalog-state">
          <Spin size="small" />
          <span>正在读取数据湖配置</span>
        </div>
      ) : !configured ? (
        <div className="lake-catalog-state lake-catalog-state--empty">
          <Empty description="尚未配置 Doris 数据湖连接">
            <Button type="primary" onClick={() => history.push('/lake/warehouse/config')}>前往连接配置</Button>
          </Empty>
        </div>
      ) : !catalogReady ? (
        <div className="lake-catalog-state lake-catalog-state--empty">
          <Empty description={warehouse?.lastError || 'Doris 已连接，但数据目录尚未就绪'}>
            <Button type="primary" onClick={() => history.push('/lake/warehouse/config')}>重新保存连接配置</Button>
          </Empty>
        </div>
      ) : (
        <>
          {catalogError ? (
            <div className="lake-catalog-error" role="alert">
              <InfoCircleOutlined />
              <span>{catalogError}</span>
            </div>
          ) : null}
          <section className="lake-catalog-workspace" aria-label="Doris 数据目录">
            <aside className="lake-catalog-databases">
            <div className="lake-catalog-pane-heading">
              <div>
                <span className="lake-catalog-pane-kicker">目录层</span>
                <h2>数据库</h2>
              </div>
              <span>{databases.length}</span>
            </div>
            <Input.Search
              allowClear
              value={databaseSearch}
              placeholder="搜索数据库"
              onChange={(event) => setDatabaseSearch(event.target.value)}
              className="lake-catalog-search"
            />
            <div className="lake-catalog-list" role="listbox" aria-label="数据库列表">
              {catalogLoading && databases.length === 0 ? <Spin size="small" /> : null}
              {!catalogLoading && visibleDatabases.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据库" /> : null}
              {visibleDatabases.map((database) => {
                const value = optionValue(database);
                const active = value === selectedDatabase;
                return (
                  <button
                    type="button"
                    role="option"
                    aria-selected={active}
                    className={`lake-catalog-database${active ? ' is-active' : ''}`}
                    key={value}
                    onClick={() => {
                      setSelectedDatabase(value);
                      setSelectedTable('');
                      setTableSearch('');
                    }}
                  >
                    <span className="lake-catalog-database__icon"><ApartmentOutlined /></span>
                    <span className="lake-catalog-database__copy">
                      <strong>{optionLabel(database)}</strong>
                      <small>数据库 / Schema</small>
                    </span>
                  </button>
                );
              })}
            </div>
            <div className="lake-catalog-note"><InfoCircleOutlined /> 数据库列表来自 Doris 实时元数据。</div>
          </aside>

          <main className="lake-catalog-tables">
            <div className="lake-catalog-pane-heading">
              <div>
                <span className="lake-catalog-pane-kicker">当前数据库</span>
                <h2>{selectedDatabase || '选择数据库'}</h2>
              </div>
              <span>{tables.length} 张表</span>
            </div>
            <div className="lake-catalog-table-toolbar">
              <Input.Search
                allowClear
                value={tableSearch}
                placeholder="搜索表名或备注"
                onChange={(event) => setTableSearch(event.target.value)}
              />
              <Text type="secondary">点击表名查看结构与样本</Text>
            </div>
            <div className="lake-catalog-list lake-catalog-list--tables" role="listbox" aria-label="表列表">
              {tableLoading ? <Spin size="small" /> : null}
              {!tableLoading && visibleTables.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前数据库暂无表" /> : null}
              {visibleTables.map((table) => {
                const value = optionValue(table);
                const active = value === selectedTable;
                return (
                  <button
                    type="button"
                    role="option"
                    aria-selected={active}
                    className={`lake-catalog-table${active ? ' is-active' : ''}`}
                    key={value}
                    onClick={() => setSelectedTable(value)}
                  >
                    <span className="lake-catalog-table__icon"><TableOutlined /></span>
                    <span className="lake-catalog-table__copy">
                      <strong>{value}</strong>
                      {table.description ? <small>{table.description}</small> : <small>数据表</small>}
                    </span>
                    <span className="lake-catalog-table__arrow">›</span>
                  </button>
                );
              })}
            </div>
          </main>

          <section className="lake-catalog-inspector" aria-label="表详情">
            <div className="lake-catalog-inspector__heading">
              <div>
                <span className="lake-catalog-pane-kicker">表检视器</span>
                <h2>{selectedTable || '选择一张表'}</h2>
                {selectedTable ? <p>{selectedDatabase}.{selectedTable}</p> : null}
              </div>
              {selectedTable ? <Tag color="blue">Doris</Tag> : null}
            </div>
            {selectedTable ? (
              <Tabs
                activeKey={activeTab}
                onChange={setActiveTab}
                items={[
                  {
                    key: 'columns',
                    label: `字段结构${columns.length ? ` · ${columns.length}` : ''}`,
                    children: (
                      <Spin spinning={detailLoading}>
                        <Table<CatalogColumn>
                          rowKey={(record) => String(record.key || record.fieldName)}
                          columns={columnTableColumns}
                          dataSource={columns}
                          pagination={false}
                          size="small"
                          scroll={{ y: 'calc(100vh - 360px)' }}
                          locale={{ emptyText: '暂无字段信息' }}
                        />
                      </Spin>
                    ),
                  },
                  {
                    key: 'preview',
                    label: `样本数据${preview.data?.length ? ` · ${preview.data.length}` : ''}`,
                    children: (
                      <Spin spinning={detailLoading}>
                        <div className="lake-catalog-preview-meta">
                          <InfoCircleOutlined />
                          <span>仅展示前 20 行，实际总行数 {Number(preview.total || 0).toLocaleString('zh-CN')}。</span>
                        </div>
                        <Table<Record<string, unknown>>
                          rowKey={(record) => String(record.__lakeCatalogRowKey)}
                          columns={previewColumns}
                          dataSource={previewRows}
                          pagination={false}
                          size="small"
                          scroll={{ x: Math.max(620, previewColumns.length * 150), y: 'calc(100vh - 410px)' }}
                          locale={{ emptyText: '暂无样本数据' }}
                        />
                      </Spin>
                    ),
                  },
                ]}
              />
            ) : (
              <div className="lake-catalog-inspector__empty">
                <TableOutlined />
                <span>从中间列表选择表，查看字段和前 20 行样本。</span>
              </div>
            )}
            {selectedTableOption?.description ? <div className="lake-catalog-inspector__description">{selectedTableOption.description}</div> : null}
            </section>
          </section>
        </>
      )}
    </div>
  );
};

export default DataLakeCatalogPage;
