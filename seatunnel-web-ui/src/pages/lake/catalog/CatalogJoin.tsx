import type { LakeQueryTableIdentity } from '@/services/lake';
import { cancelCatalogQuery, lakeCatalogApi, queryCatalogJoin } from '@/services/lake';
import { SwapOutlined } from '@ant-design/icons';
import { Alert, Button, Select, Space, Typography } from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import QueryRows, { type DisplayQueryResult } from './QueryRows';

export interface JoinDatabase {
  key: string;
  label: string;
  catalog: string;
  database: string;
  tables: { value: string; label: string }[];
  status?: 'loading' | 'loaded' | 'error';
}

type FieldOptions = {
  key: string;
  names: string[];
  error?: string;
};

function useQueryFields(identity?: LakeQueryTableIdentity) {
  const [fields, setFields] = useState<FieldOptions>();
  const { catalog, database, table } = identity || {};
  const key = JSON.stringify([catalog, database, table]);
  useEffect(() => {
    if (!catalog || !database || !table) return;
    let active = true;
    void lakeCatalogApi.queryColumns({ catalog, database, table }).then((response) => {
      if (!active) return;
      if (response.code !== 0) throw new Error(response.message || response.msg || '关联字段读取失败');
      const names = (response.data || [])
        .filter((column) => column.selectable !== false)
        .map((column) => column.name);
      setFields({ key, names, error: names.length ? undefined : '表中没有可用于查询的字段' });
    }).catch((error: unknown) => {
      if (active) setFields({ key, names: [], error: error instanceof Error ? error.message : '关联字段读取失败' });
    });
    return () => { active = false; };
  }, [catalog, database, key, table]);

  return {
    names: fields?.key === key ? fields.names : [],
    error: fields?.key === key ? fields.error : undefined,
    loading: Boolean(identity && fields?.key !== key),
  };
}

const CatalogJoin: React.FC<{
  selectedTable: LakeQueryTableIdentity;
  selectedLabel: string;
  databases: JoinDatabase[];
  loadTables: (key: string) => void;
}> = ({ selectedTable, selectedLabel, databases, loadTables }) => {
  const [databaseKey, setDatabaseKey] = useState<string>();
  const [tableName, setTableName] = useState<string>();
  const [selectedKey, setSelectedKey] = useState<string>();
  const [otherKey, setOtherKey] = useState<string>();
  const [swapped, setSwapped] = useState(false);
  const [joinType, setJoinType] = useState<'INNER' | 'LEFT'>('INNER');
  const [queryId, setQueryId] = useState<string>();
  const [result, setResult] = useState<DisplayQueryResult>();
  const [error, setError] = useState<string>();
  const inFlight = useRef<string>();
  useEffect(() => () => {
    const id = inFlight.current;
    inFlight.current = undefined;
    if (id) void cancelCatalogQuery(id).catch(() => undefined);
  }, []);

  const database = databases.find((item) => item.key === databaseKey);
  const otherTable = database && tableName
    ? { catalog: database.catalog, database: database.database, table: tableName }
    : undefined;
  const selectedFields = useQueryFields(selectedTable);
  const otherFields = useQueryFields(otherTable);
  const currentSide = { table: selectedTable, label: selectedLabel, field: selectedKey, setField: setSelectedKey, ...selectedFields };
  const otherSide = { table: otherTable, label: otherTable ? `${database?.label} · ${tableName}` : '', field: otherKey, setField: setOtherKey, ...otherFields };
  const [left, right] = swapped ? [otherSide, currentSide] : [currentSide, otherSide];
  const clearResult = () => {
    setResult(undefined);
    setError(undefined);
  };

  const submit = async () => {
    if (inFlight.current || !left.table || !right.table || !left.field || !right.field) return;
    const id = globalThis.crypto?.randomUUID?.() || `lake-query-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    inFlight.current = id;
    setQueryId(id);
    clearResult();
    try {
      const response = await queryCatalogJoin({
        leftTable: left.table,
        rightTable: right.table,
        leftColumns: left.names.map((column) => ({ table: left.table, column })),
        rightColumns: right.names.map((column) => ({ table: right.table, column })),
        leftJoinColumn: { table: left.table, column: left.field },
        rightJoinColumn: { table: right.table, column: right.field },
        joinType,
        limit: 10,
        queryId: id,
      });
      if (inFlight.current !== id) return;
      if (response.code !== 0 || !response.data) throw new Error(response.message || response.msg || '联合查询失败');
      // Keep response aliases as data keys so duplicate source field names remain distinct.
      setResult({
        ...response.data,
        columnTitles: Object.fromEntries([
          ...left.names.map((name, index) => [`left_c${index}`, `左表 · ${name}`]),
          ...right.names.map((name, index) => [`right_c${index}`, `右表 · ${name}`]),
        ]),
      });
    } catch (reason) {
      if (inFlight.current === id) {
        const message = reason instanceof Error ? reason.message : '联合查询失败';
        setError(message.includes('CANCELLED') ? '查询已取消' : message.includes('TIMEOUT') ? '查询超时' : message);
      }
    } finally {
      if (inFlight.current === id) {
        inFlight.current = undefined;
        setQueryId(undefined);
      }
    }
  };

  const cancel = async () => {
    if (!queryId) return;
    try {
      const response = await cancelCatalogQuery(queryId);
      if (response.code !== 0) throw new Error(response.message || response.msg || '取消查询失败');
    } catch (reason) {
      if (inFlight.current === queryId) setError(reason instanceof Error ? reason.message : '取消查询失败');
    }
  };

  return (
    <div className="lake-catalog-join">
      <Space wrap className="lake-catalog-join-controls">
        <Select
          aria-label="关联库"
          placeholder="选择关联库"
          value={databaseKey}
          disabled={Boolean(queryId)}
          showSearch
          optionFilterProp="label"
          options={['物理入湖', '逻辑入湖'].map((label, index) => ({
            label,
            options: databases
              .filter((item) => (item.catalog === 'internal') === (index === 0))
              .map((item) => ({ label: item.label, value: item.key })),
          })).filter((group) => group.options.length > 0)}
          onChange={(key) => {
            setDatabaseKey(key);
            setTableName(undefined);
            setOtherKey(undefined);
            setSwapped(false);
            clearResult();
            loadTables(key);
          }}
        />
        <Select
          aria-label="关联表"
          placeholder="选择关联表"
          value={tableName}
          loading={database?.status === 'loading'}
          disabled={!database || Boolean(queryId)}
          showSearch
          optionFilterProp="label"
          options={database?.tables || []}
          onChange={(table) => {
            setTableName(table);
            setOtherKey(undefined);
            clearResult();
          }}
        />
        <Button icon={<SwapOutlined />} disabled={!otherTable || Boolean(queryId)} onClick={() => {
          setSwapped(!swapped);
          clearResult();
        }}>
          交换左右表
        </Button>
      </Space>
      <div className="lake-catalog-join-fields">
        {[left, right].map((side, index) => {
          const name = index === 0 ? '左表' : '右表';
          return (
            <div key={name}>
              <Typography.Text ellipsis={{ tooltip: side.label }}>{name}{side.label ? ` · ${side.label}` : ''}</Typography.Text>
              <Select
                aria-label={`${name}关联字段`}
                placeholder="选择关联字段"
                value={side.field}
                loading={side.loading}
                disabled={!side.names.length || Boolean(queryId)}
                showSearch
                options={side.names.map((column) => ({ label: column, value: column }))}
                onChange={(column) => {
                  side.setField(column);
                  clearResult();
                }}
              />
            </div>
          );
        })}
      </div>
      <Space wrap>
        <Select
          aria-label="连接方式"
          value={joinType}
          disabled={Boolean(queryId)}
          options={[{ label: '内连接', value: 'INNER' }, { label: '左连接', value: 'LEFT' }]}
          onChange={(type) => { setJoinType(type); clearResult(); }}
        />
        <Button type="primary" loading={Boolean(queryId)}
          disabled={!left.field || !right.field || !left.names.includes(left.field) || !right.names.includes(right.field)}
          onClick={() => void submit()}>
          开始查询
        </Button>
        {queryId ? <Button onClick={() => void cancel()}>取消查询</Button> : null}
      </Space>
      {database?.status === 'error' ? (
        <Alert showIcon type="error" message="关联表读取失败"
          action={<Button onClick={() => loadTables(database.key)}>重试</Button>} />
      ) : null}
      {[selectedFields.error, otherFields.error, error].filter(Boolean).map((message, index) => (
        <Alert key={index} showIcon type="error" message={message} />
      ))}
      {result ? <QueryRows result={result} /> : null}
    </div>
  );
};

export default CatalogJoin;
