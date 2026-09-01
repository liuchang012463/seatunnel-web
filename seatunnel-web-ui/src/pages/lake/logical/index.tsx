import {
  CheckCircleOutlined,
  CloudServerOutlined,
  PlusOutlined,
  ReloadOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { Button, Card, Drawer, Form, Input, InputNumber, message, Select, Space, Tag, Typography } from 'antd';
import { history } from '@umijs/max';
import React, { useRef, useState } from 'react';
import {
  createCatalog,
  fetchCatalogCapability,
  fetchCatalogs,
  normalizeLakePage,
} from '@/services/lake';
import type { LakeApiResponse, LakeCatalog, LakeCatalogScope, LakeCapability } from '@/services/lake';
import './index.less';

const adapterOptions = [
  { label: 'MySQL', value: 'MYSQL' },
  { label: 'PostgreSQL', value: 'POSTGRESQL' },
  { label: 'Oracle', value: 'ORACLE' },
];
const scopeOptions = [
  { label: '全部资源', value: 'ALL' },
  { label: '指定数据库', value: 'DATABASE' },
  { label: '指定表', value: 'TABLE' },
];

const responseMessage = (response: LakeApiResponse<unknown>) => response.msg || response.message || '请求失败，请稍后重试';
const statusColor = (status?: string) => {
  if (status === 'READY' || status === 'VALID' || status === 'CONSISTENT') return 'success';
  if (status === 'ERROR' || status === 'FAILED' || status === 'INVALID') return 'error';
  if (status === 'CREATING' || status === 'PENDING_CREATE' || status === 'RUNNING') return 'processing';
  return 'default';
};

interface CapabilityFormValues {
  sourceDataSourceId?: number;
  adapter?: string;
  scope?: LakeCatalogScope;
}

interface CatalogFormValues {
  lakeDataSourceId?: number;
  sourceDataSourceId?: number;
  targetCatalogName: string;
  adapter: string;
  scope: LakeCatalogScope;
  databaseInclude?: string[];
  tableInclude?: string[];
}

const CapabilityCard: React.FC = () => {
  const [form] = Form.useForm<CapabilityFormValues>();
  const [loading, setLoading] = useState(false);
  const [capability, setCapability] = useState<LakeCapability & Record<string, unknown>>();

  const checkCapability = async (values: CapabilityFormValues) => {
    if (!values.sourceDataSourceId) return;
    setLoading(true);
    try {
      const response = await fetchCatalogCapability(values.sourceDataSourceId, {
        adapter: values.adapter,
        scope: values.scope,
      });
      if (response.code !== 0) throw new Error(responseMessage(response));
      setCapability((response.data || {}) as LakeCapability & Record<string, unknown>);
    } catch (error) {
      setCapability(undefined);
      message.error(error instanceof Error ? error.message : '能力检查失败');
    } finally {
      setLoading(false);
    }
  };

  const supported = capability?.logicalSupported === true || capability?.supported === true || capability?.enabled === true;
  const reasons = capability?.reasonCodes || capability?.disabledReasons || [];
  return (
    <Card
      className="lake-capability-card"
      title={<Space><CloudServerOutlined />逻辑入湖能力</Space>}
      extra={<Typography.Text type="secondary">能力检查只读，不会创建挂载</Typography.Text>}
    >
      <Form form={form} layout="inline" onFinish={checkCapability} initialValues={{ adapter: 'MYSQL', scope: 'ALL' }}>
        <Form.Item name="sourceDataSourceId" label="源数据源" rules={[{ required: true, message: '请输入数据源 ID' }]}>
          <InputNumber min={1} placeholder="DataSource ID" />
        </Form.Item>
        <Form.Item name="adapter" label="Adapter"><Select options={adapterOptions} style={{ width: 150 }} /></Form.Item>
        <Form.Item name="scope" label="Scope"><Select options={scopeOptions} style={{ width: 150 }} /></Form.Item>
        <Button type="primary" htmlType="submit" loading={loading}>检查能力</Button>
      </Form>
      {capability ? (
        <div className={`lake-capability-result ${supported ? 'is-supported' : 'is-disabled'}`}>
          {supported ? <CheckCircleOutlined /> : <WarningOutlined />}
          <Typography.Text strong>{supported ? '当前支持逻辑挂载' : '当前不可用'}</Typography.Text>
          {capability.adapter ? <Tag>{String(capability.adapter)}</Tag> : null}
          {'scope' in capability && capability.scope ? <Tag>{String(capability.scope)}</Tag> : null}
          {!supported && reasons.length ? <Typography.Text type="secondary">原因：{reasons.join('、')}</Typography.Text> : null}
          {capability.sourceNetworkReachabilityKnown === false ? <Typography.Text type="secondary">源端网络尚未探查</Typography.Text> : null}
        </div>
      ) : (
        <Typography.Text type="secondary">请输入源数据源 ID 后检查能力；不可用时会展示稳定原因。</Typography.Text>
      )}
    </Card>
  );
};

const CreateCatalogDrawer: React.FC<{ open: boolean; onClose: () => void; onCreated: () => void }> = ({
  open,
  onClose,
  onCreated,
}) => {
  const [form] = Form.useForm<CatalogFormValues>();
  const [loading, setLoading] = useState(false);
  const submit = async (values: CatalogFormValues) => {
    setLoading(true);
    try {
      const response = await createCatalog({
        ...values,
        databaseInclude: values.databaseInclude || [],
        tableInclude: values.tableInclude || [],
        options: {},
      });
      if (response.code !== 0) throw new Error(responseMessage(response));
      message.success('逻辑挂载已创建');
      form.resetFields();
      onClose();
      onCreated();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '创建逻辑挂载失败');
    } finally {
      setLoading(false);
    }
  };
  return (
    <Drawer
      open={open}
      title="创建逻辑挂载"
      width={520}
      destroyOnClose
      onClose={onClose}
      footer={<Space><Button onClick={onClose}>取消</Button><Button type="primary" loading={loading} onClick={() => form.submit()}>创建并验证</Button></Space>}
    >
      <Typography.Paragraph type="secondary">
        仅填写数据源引用和结构化范围。凭证、JDBC URL、驱动地址由服务端安全配置管理，页面不会显示或上传。
      </Typography.Paragraph>
      <Form form={form} layout="vertical" onFinish={submit} initialValues={{ adapter: 'MYSQL', scope: 'ALL' }}>
        <Form.Item name="sourceDataSourceId" label="源数据源 ID" rules={[{ required: true, message: '请输入源数据源 ID' }]}>
          <InputNumber min={1} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="lakeDataSourceId" label="湖 Doris 数据源 ID" rules={[{ required: true, message: '请输入湖数据源 ID' }]}>
          <InputNumber min={1} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="targetCatalogName" label="Catalog 名称" rules={[{ required: true, max: 128, message: '请输入 128 字符以内的名称' }]}>
          <Input maxLength={128} />
        </Form.Item>
        <Space align="start" style={{ width: '100%' }}>
          <Form.Item name="adapter" label="Adapter" rules={[{ required: true }]}><Select options={adapterOptions} style={{ width: 210 }} /></Form.Item>
          <Form.Item name="scope" label="Scope" rules={[{ required: true }]}><Select options={scopeOptions} style={{ width: 210 }} /></Form.Item>
        </Space>
        <Form.Item name="databaseInclude" label="数据库范围" extra="Scope 为 DATABASE/TABLE 时填写，可输入后回车">
          <Select mode="tags" tokenSeparators={[',']} placeholder="例如：业务库" />
        </Form.Item>
        <Form.Item name="tableInclude" label="表范围" extra="Scope 为 TABLE 时填写，可输入后回车">
          <Select mode="tags" tokenSeparators={[',']} placeholder="例如：schema.table" />
        </Form.Item>
      </Form>
    </Drawer>
  );
};

const LogicalAccessPage: React.FC = () => {
  const actionRef = useRef<ActionType>();
  const [createOpen, setCreateOpen] = useState(false);
  const columns: ProColumns<LakeCatalog>[] = [
    { title: 'Catalog', dataIndex: 'targetCatalogName', ellipsis: true },
    { title: '源数据源', dataIndex: 'sourceDataSourceId', width: 110 },
    { title: 'Adapter', dataIndex: 'adapter', width: 120 },
    { title: 'Scope', dataIndex: 'scope', width: 120 },
    { title: 'Resource', dataIndex: 'resourceStatus', width: 120, render: (_, row) => <Tag color={statusColor(row.resourceStatus)}>{row.resourceStatus || 'UNKNOWN'}</Tag> },
    { title: 'Validation', dataIndex: 'validationStatus', width: 130, render: (_, row) => <Tag color={statusColor(row.validationStatus)}>{row.validationStatus || 'UNKNOWN'}</Tag> },
    { title: '最近验证', dataIndex: 'lastObservedAt', valueType: 'dateTime', width: 170 },
    {
      title: '操作', valueType: 'option', width: 110,
      render: (_, row) => row.id ? <Button type="link" onClick={() => history.push(`/lake/logical-access/${row.id}`)}>查看详情</Button> : null,
    },
  ];
  return (
    <PageContainer title="逻辑入湖" subTitle="管理源数据的 Doris 逻辑挂载与结构化验证">
      <CapabilityCard />
      <ProTable<LakeCatalog>
        className="lake-catalog-table"
        rowKey={(row) => String(row.id || `${row.sourceDataSourceId}-${row.targetCatalogName}`)}
        actionRef={actionRef}
        columns={columns}
        search={{ labelWidth: 'auto' }}
        pagination={{ defaultPageSize: 10, showSizeChanger: true }}
        toolBarRender={() => [
          <Button key="create" type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>创建挂载</Button>,
          <Button key="refresh" icon={<ReloadOutlined />} onClick={() => actionRef.current?.reloadAndRest?.()}>刷新</Button>,
        ]}
        request={async (params) => {
          const response = await fetchCatalogs({
            pageNo: Number(params.current || 1),
            pageSize: Number(params.pageSize || 10),
            targetCatalogName: params.targetCatalogName,
            sourceDataSourceId: params.sourceDataSourceId,
            adapter: params.adapter,
            resourceStatus: params.resourceStatus,
            validationStatus: params.validationStatus,
          });
          if (response.code !== 0) throw new Error(responseMessage(response));
          const page = normalizeLakePage(response.data);
          return { data: page.data, success: true, total: page.total };
        }}
      />
      <CreateCatalogDrawer open={createOpen} onClose={() => setCreateOpen(false)} onCreated={() => actionRef.current?.reloadAndRest?.()} />
    </PageContainer>
  );
};

export default LogicalAccessPage;
