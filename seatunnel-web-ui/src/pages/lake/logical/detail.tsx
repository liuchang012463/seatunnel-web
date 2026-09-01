import {
  CheckOutlined,
  EyeOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Button, Card, Descriptions, Empty, Form, Input, InputNumber, message, Space, Spin, Table, Tabs, Tag, Tooltip, Typography } from 'antd';
import { history, useParams } from '@umijs/max';
import React, { useEffect, useMemo, useState } from 'react';
import {
  fetchCatalog,
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

const QUERY_API_UNAVAILABLE = '当前后端 controller 尚未暴露结构化查询 API；条件表单保留用于后续接入，暂不会发送请求。';

const SingleTableQuery: React.FC<{ catalog: LakeCatalog }> = ({ catalog }) => {
  const [form] = Form.useForm();
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Alert type="info" showIcon message="结构化单表验证暂不可用" description={QUERY_API_UNAVAILABLE} />
    <Typography.Paragraph type="secondary">Catalog：{catalog.targetCatalogName || '-'}。查询条件由 Catalog、数据库、表和字段选择组成；页面不接受任意 SQL。</Typography.Paragraph>
    <Form form={form} layout="inline" initialValues={{ limit: 10 }}>
      <Form.Item name="database" label="数据库"><Input placeholder="database" disabled /></Form.Item>
      <Form.Item name="table" label="表"><Input placeholder="table" disabled /></Form.Item>
      <Form.Item name="columns" label="字段"><Input placeholder="id,name（逗号分隔）" disabled /></Form.Item>
      <Form.Item name="limit" label="Limit"><InputNumber min={1} max={100} disabled /></Form.Item>
      <Tooltip title={QUERY_API_UNAVAILABLE}><span><Button type="primary" disabled>EXPLAIN / 执行</Button></span></Tooltip>
    </Form>
    <QueryResult />
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
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Alert type="info" showIcon message="结构化 JOIN 验证暂不可用" description={QUERY_API_UNAVAILABLE} />
    <Typography.Paragraph type="secondary">Catalog：{catalog.targetCatalogName || '-'}。仅支持两个 Catalog 的 INNER/LEFT 等值 Join Key；本页只生成结构化请求。</Typography.Paragraph>
    <Form form={form} layout="vertical" initialValues={{ limit: 10 }}>
      <Space align="start" wrap>
        <Card size="small" title="左表"><Form.Item name="leftDatabase" label="数据库"><Input disabled /></Form.Item><Form.Item name="leftTable" label="表"><Input disabled /></Form.Item><Form.Item name="leftColumn" label="Join Key"><Input disabled /></Form.Item></Card>
        <Card size="small" title="右表"><Form.Item name="rightDatabase" label="数据库"><Input disabled /></Form.Item><Form.Item name="rightTable" label="表"><Input disabled /></Form.Item><Form.Item name="rightColumn" label="Join Key"><Input disabled /></Form.Item></Card>
      </Space>
      <Space style={{ marginTop: 12 }}><Form.Item name="limit" label="Limit"><InputNumber min={1} max={100} disabled /></Form.Item><Tooltip title={QUERY_API_UNAVAILABLE}><span><Button disabled>预览 / EXPLAIN</Button></span></Tooltip><Tooltip title={QUERY_API_UNAVAILABLE}><span><Button type="primary" disabled>执行 JOIN</Button></span></Tooltip></Space>
    </Form>
    <QueryResult />
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
  const tabs = useMemo(() => catalog ? [
    { key: 'base', label: '基本信息', children: <Descriptions bordered column={2}><Descriptions.Item label="Catalog">{catalog.targetCatalogName || '-'}</Descriptions.Item><Descriptions.Item label="源数据源">{catalog.sourceDataSourceId || '-'}</Descriptions.Item><Descriptions.Item label="Adapter">{catalog.adapter || '-'}</Descriptions.Item><Descriptions.Item label="Scope">{catalog.scope || '-'}</Descriptions.Item><Descriptions.Item label="Resource"><Tag color={statusColor(catalog.resourceStatus)}>{catalog.resourceStatus || 'UNKNOWN'}</Tag></Descriptions.Item><Descriptions.Item label="Validation"><Tag color={statusColor(catalog.validationStatus)}>{catalog.validationStatus || 'UNKNOWN'}</Tag></Descriptions.Item><Descriptions.Item label="最近对账">{catalog.lastReconcileAt || '-'}</Descriptions.Item><Descriptions.Item label="错误码">{catalog.errorCode || '-'}</Descriptions.Item></Descriptions> },
    { key: 'query', label: '查询验证', children: <Tabs items={[{ key: 'single', label: '单表验证', children: <SingleTableQuery catalog={catalog} /> }, { key: 'join', label: '双 Catalog JOIN', children: <JoinQuery catalog={catalog} /> }]} /> },
    { key: 'snapshot', label: '挂载资源', children: <Card><Typography.Text type="secondary">服务端返回的脱敏实际快照</Typography.Text><pre className="lake-snapshot">{JSON.stringify(catalog.actualSnapshot || {}, null, 2)}</pre></Card> },
  ] : [], [catalog]);
  if (loading) return <PageContainer title="逻辑挂载详情"><Spin /></PageContainer>;
  if (!catalog) return <PageContainer title="逻辑挂载详情"><Empty description="未找到逻辑挂载" /></PageContainer>;
  const unsupportedActionReason = '当前后端 controller 尚未提供该操作 API，暂不可用。';
  const unsupportedButton = (label: string) => (
    <Tooltip title={unsupportedActionReason} key={label}>
      <span><Button disabled>{label}</Button></span>
    </Tooltip>
  );
  return <PageContainer title={catalog.targetCatalogName || '逻辑挂载详情'} onBack={() => history.push('/lake/logical-access')} extra={<Space><Button icon={<CheckOutlined />} loading={actionLoading === '验证'} onClick={() => execute('验证', validateCatalog)}>验证</Button>{unsupportedButton('Refresh')}{unsupportedButton('Reconcile')}{unsupportedButton('删除')}</Space>}>
    {catalog.errorCode || catalog.errorMessage ? <Card size="small" style={{ marginBottom: 16 }}><Typography.Text type="danger">{catalog.errorCode || '操作异常'}：{catalog.errorMessage || '请查看操作记录并重试'}</Typography.Text></Card> : null}
    <Tabs items={tabs} />
  </PageContainer>;
};

export default LogicalCatalogDetail;
