import type { DataExplorationTable } from '@/pages/data-source/types';
import { ApartmentOutlined, RightOutlined, SearchOutlined, TableOutlined } from '@ant-design/icons';
import { Empty, Input, Pagination, Select, Spin } from 'antd';
import { useMemo, useState } from 'react';
import type { CatalogSource } from './service';
import type useTopologyCatalog from './useTopologyCatalog';
import type { CatalogScope } from './useTopologyCatalog';

type Catalog = ReturnType<typeof useTopologyCatalog>;
const pageSize = 20;

function TableList({ tables, loading, focusedId, tableCatalog, onFocus }: {
  tables: DataExplorationTable[];
  loading: boolean;
  focusedId?: string;
  tableCatalog: boolean;
  onFocus: (tableId: string) => void;
}) {
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    return term
      ? tables.filter((table) => [table.name, table.displayName, table.fullyQualifiedName]
        .some((value) => value?.toLowerCase().includes(term)))
      : tables;
  }, [search, tables]);
  const currentPage = Math.min(page, Math.max(1, Math.ceil(filtered.length / pageSize)));
  const visible = filtered.slice((currentPage - 1) * pageSize, currentPage * pageSize);
  const label = tableCatalog ? '数据表' : '资产';
  const searchLabel = tableCatalog ? '搜索表名或 FQN' : '搜索资产名或 FQN';

  return (
    <>
      <div className="data-topology-list-heading">
        <strong><TableOutlined /> {label}</strong>
        <span>{tables.length}</span>
      </div>
      <Input
        aria-label={searchLabel}
        placeholder={searchLabel}
        prefix={<SearchOutlined />}
        value={search}
        allowClear
        onChange={(event) => { setSearch(event.target.value); setPage(1); }}
      />
      <div className="data-topology-table-list">
        {loading && tables.length === 0 ? <Spin size="small" aria-label="正在读取表目录" />
          : visible.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={tables.length ? `没有匹配的${label}` : `暂无${label}`} />
            : visible.map((table) => (
              <button
                type="button"
                className="data-topology-table-item"
                key={table.id}
                title={table.fullyQualifiedName}
                aria-pressed={table.id === focusedId}
                onClick={() => onFocus(table.id)}
              >
                <TableOutlined />
                <span>
                  <strong>{table.displayName || table.name}</strong>
                  <small>{table.fullyQualifiedName}</small>
                </span>
                {table.columnCount !== undefined && <small>{table.columnCount} 列</small>}
              </button>
            ))}
      </div>
      {filtered.length > pageSize && <Pagination size="small" current={currentPage} total={filtered.length}
        pageSize={pageSize} showSizeChanger={false} showLessItems onChange={setPage} />}
    </>
  );
}

export default function TopologyBrowser({ catalog, sources, focusedId, onScope, onFocus, onOverview }: {
  catalog: Catalog;
  sources: CatalogSource[];
  focusedId?: string;
  onScope: (scope: CatalogScope) => void;
  onFocus: (tableId: string) => void;
  onOverview: () => void;
}) {
  const { source, database, databases, schema, schemas, directory, loading } = catalog;
  return (
    <nav className="data-topology-browser" aria-label={source.tableCatalog ? '血缘表目录' : '血缘资产目录'}>
      <div className="data-topology-scope">
        <label>
          <span>数据源</span>
          <Select
            aria-label="数据源" placeholder="选择数据源" showSearch optionFilterProp="label"
            value={source?.id} options={sources.map(({ id, name }) => ({ value: id, label: name }))}
            onChange={(sourceId) => onScope({ sourceId })}
          />
        </label>
        {source.tableCatalog && <><label>
          <span>数据库</span>
          <Select
            aria-label="数据库" placeholder="选择数据库" showSearch optionFilterProp="label"
            value={database?.fullyQualifiedName} loading={databases.loading} disabled={!source}
            options={databases.data?.map(({ name, fullyQualifiedName }) => ({ value: fullyQualifiedName, label: name }))}
            onChange={(databaseFqn) => onScope({ sourceId: source?.id, databaseFqn })}
          />
        </label>
        <label>
          <span>Schema</span>
          <Select
            aria-label="Schema" placeholder="选择 Schema" showSearch optionFilterProp="label"
            value={schema?.fullyQualifiedName} loading={schemas.loading} disabled={!database}
            options={schemas.data?.map(({ name, fullyQualifiedName }) => ({ value: fullyQualifiedName, label: name }))}
            onChange={(schemaFqn) => onScope({ sourceId: source?.id, databaseFqn: database?.fullyQualifiedName, schemaFqn })}
          />
        </label></>}
        <button
          type="button" className="data-topology-overview" aria-pressed={!focusedId}
          disabled={source.tableCatalog && !schema} onClick={onOverview}
        >
          <span><ApartmentOutlined /> {source.tableCatalog ? 'Schema 血缘总览' : '数据源血缘'}</span><RightOutlined />
        </button>
      </div>
      <TableList key={schema?.fullyQualifiedName} tables={directory} loading={loading}
        tableCatalog={source.tableCatalog} focusedId={focusedId} onFocus={onFocus} />
    </nav>
  );
}
