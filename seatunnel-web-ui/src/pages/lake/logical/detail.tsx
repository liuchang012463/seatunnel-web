import type {
  LakeApiResponse,
  LakeCatalog,
  LakePhysicalDataSource,
  LakeQueryColumnOption,
  LakeReadOnlyQueryPreview,
  LakeResourceOperation,
} from '@/services/lake';
import {
  cancelCatalogQuery,
  deleteCatalog,
  fetchCatalog,
  fetchCatalogQueryColumns,
  fetchCatalogQueryDatabases,
  fetchCatalogQueryTables,
  fetchCatalogs,
  fetchLakeOperations,
  fetchPhysicalSource,
  fetchPhysicalSources,
  previewCatalogJoin,
  previewCatalogSingle,
  queryCatalogJoin,
  queryCatalogSingle,
  reconcileCatalog,
  refreshCatalog,
  updateCatalog,
  validateCatalog,
} from '@/services/lake';
import {
  ArrowLeftOutlined,
  CheckOutlined,
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  EyeOutlined,
  ReloadOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, useParams } from '@umijs/max';
import type { FormInstance } from 'antd';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Collapse,
  Descriptions,
  Drawer,
  Dropdown,
  Empty,
  Form,
  Input,
  InputNumber,
  message,
  Modal,
  Select,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import { OperationTimeline } from '../components/LakeStatus';
import CatalogScopePicker from './CatalogScopePicker';
import './index.less';
import type { DisplayQueryResult } from './presentation';
import {
  adapterLabel,
  catalogScopeLabel,
  catalogStatusLabel,
  defaultQueryColumns,
  labelQueryResult,
  catalogStatusColor as statusColor,
} from './presentation';

const responseMessage = (response: LakeApiResponse<unknown>) =>
  response.msg || response.message || '请求失败，请稍后重试';
const toColumns = (result: DisplayQueryResult) =>
  result.columns.map((name) => ({
    title: result.columnTitles?.[name] || name,
    dataIndex: name,
    key: name,
    ellipsis: true,
  }));

interface QueryError {
  code?: string;
  message: string;
}

const readQueryError = (error: unknown, fallback: string): QueryError => {
  const candidate = error as { message?: string; response?: LakeApiResponse<unknown> } | undefined;
  const response = candidate?.response;
  return {
    code: response?.message || response?.msg || candidate?.message,
    message: response?.message || response?.msg || candidate?.message || fallback,
  };
};

const queryStatusLabel = (code?: string) => {
  if (!code) return '查询失败';
  if (code.includes('TIMEOUT')) return '查询超时';
  if (code.includes('CANCEL')) return '查询已取消';
  if (code.includes('PERMISSION')) return '没有查询权限';
  if (code.includes('SENSITIVE')) return '敏感字段不可查询';
  if (code.includes('UNSUPPORTED')) return '字段类型不支持';
  return '查询条件未通过服务端校验';
};

const newQueryId = () => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `lake-query-${Date.now()}-${Math.random().toString(36).slice(2)}`;
};

const QueryResult: React.FC<{
  result?: DisplayQueryResult;
  loading?: boolean;
  error?: QueryError;
}> = ({ result, loading, error }) => {
  if (loading)
    return (
      <Card size="small">
        <Spin tip="正在执行只读查询" />
      </Card>
    );
  if (error)
    return (
      <Card size="small">
        <Alert type="error" showIcon message={queryStatusLabel(error.code)} description={error.message} />
      </Card>
    );
  if (!result)
    return (
      <div className="lake-query-empty">
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择一张表，点击“预览数据”即可查看样例" />
      </div>
    );
  return (
    <Card
      size="small"
      className="lake-query-result"
      title={
        <Space>
          <EyeOutlined />
          {result.explain ? '查询执行计划' : '数据预览'}
        </Space>
      }
      extra={
        <Typography.Text type="secondary">
          {result.rowCount} 行 · {result.elapsedMillis} 毫秒
          {result.truncated ? ' · 已达到预览上限' : ''}
        </Typography.Text>
      }
    >
      <Table
        size="small"
        rowKey={(_, index) => String(index)}
        scroll={{ x: 'max-content' }}
        columns={toColumns(result)}
        dataSource={result.rows || []}
        pagination={false}
      />
    </Card>
  );
};

const QueryPreview: React.FC<{ preview?: LakeReadOnlyQueryPreview }> = ({ preview }) =>
  preview ? (
    <Card size="small" title="SQL 预览" className="lake-query-preview">
      <pre>{preview.sql}</pre>
      <Typography.Text type="secondary">此查询由系统自动生成，仅用于读取数据。</Typography.Text>
    </Card>
  ) : null;

interface CatalogTablePickerProps {
  form: FormInstance;
  catalogId?: number;
  prefix?: 'left' | 'right';
  includeJoinKey?: boolean;
  refreshKey?: number;
  advancedOptions?: React.ReactNode;
}

export const CatalogTablePicker: React.FC<CatalogTablePickerProps> = ({
  form,
  catalogId,
  prefix,
  includeJoinKey,
  refreshKey = 0,
  advancedOptions,
}) => {
  const field = (name: string) => (prefix ? prefix + name[0].toUpperCase() + name.slice(1) : name);
  const databaseField = field('database');
  const tableField = field('table');
  const columnsField = field('columns');
  const joinKeyField = field('joinColumn');
  const database = Form.useWatch(databaseField, form) as string | undefined;
  const table = Form.useWatch(tableField, form) as string | undefined;
  const selectedColumns = Form.useWatch(columnsField, form) as string[] | undefined;
  const [databases, setDatabases] = useState<string[]>([]);
  const [tables, setTables] = useState<string[]>([]);
  const [columns, setColumns] = useState<LakeQueryColumnOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [metadataError, setMetadataError] = useState<string>();
  const [retryKey, setRetryKey] = useState(0);
  const previousCatalog = useRef<number>();
  const previousDatabase = useRef<string>();
  const previousTable = useRef<string>();

  useEffect(() => {
    let active = true;
    const sameCatalog = previousCatalog.current === catalogId;
    previousCatalog.current = catalogId;
    const selected = sameCatalog ? form.getFieldValue(databaseField) : undefined;
    if (!sameCatalog)
      form.setFieldsValue({
        [databaseField]: undefined,
        [tableField]: undefined,
        [columnsField]: undefined,
        [joinKeyField]: undefined,
      });
    setDatabases([]);
    if (!catalogId) return;
    setLoading(true);
    setMetadataError(undefined);
    void fetchCatalogQueryDatabases(catalogId)
      .then((response) => {
        if (!active) return;
        if (response.code !== 0) throw new Error(responseMessage(response));
        const values = response.data || [];
        setDatabases(values);
        // A single mounted namespace needs no manual selection.
        form.setFieldValue(
          databaseField,
          values.includes(selected) ? selected : values.length === 1 ? values[0] : undefined,
        );
      })
      .catch(() => {
        if (active) setMetadataError('库表读取失败，请重试；若源端刚有变化，可点击上方“刷新库表”。');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [catalogId, refreshKey, retryKey, databaseField, tableField, columnsField, joinKeyField, form]);

  useEffect(() => {
    let active = true;
    const identity = String(catalogId) + '/' + database;
    const sameDatabase = previousDatabase.current === identity;
    const selected = sameDatabase ? form.getFieldValue(tableField) : undefined;
    previousDatabase.current = identity;
    if (!sameDatabase)
      form.setFieldsValue({
        [tableField]: undefined,
        [columnsField]: undefined,
        [joinKeyField]: undefined,
      });
    setTables([]);
    setColumns([]);
    if (!catalogId || !database) return;
    setLoading(true);
    setMetadataError(undefined);
    void fetchCatalogQueryTables(catalogId, database)
      .then((response) => {
        if (!active) return;
        if (response.code !== 0) throw new Error(responseMessage(response));
        const values = response.data || [];
        setTables(values);
        form.setFieldValue(
          tableField,
          values.includes(selected) ? selected : values.length === 1 ? values[0] : undefined,
        );
      })
      .catch(() => {
        if (active) setMetadataError('表列表读取失败，请重试。');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [catalogId, database, refreshKey, retryKey, tableField, columnsField, joinKeyField, form]);

  useEffect(() => {
    let active = true;
    const identity = String(catalogId) + '/' + database + '/' + table;
    const selected: string[] = previousTable.current === identity ? form.getFieldValue(columnsField) || [] : [];
    const selectedKey = previousTable.current === identity ? form.getFieldValue(joinKeyField) : undefined;
    previousTable.current = identity;
    form.setFieldsValue({
      [columnsField]: undefined,
      [joinKeyField]: undefined,
    });
    setColumns([]);
    if (!catalogId || !database || !table) return;
    setLoading(true);
    setMetadataError(undefined);
    void fetchCatalogQueryColumns(catalogId, database, table)
      .then((response) => {
        if (!active) return;
        if (response.code !== 0) throw new Error(responseMessage(response));
        const values = response.data || [];
        setColumns(values);
        const allowed = values.filter((column) => column.selectable !== false).map((column) => column.name);
        const retained = selected.filter((name) => allowed.includes(name));
        form.setFieldValue(columnsField, retained.length ? retained : defaultQueryColumns(values));
        if (includeJoinKey)
          form.setFieldValue(
            joinKeyField,
            allowed.includes(selectedKey) ? selectedKey : allowed.includes('id') ? 'id' : allowed[0],
          );
      })
      .catch(() => {
        if (active) {
          console.warn('[逻辑入湖] 查询字段读取失败', {
            catalogId,
            database,
            table,
          });
          setMetadataError('字段读取失败，请重试。');
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    // Ignore late responses when users switch tables quickly.
    return () => {
      active = false;
    };
  }, [catalogId, database, table, refreshKey, retryKey, columnsField, joinKeyField, includeJoinKey, form]);

  const options = columns.map((column) => ({
    value: column.name,
    label: column.name + (column.type ? ' · ' + column.type : '') + (column.selectable === false ? '（不可查询）' : ''),
    disabled: column.selectable === false,
  }));
  return (
    <div className="lake-query-picker">
      {metadataError ? (
        <Alert
          type="warning"
          showIcon
          message={metadataError}
          action={<Button onClick={() => setRetryKey((value) => value + 1)}>重新读取</Button>}
        />
      ) : null}
      <div className="lake-query-fields">
        <Form.Item name={databaseField} label="库 / Schema" rules={[{ required: true, message: '请选择库或 Schema' }]}>
          <Select
            showSearch
            loading={loading && !databases.length}
            options={databases.map((value) => ({ value, label: value }))}
            placeholder="请选择库或 Schema"
            optionFilterProp="label"
            disabled={!catalogId}
          />
        </Form.Item>
        <Form.Item name={tableField} label="数据表" rules={[{ required: true, message: '请选择要预览的数据表' }]}>
          <Select
            showSearch
            loading={loading && !!database && !tables.length}
            options={tables.map((value) => ({ value, label: value }))}
            placeholder={database ? '搜索并选择数据表' : '请先选择库或 Schema'}
            optionFilterProp="label"
            disabled={!database}
          />
        </Form.Item>
        {includeJoinKey ? (
          <Form.Item
            name={joinKeyField}
            label="关联字段"
            rules={[{ required: true, message: '请选择两表用于关联的字段' }]}
          >
            <Select
              showSearch
              options={options}
              placeholder="选择关联字段"
              optionFilterProp="label"
              disabled={!table}
            />
          </Form.Item>
        ) : null}
      </div>
      {table && !loading && columns.length > 0 && !columns.some((column) => column.selectable !== false) ? (
        <Alert type="warning" showIcon message="此表暂无可预览的字段，请选择其他数据表。" />
      ) : null}
      <Collapse
        ghost
        className="lake-query-options"
        items={[
          {
            key: 'options',
            label:
              '查询选项' +
              (selectedColumns?.length
                ? '（已选 ' + selectedColumns.length + ' 个字段）'
                : '（默认前 20 个可查询字段）'),
            forceRender: true,
            children: (
              <>
                <Form.Item
                  name={columnsField}
                  label="预览字段"
                  rules={[{ required: true, message: '请至少选择一个可查询字段' }]}
                >
                  <Select
                    mode="multiple"
                    allowClear
                    maxTagCount="responsive"
                    options={options}
                    placeholder="默认选择前 20 个可查询字段，可按需调整"
                    optionFilterProp="label"
                    disabled={!table}
                  />
                </Form.Item>
                {advancedOptions}
              </>
            ),
          },
        ]}
      />
    </div>
  );
};

const SingleTableQuery: React.FC<{
  catalog: LakeCatalog;
  refreshKey: number;
}> = ({ catalog, refreshKey }) => {
  const [form] = Form.useForm();
  const [result, setResult] = useState<DisplayQueryResult>();
  const [preview, setPreview] = useState<LakeReadOnlyQueryPreview>();
  const [error, setError] = useState<QueryError>();
  const [submitting, setSubmitting] = useState(false);
  const [activeQueryId, setActiveQueryId] = useState<string>();
  const selectedColumns = Form.useWatch('columns', form) as string[] | undefined;
  const explainOnly = Form.useWatch('explain', form) as boolean | undefined;
  const previewLimit = Form.useWatch('limit', form) as number | undefined;
  const buildRequest = (values: {
    database?: string;
    table?: string;
    columns?: string[];
    limit?: number;
    explain?: boolean;
  }) => {
    const identity = {
      catalog: catalog.targetCatalogName || '',
      database: values.database || '',
      table: values.table || '',
    };
    return {
      table: identity,
      selectedColumns: (values.columns || []).map((column) => ({
        table: identity,
        column,
      })),
      limit: values.limit || 10,
      explain: Boolean(values.explain),
    };
  };
  const submit = async (values: {
    database?: string;
    table?: string;
    columns?: string[];
    limit?: number;
    explain?: boolean;
  }) => {
    if (!catalog.id) return;
    const queryId = newQueryId();
    setSubmitting(true);
    setActiveQueryId(queryId);
    setError(undefined);
    try {
      const response = await queryCatalogSingle(catalog.id, {
        ...buildRequest(values),
        queryId,
      });
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response));
      setResult(labelQueryResult(response.data, values.columns || []));
      setPreview(undefined);
      message.success(values.explain ? '执行计划已生成' : '数据预览已完成');
    } catch (error) {
      setResult(undefined);
      setError(readQueryError(error, '查询失败'));
    } finally {
      setSubmitting(false);
      setActiveQueryId(undefined);
    }
  };
  const cancel = async () => {
    if (!activeQueryId) return;
    try {
      const response = await cancelCatalogQuery(activeQueryId);
      if (response.code !== 0) throw new Error(responseMessage(response));
      message.info(response.data ? '已请求取消查询' : '查询已结束或不存在');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '取消查询失败');
    }
  };
  const previewRequest = async () => {
    if (!catalog.id) return;
    try {
      const values = await form.validateFields();
      const response = await previewCatalogSingle(catalog.id, buildRequest(values));
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response));
      setPreview(response.data);
      setError(undefined);
    } catch (error) {
      if ((error as { errorFields?: unknown[] })?.errorFields) return;
      setError(readQueryError(error, '无法生成安全查询预览'));
    }
  };
  return (
    <div className="lake-data-preview">
      <Form
        name="lake-single-query"
        form={form}
        layout="vertical"
        disabled={submitting}
        initialValues={{ limit: 10, explain: false }}
        onFinish={submit}
        onValuesChange={() => {
          setResult(undefined);
          setPreview(undefined);
          setError(undefined);
        }}
      >
        <CatalogTablePicker
          form={form}
          catalogId={catalog.id}
          refreshKey={refreshKey}
          advancedOptions={
            <>
              <Space wrap align="start">
                <Form.Item name="limit" label="预览行数">
                  <InputNumber min={1} max={100} />
                </Form.Item>
                <Form.Item name="explain" label="查询方式" valuePropName="checked">
                  <Checkbox>仅查看执行计划</Checkbox>
                </Form.Item>
              </Space>
              <Button onClick={() => void previewRequest()}>查看生成的 SQL</Button>
              <QueryPreview preview={preview} />
            </>
          }
        />
        <Space wrap className="lake-query-actions">
          <Button type="primary" htmlType="submit" loading={submitting} disabled={!selectedColumns?.length}>
            {explainOnly ? '查看执行计划' : '预览数据'}
          </Button>
          {submitting ? (
            <Button disabled={false} onClick={() => void cancel()}>
              取消查询
            </Button>
          ) : null}
          <Typography.Text type="secondary">
            {explainOnly ? '仅查看查询执行计划' : `预览 ${previewLimit || 10} 行，仅查询源端数据`}
          </Typography.Text>
        </Space>
      </Form>
      <QueryResult result={result} loading={submitting} error={error} />
    </div>
  );
};

interface JoinValues {
  leftCatalog: string;
  leftDatabase: string;
  leftTable: string;
  leftColumns: string[];
  leftJoinColumn: string;
  rightCatalog: string;
  rightDatabase: string;
  rightTable: string;
  rightColumns: string[];
  rightJoinColumn: string;
  limit?: number;
  explain?: boolean;
  joinType?: 'INNER' | 'LEFT';
}

const JoinQuery: React.FC<{
  catalog: LakeCatalog;
  catalogs: LakeCatalog[];
  refreshKey: number;
}> = ({ catalog, catalogs, refreshKey }) => {
  const [form] = Form.useForm<JoinValues>();
  const [result, setResult] = useState<DisplayQueryResult>();
  const [preview, setPreview] = useState<LakeReadOnlyQueryPreview>();
  const [error, setError] = useState<QueryError>();
  const [submitting, setSubmitting] = useState(false);
  const [activeQueryId, setActiveQueryId] = useState<string>();
  const [leftCatalogId, setLeftCatalogId] = useState<number | undefined>(catalog.id);
  const [leftCatalogName, setLeftCatalogName] = useState(catalog.targetCatalogName);
  const [rightCatalogId, setRightCatalogId] = useState<number>();
  const [rightCatalogName, setRightCatalogName] = useState<string>();
  const [sources, setSources] = useState<LakePhysicalDataSource[]>([]);
  useEffect(() => {
    let active = true;
    void fetchPhysicalSources({ pageNo: 1, pageSize: 100 })
      .then((response) => {
        if (active && response.code === 0) {
          const rows = Array.isArray(response.data) ? response.data : response.data?.bizData || [];
          setSources(rows);
        }
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, []);
  const leftFields = Form.useWatch('leftColumns', form) as string[] | undefined;
  const rightFields = Form.useWatch('rightColumns', form) as string[] | undefined;
  const catalogOptions = catalogs
    .filter((item) => item.id && item.targetCatalogName && item.resourceStatus === 'READY')
    .map((item) => ({
      value: item.targetCatalogName as string,
      label:
        sources.find((source) => source.sourceDataSourceId === item.sourceDataSourceId)?.sourceDataSourceName ||
        adapterLabel(item.adapter) + ' · ' + catalogScopeLabel(item),
    }));
  useEffect(() => {
    form.setFieldValue('leftCatalog', leftCatalogName);
  }, [form, leftCatalogName]);
  useEffect(() => {
    form.setFieldValue('rightCatalog', rightCatalogName);
  }, [form, rightCatalogName]);
  const selectCatalog = (side: 'left' | 'right', name: string) => {
    const selected = catalogs.find((item) => item.targetCatalogName === name);
    if (!selected?.targetCatalogName) return;
    const prefix = side === 'left' ? 'left' : 'right';
    form.setFieldsValue({
      [`${prefix}Catalog`]: selected.targetCatalogName,
      [`${prefix}Database`]: undefined,
      [`${prefix}Table`]: undefined,
      [`${prefix}Columns`]: undefined,
      [`${prefix}JoinColumn`]: undefined,
    });
    if (side === 'left') {
      setLeftCatalogId(selected.id);
      setLeftCatalogName(selected.targetCatalogName);
    } else {
      setRightCatalogId(selected.id);
      setRightCatalogName(selected.targetCatalogName);
    }
  };
  const buildRequest = (values: JoinValues) => {
    const leftIdentity = {
      catalog: values.leftCatalog,
      database: values.leftDatabase,
      table: values.leftTable,
    };
    const rightIdentity = {
      catalog: values.rightCatalog,
      database: values.rightDatabase,
      table: values.rightTable,
    };
    return {
      leftTable: leftIdentity,
      rightTable: rightIdentity,
      leftColumns: values.leftColumns.map((column) => ({
        table: leftIdentity,
        column,
      })),
      rightColumns: values.rightColumns.map((column) => ({
        table: rightIdentity,
        column,
      })),
      leftJoinColumn: { table: leftIdentity, column: values.leftJoinColumn },
      rightJoinColumn: { table: rightIdentity, column: values.rightJoinColumn },
      limit: values.limit || 10,
      explain: Boolean(values.explain),
      joinType: values.joinType || 'INNER',
    };
  };
  const submit = async (values: JoinValues) => {
    const queryId = newQueryId();
    setSubmitting(true);
    setActiveQueryId(queryId);
    setError(undefined);
    try {
      const response = await queryCatalogJoin({
        ...buildRequest(values),
        queryId,
      });
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response));
      setResult(labelQueryResult(response.data, values.leftColumns, values.rightColumns));
      setPreview(undefined);
      message.success(values.explain ? '关联查询执行计划已生成' : '关联数据预览已完成');
    } catch (error) {
      setResult(undefined);
      setError(readQueryError(error, '关联查询失败'));
    } finally {
      setSubmitting(false);
      setActiveQueryId(undefined);
    }
  };
  const cancel = async () => {
    if (!activeQueryId) return;
    try {
      const response = await cancelCatalogQuery(activeQueryId);
      if (response.code !== 0) throw new Error(responseMessage(response));
      message.info(response.data ? '已请求取消查询' : '查询已结束或不存在');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '取消查询失败');
    }
  };
  const previewRequest = async () => {
    try {
      const values = await form.validateFields();
      const response = await previewCatalogJoin(buildRequest(values));
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response));
      setPreview(response.data);
      setError(undefined);
    } catch (error) {
      if ((error as { errorFields?: unknown[] })?.errorFields) return;
      setError(readQueryError(error, '无法生成关联查询预览'));
    }
  };
  if (catalogOptions.length < 2)
    return (
      <Alert
        type="info"
        showIcon
        message="联合查询需要两个可查询的挂载"
        description="请先创建另一个数据源的挂载，或收起此区域返回单表预览。"
      />
    );
  return (
    <div className="lake-join-query">
      <Typography.Paragraph type="secondary">选择另一侧挂载与关联字段，即可预览两张表的匹配数据。</Typography.Paragraph>
      <Form
        name="lake-join-query"
        form={form}
        layout="vertical"
        disabled={submitting}
        initialValues={{
          leftCatalog: catalog.targetCatalogName,
          limit: 10,
          joinType: 'INNER',
          explain: false,
        }}
        onFinish={submit}
        onValuesChange={() => {
          setResult(undefined);
          setPreview(undefined);
          setError(undefined);
        }}
      >
        <div className="lake-join-sides">
          <Card size="small" title="左表" className="lake-join-side">
            <Form.Item name="leftCatalog" label="左侧挂载" rules={[{ required: true, message: '请选择左侧挂载' }]}>
              <Select
                showSearch
                options={catalogOptions}
                onChange={(name) => selectCatalog('left', name)}
                placeholder="选择可查询的挂载"
              />
            </Form.Item>
            <CatalogTablePicker
              form={form}
              catalogId={leftCatalogId}
              prefix="left"
              includeJoinKey
              refreshKey={refreshKey}
            />
          </Card>
          <Card size="small" title="右表" className="lake-join-side">
            <Form.Item name="rightCatalog" label="右侧挂载" rules={[{ required: true, message: '请选择右侧挂载' }]}>
              <Select
                showSearch
                options={catalogOptions.filter((item) => item.value !== leftCatalogName)}
                onChange={(name) => selectCatalog('right', name)}
                placeholder="选择另一个数据源的挂载"
              />
            </Form.Item>
            <CatalogTablePicker
              form={form}
              catalogId={rightCatalogId}
              prefix="right"
              includeJoinKey
              refreshKey={refreshKey}
            />
          </Card>
        </div>
        <Collapse
          ghost
          items={[
            {
              key: 'options',
              label: '关联查询选项（默认匹配两表数据，预览 10 行）',
              forceRender: true,
              children: (
                <>
                  <Space wrap align="start">
                    <Form.Item name="joinType" label="关联方式">
                      <Select
                        style={{ width: 240 }}
                        options={[
                          { label: '仅显示两边匹配的数据', value: 'INNER' },
                          { label: '保留左表全部数据', value: 'LEFT' },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item name="limit" label="预览行数">
                      <InputNumber min={1} max={100} />
                    </Form.Item>
                    <Form.Item name="explain" label="查询方式" valuePropName="checked">
                      <Checkbox>仅查看执行计划</Checkbox>
                    </Form.Item>
                  </Space>
                  <Button onClick={() => void previewRequest()}>查看生成的 SQL</Button>
                </>
              ),
            },
          ]}
        />
        <Space wrap className="lake-query-actions">
          <Button
            htmlType="submit"
            type="primary"
            loading={submitting}
            disabled={!leftFields?.length || !rightFields?.length}
          >
            预览关联数据
          </Button>
          {submitting ? (
            <Button disabled={false} onClick={() => void cancel()}>
              取消查询
            </Button>
          ) : null}
        </Space>
      </Form>
      <QueryPreview preview={preview} />
      <QueryResult result={result} loading={submitting} error={error} />
    </div>
  );
};

interface CatalogUpdateValues {
  targetCatalogName: string;
  adapter: string;
  scope: string;
  databaseInclude?: string[];
  tableInclude?: string[];
}

const CatalogUpdateDrawer: React.FC<{
  catalog: LakeCatalog;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}> = ({ catalog, open, onClose, onSaved }) => {
  const [form] = Form.useForm<CatalogUpdateValues>();
  const [saving, setSaving] = useState(false);
  const submit = async (values: CatalogUpdateValues) => {
    if (!catalog.id || catalog.lockVersion == null) return;
    setSaving(true);
    try {
      const response = await updateCatalog(catalog.id, {
        ...values,
        databaseInclude: values.databaseInclude || [],
        tableInclude: values.tableInclude || [],
        options: {},
        expectedLockVersion: catalog.lockVersion,
      });
      if (response.code !== 0) throw new Error(responseMessage(response));
      message.success('逻辑挂载配置已更新并验证');
      onClose();
      onSaved();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '更新逻辑挂载失败');
    } finally {
      setSaving(false);
    }
  };
  return (
    <Drawer
      open={open}
      title="调整挂载范围"
      width={760}
      destroyOnHidden
      onClose={onClose}
      footer={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={saving} onClick={() => form.submit()}>
            保存并验证
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">勾选需要通过数据湖查询的库表，保存时自动检查配置。</Typography.Paragraph>
      <Form
        name="lake-catalog-update"
        form={form}
        layout="vertical"
        onFinish={submit}
        onFinishFailed={({ errorFields }) => message.warning(errorFields[0]?.errors[0] || '请完善挂载范围')}
        initialValues={{
          targetCatalogName: catalog.targetCatalogName,
          adapter: catalog.adapter,
          scope: catalog.scope,
          databaseInclude: catalog.databaseInclude || [],
          tableInclude: catalog.tableInclude || [],
        }}
        style={{ marginTop: 18 }}
      >
        <Form.Item name="targetCatalogName" hidden>
          <Input />
        </Form.Item>
        <Form.Item name="adapter" hidden>
          <Input />
        </Form.Item>
        <CatalogScopePicker form={form} sourceId={catalog.sourceDataSourceId} adapter={catalog.adapter} />
      </Form>
    </Drawer>
  );
};

const LogicalCatalogDetail: React.FC = () => {
  const { catalogId } = useParams<{ catalogId?: string }>();
  const [catalog, setCatalog] = useState<LakeCatalog>();
  const [source, setSource] = useState<LakePhysicalDataSource>();
  const [catalogs, setCatalogs] = useState<LakeCatalog[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string>();
  const [updateOpen, setUpdateOpen] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const [joinOpen, setJoinOpen] = useState(false);
  const [operations, setOperations] = useState<LakeResourceOperation[]>([]);
  const load = async () => {
    if (!catalogId) return;
    try {
      const response = await fetchCatalog(catalogId);
      if (response.code !== 0) throw new Error(responseMessage(response));
      setCatalog(response.data);
      if (response.data?.sourceDataSourceId) {
        void fetchPhysicalSource(response.data.sourceDataSourceId)
          .then((result) => {
            if (result.code === 0) setSource(result.data);
          })
          .catch(() => undefined);
      }
      if (response.data?.id) {
        void Promise.all([
          fetchLakeOperations('EXTERNAL_CATALOG_BINDING', response.data.id),
          fetchLakeOperations('READONLY_QUERY', response.data.id),
        ])
          .then(([bindingOperations, queryOperations]) => {
            const rows = [
              ...(bindingOperations.code === 0 ? bindingOperations.data || [] : []),
              ...(queryOperations.code === 0 ? queryOperations.data || [] : []),
            ];
            rows.sort(
              (left, right) => new Date(right.startedAt || '').getTime() - new Date(left.startedAt || '').getTime(),
            );
            setOperations(rows);
          })
          .catch(() => undefined);
      }
      setCatalogs(response.data ? [response.data] : []);
      void fetchCatalogs({ pageNo: 1, pageSize: 100 })
        .then((catalogResponse) => {
          if (catalogResponse.code === 0) {
            const rows = Array.isArray(catalogResponse.data)
              ? catalogResponse.data
              : catalogResponse.data?.bizData || [];
            setCatalogs(rows.length ? rows : response.data ? [response.data] : []);
          }
        })
        .catch(() => undefined);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '加载挂载失败');
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    setLoading(true);
    setCatalog(undefined);
    setSource(undefined);
    setJoinOpen(false);
    setOperations([]);
    void load();
  }, [catalogId]);
  const execute = async (name: string, action: (id: string) => Promise<LakeApiResponse<LakeCatalog>>) => {
    if (!catalogId) return;
    setActionLoading(name);
    try {
      const response = await action(catalogId);
      if (response.code !== 0) throw new Error(responseMessage(response));
      message.success(name + '完成');
      if (name === '移除挂载') {
        history.push('/lake/logical-access');
        return;
      }
      // Keep the preview form mounted while updating status, so selections are not lost.
      await load();
      if (name === '刷新库表') setRefreshKey((value) => value + 1);
    } catch (error) {
      message.error(error instanceof Error ? error.message : name + '失败');
    } finally {
      setActionLoading(undefined);
    }
  };
  const confirmDelete = () => {
    Modal.confirm({
      title: '移除此挂载？',
      content: '移除后将无法通过此挂载查询数据，源数据库中的数据会保留。',
      okText: '确认移除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => execute('移除挂载', deleteCatalog),
    });
  };
  if (loading)
    return (
      <PageContainer title={false} className="lake-logical-detail">
        <div className="lake-detail-loading">
          <Spin />
          正在加载挂载…
        </div>
      </PageContainer>
    );
  if (!catalog)
    return (
      <PageContainer title={false}>
        <Empty description="未找到此挂载">
          <Button onClick={() => history.push('/lake/logical-access')}>返回挂载列表</Button>
        </Empty>
      </PageContainer>
    );
  const busy = Boolean(actionLoading) || catalog.resourceStatus === 'DELETING';
  const unavailable = Boolean(catalog.deleted) || catalog.resourceStatus !== 'READY';
  const sourceName = source?.sourceDataSourceName || '数据源 #' + catalog.sourceDataSourceId;
  const errorHints: Record<string, string> = {
    LAKE_DORIS_UNAVAILABLE: '暂时无法确认挂载状态，请检查数据湖连接，或通过“更多操作”重新检查挂载。',
    LAKE_CATALOG_VALIDATION_FAILED: '挂载配置与实际状态有差异，请调整挂载范围后重新保存。',
    LAKE_CATALOG_REQUEST_INVALID: '挂载配置不完整，请检查数据源连接与挂载范围。',
    LAKE_OPERATION_STALE: '挂载状态已更新，请重新检查后重试。',
  };
  const operationNames: Record<string, string> = {
    CREATE_CATALOG: '创建挂载',
    UPDATE_CATALOG: '调整挂载范围',
    VALIDATE_CATALOG: '检查挂载',
    VALIDATE: '检查挂载',
    REFRESH_CATALOG: '刷新库表',
    DROP_CATALOG: '移除挂载',
    RECONCILE: '同步挂载状态',
    DELETE: '移除挂载',
    READONLY_QUERY: '预览数据',
  };
  const operationStatuses: Record<string, string> = {
    PENDING: '等待处理',
    RUNNING: '处理中',
    SUCCEEDED: '已完成',
    FAILED: '未完成',
    IGNORED: '已跳过',
  };
  const tabs = [
    {
      key: 'data',
      label: '数据预览',
      children: unavailable ? (
        <Alert
          type="warning"
          showIcon
          message="此挂载暂不可查询"
          description="请先检查挂载状态，或调整范围后重新保存。"
        />
      ) : (
        <>
          <div className="lake-single-query" hidden={joinOpen}>
            <SingleTableQuery key={catalog.id} catalog={catalog} refreshKey={refreshKey} />
          </div>
          <Collapse
            ghost
            className={joinOpen ? 'lake-advanced-feature is-open' : 'lake-advanced-feature'}
            activeKey={joinOpen ? ['join'] : []}
            onChange={(keys) => setJoinOpen(Array.isArray(keys) ? keys.includes('join') : keys === 'join')}
            items={[
              {
                key: 'join',
                label: '跨源联合查询（高级）',
                children: <JoinQuery catalog={catalog} catalogs={catalogs} refreshKey={refreshKey} />,
              },
            ]}
          />
        </>
      ),
    },
    {
      key: 'info',
      label: '挂载信息',
      children: (
        <div className="lake-mount-info">
          <Descriptions bordered column={2}>
            <Descriptions.Item label="数据来源">{sourceName}</Descriptions.Item>
            <Descriptions.Item label="数据库类型">{adapterLabel(catalog.adapter)}</Descriptions.Item>
            <Descriptions.Item label="挂载范围" span={2}>
              {catalogScopeLabel(catalog)}
            </Descriptions.Item>
            {catalog.scope === 'TABLE' ? (
              <Descriptions.Item label="已挂载数据表" span={2}>
                {catalog.tableInclude?.join('、') || '未选择'}
              </Descriptions.Item>
            ) : null}
            <Descriptions.Item label="查询状态">
              <Tag color={statusColor(catalog.resourceStatus)}>{catalogStatusLabel(catalog.resourceStatus)}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="配置检查">
              <Tag color={statusColor(catalog.validationStatus)}>{catalogStatusLabel(catalog.validationStatus)}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="最近检查" span={2}>
              {catalog.lastObservedAt || '尚未检查'}
            </Descriptions.Item>
          </Descriptions>
          <Typography.Paragraph type="secondary" className="lake-info-note">
            源端新增或变更库表后，可点击“刷新库表”更新。挂载范围包含整库或整个 Schema 时，新增表会自动纳入。
          </Typography.Paragraph>
          <Collapse
            ghost
            items={[
              {
                key: 'technical',
                label: '技术信息（排查问题时查看）',
                children: (
                  <>
                    <Descriptions size="small" bordered column={2}>
                      <Descriptions.Item label="挂载标识">{catalog.targetCatalogName}</Descriptions.Item>
                      <Descriptions.Item label="数据源编号">{catalog.sourceDataSourceId}</Descriptions.Item>
                      <Descriptions.Item label="配置版本">{catalog.lockVersion}</Descriptions.Item>
                      <Descriptions.Item label="最近状态同步">
                        {catalog.lastReconcileAt || '尚未同步'}
                      </Descriptions.Item>
                    </Descriptions>
                    {catalog.errorCode ? (
                      <Typography.Paragraph>
                        错误信息：{catalog.errorCode} · {catalog.errorMessage}
                      </Typography.Paragraph>
                    ) : null}
                    <Collapse
                      ghost
                      items={[
                        {
                          key: 'snapshot',
                          label: '查看脱敏配置快照',
                          children: (
                            <pre className="lake-snapshot">{JSON.stringify(catalog.actualSnapshot || {}, null, 2)}</pre>
                          ),
                        },
                      ]}
                    />
                  </>
                ),
              },
            ]}
          />
        </div>
      ),
    },
    {
      key: 'operations',
      label: '操作记录',
      children: (
        <OperationTimeline
          items={operations.map((operation) => ({
            title:
              (operationNames[operation.operationType || ''] || '挂载操作') +
              ' · ' +
              (operationStatuses[operation.status || ''] || '待确认'),
            description: [
              operation.finishedAt || operation.startedAt,
              operation.status === 'FAILED' ? '操作未完成，可重新检查挂载或查看技术信息。' : undefined,
            ]
              .filter(Boolean)
              .join(' · '),
            status: operation.status === 'FAILED' ? 'error' : operation.status === 'SUCCEEDED' ? 'finish' : 'wait',
          }))}
          emptyText="暂无挂载操作记录"
        />
      ),
    },
  ];
  return (
    <PageContainer title={false} className="lake-logical-detail">
      <div className="lake-detail-toolbar">
        <div className="lake-detail-identity">
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            aria-label="返回挂载列表"
            onClick={() => history.push('/lake/logical-access')}
          />
          <Typography.Text strong ellipsis={{ tooltip: sourceName }}>
            {sourceName}
          </Typography.Text>
          <Tag color={statusColor(catalog.resourceStatus)}>{catalogStatusLabel(catalog.resourceStatus)}</Tag>
        </div>
        <Space wrap>
          <Button
            icon={<EditOutlined />}
            aria-label="调整挂载范围"
            disabled={busy || Boolean(catalog.deleted)}
            onClick={() => setUpdateOpen(true)}
          >
            调整挂载范围
          </Button>
          <Tooltip title="重新读取源端库表，更新当前可查询的数据表">
            <Button
              icon={<ReloadOutlined />}
              aria-label="刷新库表"
              loading={actionLoading === '刷新库表'}
              disabled={busy || Boolean(catalog.deleted)}
              onClick={() => void execute('刷新库表', refreshCatalog)}
            >
              刷新库表
            </Button>
          </Tooltip>
          <Dropdown
            trigger={['click']}
            menu={{
              items: [
                {
                  key: 'validate',
                  label: '重新检查挂载',
                  icon: <CheckOutlined />,
                  onClick: () => void execute('检查挂载', validateCatalog),
                },
                {
                  key: 'reconcile',
                  label: '同步挂载状态',
                  icon: <SyncOutlined />,
                  onClick: () => void execute('同步挂载状态', reconcileCatalog),
                },
                { type: 'divider' },
                {
                  key: 'remove',
                  label: '移除挂载',
                  danger: true,
                  icon: <DeleteOutlined />,
                  onClick: confirmDelete,
                },
              ],
            }}
            disabled={busy || Boolean(catalog.deleted)}
          >
            <Button
              loading={Boolean(actionLoading) && actionLoading !== '刷新库表'}
              aria-label="更多操作"
              aria-haspopup="menu"
            >
              更多操作 <DownOutlined />
            </Button>
          </Dropdown>
        </Space>
      </div>
      {catalog.errorCode ? (
        <Alert
          type="warning"
          showIcon
          message="挂载需要检查"
          description={errorHints[catalog.errorCode] || '请重新检查挂载，或在挂载信息中查看处理线索。'}
        />
      ) : null}
      <Tabs defaultActiveKey="data" items={tabs} className="lake-detail-tabs" />
      <CatalogUpdateDrawer
        key={String(catalog.id) + ':' + catalog.lockVersion}
        catalog={catalog}
        open={updateOpen}
        onClose={() => setUpdateOpen(false)}
        onSaved={() => {
          void load();
          setRefreshKey((value) => value + 1);
        }}
      />
    </PageContainer>
  );
};

export default LogicalCatalogDetail;
