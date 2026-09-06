import {
  CheckCircleOutlined,
  CloudServerOutlined,
  PlusOutlined,
  ReloadOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { Alert, Button, Card, Drawer, Empty, Form, Input, message, Select, Space, Tag, Typography } from 'antd';
import { history } from '@umijs/max';
import { useLocation } from '@umijs/max';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  createCatalog,
  fetchCatalogCapability,
  fetchCatalogs,
  fetchPhysicalSources,
  normalizeLakePage,
  probeCatalogCapability,
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
  { label: '全部库表', value: 'ALL' },
  { label: '指定数据库', value: 'DATABASE' },
  { label: '指定表', value: 'TABLE' },
];

const optionLabel = (options: Array<{ label: string; value: string }>, value?: string) =>
  options.find((option) => option.value === value)?.label || value || '未设置';

const statusLabel = (status?: string) => {
  const labels: Record<string, string> = {
    READY: '就绪',
    VALID: '已通过',
    CONSISTENT: '一致',
    ERROR: '错误',
    FAILED: '失败',
    CREATE_FAILED: '创建失败',
    INVALID: '未通过',
    MISSING: '远端缺失',
    CREATING: '创建中',
    PENDING_CREATE: '等待创建',
    RUNNING: '运行中',
    DELETING: '删除中',
    DELETED: '已删除',
    UNKNOWN: '状态未知',
  };
  return labels[status || ''] || '未知';
};

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
  label: `${source.sourceDataSourceName || `DataSource #${source.sourceDataSourceId}`} · ${source.dbType || '未知类型'} · ${source.unitCode || '未归属'}/${source.systemCode || '未归属'}`,
});

const adapterForDbType = (dbType?: string): string | undefined => {
  const normalized = String(dbType || '').toUpperCase();
  if (normalized.includes('POSTGRES')) return 'POSTGRESQL';
  if (normalized.includes('ORACLE')) return 'ORACLE';
  if (normalized.includes('MYSQL')) return 'MYSQL';
  return undefined;
};

const CapabilityCard: React.FC<{
  sources: LakePhysicalDataSource[];
  initialSourceId?: number;
  onSourceChange?: (sourceDataSourceId?: number) => void;
}> = ({ sources, initialSourceId, onSourceChange }) => {
  const [form] = Form.useForm<CapabilityFormValues>();
  const [loading, setLoading] = useState(false);
  const [probeLoading, setProbeLoading] = useState(false);
  const [capability, setCapability] = useState<LakeLogicalCapability>();
  const sourceDataSourceId = Form.useWatch('sourceDataSourceId', form);
  const adapter = Form.useWatch('adapter', form);
  const scope = Form.useWatch('scope', form);

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

  const probeCapability = async () => {
    if (!sourceDataSourceId) {
      message.warning('请先选择数据源');
      return;
    }
    setProbeLoading(true);
    try {
      const response = await probeCatalogCapability(sourceDataSourceId, { adapter, scope });
      if (response.code !== 0) throw new Error(responseMessage(response));
      setCapability(response.data);
      message.success(response.data?.sourceNetworkReachable ? '湖侧已确认源端可达' : '湖侧未能连接源端');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '源端探查失败');
    } finally {
      setProbeLoading(false);
    }
  };

  const supported = capability?.logicalSupported === true || capability?.supported === true || capability?.enabled === true;
  const reasons = capability?.reasonCodes?.length ? capability.reasonCodes : capability?.disabledReasons || [];
  const sourceNetworkPending = !supported
    && capability?.lakeDorisReachable === true
    && reasons.length === 1
    && reasons[0] === 'SOURCE_NETWORK_UNKNOWN';
  const sourceNetworkLabel = !capability?.sourceNetworkReachabilityKnown
    ? '尚未探查'
    : capability.sourceNetworkReachable ? '可达' : '不可达';
  const capabilityLabel = supported
    ? '当前支持逻辑挂载'
    : sourceNetworkPending
      ? '静态条件就绪，创建时验证源端网络'
      : '当前不可用';
  return (
    <Card
      className="lake-capability-card"
      title={<Space><CloudServerOutlined />挂载前检查</Space>}
      extra={<Typography.Text type="secondary">先检查条件，需要时探查源端网络</Typography.Text>}
    >
      <Form form={form} layout="inline" onFinish={checkCapability} initialValues={{ adapter: 'MYSQL', scope: 'ALL', sourceDataSourceId: initialSourceId }}>
        <Form.Item name="sourceDataSourceId" label="数据源" rules={[{ required: true, message: '请选择数据源' }]}>
          <Select
            showSearch
            allowClear
            options={sources.map(sourceOption)}
            placeholder="选择已有数据源"
            optionFilterProp="label"
            style={{ width: 360, minWidth: 0 }}
            onChange={(value) => {
              setCapability(undefined);
              onSourceChange?.(value);
            }}
          />
        </Form.Item>
        <Form.Item name="adapter" label="数据库类型"><Select options={adapterOptions} style={{ width: 130 }} onChange={() => setCapability(undefined)} /></Form.Item>
        <Form.Item name="scope" label="挂载范围"><Select options={scopeOptions} style={{ width: 130 }} onChange={() => setCapability(undefined)} /></Form.Item>
        <Space>
          <Button type="primary" htmlType="submit" loading={loading}>检查条件</Button>
          <Button
            onClick={probeCapability}
            loading={probeLoading}
            disabled={!sourceDataSourceId || capability?.lakeDorisReachable === false}
          >
            {capability?.sourceNetworkReachabilityKnown ? '重新探查源端' : '从湖侧探查源端'}
          </Button>
        </Space>
      </Form>
      {capability ? (
        <div className={`lake-capability-result ${supported ? 'is-supported' : sourceNetworkPending ? 'is-pending' : 'is-disabled'}`}>
          {supported ? <CheckCircleOutlined /> : <WarningOutlined />}
          <Typography.Text strong>{capabilityLabel}</Typography.Text>
          <Typography.Text type="secondary">源端网络：{sourceNetworkLabel}</Typography.Text>
          {capability.adapter ? <Tag>{optionLabel(adapterOptions, String(capability.adapter))}</Tag> : null}
          {capability.scope ? <Tag>{optionLabel(scopeOptions, String(capability.scope))}</Tag> : null}
          {!supported && !sourceNetworkPending ? <CapabilityReason reasons={reasons} /> : null}
          {sourceNetworkPending ? <CapabilityReason reasons={reasons} /> : null}
        </div>
      ) : (
        <Typography.Text type="secondary">操作顺序：选择数据源 → 检查条件 →（需要时）探查源端 → 创建挂载。</Typography.Text>
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
  const [capabilityLoading, setCapabilityLoading] = useState(false);
  const [probeLoading, setProbeLoading] = useState(false);
  const [capability, setCapability] = useState<LakeLogicalCapability>();
  const [capabilityError, setCapabilityError] = useState<string>();
  const sourceDataSourceId = Form.useWatch('sourceDataSourceId', form);
  const adapter = Form.useWatch('adapter', form);
  const scope = Form.useWatch('scope', form);
  const selectedSource = sources.find((source) => source.sourceDataSourceId === sourceDataSourceId);
  const sourceAdapter = adapterForDbType(selectedSource?.dbType);

  useEffect(() => {
    if (!open) return;
    form.setFieldsValue({
      sourceDataSourceId: initialSourceId,
      adapter: sourceAdapter || 'MYSQL',
      scope: 'ALL',
    });
    setCapability(undefined);
    setCapabilityError(undefined);
  }, [form, initialSourceId, open, sourceAdapter]);

  useEffect(() => {
    if (!open || !sourceDataSourceId || !adapter || !scope) return;
    let cancelled = false;
    setCapabilityLoading(true);
    setCapabilityError(undefined);
    void fetchCatalogCapability(sourceDataSourceId, { adapter, scope })
      .then((response) => {
        if (cancelled) return;
        if (response.code !== 0 || !response.data) throw new Error(responseMessage(response));
        setCapability(response.data);
      })
      .catch((error) => {
        if (!cancelled) {
          setCapability(undefined);
          setCapabilityError(error instanceof Error ? error.message : '能力检查失败');
        }
      })
      .finally(() => {
        if (!cancelled) setCapabilityLoading(false);
      });
    return () => { cancelled = true; };
  }, [adapter, open, scope, sourceDataSourceId]);

  const supported = capability?.logicalSupported === true || capability?.supported === true || capability?.enabled === true;
  const reasons = capability?.reasonCodes?.length ? capability.reasonCodes : capability?.disabledReasons || [];
  const sourceNetworkPending = !supported && capability?.lakeDorisReachable === true && reasons.length === 1 && reasons[0] === 'SOURCE_NETWORK_UNKNOWN';
  const canAttempt = supported || sourceNetworkPending;
  const probe = async () => {
    if (!sourceDataSourceId || !adapter || !scope) return;
    setProbeLoading(true);
    try {
      const response = await probeCatalogCapability(sourceDataSourceId, { adapter, scope });
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response));
      setCapability(response.data);
      message.success(response.data.sourceNetworkReachable ? '湖侧已确认源端可达' : '湖侧未能连接源端');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '源端探查失败');
    } finally {
      setProbeLoading(false);
    }
  };
  const submit = async (values: CatalogFormValues) => {
    if (!canAttempt) {
      message.warning(sourceNetworkPending ? '源端网络将在创建时验证，请稍候重试' : '当前数据源不满足逻辑挂载条件');
      return;
    }
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
      footer={<Space><Button onClick={onClose}>取消</Button><Button type="primary" loading={loading} disabled={capabilityLoading || !canAttempt} onClick={() => form.submit()}>创建并验证</Button></Space>}
    >
      <Typography.Paragraph type="secondary">
        选择数据源和挂载范围即可。凭证、JDBC 地址和驱动由服务端安全配置管理，页面不会显示或上传敏感信息。
      </Typography.Paragraph>
      {capabilityLoading ? <Alert type="info" showIcon message="正在检查数据源能力…" /> : null}
      {capabilityError ? <Alert type="error" showIcon message="能力检查失败" description={capabilityError} /> : null}
      {!capabilityLoading && !capabilityError && capability ? <Alert
        type={canAttempt ? 'warning' : 'error'}
        showIcon
        message={canAttempt ? (sourceNetworkPending ? '静态条件就绪，建议先探查源端网络' : '当前支持逻辑挂载') : '当前不可创建逻辑挂载'}
        description={canAttempt ? undefined : <CapabilityReason reasons={reasons} fallback="服务端未返回可用原因" />}
        action={sourceNetworkPending ? <Button size="small" loading={probeLoading} onClick={probe}>从湖侧探查</Button> : undefined}
      /> : null}
      <Form form={form} layout="vertical" onFinish={submit} initialValues={{ adapter: 'MYSQL', scope: 'ALL', sourceDataSourceId: initialSourceId }}>
        <Form.Item name="sourceDataSourceId" label="数据源" rules={[{ required: true, message: '请选择数据源' }]}>
          <Select showSearch options={sources.map(sourceOption)} placeholder="选择已有数据源" optionFilterProp="label" />
        </Form.Item>
        <Form.Item name="targetCatalogName" label="挂载名称" rules={[{ required: true, max: 128, message: '请输入 128 字符以内的名称' }]}>
          <Input maxLength={128} />
        </Form.Item>
        <Space align="start" style={{ width: '100%' }}>
          <Form.Item name="adapter" label="数据库类型" rules={[{ required: true }]}><Select options={adapterOptions} style={{ width: 210 }} /></Form.Item>
          <Form.Item name="scope" label="挂载范围" rules={[{ required: true }]}><Select options={scopeOptions} style={{ width: 210 }} /></Form.Item>
        </Space>
        <Form.Item name="databaseInclude" label="数据库范围" extra="选择指定数据库或指定表时填写，可输入后回车">
          <Select mode="tags" tokenSeparators={[',']} placeholder="例如：业务库" />
        </Form.Item>
        <Form.Item name="tableInclude" label="表范围" extra="选择指定表时填写，可输入后回车；只填表名，例如 orders">
          <Select mode="tags" tokenSeparators={[',']} placeholder="例如：orders" />
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
  const [selectedSourceId, setSelectedSourceId] = useState<number | undefined>(initialSourceId);

  useEffect(() => {
    setSelectedSourceId(initialSourceId);
  }, [initialSourceId]);

  useEffect(() => {
    void fetchPhysicalSources({ pageNo: 1, pageSize: 100 }).then((response) => {
      if (response.code === 0) setSources(normalizeLakePage(response.data).data);
    }).catch(() => undefined).finally(() => setSourcesLoading(false));
  }, []);
  const sourceLabelById = useMemo(
    () => new Map(sources.map((source) => [source.sourceDataSourceId, sourceOption(source).label])),
    [sources],
  );
  const columns: ProColumns<LakeCatalog>[] = [
    { title: '挂载名称', dataIndex: 'targetCatalogName', ellipsis: true },
    {
      title: '数据源',
      dataIndex: 'sourceDataSourceId',
      width: 260,
      ellipsis: true,
      render: (value) => sourceLabelById.get(Number(value)) || `数据源 #${value || '未知'}`,
    },
    { title: '数据库类型', dataIndex: 'adapter', width: 120, render: (value) => optionLabel(adapterOptions, String(value || '')) },
    { title: '挂载范围', dataIndex: 'scope', width: 120, render: (value) => optionLabel(scopeOptions, String(value || '')) },
    { title: '挂载状态', dataIndex: 'resourceStatus', width: 120, render: (_, row) => <Tag color={statusColor(row.resourceStatus)}>{statusLabel(row.resourceStatus)}</Tag> },
    { title: '核验状态', dataIndex: 'validationStatus', width: 130, render: (_, row) => <Tag color={statusColor(row.validationStatus)}>{statusLabel(row.validationStatus)}</Tag> },
    { title: '最近验证', dataIndex: 'lastObservedAt', valueType: 'dateTime', width: 170 },
    {
      title: '操作', valueType: 'option', width: 110,
      render: (_, row) => row.id ? <Button type="link" onClick={() => history.push(`/lake/logical-access/${row.id}`)}>查看详情</Button> : null,
    },
  ];
  return (
    <PageContainer title="逻辑入湖" subTitle="选择已有数据源，按步骤完成 Doris 逻辑挂载">
      <CapabilityCard sources={sources} initialSourceId={initialSourceId} onSourceChange={setSelectedSourceId} />
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
      <CreateCatalogDrawer open={createOpen} onClose={() => setCreateOpen(false)} onCreated={() => actionRef.current?.reloadAndRest?.()} sources={sources} initialSourceId={selectedSourceId} />
    </PageContainer>
  );
};

export default LogicalAccessPage;
