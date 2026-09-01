import {
  CheckCircleOutlined,
  CloudServerOutlined,
  PlusOutlined,
  ReloadOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { Button, Card, Drawer, Empty, Form, Input, message, Select, Space, Tag, Typography } from 'antd';
import { history } from '@umijs/max';
import { useLocation } from '@umijs/max';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  createCatalog,
  fetchCatalogCapability,
  fetchCatalogs,
  fetchPhysicalSources,
  normalizeLakePage,
} from '@/services/lake';
import type { LakeApiResponse, LakeCatalog, LakeCatalogScope, LakeLogicalCapability, LakePhysicalDataSource } from '@/services/lake';
import { CapabilityReason } from '../components/LakeStatus';
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
  sourceDataSourceId?: number;
  targetCatalogName: string;
  adapter: string;
  scope: LakeCatalogScope;
  databaseInclude?: string[];
  tableInclude?: string[];
}

const sourceOption = (source: LakePhysicalDataSource) => ({
  value: source.sourceDataSourceId,
  label: `${source.sourceDataSourceName || `DataSource #${source.sourceDataSourceId}`} · ${source.unitCode || '未归属'}/${source.systemCode || '未归属'}`,
});

const CapabilityCard: React.FC<{ sources: LakePhysicalDataSource[]; initialSourceId?: number }> = ({ sources, initialSourceId }) => {
  const [form] = Form.useForm<CapabilityFormValues>();
  const [loading, setLoading] = useState(false);
  const [capability, setCapability] = useState<LakeLogicalCapability>();

  const checkCapability = async (values: CapabilityFormValues) => {
    if (!values.sourceDataSourceId) return;
    setLoading(true);
    try {
      const response = await fetchCatalogCapability(values.sourceDataSourceId, {
        adapter: values.adapter,
        scope: values.scope,
      });
      if (response.code !== 0) throw new Error(responseMessage(response));
      setCapability(response.data);
    } catch (error) {
      setCapability(undefined);
      message.error(error instanceof Error ? error.message : '能力检查失败');
    } finally {
      setLoading(false);
    }
  };

  const supported = capability?.logicalSupported === true || capability?.supported === true || capability?.enabled === true;
  const reasons = capability?.reasonCodes?.length ? capability.reasonCodes : capability?.disabledReasons || [];
  const sourceNetworkPending = !supported
    && capability?.lakeDorisReachable === true
    && reasons.length === 1
    && reasons[0] === 'SOURCE_NETWORK_UNKNOWN';
  const capabilityLabel = supported
    ? '当前支持逻辑挂载'
    : sourceNetworkPending
      ? '静态条件就绪，创建时验证源端网络'
      : '当前不可用';
  return (
    <Card
      className="lake-capability-card"
      title={<Space><CloudServerOutlined />逻辑入湖能力</Space>}
      extra={<Typography.Text type="secondary">能力检查只读，不会创建挂载</Typography.Text>}
    >
      <Form form={form} layout="inline" onFinish={checkCapability} initialValues={{ adapter: 'MYSQL', scope: 'ALL', sourceDataSourceId: initialSourceId }}>
        <Form.Item name="sourceDataSourceId" label="源数据源" rules={[{ required: true, message: '请选择数据源' }]}>
          <Select showSearch allowClear options={sources.map(sourceOption)} placeholder="选择已有数据源" optionFilterProp="label" style={{ minWidth: 300 }} />
        </Form.Item>
        <Form.Item name="adapter" label="Adapter"><Select options={adapterOptions} style={{ width: 150 }} /></Form.Item>
        <Form.Item name="scope" label="Scope"><Select options={scopeOptions} style={{ width: 150 }} /></Form.Item>
        <Button type="primary" htmlType="submit" loading={loading}>检查能力</Button>
      </Form>
      {capability ? (
        <div className={`lake-capability-result ${supported ? 'is-supported' : sourceNetworkPending ? 'is-pending' : 'is-disabled'}`}>
          {supported ? <CheckCircleOutlined /> : <WarningOutlined />}
          <Typography.Text strong>{capabilityLabel}</Typography.Text>
          {capability.adapter ? <Tag>{String(capability.adapter)}</Tag> : null}
          {capability.scope ? <Tag>{String(capability.scope)}</Tag> : null}
          {!supported && !sourceNetworkPending ? <CapabilityReason reasons={reasons} /> : null}
          {sourceNetworkPending ? <CapabilityReason reasons={reasons} /> : null}
        </div>
      ) : (
        <Typography.Text type="secondary">请输入源数据源 ID 后检查能力；不可用时会展示稳定原因。</Typography.Text>
      )}
    </Card>
  );
};

const CreateCatalogDrawer: React.FC<{ open: boolean; onClose: () => void; onCreated: () => void; sources: LakePhysicalDataSource[]; initialSourceId?: number }> = ({
  open,
  onClose,
  onCreated,
  sources,
  initialSourceId,
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
      <Form form={form} layout="vertical" onFinish={submit} initialValues={{ adapter: 'MYSQL', scope: 'ALL', sourceDataSourceId: initialSourceId }}>
        <Form.Item name="sourceDataSourceId" label="源数据源" rules={[{ required: true, message: '请选择源数据源' }]}>
          <Select showSearch options={sources.map(sourceOption)} placeholder="选择已有数据源" optionFilterProp="label" />
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
  const location = useLocation();
  const initialSourceId = useMemo(() => {
    const value = new URLSearchParams(location.search).get('sourceDataSourceId');
    return value ? Number(value) : undefined;
  }, [location.search]);
  const actionRef = useRef<ActionType>();
  const [createOpen, setCreateOpen] = useState(false);
  const [sources, setSources] = useState<LakePhysicalDataSource[]>([]);
  const [sourcesLoading, setSourcesLoading] = useState(true);

  useEffect(() => {
    void fetchPhysicalSources({ pageNo: 1, pageSize: 100 }).then((response) => {
      if (response.code === 0) setSources(normalizeLakePage(response.data).data);
    }).catch(() => undefined).finally(() => setSourcesLoading(false));
  }, []);
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
      <CapabilityCard sources={sources} initialSourceId={initialSourceId} />
      <ProTable<LakeCatalog>
        className="lake-catalog-table"
        rowKey={(row) => String(row.id || `${row.sourceDataSourceId}-${row.targetCatalogName}`)}
        actionRef={actionRef}
        columns={columns}
        search={{ labelWidth: 'auto' }}
        pagination={{ defaultPageSize: 10, showSizeChanger: true }}
        toolBarRender={() => [
          <Button key="create" type="primary" icon={<PlusOutlined />} disabled={sourcesLoading || !sources.length} onClick={() => setCreateOpen(true)}>创建挂载</Button>,
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
      {!sourcesLoading && !sources.length ? <Card className="lake-logical-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可用业务数据源" /><Typography.Text type="secondary">请先在数据源管理中完成连接和 Metadata 探查。</Typography.Text></Card> : null}
      <CreateCatalogDrawer open={createOpen} onClose={() => setCreateOpen(false)} onCreated={() => actionRef.current?.reloadAndRest?.()} sources={sources} initialSourceId={initialSourceId} />
    </PageContainer>
  );
};

export default LogicalAccessPage;
