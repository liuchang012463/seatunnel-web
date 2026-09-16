import {
  ApartmentOutlined,
  DatabaseOutlined,
  InfoCircleOutlined,
  LoadingOutlined,
  ReloadOutlined,
  TableOutlined,
} from '@ant-design/icons';
import { Button, Empty, Input, Spin, Table, Tabs, Tag, Tree, Typography } from 'antd';
import type { TreeDataNode } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { history } from '@umijs/max';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { fetchLakeWarehouse, lakeCatalogApi } from '@/services/lake';
import type { LakeWarehouseConfig } from '@/services/lake';
import './index.less';

const { Paragraph, Title } = Typography;

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

type TableLoadState = {
  status: 'loading' | 'loaded' | 'error';
  items: CatalogOption[];
};

type CatalogTreeSelection =
  | { type: 'database'; database: string }
  | { type: 'table'; database: string; table: string };

const optionValue = (option?: CatalogOption) => String(option?.value ?? '');
const optionLabel = (option?: CatalogOption) => String(option?.label || option?.value || '');

const databaseNodeKey = (database: string) => `database:${encodeURIComponent(database)}`;
const tableNodeKey = (database: string, table: string) =>
  `table:${encodeURIComponent(database)}:${encodeURIComponent(table)}`;

const parseTreeSelection = (key: string): CatalogTreeSelection | undefined => {
  const [type, encodedDatabase, encodedTable] = key.split(':');
  if (type === 'database' && encodedDatabase) {
    return { type, database: decodeURIComponent(encodedDatabase) };
  }
  if (type === 'table' && encodedDatabase && encodedTable) {
    return {
      type,
      database: decodeURIComponent(encodedDatabase),
      table: decodeURIComponent(encodedTable),
    };
  }
  return undefined;
};

const DataLakeCatalogPage: React.FC = () => {
  const [warehouse, setWarehouse] = useState<LakeWarehouseConfig>();
  const [warehouseLoading, setWarehouseLoading] = useState(true);
  const [catalogLoading, setCatalogLoading] = useState(false);
  const [catalogError, setCatalogError] = useState('');
  const [databases, setDatabases] = useState<CatalogOption[]>([]);
  const [tableStates, setTableStates] = useState<Record<string, TableLoadState>>({});
  const [tableLoadingByDatabase, setTableLoadingByDatabase] = useState<Record<string, boolean>>({});
  const [columns, setColumns] = useState<CatalogColumn[]>([]);
  const [preview, setPreview] = useState<PreviewResult>({});
  const [selectedDatabase, setSelectedDatabase] = useState('');
  const [selectedTable, setSelectedTable] = useState('');
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const [databaseSearch, setDatabaseSearch] = useState('');
  const [tableSearch, setTableSearch] = useState('');
  const [detailLoading, setDetailLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('columns');
  const initialDirectoryExpanded = useRef(false);

  const configured = Boolean(warehouse?.configured);
  const catalogReady = warehouse?.catalogReady !== false;
  const selectedTableState = tableStates[selectedDatabase];
  const selectedTableOption = selectedTableState?.items.find(
    (item) => optionValue(item) === selectedTable,
  );

  const loadTablesForDatabase = useCallback(
    async (database: string) => {
      if (!configured || !catalogReady || !database) {
        return;
      }

      const currentState = tableStates[database];
      if (currentState?.status === 'loading' || currentState?.status === 'loaded') {
        return;
      }

      setTableLoadingByDatabase((current) => ({ ...current, [database]: true }));
      setTableStates((current) => ({
        ...current,
        [database]: { status: 'loading', items: current[database]?.items || [] },
      }));

      try {
        const response = await lakeCatalogApi.listTablesByDatabase(database);
        if (response.code !== 0) {
          throw new Error(response.message || '表列表读取失败');
        }
        const nextTables = Array.isArray(response.data) ? response.data : [];
        setTableStates((current) => ({
          ...current,
          [database]: { status: 'loaded', items: nextTables },
        }));
        setCatalogError('');
      } catch (error) {
        setTableStates((current) => ({
          ...current,
          [database]: { status: 'error', items: [] },
        }));
        setCatalogError(error instanceof Error ? error.message : '表列表读取失败');
      } finally {
        setTableLoadingByDatabase((current) => ({ ...current, [database]: false }));
      }
    },
    [catalogReady, configured, tableStates],
  );

  const loadDatabases = useCallback(async () => {
    if (!configured || !catalogReady) {
      initialDirectoryExpanded.current = false;
      setDatabases([]);
      setTableStates({});
      setTableLoadingByDatabase({});
      setExpandedKeys([]);
      setSelectedDatabase('');
      setSelectedTable('');
      return;
    }

    setCatalogLoading(true);
    try {
      const response = await lakeCatalogApi.listDatabases();
      if (response.code !== 0) {
        throw new Error(response.message || '数据库列表读取失败');
      }
      const nextDatabases = Array.isArray(response.data) ? response.data : [];
      initialDirectoryExpanded.current = false;
      setCatalogError('');
      setDatabases(nextDatabases);
      setTableStates({});
      setTableLoadingByDatabase({});
      setExpandedKeys([]);
      setSelectedDatabase((current) =>
        nextDatabases.some((item) => optionValue(item) === current)
          ? current
          : optionValue(nextDatabases[0]),
      );
      setSelectedTable('');
    } catch (error) {
      initialDirectoryExpanded.current = false;
      setDatabases([]);
      setTableStates({});
      setTableLoadingByDatabase({});
      setExpandedKeys([]);
      setSelectedDatabase('');
      setSelectedTable('');
      setCatalogError(error instanceof Error ? error.message : '数据库列表读取失败');
    } finally {
      setCatalogLoading(false);
    }
  }, [catalogReady, configured]);

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
      if (columnResponse.code !== 0) {
        throw new Error(columnResponse.message || '字段读取失败');
      }
      if (previewResponse.code !== 0) {
        throw new Error(previewResponse.message || '样本数据读取失败');
      }
      setColumns(Array.isArray(columnResponse.data) ? columnResponse.data : []);
      setPreview((previewResponse.data || {}) as PreviewResult);
      setCatalogError('');
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
    if (!databases.length || initialDirectoryExpanded.current) {
      return;
    }

    const currentDatabase = databases.some((item) => optionValue(item) === selectedDatabase)
      ? selectedDatabase
      : optionValue(databases[0]);
    if (!currentDatabase) return;

    initialDirectoryExpanded.current = true;
    setSelectedDatabase(currentDatabase);
    setExpandedKeys([databaseNodeKey(currentDatabase)]);
    void loadTablesForDatabase(currentDatabase);
  }, [databases, loadTablesForDatabase, selectedDatabase]);

  useEffect(() => {
    if (!selectedDatabase || selectedTableState?.status !== 'loaded') {
      return;
    }
    setSelectedTable((current) =>
      current && selectedTableState.items.some((item) => optionValue(item) === current)
        ? current
        : optionValue(selectedTableState.items[0]),
    );
  }, [selectedDatabase, selectedTableState]);

  useEffect(() => {
    void loadDetail();
  }, [loadDetail]);

  const visibleDatabases = useMemo(() => {
    const keyword = databaseSearch.trim().toLowerCase();
    if (!keyword) return databases;
    return databases.filter((item) => optionLabel(item).toLowerCase().includes(keyword));
  }, [databaseSearch, databases]);

  const treeData = useMemo<TreeDataNode[]>(() => {
    const keyword = tableSearch.trim().toLowerCase();
    return visibleDatabases.map((database) => {
      const databaseName = optionValue(database);
      const state = tableStates[databaseName];
      const visibleTables = (state?.items || []).filter((table) => {
        if (!keyword) return true;
        return `${optionValue(table)} ${optionLabel(table)}`.toLowerCase().includes(keyword);
      });

      return {
        key: databaseNodeKey(databaseName),
        icon: <ApartmentOutlined />,
        title: (
          <span className="lake-catalog-tree-node">
            <span className="lake-catalog-tree-node__copy">
              <strong>{optionLabel(database)}</strong>
              <small>数据库</small>
            </span>
            {state?.status === 'loading' ? <LoadingOutlined spin /> : null}
            {state?.status === 'loaded' ? <em>{state.items.length}</em> : null}
          </span>
        ),
        isLeaf: state?.status === 'loaded' && state.items.length === 0,
        children:
          state?.status === 'loaded'
            ? visibleTables.map((table) => {
                const tableName = optionValue(table);
                return {
                  key: tableNodeKey(databaseName, tableName),
                  isLeaf: true,
                  icon: <TableOutlined />,
                  title: (
                    <span className="lake-catalog-tree-node lake-catalog-tree-node--table">
                      <span className="lake-catalog-tree-node__copy">
                        <strong>{tableName}</strong>
                        <small>{table.description || '数据表'}</small>
                      </span>
                    </span>
                  ),
                };
              })
            : undefined,
      };
    });
  }, [tableSearch, tableStates, visibleDatabases]);

  const selectedTreeKeys = selectedTable
    ? [tableNodeKey(selectedDatabase, selectedTable)]
    : selectedDatabase
      ? [databaseNodeKey(selectedDatabase)]
      : [];

  const handleExpand = (keys: React.Key[]) => {
    const nextKeys = keys.map(String);
    const previousKeys = new Set(expandedKeys);
    setExpandedKeys(nextKeys);
    nextKeys
      .filter((key) => !previousKeys.has(key))
      .map(parseTreeSelection)
      .filter((selection): selection is Extract<CatalogTreeSelection, { type: 'database' }> =>
        selection?.type === 'database',
      )
      .forEach((selection) => void loadTablesForDatabase(selection.database));
  };

  const handleSelect = (keys: React.Key[]) => {
    const key = String(keys[0] || '');
    const selection = parseTreeSelection(key);
    if (!selection) return;

    if (selection.type === 'database') {
      setSelectedDatabase(selection.database);
      setSelectedTable('');
      setTableSearch('');
      if (!expandedKeys.includes(key)) {
        setExpandedKeys((current) => [...current, key]);
      }
      void loadTablesForDatabase(selection.database);
      return;
    }

    setSelectedDatabase(selection.database);
    setSelectedTable(selection.table);
  };

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
    return Object.keys(firstRow || {}).map((key) => ({
      title: key,
      dataIndex: key,
      key,
      ellipsis: true,
    }));
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
  };

  const tableLoading = Object.values(tableLoadingByDatabase).some(Boolean);

  return (
    <div className="lake-catalog-page">
      <header className="lake-catalog-header">
        <div className="lake-catalog-heading">
          <div className="lake-catalog-heading__mark" aria-hidden="true">
            <DatabaseOutlined />
          </div>
          <div>
            <span className="lake-catalog-kicker">数据湖 / 目录浏览</span>
            <Title level={1}>数据湖目录</Title>
            <Paragraph>浏览数据湖中的数据库、数据表与样本数据。</Paragraph>
          </div>
        </div>
        <div className="lake-catalog-actions">
          {configured ? <Tag color="cyan">数据湖</Tag> : null}
          <Button
            icon={<ReloadOutlined />}
            loading={warehouseLoading || catalogLoading || tableLoading}
            onClick={() => void refresh()}
          >
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
          <Empty description="尚未配置数据湖连接">
            <Button type="primary" onClick={() => history.push('/lake/warehouse/config')}>
              前往连接配置
            </Button>
          </Empty>
        </div>
      ) : !catalogReady ? (
        <div className="lake-catalog-state lake-catalog-state--empty">
          <Empty description={warehouse?.lastError || '数据湖连接已建立，但目录尚未就绪'}>
            <Button type="primary" onClick={() => history.push('/lake/warehouse/config')}>
              重新保存连接配置
            </Button>
          </Empty>
        </div>
      ) : (
        <section className="lake-catalog-workspace" aria-label="数据湖目录">
          <aside className="lake-catalog-tree-pane" aria-label="数据库与数据表目录">
            <div className="lake-catalog-pane-heading">
              <div>
                <span className="lake-catalog-pane-kicker">目录层</span>
                <h2>数据库</h2>
              </div>
              <span>{databases.length} 个</span>
            </div>
            <Input.Search
              allowClear
              value={databaseSearch}
              placeholder="搜索数据库"
              onChange={(event) => setDatabaseSearch(event.target.value)}
              className="lake-catalog-search"
            />
            <Input.Search
              allowClear
              value={tableSearch}
              placeholder="搜索当前目录中的表"
              onChange={(event) => setTableSearch(event.target.value)}
              className="lake-catalog-search lake-catalog-search--table"
            />
            {catalogError ? (
              <div className="lake-catalog-tree-error" role="alert">
                <InfoCircleOutlined />
                <span>{catalogError}</span>
              </div>
            ) : null}
            <div className="lake-catalog-tree-wrap">
              {catalogLoading && databases.length === 0 ? (
                <div className="lake-catalog-tree-state">
                  <Spin size="small" />
                  <span>正在读取目录</span>
                </div>
              ) : null}
              {!catalogLoading && treeData.length === 0 ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无业务数据库" />
              ) : null}
              {treeData.length > 0 ? (
                <Tree
                  blockNode
                  showIcon
                  showLine={{ showLeafIcon: false }}
                  className="lake-catalog-tree"
                  expandedKeys={expandedKeys}
                  selectedKeys={selectedTreeKeys}
                  treeData={treeData}
                  onExpand={handleExpand}
                  onSelect={handleSelect}
                />
              ) : null}
            </div>
            <div className="lake-catalog-note">
              <InfoCircleOutlined /> 数据库和数据表来自数据湖实时目录。
            </div>
          </aside>

          <section className="lake-catalog-inspector" aria-label="数据表详情">
            <div className="lake-catalog-inspector__heading">
              <div>
                <span className="lake-catalog-pane-kicker">表检视器</span>
                <h2>{selectedTable || '选择一张表'}</h2>
                {selectedTable ? <p>{selectedDatabase}.{selectedTable}</p> : null}
              </div>
              {selectedTable ? <Tag color="blue">数据湖</Tag> : null}
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
                          <span>
                            仅展示前 20 行，实际总行数{' '}
                            {Number(preview.total || 0).toLocaleString('zh-CN')}。
                          </span>
                        </div>
                        <Table<Record<string, unknown>>
                          rowKey={(record) => String(record.__lakeCatalogRowKey)}
                          columns={previewColumns}
                          dataSource={previewRows}
                          pagination={false}
                          size="small"
                          scroll={{
                            x: Math.max(620, previewColumns.length * 150),
                            y: 'calc(100vh - 410px)',
                          }}
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
                <span>从左侧目录选择表，查看字段和前 20 行样本。</span>
              </div>
            )}
            {selectedTableOption?.description ? (
              <div className="lake-catalog-inspector__description">
                {selectedTableOption.description}
              </div>
            ) : null}
          </section>
        </section>
      )}
    </div>
  );
};

export default DataLakeCatalogPage;
