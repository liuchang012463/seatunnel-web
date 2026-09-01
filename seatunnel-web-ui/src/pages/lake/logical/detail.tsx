import {
  CheckOutlined,
  DeleteOutlined,
  EyeOutlined,
  ReloadOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { Button, Card, Descriptions, Empty, Form, Input, InputNumber, message, Modal, Space, Spin, Table, Tabs, Tag, Typography } from 'antd';
import { history, useParams } from '@umijs/max';
import React, { useEffect, useMemo, useState } from 'react';
import {
  deleteCatalog,
  executeJoinQuery,
  fetchCatalog,
  normalizeLakePage,
  previewJoinQuery,
  querySingleTable,
  reconcileCatalog,
  refreshCatalog,
  validateCatalog,
} from '@/services/lake';
import type { LakeApiResponse, LakeCatalog, LakeReadOnlyQueryResult } from '@/services/lake';
import './index.less';

const responseMessage = (response: LakeApiResponse<unknown>) => response.msg || response.message || '请求失败，请稍后重试';
const statusColor = (status?: string) => status === 'READY' || status === 'VALID' ? 'success' : status === 'ERROR' || status === 'INVALID' ? 'error' : 'default';
const toColumns = (columns: string[]) => columns.map((name) => ({ title: name, dataIndex: name, key: name, ellipsis: true }));

const QueryResult: React.FC<{ result?: LakeReadOnlyQueryResult }> = ({ result }) => {
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

const SingleTableQuery: React.FC<{ catalog: LakeCatalog }> = ({ catalog }) => {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<LakeReadOnlyQueryResult>();
  const run = async (values: { database: string; table: string; columns?: string; limit?: number; explain?: boolean }) => {
    setLoading(true);
    try {
      const table = { catalog: catalog.targetCatalogName || '', database: values.database, table: values.table };
      const columns = (values.columns || '').split(',').map((column) => column.trim()).filter(Boolean).map((column) => ({ table, column }));
      const response = await querySingleTable({ table, selectedColumns: columns, limit: values.limit || 10, explain: Boolean(values.explain) });
      if (response.code !== 0) throw new Error(responseMessage(response));
      setResult(response.data as unknown as LakeReadOnlyQueryResult);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '查询失败');
    } finally {
      setLoading(false);
    }
  };
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Typography.Paragraph type="secondary">查询条件由 Catalog、数据库、表和字段选择组成；页面不接受任意 SQL。</Typography.Paragraph>
    <Form form={form} layout="inline" onFinish={run} initialValues={{ limit: 10 }}>
      <Form.Item name="database" label="数据库" rules={[{ required: true }]}><Input placeholder="database" /></Form.Item>
      <Form.Item name="table" label="表" rules={[{ required: true }]}><Input placeholder="table" /></Form.Item>
      <Form.Item name="columns" label="字段"><Input placeholder="id,name（逗号分隔）" /></Form.Item>
      <Form.Item name="limit" label="Limit" rules={[{ type: 'number', min: 1, max: 100 }]}><InputNumber min={1} max={100} /></Form.Item>
      <Button type="primary" htmlType="submit" loading={loading}>EXPLAIN / 执行</Button>
    </Form>
    <QueryResult result={result} />
  </Space>;
};

interface JoinValues {
  leftDatabase: string;
  leftTable: string;
  leftColumn: string;
  rightDatabase: string;
  rightTable: string;
  rightColumn: string;
  limit?: number;
}

const JoinQuery: React.FC<{ catalog: LakeCatalog }> = ({ catalog }) => {
  const [form] = Form.useForm<JoinValues>();
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<LakeReadOnlyQueryResult>();
  const run = async (values: JoinValues, preview = false) => {
    setLoading(true);
    try {
      const leftTable = { catalog: catalog.targetCatalogName || '', database: values.leftDatabase, table: values.leftTable };
      const rightTable = { catalog: catalog.targetCatalogName || '', database: values.rightDatabase, table: values.rightTable };
      const payload = {
        leftTable,
        rightTable,
        leftColumns: [{ table: leftTable, column: values.leftColumn }],
        rightColumns: [{ table: rightTable, column: values.rightColumn }],
        leftJoinColumn: { table: leftTable, column: values.leftColumn },
        rightJoinColumn: { table: rightTable, column: values.rightColumn },
        limit: values.limit || 10,
        explain: preview,
      };
      const response = preview ? await previewJoinQuery(payload) : await executeJoinQuery(payload);
      if (response.code !== 0) throw new Error(responseMessage(response));
      if (!preview) setResult(response.data as unknown as LakeReadOnlyQueryResult);
      else message.success('结构化 JOIN 校验通过');
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'JOIN 校验失败');
    } finally {
      setLoading(false);
    }
  };
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Typography.Paragraph type="secondary">仅支持两个 Catalog 的 INNER/LEFT 等值 Join Key；本页只生成结构化请求。</Typography.Paragraph>
    <Form form={form} layout="vertical" onFinish={(values) => run(values)} initialValues={{ limit: 10 }}>
      <Space align="start" wrap>
        <Card size="small" title="左表"><Form.Item name="leftDatabase" label="数据库" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="leftTable" label="表" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="leftColumn" label="Join Key" rules={[{ required: true }]}><Input /></Form.Item></Card>
        <Card size="small" title="右表"><Form.Item name="rightDatabase" label="数据库" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="rightTable" label="表" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="rightColumn" label="Join Key" rules={[{ required: true }]}><Input /></Form.Item></Card>
      </Space>
      <Space style={{ marginTop: 12 }}><Form.Item name="limit" label="Limit"><InputNumber min={1} max={100} /></Form.Item><Button onClick={() => form.validateFields().then((values) => run(values, true))}>预览 / EXPLAIN</Button><Button type="primary" htmlType="submit" loading={loading}>执行 JOIN</Button></Space>
    </Form>
    <QueryResult result={result} />
  </Space>;
};

const LogicalCatalogDetail: React.FC = () => {
  const { catalogId } = useParams<{ catalogId?: string }>();
  const [catalog, setCatalog] = useState<LakeCatalog>();
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string>();
  const load = async () => {
    if (!catalogId) return;
    setLoading(true);
    try {
      const response = await fetchCatalog(catalogId);
      if (response.code !== 0) throw new Error(responseMessage(response));
      setCatalog(response.data);
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
  const remove = () => {
    if (!catalogId) return;
    Modal.confirm({
      title: '删除逻辑挂载？',
      icon: <DeleteOutlined />,
      okText: '确认删除',
      okType: 'danger',
      cancelText: '取消',
      content: '该操作仅删除 Doris 中的逻辑挂载关系，不会删除源数据库数据。',
      onOk: async () => {
        const response = await deleteCatalog(catalogId);
        if (response.code !== 0) throw new Error(responseMessage(response));
        message.success('逻辑挂载已删除');
        history.push('/lake/logical-access');
      },
    });
  };
  const tabs = useMemo(() => catalog ? [
    { key: 'base', label: '基本信息', children: <Descriptions bordered column={2}><Descriptions.Item label="Catalog">{catalog.targetCatalogName || '-'}</Descriptions.Item><Descriptions.Item label="源数据源">{catalog.sourceDataSourceId || '-'}</Descriptions.Item><Descriptions.Item label="Adapter">{catalog.adapter || '-'}</Descriptions.Item><Descriptions.Item label="Scope">{catalog.scope || '-'}</Descriptions.Item><Descriptions.Item label="Resource"><Tag color={statusColor(catalog.resourceStatus)}>{catalog.resourceStatus || 'UNKNOWN'}</Tag></Descriptions.Item><Descriptions.Item label="Validation"><Tag color={statusColor(catalog.validationStatus)}>{catalog.validationStatus || 'UNKNOWN'}</Tag></Descriptions.Item><Descriptions.Item label="最近对账">{catalog.lastReconcileAt || '-'}</Descriptions.Item><Descriptions.Item label="错误码">{catalog.errorCode || '-'}</Descriptions.Item></Descriptions> },
    { key: 'query', label: '查询验证', children: <Tabs items={[{ key: 'single', label: '单表验证', children: <SingleTableQuery catalog={catalog} /> }, { key: 'join', label: '双 Catalog JOIN', children: <JoinQuery catalog={catalog} /> }]} /> },
    { key: 'snapshot', label: '挂载资源', children: <Card><Typography.Text type="secondary">服务端返回的脱敏实际快照</Typography.Text><pre className="lake-snapshot">{JSON.stringify(catalog.actualSnapshot || {}, null, 2)}</pre></Card> },
  ] : [], [catalog]);
  if (loading) return <PageContainer title="逻辑挂载详情"><Spin /></PageContainer>;
  if (!catalog) return <PageContainer title="逻辑挂载详情"><Empty description="未找到逻辑挂载" /></PageContainer>;
  return <PageContainer title={catalog.targetCatalogName || '逻辑挂载详情'} onBack={() => history.push('/lake/logical-access')} extra={<Space><Button icon={<CheckOutlined />} loading={actionLoading === '验证'} onClick={() => execute('验证', validateCatalog)}>验证</Button><Button icon={<ReloadOutlined />} loading={actionLoading === '刷新'} onClick={() => execute('刷新', refreshCatalog)}>Refresh</Button><Button icon={<SyncOutlined />} loading={actionLoading === '对账'} onClick={() => execute('对账', reconcileCatalog)}>Reconcile</Button><Button danger icon={<DeleteOutlined />} onClick={remove}>删除</Button></Space>}>
    {catalog.errorCode || catalog.errorMessage ? <Card size="small" style={{ marginBottom: 16 }}><Typography.Text type="danger">{catalog.errorCode || '操作异常'}：{catalog.errorMessage || '请查看操作记录并重试'}</Typography.Text></Card> : null}
    <Tabs items={tabs} />
  </PageContainer>;
};

export default LogicalCatalogDetail;
