import {
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { Alert, Button, Card, Drawer, Empty, Form, Input, message, Select, Space, Tag, Typography, Pagination } from 'antd';
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
import { useMasterDataNames, type MasterDataNames } from '../useMasterDataNames';
import { withTimeout } from '@/utils/withTimeout';
import CatalogScopePicker from './CatalogScopePicker';
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

interface CatalogFormValues {
  sourceDataSourceId?: number;
  targetCatalogName: string;
  adapter: string;
  scope: LakeCatalogScope;
  databaseInclude?: string[];
  tableInclude?: string[];
}

const sourceOption = (source: LakePhysicalDataSource, names?: MasterDataNames) => ({
  value: source.sourceDataSourceId,
  label: `${source.sourceDataSourceName || `DataSource #${source.sourceDataSourceId}`} · ${source.dbType || '未知类型'} · ${names?.unitNameByCode(source.unitCode) || '未归属'}/${names?.systemNameByCode(source.systemCode) || '未归属'}`,
});

const adapterForDbType = (dbType?: string): string | undefined => {
  const normalized = String(dbType || '').toUpperCase();
  if (normalized === 'POSTGRE_SQL' || normalized.includes('POSTGRES')) return 'POSTGRESQL';
  if (normalized.includes('ORACLE')) return 'ORACLE';
  if (normalized.includes('MYSQL')) return 'MYSQL';
  return undefined;
};

const CreateCatalogDrawer: React.FC<{ open: boolean; onClose: () => void; onCreated: () => void; sources: LakePhysicalDataSource[]; initialSourceId?: number; masterNames: MasterDataNames }> = ({
  open,
  onClose,
  onCreated,
  sources,
  initialSourceId,
  masterNames,
}) => {
  const [form] = Form.useForm<CatalogFormValues>();
  const [loading, setLoading] = useState(false);
  const [capabilityLoading, setCapabilityLoading] = useState(false);
  const [capability, setCapability] = useState<LakeLogicalCapability>();
  const [capabilityError, setCapabilityError] = useState<string>();
  const sourceDataSourceId = Form.useWatch('sourceDataSourceId', form);
  const adapter = Form.useWatch('adapter', form);
  const scope = Form.useWatch('scope', form);
  const initialSource = sources.find((source) => source.sourceDataSourceId === initialSourceId);
  const initialSourceAdapter = adapterForDbType(initialSource?.dbType);
  const initializedSourceRef = useRef<string>();

  useEffect(() => {
    if (!open) {
      initializedSourceRef.current = undefined;
      return;
    }
    const sourceKey = `${initialSourceId ?? ''}:${initialSourceAdapter ?? ''}`;
    if (initializedSourceRef.current === sourceKey) return;
    initializedSourceRef.current = sourceKey;
    form.setFieldsValue({
      sourceDataSourceId: initialSourceId,
      adapter: initialSourceAdapter,
      scope: 'TABLE',
      targetCatalogName: initialSourceId ? `ext_ds_${initialSourceId}` : '',
      databaseInclude: [],
      tableInclude: [],
    });
    setCapability(undefined);
    setCapabilityError(undefined);
  }, [form, initialSourceAdapter, initialSourceId, open, sources]);

  useEffect(() => {
    if (!open || !sourceDataSourceId || !adapter || !scope) return;
    let cancelled = false;
    setCapability(undefined);
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
  const sourceNetworkPending = !supported && reasons.length > 0 && reasons.every((reason) => reason.endsWith('_UNKNOWN'));
  const canAttempt = supported || sourceNetworkPending;
  const submit = async (values: CatalogFormValues) => {
    if (!canAttempt || !sourceDataSourceId || !adapter || !scope) {
      message.warning(sourceNetworkPending ? '源端网络将在创建时验证，请稍候重试' : '当前数据源不满足逻辑挂载条件');
      return;
    }
    setLoading(true);
    try {
      if (sourceNetworkPending) {
        const checked = await probeCatalogCapability(sourceDataSourceId, { adapter, scope });
        if (checked.code !== 0 || !checked.data) throw new Error(responseMessage(checked));
        setCapability(checked.data);
        if (!(checked.data.logicalSupported || checked.data.supported || checked.data.enabled)) throw new Error('连接验证未通过，请查看上方原因后重试');
      }
      const response = await createCatalog({
        ...values,
        databaseInclude: values.databaseInclude || [],
        tableInclude: values.tableInclude || [],
        options: {},
      });
      if (response.code !== 0) throw new Error(responseMessage(response));
      if (response.data?.resourceStatus === 'READY') message.success('逻辑挂载已创建并验证');
      else message.warning('挂载已提交，验证尚未通过，请在详情中查看原因');
      form.resetFields();
      onClose();
      onCreated();
      if (response.data?.id) history.push(`/lake/logical-access/${response.data.id}`);
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
      width={760}
      destroyOnHidden
      onClose={onClose}
      footer={<Space><Button onClick={onClose}>取消</Button><Button type="primary" loading={loading} disabled={capabilityLoading || !canAttempt} onClick={() => form.submit()}>创建并验证</Button></Space>}
    >
      <Typography.Paragraph type="secondary">
        选择已有数据源，勾选要挂载的库表。数据保留在源端，可直接通过 Doris 查询。
      </Typography.Paragraph>
      {capabilityLoading ? <Alert type="info" showIcon message="正在检查数据源能力…" /> : null}
      {capabilityError ? <Alert type="error" showIcon message="能力检查失败" description={capabilityError} /> : null}
      {!capabilityLoading && !capabilityError && capability ? <Alert
        type={supported ? 'success' : canAttempt ? 'info' : 'error'}
        showIcon
        message={supported ? '连接检查通过' : sourceNetworkPending ? '创建时将自动验证湖端到源端的连接' : '当前不可创建逻辑挂载'}
        description={canAttempt ? undefined : <CapabilityReason reasons={reasons} fallback="服务端未返回可用原因" />}
        action={!canAttempt && reasons.some((reason) => /DRIVER|ADAPTER_DISABLED|CONTROL_PLANE/.test(reason))
          ? <Button onClick={() => history.push('/lake/warehouse/config')}>配置数据湖</Button> : undefined}
      /> : null}
      <Form name="lake-catalog-create" form={form} layout="vertical" onFinish={submit} onFinishFailed={({ errorFields }) => message.warning(errorFields[0]?.errors[0] || '请完善挂载配置')} initialValues={{ scope: 'TABLE' }}>
        <Form.Item name="sourceDataSourceId" label="数据源" rules={[{ required: true, message: '请选择数据源' }]}>
          <Select
            showSearch
            options={sources.filter((source) => adapterForDbType(source.dbType)).map((source) => sourceOption(source, masterNames))}
            placeholder="选择已有数据源"
            optionFilterProp="label"
            onChange={(value) => {
              const nextSource = sources.find((source) => source.sourceDataSourceId === value);
              const nextAdapter = adapterForDbType(nextSource?.dbType);
              const currentAdapter = form.getFieldValue('adapter');
              form.setFieldsValue({
                sourceDataSourceId: value,
                scope: 'TABLE',
                targetCatalogName: `ext_ds_${value}`,
                databaseInclude: [],
                tableInclude: [],
                ...(nextAdapter && currentAdapter !== nextAdapter ? { adapter: nextAdapter } : {}),
              });
              setCapability(undefined);
              setCapabilityError(undefined);
            }}
          />
        </Form.Item>
        <Form.Item name="targetCatalogName" label="挂载名称" rules={[{ required: true, max: 128, message: '请输入 128 字符以内的名称' }]}>
          <Input maxLength={128} />
        </Form.Item>
        <Form.Item name="adapter" label="数据库类型" hidden><Select options={adapterOptions} /></Form.Item>
        {adapter && <Typography.Paragraph type="secondary">数据库类型：{optionLabel(adapterOptions, adapter)}（随数据源自动确定）</Typography.Paragraph>}
        <CatalogScopePicker form={form} sourceId={sourceDataSourceId} adapter={adapter} />
      </Form>
    </Drawer>
  );
};

const LogicalAccessPage: React.FC = () => {
  const masterNames = useMasterDataNames();
  const location = useLocation();
  const initialSourceId = useMemo(() => {
    const value = new URLSearchParams(location.search).get('sourceDataSourceId');
    return value ? Number(value) : undefined;
  }, [location.search]);
  const actionRef = useRef<ActionType>();
  const [createOpen, setCreateOpen] = useState(!!initialSourceId);
  const [pageNumber, setPageNumber] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [total, setTotal] = useState(0);
  const [sources, setSources] = useState<LakePhysicalDataSource[]>([]);
  const [sourcesLoading, setSourcesLoading] = useState(true);
  const [sourcesError, setSourcesError] = useState<string>();
  const [sourcesReloadKey, setSourcesReloadKey] = useState(0);
  const [catalogError, setCatalogError] = useState<string>();
  const [selectedSourceId, setSelectedSourceId] = useState<number | undefined>(initialSourceId);

  useEffect(() => {
    setSelectedSourceId(initialSourceId);
  }, [initialSourceId]);

  useEffect(() => {
    let cancelled = false;
    setSourcesLoading(true);
    setSourcesError(undefined);
    const loadSources = async () => {
      try {
        const response = await withTimeout(
          fetchPhysicalSources({ pageNo: 1, pageSize: 100 }),
          10000,
          '业务数据源请求超时，请稍后重试',
        );
        if (response.code !== 0) throw new Error(responseMessage(response));
        if (!cancelled) setSources(normalizeLakePage(response.data).data);
      } catch (error) {
        if (!cancelled) {
          setSources([]);
          setSourcesError(error instanceof Error ? error.message : '业务数据源加载失败');
        }
      } finally {
        if (!cancelled) setSourcesLoading(false);
      }
    };
    void loadSources();
    return () => { cancelled = true; };
  }, [sourcesReloadKey]);
  const sourceLabelById = useMemo(
    () => new Map(sources.map((source) => [source.sourceDataSourceId, sourceOption(source, masterNames).label])),
    [masterNames, sources],
  );
  const columns: ProColumns<LakeCatalog>[] = [
    { title: '挂载名称', dataIndex: 'targetCatalogName', width: 200, ellipsis: true },
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
    <PageContainer className="lake-logical-page" title={false}>
      {sourcesError ? (
        <Alert
          className="lake-table-alert"
          type="error"
          showIcon
          message="业务数据源加载失败"
          description={sourcesError}
          action={<Button type="link" onClick={() => setSourcesReloadKey((value) => value + 1)}>重试</Button>}
        />
      ) : null}
      {catalogError ? (
        <Alert
          className="lake-table-alert"
          type="error"
          showIcon
          message="逻辑入湖列表加载失败"
          description={catalogError}
          action={<Button type="link" onClick={() => actionRef.current?.reloadAndRest?.()}>重试</Button>}
        />
      ) : null}
      <ProTable<LakeCatalog>
        className="lake-catalog-table"
        rowKey={(row) => String(row.id || `${row.sourceDataSourceId}-${row.targetCatalogName}`)}
        actionRef={actionRef}
        columns={columns}
        scroll={{ x: 1200 }}
        search={false}
        // The default toolbar renders refresh/settings as non-focusable spans; the explicit
        // refresh button below is keyboard accessible.
        options={false}
        pagination={false}
        params={{ current: pageNumber, pageSize }}
        toolBarRender={() => [
          <Button key="create" type="primary" icon={<PlusOutlined />} disabled={sourcesLoading || !sources.length} onClick={() => setCreateOpen(true)}>创建挂载</Button>,
          <Button key="refresh" icon={<ReloadOutlined />} onClick={() => actionRef.current?.reloadAndRest?.()}>刷新</Button>,
        ]}
        request={async (params) => {
          setCatalogError(undefined);
          try {
            const response = await withTimeout(
              fetchCatalogs({
                pageNo: Number(params.current || 1),
                pageSize: Number(params.pageSize || 10),
                targetCatalogName: params.targetCatalogName,
                sourceDataSourceId: params.sourceDataSourceId,
                adapter: params.adapter,
                resourceStatus: params.resourceStatus,
                validationStatus: params.validationStatus,
              }),
              10000,
              '逻辑入湖列表请求超时，请稍后重试',
            );
            if (response.code !== 0) throw new Error(responseMessage(response));
            const page = normalizeLakePage(response.data);
            setTotal(page.total);
            return { data: page.data, success: true, total: page.total };
          } catch (error) {
            setTotal(0);
            setCatalogError(error instanceof Error ? error.message : '逻辑入湖列表加载失败');
            return { data: [], success: false, total: 0 };
          }
        }}
        />
      <div className="lake-logical-pagination"><Pagination current={pageNumber} pageSize={pageSize} total={total} showSizeChanger showTotal={(count) => `共 ${count} 个挂载`} onChange={(next, size) => { setPageNumber(next); setPageSize(size); }} /></div>
      {!sourcesLoading && !sources.length ? <Card className="lake-logical-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可用业务数据源" /><Typography.Text type="secondary">请先在数据源管理中创建 MySQL、PostgreSQL 或 Oracle 连接。</Typography.Text></Card> : null}
      <CreateCatalogDrawer open={createOpen} onClose={() => setCreateOpen(false)} onCreated={() => actionRef.current?.reloadAndRest?.()} sources={sources} initialSourceId={selectedSourceId} masterNames={masterNames} />
    </PageContainer>
  );
};

export default LogicalAccessPage;
