import {
  ApartmentOutlined,
  DatabaseOutlined,
  InfoCircleOutlined,
  LinkOutlined,
  LoadingOutlined,
  ReloadOutlined,
  TableOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { Button, Empty, Input, Spin, Table, Tabs, Tree, Typography } from 'antd';
import type { TreeDataNode } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { history } from '@umijs/max';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  fetchCatalogQueryColumns,
  fetchCatalogQueryDatabases,
  fetchCatalogQueryTables,
  fetchCatalogs,
  fetchLakeWarehouse,
  lakeCatalogApi,
  normalizeLakePage,
  queryCatalogSingle,
} from '@/services/lake';
import type { LakeCatalog, LakeQueryColumnOption, LakeWarehouseConfig } from '@/services/lake';
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

type CatalogSource = 'physical' | 'logical';

interface LogicalDatabaseState {
  status: 'loading' | 'loaded' | 'error';
  items: string[];
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
  | { type: 'source'; source: CatalogSource }
  | { type: 'logicalCatalog'; catalogId: number }
  | { type: 'database'; source: 'physical'; database: string }
  | { type: 'database'; source: 'logical'; catalogId: number; database: string }
  | { type: 'table'; source: 'physical'; database: string; table: string }
  | { type: 'table'; source: 'logical'; catalogId: number; database: string; table: string };

const optionValue = (option?: CatalogOption) => String(option?.value ?? '');
const optionLabel = (option?: CatalogOption) => String(option?.label || option?.value || '');

const FIELD_KEY_LABELS: Record<string, string> = {
  PRI: '主键',
  PRIMARY: '主键',
  UNI: '唯一键',
  UNIQUE: '唯一键',
  MUL: '普通索引',
  INDEX: '普通索引',
  FULLTEXT: '全文索引',
  SPATIAL: '空间索引',
  CLUSTERED: '聚簇键',
  DUPLICATE: '重复键',
};

const fieldKeyLabel = (value?: unknown) => {
  const normalized = String(value ?? '').trim().toUpperCase();
  return normalized ? FIELD_KEY_LABELS[normalized] || normalized : '-';
};

const sourceNodeKey = (source: CatalogSource) => `source:${source}`;
const logicalCatalogNodeKey = (catalogId: number) => `logical-catalog:${catalogId}`;
const physicalDatabaseNodeKey = (database: string) =>
  `physical-database:${encodeURIComponent(database)}`;
const logicalDatabaseNodeKey = (catalogId: number, database: string) =>
  `logical-database:${catalogId}:${encodeURIComponent(database)}`;
const physicalTableNodeKey = (database: string, table: string) =>
  `physical-table:${encodeURIComponent(database)}:${encodeURIComponent(table)}`;
const logicalTableNodeKey = (catalogId: number, database: string, table: string) =>
  `logical-table:${catalogId}:${encodeURIComponent(database)}:${encodeURIComponent(table)}`;
const tableStateKey = (source: CatalogSource, database: string, catalogId?: number) =>
  source === 'physical' ? `physical:${database}` : `logical:${catalogId}:${database}`;

const parseTreeSelection = (key: string): CatalogTreeSelection | undefined => {
  const [type, first, second, third] = key.split(':');
  if (type === 'source' && (first === 'physical' || first === 'logical')) {
    return { type, source: first };
  }
  if (type === 'logical-catalog' && Number.isInteger(Number(first))) {
    return { type: 'logicalCatalog', catalogId: Number(first) };
  }
  if (type === 'physical-database' && first) {
    return { type: 'database', source: 'physical', database: decodeURIComponent(first) };
  }
  if (type === 'logical-database' && first && second && Number.isInteger(Number(first))) {
    return {
      type: 'database',
      source: 'logical',
      catalogId: Number(first),
      database: decodeURIComponent(second),
    };
  }
  if (type === 'physical-table' && first && second) {
    return {
      type: 'table',
      source: 'physical',
      database: decodeURIComponent(first),
      table: decodeURIComponent(second),
    };
  }
  if (type === 'logical-table' && first && second && third && Number.isInteger(Number(first))) {
    return {
      type: 'table',
      source: 'logical',
      catalogId: Number(first),
      database: decodeURIComponent(second),
      table: decodeURIComponent(third),
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
  const [logicalCatalogs, setLogicalCatalogs] = useState<LakeCatalog[]>([]);
  const [logicalDatabaseStates, setLogicalDatabaseStates] = useState<Record<string, LogicalDatabaseState>>({});
  const [tableStates, setTableStates] = useState<Record<string, TableLoadState>>({});
  const [tableLoadingByDatabase, setTableLoadingByDatabase] = useState<Record<string, boolean>>({});
  const [columns, setColumns] = useState<CatalogColumn[]>([]);
  const [preview, setPreview] = useState<PreviewResult>({});
  const [selectedSource, setSelectedSource] = useState<CatalogSource>('physical');
  const [selectedCatalogId, setSelectedCatalogId] = useState<number>();
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
  const selectedTableStateKey = selectedDatabase
    ? tableStateKey(selectedSource, selectedDatabase, selectedCatalogId)
    : '';
  const selectedTableState = selectedTableStateKey ? tableStates[selectedTableStateKey] : undefined;
  const selectedTableOption = selectedTableState?.items.find(
    (item) => optionValue(item) === selectedTable,
  );
  const selectedLogicalCatalog = logicalCatalogs.find((catalog) => catalog.id === selectedCatalogId);
  const selectedTablePath = selectedSource === 'logical'
    ? [selectedLogicalCatalog?.targetCatalogName, selectedDatabase, selectedTable].filter(Boolean).join('.')
    : [selectedDatabase, selectedTable].filter(Boolean).join('.');
  const selectedTableComment = selectedTableOption?.description?.trim();
  const logicalDatabaseCount = Object.values(logicalDatabaseStates).reduce(
    (total, state) => total + (state.status === 'loaded' ? state.items.length : 0),
    0,
  );

  const loadTablesForDatabase = useCallback(
    async (source: CatalogSource, database: string, catalogId?: number) => {
      if (!configured || !catalogReady || !database || (source === 'logical' && !catalogId)) {
        return;
      }

      const stateKey = tableStateKey(source, database, catalogId);
      const currentState = tableStates[stateKey];
      if (currentState?.status === 'loading' || currentState?.status === 'loaded') {
        return;
      }

      setTableLoadingByDatabase((current) => ({ ...current, [stateKey]: true }));
      setTableStates((current) => ({
        ...current,
        [stateKey]: { status: 'loading', items: current[stateKey]?.items || [] },
      }));

      try {
        let nextTables: CatalogOption[];
        if (source === 'physical') {
          const response = await lakeCatalogApi.listTablesByDatabase(database);
          if (response.code !== 0) {
            throw new Error(response.message || '表列表读取失败');
          }
          nextTables = Array.isArray(response.data) ? response.data : [];
        } else {
          const response = await fetchCatalogQueryTables(catalogId as number, database);
          if (response.code !== 0) {
            throw new Error(response.message || response.msg || '逻辑表列表读取失败');
          }
          nextTables = (response.data || []).map((table) => ({
            value: table,
            label: table,
          }));
        }
        setTableStates((current) => ({
          ...current,
          [stateKey]: { status: 'loaded', items: nextTables },
        }));
        setCatalogError('');
      } catch (error) {
        setTableStates((current) => ({
          ...current,
          [stateKey]: { status: 'error', items: [] },
        }));
        setCatalogError(error instanceof Error ? error.message : '表列表读取失败');
      } finally {
        setTableLoadingByDatabase((current) => ({ ...current, [stateKey]: false }));
      }
    },
    [catalogReady, configured, tableStates],
  );

  const loadDirectories = useCallback(async () => {
    if (!configured || !catalogReady) {
      initialDirectoryExpanded.current = false;
      setDatabases([]);
      setLogicalCatalogs([]);
      setLogicalDatabaseStates({});
      setTableStates({});
      setTableLoadingByDatabase({});
      setExpandedKeys([]);
      setSelectedSource('physical');
      setSelectedCatalogId(undefined);
      setSelectedDatabase('');
      setSelectedTable('');
      return;
    }

    setCatalogLoading(true);
    try {
      const [databaseResponse, catalogResponse] = await Promise.all([
        lakeCatalogApi.listDatabases(),
        fetchCatalogs({ pageNo: 1, pageSize: 100, resourceStatus: 'READY' }),
      ]);
      if (databaseResponse.code !== 0) {
        throw new Error(databaseResponse.message || '物理数据库列表读取失败');
      }
      if (catalogResponse.code !== 0) {
        throw new Error(catalogResponse.message || catalogResponse.msg || '逻辑目录列表读取失败');
      }

      const nextDatabases = Array.isArray(databaseResponse.data) ? databaseResponse.data : [];
      const nextLogicalCatalogs = normalizeLakePage(catalogResponse.data).data.filter(
        (catalog) => Boolean(catalog.id && catalog.targetCatalogName && !catalog.deleted),
      );
      const logicalDatabaseResults = await Promise.all(
        nextLogicalCatalogs.map(async (catalog) => {
          const catalogId = catalog.id;
          if (!catalogId) {
            return { catalogId: '', state: { status: 'error' as const, items: [] }, error: '逻辑目录缺少 ID' };
          }
          try {
            const response = await fetchCatalogQueryDatabases(catalogId);
            if (response.code !== 0) {
              throw new Error(response.message || response.msg || '逻辑数据库列表读取失败');
            }
            return {
              catalogId: String(catalogId),
              state: { status: 'loaded' as const, items: response.data || [] },
              error: '',
            };
          } catch (error) {
            return {
              catalogId: String(catalogId),
              state: { status: 'error' as const, items: [] },
              error: error instanceof Error ? error.message : '逻辑数据库列表读取失败',
            };
          }
        }),
      );
      const nextLogicalDatabaseStates: Record<string, LogicalDatabaseState> = {};
      logicalDatabaseResults.forEach(({ catalogId, state }) => {
        if (catalogId) nextLogicalDatabaseStates[catalogId] = state;
      });
      const logicalDirectoryError = logicalDatabaseResults.find((result) => result.error)?.error || '';

      initialDirectoryExpanded.current = false;
      setCatalogError(logicalDirectoryError);
      setDatabases(nextDatabases);
      setLogicalCatalogs(nextLogicalCatalogs);
      setLogicalDatabaseStates(nextLogicalDatabaseStates);
      setTableStates({});
      setTableLoadingByDatabase({});
      setExpandedKeys([]);
      setSelectedSource('physical');
      setSelectedCatalogId(undefined);
      setSelectedDatabase(optionValue(nextDatabases[0]));
      setSelectedTable('');
    } catch (error) {
      initialDirectoryExpanded.current = false;
      setDatabases([]);
      setLogicalCatalogs([]);
      setLogicalDatabaseStates({});
      setTableStates({});
      setTableLoadingByDatabase({});
      setExpandedKeys([]);
      setSelectedSource('physical');
      setSelectedCatalogId(undefined);
      setSelectedDatabase('');
      setSelectedTable('');
      setCatalogError(error instanceof Error ? error.message : '目录列表读取失败');
    } finally {
      setCatalogLoading(false);
    }
  }, [catalogReady, configured]);

  const loadDetail = useCallback(async () => {
    if (
      !configured
      || !catalogReady
      || !selectedDatabase
      || !selectedTable
      || (selectedSource === 'logical' && !selectedCatalogId)
    ) {
      setColumns([]);
      setPreview({});
      return;
    }

    setDetailLoading(true);
    try {
      if (selectedSource === 'physical') {
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
      } else {
        const catalogId = selectedCatalogId as number;
        const columnResponse = await fetchCatalogQueryColumns(
          catalogId,
          selectedDatabase,
          selectedTable,
        );
        if (columnResponse.code !== 0) {
          throw new Error(columnResponse.message || columnResponse.msg || '逻辑字段读取失败');
        }
        const logicalColumns: LakeQueryColumnOption[] = Array.isArray(columnResponse.data)
          ? columnResponse.data
          : [];
        setColumns(logicalColumns.map((column, index) => ({
          key: index + 1,
          fieldName: column.name,
          fieldType: column.type,
          isNullable: column.nullable ? 'YES' : 'NO',
        })));

        const tableIdentity = {
          catalog: selectedLogicalCatalog?.targetCatalogName || '',
          database: selectedDatabase,
          table: selectedTable,
        };
        const selectableColumns = logicalColumns.filter(
          (column) => column.selectable !== false && column.name,
        );
        if (!selectableColumns.length) {
          setPreview({});
        } else {
          const previewResponse = await queryCatalogSingle(catalogId, {
            table: tableIdentity,
            selectedColumns: selectableColumns.map((column) => ({
              table: tableIdentity,
              column: column.name,
            })),
            limit: 20,
            explain: false,
          });
          if (previewResponse.code !== 0 || !previewResponse.data) {
            throw new Error(previewResponse.message || previewResponse.msg || '逻辑样本数据读取失败');
          }
          setPreview({
            columns: (previewResponse.data.columns || []).map((column) => ({
              title: column,
              dataIndex: column,
              key: column,
            })),
            data: previewResponse.data.rows || [],
            total: previewResponse.data.rowCount,
          });
        }
      }
      setCatalogError('');
    } catch (error) {
      setColumns([]);
      setPreview({});
      setCatalogError(error instanceof Error ? error.message : '表详情读取失败');
    } finally {
      setDetailLoading(false);
    }
  }, [
    catalogReady,
    configured,
    selectedCatalogId,
    selectedDatabase,
    selectedLogicalCatalog,
    selectedSource,
    selectedTable,
  ]);

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
    void loadDirectories();
  }, [loadDirectories]);

  useEffect(() => {
    if (initialDirectoryExpanded.current) {
      return;
    }

    const firstPhysicalDatabase = optionValue(databases[0]);
    const firstLogicalCatalog = logicalCatalogs.find((catalog) => {
      const state = catalog.id ? logicalDatabaseStates[String(catalog.id)] : undefined;
      return Boolean(catalog.id && state?.status === 'loaded' && state.items.length);
    });
    if (!firstPhysicalDatabase && !firstLogicalCatalog) return;

    initialDirectoryExpanded.current = true;
    const nextExpandedKeys = [sourceNodeKey('physical'), sourceNodeKey('logical')];
    logicalCatalogs.forEach((catalog) => {
      if (catalog.id && logicalDatabaseStates[String(catalog.id)]?.status === 'loaded') {
        nextExpandedKeys.push(logicalCatalogNodeKey(catalog.id));
      }
    });
    if (firstPhysicalDatabase) {
      setSelectedSource('physical');
      setSelectedCatalogId(undefined);
      setSelectedDatabase(firstPhysicalDatabase);
      setSelectedTable('');
      nextExpandedKeys.push(physicalDatabaseNodeKey(firstPhysicalDatabase));
      void loadTablesForDatabase('physical', firstPhysicalDatabase);
    } else if (firstLogicalCatalog?.id) {
      const firstDatabase = logicalDatabaseStates[String(firstLogicalCatalog.id)]?.items[0];
      if (firstDatabase) {
        setSelectedSource('logical');
        setSelectedCatalogId(firstLogicalCatalog.id);
        setSelectedDatabase(firstDatabase);
        setSelectedTable('');
        nextExpandedKeys.push(logicalDatabaseNodeKey(firstLogicalCatalog.id, firstDatabase));
        void loadTablesForDatabase('logical', firstDatabase, firstLogicalCatalog.id);
      }
    }
    setExpandedKeys(nextExpandedKeys);
  }, [databases, loadTablesForDatabase, logicalCatalogs, logicalDatabaseStates]);

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
    const physicalDatabaseNodes = visibleDatabases.map((database) => {
      const databaseName = optionValue(database);
      const state = tableStates[tableStateKey('physical', databaseName)];
      const visibleTables = (state?.items || []).filter((table) => {
        if (!keyword) return true;
        return `${optionValue(table)} ${optionLabel(table)}`.toLowerCase().includes(keyword);
      });

      return {
        key: physicalDatabaseNodeKey(databaseName),
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
                  key: physicalTableNodeKey(databaseName, tableName),
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

    const logicalCatalogNodes = logicalCatalogs
      .filter((catalog) => Boolean(catalog.id && catalog.targetCatalogName))
      .filter((catalog) => {
        if (!databaseSearch.trim()) return true;
        const catalogName = String(catalog.targetCatalogName || '').toLowerCase();
        const state = catalog.id ? logicalDatabaseStates[String(catalog.id)] : undefined;
        return catalogName.includes(databaseSearch.trim().toLowerCase())
          || (state?.items || []).some((database) => database.toLowerCase().includes(databaseSearch.trim().toLowerCase()));
      })
      .map((catalog) => {
        const catalogId = catalog.id as number;
        const state = logicalDatabaseStates[String(catalogId)];
        const visibleLogicalDatabases = (state?.items || []).filter((database) => {
          const databaseKeyword = databaseSearch.trim().toLowerCase();
          return !databaseKeyword || database.toLowerCase().includes(databaseKeyword);
        });

        return {
          key: logicalCatalogNodeKey(catalogId),
          icon: <LinkOutlined />,
          title: (
            <span className="lake-catalog-tree-node lake-catalog-tree-node--catalog">
              <span className="lake-catalog-tree-node__copy">
                <strong>{catalog.targetCatalogName}</strong>
                <small>逻辑目录</small>
              </span>
              {state?.status === 'loading' ? <LoadingOutlined spin /> : null}
              {state?.status === 'error' ? <WarningOutlined /> : null}
              {state?.status === 'loaded' ? <em>{state.items.length}</em> : null}
            </span>
          ),
          isLeaf: state?.status === 'loaded' && state.items.length === 0,
          children:
            state?.status === 'loaded'
              ? visibleLogicalDatabases.map((database) => {
                  const databaseState = tableStates[tableStateKey('logical', database, catalogId)];
                  const visibleTables = (databaseState?.items || []).filter((table) => {
                    if (!keyword) return true;
                    return `${optionValue(table)} ${optionLabel(table)}`.toLowerCase().includes(keyword);
                  });
                  return {
                    key: logicalDatabaseNodeKey(catalogId, database),
                    icon: <DatabaseOutlined />,
                    title: (
                      <span className="lake-catalog-tree-node">
                        <span className="lake-catalog-tree-node__copy">
                          <strong>{database}</strong>
                          <small>数据库</small>
                        </span>
                        {databaseState?.status === 'loading' ? <LoadingOutlined spin /> : null}
                        {databaseState?.status === 'loaded' ? <em>{databaseState.items.length}</em> : null}
                      </span>
                    ),
                    isLeaf: databaseState?.status === 'loaded' && databaseState.items.length === 0,
                    children:
                      databaseState?.status === 'loaded'
                        ? visibleTables.map((table) => {
                            const tableName = optionValue(table);
                            return {
                              key: logicalTableNodeKey(catalogId, database, tableName),
                              isLeaf: true,
                              icon: <TableOutlined />,
                              title: (
                                <span className="lake-catalog-tree-node lake-catalog-tree-node--table">
                                  <span className="lake-catalog-tree-node__copy">
                                    <strong>{tableName}</strong>
                                    <small>逻辑表</small>
                                  </span>
                                </span>
                              ),
                            };
                          })
                        : undefined,
                  };
                })
              : undefined,
        };
      });

    return [
      {
        key: sourceNodeKey('physical'),
        icon: <DatabaseOutlined />,
        title: (
          <span className="lake-catalog-tree-node lake-catalog-tree-node--group">
            <span className="lake-catalog-tree-node__copy">
              <strong>物理入湖</strong>
              <small>仓库数据库</small>
            </span>
            <em>{databases.length}</em>
          </span>
        ),
        isLeaf: physicalDatabaseNodes.length === 0,
        children: physicalDatabaseNodes,
      },
      {
        key: sourceNodeKey('logical'),
        icon: <ApartmentOutlined />,
        title: (
          <span className="lake-catalog-tree-node lake-catalog-tree-node--group lake-catalog-tree-node--logical">
            <span className="lake-catalog-tree-node__copy">
              <strong>逻辑入湖</strong>
              <small>外部目录数据库</small>
            </span>
            <em>{logicalDatabaseCount}</em>
          </span>
        ),
        isLeaf: logicalCatalogNodes.length === 0,
        children: logicalCatalogNodes,
      },
    ];
  }, [
    databaseSearch,
    databases.length,
    logicalCatalogs,
    logicalDatabaseCount,
    logicalDatabaseStates,
    tableSearch,
    tableStates,
    visibleDatabases,
  ]);

  const selectedTreeKeys = selectedTable
    ? [selectedSource === 'physical'
      ? physicalTableNodeKey(selectedDatabase, selectedTable)
      : logicalTableNodeKey(selectedCatalogId as number, selectedDatabase, selectedTable)]
    : selectedDatabase
      ? [selectedSource === 'physical'
        ? physicalDatabaseNodeKey(selectedDatabase)
        : logicalDatabaseNodeKey(selectedCatalogId as number, selectedDatabase)]
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
      .forEach((selection) => void loadTablesForDatabase(
        selection.source,
        selection.database,
        selection.source === 'logical' ? selection.catalogId : undefined,
      ));
  };

  const handleSelect = (keys: React.Key[]) => {
    const key = String(keys[0] || '');
    const selection = parseTreeSelection(key);
    if (!selection) return;

    if (selection.type === 'source' || selection.type === 'logicalCatalog') {
      if (!expandedKeys.includes(key)) {
        setExpandedKeys((current) => [...current, key]);
      }
      return;
    }

    if (selection.type === 'database') {
      setSelectedSource(selection.source);
      setSelectedCatalogId(selection.source === 'logical' ? selection.catalogId : undefined);
      setSelectedDatabase(selection.database);
      setSelectedTable('');
      setTableSearch('');
      if (!expandedKeys.includes(key)) {
        setExpandedKeys((current) => [...current, key]);
      }
      void loadTablesForDatabase(
        selection.source,
        selection.database,
        selection.source === 'logical' ? selection.catalogId : undefined,
      );
      return;
    }

    setSelectedSource(selection.source);
    setSelectedCatalogId(selection.source === 'logical' ? selection.catalogId : undefined);
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
    {
      title: '是否可空',
      dataIndex: 'isNullable',
      key: 'isNullable',
      width: 100,
      render: (value) => value || '-',
    },
    {
      title: '键',
      dataIndex: 'fieldKey',
      key: 'fieldKey',
      width: 100,
      render: (value) => fieldKeyLabel(value),
    },
    {
      title: '注释',
      dataIndex: 'fieldComment',
      key: 'fieldComment',
      ellipsis: true,
      render: (value) => value || '-',
    },
  ];

  const refresh = async () => {
    await loadDirectories();
  };

  const tableLoading = Object.values(tableLoadingByDatabase).some(Boolean);
  const directoryCount = databases.length + logicalDatabaseCount;
  const hasDirectoryEntries = databases.length > 0 || logicalCatalogs.length > 0;

  return (
    <div className="lake-catalog-page">
      <header className="lake-catalog-header">
        <div className="lake-catalog-heading">
          <div className="lake-catalog-heading__mark" aria-hidden="true">
            <DatabaseOutlined />
          </div>
          <div>
            <Title level={1}>数据湖目录</Title>
            <Paragraph>浏览数据湖中的数据库、数据表与样本数据。</Paragraph>
          </div>
        </div>
        <div className="lake-catalog-actions">
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
              <span>{directoryCount} 个</span>
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
              {catalogLoading && !hasDirectoryEntries ? (
                <div className="lake-catalog-tree-state">
                  <Spin size="small" />
                  <span>正在读取目录</span>
                </div>
              ) : null}
              {!catalogLoading && !hasDirectoryEntries ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可浏览数据库" />
              ) : null}
              {hasDirectoryEntries ? (
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
                {selectedTable ? <p>{selectedTablePath}</p> : null}
              </div>
            </div>
            {selectedTable ? (
              <>
                <div className="lake-catalog-inspector__comment">
                  <span>表注释</span>
                  <span title={selectedTableComment || '暂无注释'}>
                    {selectedTableComment || '暂无注释'}
                  </span>
                </div>
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
                            scroll={{ y: 'calc(100vh - 395px)' }}
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
                              {selectedSource === 'logical'
                                ? <>逻辑目录查询返回 {Number(preview.total || 0).toLocaleString('zh-CN')} 行。</>
                                : <>仅展示前 20 行，实际总行数{' '}{Number(preview.total || 0).toLocaleString('zh-CN')}。</>}
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
                              y: 'calc(100vh - 445px)',
                            }}
                            locale={{ emptyText: '暂无样本数据' }}
                          />
                        </Spin>
                      ),
                    },
                  ]}
                />
              </>
            ) : (
              <div className="lake-catalog-inspector__empty">
                <TableOutlined />
                <span>从左侧目录选择表，查看字段和前 20 行样本。</span>
              </div>
            )}
          </section>
        </section>
      )}
    </div>
  );
};

export default DataLakeCatalogPage;
