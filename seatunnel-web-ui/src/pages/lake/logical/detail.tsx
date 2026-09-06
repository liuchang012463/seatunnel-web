import {
  CheckOutlined,
  DeleteOutlined,
  EyeOutlined,
  EditOutlined,
  ReloadOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Button, Card, Checkbox, Descriptions, Drawer, Empty, Form, Input, InputNumber, message, Modal, Select, Space, Spin, Table, Tabs, Tag, Typography } from 'antd';
import type { FormInstance } from 'antd';
import { history, useParams } from '@umijs/max';
import React, { useEffect, useMemo, useState } from 'react';
import {
  fetchCatalog,
  fetchLakeOperations,
  fetchCatalogQueryColumns,
  fetchCatalogQueryDatabases,
  fetchCatalogQueryTables,
  fetchCatalogs,
  deleteCatalog,
  previewCatalogJoin,
  previewCatalogSingle,
  reconcileCatalog,
  refreshCatalog,
  queryCatalogJoin,
  queryCatalogSingle,
  cancelCatalogQuery,
  updateCatalog,
  validateCatalog,
} from '@/services/lake';
import type { LakeApiResponse, LakeCatalog, LakeQueryColumnOption, LakeReadOnlyQueryPreview, LakeReadOnlyQueryResult, LakeResourceOperation } from '@/services/lake';
import { LakeErrorAlert, LakeResourceStatusTag, OperationTimeline, operationToStep } from '../components/LakeStatus';
import './index.less';

const responseMessage = (response: LakeApiResponse<unknown>) => response.msg || response.message || '请求失败，请稍后重试';
const statusColor = (status?: string) => status === 'READY' || status === 'VALID' ? 'success' : status === 'ERROR' || status === 'INVALID' ? 'error' : 'default';
const toColumns = (columns: string[]) => columns.map((name) => ({ title: name, dataIndex: name, key: name, ellipsis: true }));

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

const QueryResult: React.FC<{ result?: LakeReadOnlyQueryResult; loading?: boolean; error?: QueryError }> = ({ result, loading, error }) => {
  if (loading) return <Card size="small"><Spin tip="正在执行只读查询" /></Card>;
  if (error) return <Card size="small"><Alert type="error" showIcon message={queryStatusLabel(error.code)} description={error.message} /></Card>;
  if (!result) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="填写结构化条件并执行查询" />;
  return (
    <Card size="small" title={<Space><EyeOutlined />结果</Space>} extra={<Typography.Text type="secondary">{result.rowCount} 行 · {result.elapsedMillis} ms{result.truncated ? ' · 已截断' : ''}</Typography.Text>}>
      <Table
        size="small"
        rowKey={(_, index) => String(index)}
        scroll={{ x: 'max-content' }}
        columns={toColumns(result.columns || [])}
        dataSource={result.rows || []}
        pagination={false}
      />
    </Card>
  );
};

const QueryPreview: React.FC<{ preview?: LakeReadOnlyQueryPreview }> = ({ preview }) => preview ? (
  <Card size="small" title="SQL 预览" className="lake-query-preview">
    <pre>{preview.sql}</pre>
    <Typography.Text type="secondary">服务端已按只读规则生成；提交执行时不会接受或回传页面编辑的 SQL。</Typography.Text>
  </Card>
) : null;

interface CatalogTablePickerProps {
  form: FormInstance;
  catalogId?: number;
  catalogName?: string;
  prefix?: 'left' | 'right';
  includeJoinKey?: boolean;
}

const CatalogTablePicker: React.FC<CatalogTablePickerProps> = ({ form, catalogId, catalogName, prefix, includeJoinKey }) => {
  const field = (name: string) => prefix ? `${prefix}${name[0].toUpperCase()}${name.slice(1)}` : name;
  const databaseField = field('database');
  const tableField = field('table');
  const columnsField = field('columns');
  const joinKeyField = field('joinColumn');
  const database = Form.useWatch(databaseField, form) as string | undefined;
  const table = Form.useWatch(tableField, form) as string | undefined;
  const [databases, setDatabases] = useState<string[]>([]);
  const [tables, setTables] = useState<string[]>([]);
  const [columns, setColumns] = useState<LakeQueryColumnOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [metadataError, setMetadataError] = useState<string>();

  useEffect(() => {
    form.setFieldsValue({ [databaseField]: undefined, [tableField]: undefined, [columnsField]: undefined, [joinKeyField]: undefined });
    setDatabases([]);
    setTables([]);
    setColumns([]);
    if (!catalogId) return;
    setLoading(true);
    setMetadataError(undefined);
    void fetchCatalogQueryDatabases(catalogId)
      .then((response) => {
        if (response.code !== 0) throw new Error(response.message || response.msg || 'Catalog 数据库读取失败');
        setDatabases(response.data || []);
      })
      .catch((error) => setMetadataError(readQueryError(error, 'Catalog 数据库读取失败').message))
      .finally(() => setLoading(false));
  }, [catalogId, databaseField, tableField, columnsField, joinKeyField, form]);

  useEffect(() => {
    form.setFieldsValue({ [tableField]: undefined, [columnsField]: undefined, [joinKeyField]: undefined });
    setTables([]);
    setColumns([]);
    if (!catalogId || !database) return;
    setLoading(true);
    setMetadataError(undefined);
    void fetchCatalogQueryTables(catalogId, database)
      .then((response) => {
        if (response.code !== 0) throw new Error(response.message || response.msg || 'Catalog 表读取失败');
        setTables(response.data || []);
      })
      .catch((error) => setMetadataError(readQueryError(error, 'Catalog 表读取失败').message))
      .finally(() => setLoading(false));
  }, [catalogId, database, tableField, columnsField, joinKeyField, form]);

  useEffect(() => {
    form.setFieldsValue({ [columnsField]: undefined, [joinKeyField]: undefined });
    setColumns([]);
    if (!catalogId || !database || !table) return;
    setLoading(true);
    setMetadataError(undefined);
    void fetchCatalogQueryColumns(catalogId, database, table)
      .then((response) => {
        if (response.code !== 0) throw new Error(response.message || response.msg || 'Catalog 字段读取失败');
        setColumns(response.data || []);
      })
      .catch((error) => setMetadataError(readQueryError(error, 'Catalog 字段读取失败').message))
      .finally(() => setLoading(false));
  }, [catalogId, database, table, columnsField, joinKeyField, form]);

  const options = columns.map((column) => ({
    value: column.name,
    label: `${column.name}${column.type ? ` · ${column.type}` : ''}${column.selectable === false ? `（${column.reason || '不可查询'}）` : ''}`,
    disabled: column.selectable === false,
  }));
  return <div className="lake-query-picker">
    <Typography.Text type="secondary">{catalogName || '未选择 Catalog'} · 服务端实时读取可用对象</Typography.Text>
    {metadataError ? <Alert type="warning" showIcon message="查询元数据暂不可用" description={metadataError} /> : null}
    <Space align="start" wrap>
      <Form.Item name={databaseField} label="数据库" rules={[{ required: true, message: '请选择数据库' }]}>
        <Select showSearch allowClear loading={loading && !databases.length} options={databases.map((value) => ({ value, label: value }))} placeholder="选择数据库" optionFilterProp="label" style={{ minWidth: 190 }} disabled={!catalogId} />
      </Form.Item>
      <Form.Item name={tableField} label="表" rules={[{ required: true, message: '请选择表' }]}>
        <Select showSearch allowClear loading={loading && !!database && !tables.length} options={tables.map((value) => ({ value, label: value }))} placeholder="选择表" optionFilterProp="label" style={{ minWidth: 190 }} disabled={!database} />
      </Form.Item>
      <Form.Item name={columnsField} label="返回字段" rules={[{ required: true, message: '至少选择一个字段' }]}>
        <Select mode="multiple" allowClear maxTagCount="responsive" options={options} placeholder="选择字段" optionFilterProp="label" style={{ minWidth: 260 }} disabled={!table} />
      </Form.Item>
      {includeJoinKey ? <Form.Item name={joinKeyField} label="等值 Join Key" rules={[{ required: true, message: '请选择 Join Key' }]}>
        <Select showSearch options={options} placeholder="选择 Join Key" optionFilterProp="label" style={{ minWidth: 180 }} disabled={!table} />
      </Form.Item> : null}
    </Space>
  </div>;
};

const SingleTableQuery: React.FC<{ catalog: LakeCatalog }> = ({ catalog }) => {
  const [form] = Form.useForm();
  const [result, setResult] = useState<LakeReadOnlyQueryResult>();
  const [preview, setPreview] = useState<LakeReadOnlyQueryPreview>();
  const [error, setError] = useState<QueryError>();
  const [submitting, setSubmitting] = useState(false);
  const [activeQueryId, setActiveQueryId] = useState<string>();
  const buildRequest = (values: { database?: string; table?: string; columns?: string[]; limit?: number; explain?: boolean }) => {
    const identity = { catalog: catalog.targetCatalogName || '', database: values.database || '', table: values.table || '' };
    return {
      table: identity,
      selectedColumns: (values.columns || []).map((column) => ({ table: identity, column })),
      limit: values.limit || 10,
      explain: Boolean(values.explain),
    };
  };
  const submit = async (values: { database?: string; table?: string; columns?: string[]; limit?: number; explain?: boolean }) => {
    if (!catalog.id) return;
    const queryId = newQueryId();
    setSubmitting(true);
    setActiveQueryId(queryId);
    setError(undefined);
    try {
      const response = await queryCatalogSingle(catalog.id, { ...buildRequest(values), queryId });
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response));
      setResult(response.data);
      setPreview(undefined);
      message.success(values.explain ? 'EXPLAIN 已完成' : '只读查询已完成');
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
      setError(readQueryError(error, '无法生成安全查询预览'));
    }
  };
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Alert type="info" showIcon message="结构化只读验证" description="Catalog 由当前挂载固定；服务端只接受字段选择、LIMIT 和 EXPLAIN，不接受任意 SQL，也不会写入源库。" />
    <Typography.Paragraph type="secondary">Catalog：{catalog.targetCatalogName || '-'}。数据库、表和字段来自 Doris Catalog 元数据；敏感或不支持类型会直接禁用。</Typography.Paragraph>
    <Form form={form} layout="vertical" initialValues={{ limit: 10 }} onFinish={submit}>
      <CatalogTablePicker form={form} catalogId={catalog.id} catalogName={catalog.targetCatalogName} />
      <Space wrap>
      <Form.Item name="limit" label="Limit"><InputNumber min={1} max={100} /></Form.Item>
      <Form.Item name="explain" valuePropName="checked"><Checkbox>EXPLAIN</Checkbox></Form.Item>
      <Button onClick={() => void previewRequest()}>生成 SQL 预览</Button>
      <Button type="primary" htmlType="submit" loading={submitting}>执行只读验证</Button>
      {submitting ? <Button danger onClick={() => void cancel()}>取消查询</Button> : null}
      </Space>
    </Form>
    <QueryPreview preview={preview} />
    <QueryResult result={result} loading={submitting} error={error} />
  </Space>;
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

const JoinQuery: React.FC<{ catalog: LakeCatalog; catalogs: LakeCatalog[] }> = ({ catalog, catalogs }) => {
  const [form] = Form.useForm<JoinValues>();
  const [result, setResult] = useState<LakeReadOnlyQueryResult>();
  const [preview, setPreview] = useState<LakeReadOnlyQueryPreview>();
  const [error, setError] = useState<QueryError>();
  const [submitting, setSubmitting] = useState(false);
  const [activeQueryId, setActiveQueryId] = useState<string>();
  const [leftCatalogId, setLeftCatalogId] = useState<number | undefined>(catalog.id);
  const [leftCatalogName, setLeftCatalogName] = useState(catalog.targetCatalogName);
  const [rightCatalogId, setRightCatalogId] = useState<number>();
  const [rightCatalogName, setRightCatalogName] = useState<string>();
  const catalogOptions = catalogs.filter((item) => item.id && item.targetCatalogName && item.resourceStatus === 'READY').map((item) => ({ value: item.targetCatalogName as string, label: item.targetCatalogName as string }));
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
    form.setFieldsValue({ [`${prefix}Catalog`]: selected.targetCatalogName, [`${prefix}Database`]: undefined, [`${prefix}Table`]: undefined, [`${prefix}Columns`]: undefined, [`${prefix}JoinColumn`]: undefined });
    if (side === 'left') { setLeftCatalogId(selected.id); setLeftCatalogName(selected.targetCatalogName); } else { setRightCatalogId(selected.id); setRightCatalogName(selected.targetCatalogName); }
  };
  const buildRequest = (values: JoinValues) => {
    const leftIdentity = { catalog: values.leftCatalog, database: values.leftDatabase, table: values.leftTable };
    const rightIdentity = { catalog: values.rightCatalog, database: values.rightDatabase, table: values.rightTable };
    return {
      leftTable: leftIdentity,
      rightTable: rightIdentity,
      leftColumns: values.leftColumns.map((column) => ({ table: leftIdentity, column })),
      rightColumns: values.rightColumns.map((column) => ({ table: rightIdentity, column })),
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
      const response = await queryCatalogJoin({ ...buildRequest(values), queryId });
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response));
      setResult(response.data);
      setPreview(undefined);
      message.success(values.explain ? 'JOIN EXPLAIN 已完成' : 'JOIN 只读验证已完成');
    } catch (error) {
      setResult(undefined);
      setError(readQueryError(error, 'JOIN 查询失败'));
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
      setError(readQueryError(error, '无法生成安全 JOIN 预览'));
    }
  };
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Alert type="info" showIcon message="结构化跨 Catalog JOIN" description="仅支持两个已就绪 Catalog 的等值 JOIN；字段选择和 LIMIT 由服务端校验，结果仅用于小数据量验证。" />
    <Typography.Paragraph type="secondary">两侧 Catalog、数据库、表和字段均来自已就绪挂载；只支持 INNER / LEFT 等值 Join，不提供任意 SQL 编辑。</Typography.Paragraph>
    <Form form={form} layout="vertical" initialValues={{ leftCatalog: catalog.targetCatalogName, limit: 10, joinType: 'INNER' }} onFinish={submit}>
      <Space align="start" wrap>
        <Card size="small" title="左表" className="lake-join-side"><Form.Item name="leftCatalog" label="Catalog" rules={[{ required: true, message: '请选择左侧 Catalog' }]}><Select showSearch options={catalogOptions} onChange={(name) => selectCatalog('left', name)} placeholder="选择已就绪 Catalog" /></Form.Item><CatalogTablePicker form={form} catalogId={leftCatalogId} catalogName={leftCatalogName} prefix="left" includeJoinKey /></Card>
        <Card size="small" title="右表" className="lake-join-side"><Form.Item name="rightCatalog" label="Catalog" rules={[{ required: true, message: '请选择右侧 Catalog' }]}><Select showSearch options={catalogOptions.filter((item) => item.value !== leftCatalogName)} onChange={(name) => selectCatalog('right', name)} placeholder="选择另一个 Catalog" /></Form.Item><CatalogTablePicker form={form} catalogId={rightCatalogId} catalogName={rightCatalogName} prefix="right" includeJoinKey /></Card>
      </Space>
      <Space style={{ marginTop: 12 }} wrap><Form.Item name="joinType" label="Join 类型"><Select style={{ width: 120 }} options={[{ label: 'INNER', value: 'INNER' }, { label: 'LEFT', value: 'LEFT' }]} /></Form.Item><Form.Item name="limit" label="Limit"><InputNumber min={1} max={100} /></Form.Item><Form.Item name="explain" valuePropName="checked"><Checkbox>EXPLAIN</Checkbox></Form.Item><Button onClick={() => void previewRequest()}>生成 SQL 预览</Button><Button htmlType="submit" type="primary" loading={submitting}>执行 JOIN 验证</Button>{submitting ? <Button danger onClick={() => void cancel()}>取消查询</Button> : null}</Space>
    </Form>
    <QueryPreview preview={preview} />
    <QueryResult result={result} loading={submitting} error={error} />
  </Space>;
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
      title="更新逻辑挂载"
      width={520}
      destroyOnHidden
      onClose={onClose}
      footer={<Space><Button onClick={onClose}>取消</Button><Button type="primary" loading={saving} onClick={() => form.submit()}>保存并验证</Button></Space>}
    >
      <Alert type="info" showIcon message="只更新挂载配置，不会修改源数据" description="Catalog 名称是稳定标识，不能在此处重命名；凭证与 Driver 仍由服务端安全配置管理。" />
      <Form
        form={form}
        layout="vertical"
        onFinish={submit}
        initialValues={{
          targetCatalogName: catalog.targetCatalogName,
          adapter: catalog.adapter,
          scope: catalog.scope,
          databaseInclude: catalog.databaseInclude || [],
          tableInclude: catalog.tableInclude || [],
        }}
        style={{ marginTop: 18 }}
      >
        <Form.Item name="targetCatalogName" label="Catalog 名称"><Input disabled /></Form.Item>
        <Space align="start" style={{ width: '100%' }}>
          <Form.Item name="adapter" label="Adapter" rules={[{ required: true }]}><Select options={[{ label: 'MySQL', value: 'MYSQL' }, { label: 'PostgreSQL', value: 'POSTGRESQL' }, { label: 'Oracle', value: 'ORACLE' }]} style={{ width: 210 }} /></Form.Item>
          <Form.Item name="scope" label="Scope" rules={[{ required: true }]}><Select options={[{ label: '全部资源', value: 'ALL' }, { label: '指定数据库', value: 'DATABASE' }, { label: '指定表', value: 'TABLE' }]} style={{ width: 210 }} /></Form.Item>
        </Space>
        <Form.Item name="databaseInclude" label="数据库范围"><Select mode="tags" tokenSeparators={[',']} placeholder="Scope 为 DATABASE/TABLE 时填写" /></Form.Item>
        <Form.Item name="tableInclude" label="表范围"><Select mode="tags" tokenSeparators={[',']} placeholder="Scope 为 TABLE 时填写" /></Form.Item>
      </Form>
    </Drawer>
  );
};

const LogicalCatalogDetail: React.FC = () => {
  const { catalogId } = useParams<{ catalogId?: string }>();
  const [catalog, setCatalog] = useState<LakeCatalog>();
  const [catalogs, setCatalogs] = useState<LakeCatalog[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string>();
  const [updateOpen, setUpdateOpen] = useState(false);
  const [operations, setOperations] = useState<LakeResourceOperation[]>([]);
  const load = async () => {
    if (!catalogId) return;
    setOperations([]);
    setLoading(true);
    try {
      const response = await fetchCatalog(catalogId);
      if (response.code !== 0) throw new Error(responseMessage(response));
      setCatalog(response.data);
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
            rows.sort((left, right) => {
              const leftTime = new Date(left.startedAt || '').getTime();
              const rightTime = new Date(right.startedAt || '').getTime();
              return rightTime - leftTime;
            });
            setOperations(rows);
          })
          .catch(() => undefined);
      }
      setCatalogs(response.data ? [response.data] : []);
      void fetchCatalogs({ pageNo: 1, pageSize: 100 }).then((catalogResponse) => {
        if (catalogResponse.code === 0) {
          const rows = Array.isArray(catalogResponse.data) ? catalogResponse.data : catalogResponse.data?.bizData || [];
          setCatalogs(rows.length ? rows : response.data ? [response.data] : []);
        }
      }).catch(() => undefined);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '加载 Catalog 失败');
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { load(); }, [catalogId]);
  const execute = async (name: string, action: (id: string) => Promise<LakeApiResponse<LakeCatalog>>) => {
    if (!catalogId) return;
    setActionLoading(name);
    try {
      const response = await action(catalogId);
      if (response.code !== 0) throw new Error(responseMessage(response));
      message.success(`${name}完成`);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : `${name}失败`);
    } finally {
      setActionLoading(undefined);
    }
  };
  const confirmDelete = () => {
    if (!catalogId || !catalog) return;
    Modal.confirm({
      title: '删除逻辑挂载？',
      content: '该操作仅删除 Doris 中的逻辑挂载关系，不会删除源数据库数据。',
      okText: '确认删除挂载',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => execute('删除', deleteCatalog),
    });
  };
  const tabs = useMemo(() => catalog ? [
    { key: 'base', label: '基本信息', children: <Descriptions bordered column={{ xs: 1, sm: 2 }}><Descriptions.Item label="Catalog">{catalog.targetCatalogName || '-'}</Descriptions.Item><Descriptions.Item label="源数据源">{catalog.sourceDataSourceId || '-'}</Descriptions.Item><Descriptions.Item label="Adapter">{catalog.adapter || '-'}</Descriptions.Item><Descriptions.Item label="Scope">{catalog.scope || '-'}</Descriptions.Item><Descriptions.Item label="Resource"><LakeResourceStatusTag status={catalog.resourceStatus} /></Descriptions.Item><Descriptions.Item label="Validation"><Tag color={statusColor(catalog.validationStatus)}>{catalog.validationStatus || 'UNKNOWN'}</Tag></Descriptions.Item><Descriptions.Item label="最近观察">{catalog.lastObservedAt || '-'}</Descriptions.Item><Descriptions.Item label="最近对账">{catalog.lastReconcileAt || '-'}</Descriptions.Item><Descriptions.Item label="锁版本">{catalog.lockVersion || '-'}</Descriptions.Item><Descriptions.Item label="错误码">{catalog.errorCode || '-'}</Descriptions.Item></Descriptions> },
    { key: 'query', label: '查询验证', children: <Tabs items={[{ key: 'single', label: '单表验证', children: <SingleTableQuery catalog={catalog} /> }, { key: 'join', label: '双 Catalog JOIN', children: <JoinQuery catalog={catalog} catalogs={catalogs} /> }]} /> },
    { key: 'snapshot', label: '挂载资源', children: <Card><Typography.Text type="secondary">服务端返回的脱敏实际快照</Typography.Text><pre className="lake-snapshot">{JSON.stringify(catalog.actualSnapshot || {}, null, 2)}</pre></Card> },
    { key: 'consistency', label: '一致性', children: <Card><Alert type={catalog.validationStatus === 'MATCH' ? 'success' : 'warning'} showIcon message={catalog.validationStatus === 'MATCH' ? '挂载配置与 Doris 实际状态一致' : '当前需要重新观察或处理漂移'} description="页面读取的是服务端最近一次显式 Validate / Refresh / Reconcile 的结果，进入详情不会自动访问 Doris。" /></Card> },
    { key: 'operations', label: '操作记录', children: <Card><OperationTimeline items={operations.map(operationToStep)} emptyText="暂无已记录的 Catalog 操作" /></Card> },
  ] : [], [catalog, catalogs, operations]);
  if (loading) return <PageContainer title="逻辑挂载详情"><Spin /></PageContainer>;
  if (!catalog) return <PageContainer title="逻辑挂载详情"><Empty description="未找到逻辑挂载" /></PageContainer>;
  const busy = Boolean(actionLoading) || catalog.resourceStatus === 'DELETING';
  return <PageContainer title={catalog.targetCatalogName || '逻辑挂载详情'} onBack={() => history.push('/lake/logical-access')} extra={<Space wrap><Button icon={<EditOutlined />} disabled={busy || Boolean(catalog.deleted)} onClick={() => setUpdateOpen(true)}>更新挂载</Button><Button icon={<CheckOutlined />} loading={actionLoading === '验证'} disabled={busy || Boolean(catalog.deleted)} onClick={() => execute('验证', validateCatalog)}>验证</Button><Button icon={<ReloadOutlined />} loading={actionLoading === 'Refresh'} disabled={busy || Boolean(catalog.deleted)} onClick={() => execute('Refresh', refreshCatalog)}>Refresh</Button><Button icon={<SyncOutlined />} loading={actionLoading === 'Reconcile'} disabled={busy || Boolean(catalog.deleted)} onClick={() => execute('Reconcile', reconcileCatalog)}>Reconcile</Button><Button danger icon={<DeleteOutlined />} loading={actionLoading === '删除'} disabled={busy || Boolean(catalog.deleted)} onClick={confirmDelete}>删除</Button></Space>}>
    <LakeErrorAlert code={catalog.errorCode} message={catalog.errorMessage} action={<Typography.Text type="secondary">可先执行 Reconcile，确认服务端观察结果后再重试。</Typography.Text>} />
    <Tabs items={tabs} />
    <CatalogUpdateDrawer catalog={catalog} open={updateOpen} onClose={() => setUpdateOpen(false)} onSaved={load} />
  </PageContainer>;
};

export default LogicalCatalogDetail;
