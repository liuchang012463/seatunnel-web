import type {
  LakeApiResponse,
  LakeCatalog,
  LakeCatalogScope,
  LakeLogicalCapability,
  LakePhysicalDataSource,
} from '@/services/lake';
import {
  createCatalog,
  fetchCatalogCapability,
  fetchCatalogs,
  fetchPhysicalSources,
  normalizeLakePage,
  probeCatalogCapability,
} from '@/services/lake';
import { withTimeout } from '@/utils/withTimeout';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { history, useLocation } from '@umijs/max';
import {
  Alert,
  Button,
  Collapse,
  Drawer,
  Empty,
  Form,
  Input,
  message,
  Pagination,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { CapabilityReason } from '../components/LakeStatus';
import { useMasterDataNames, type MasterDataNames } from '../useMasterDataNames';
import CatalogScopePicker from './CatalogScopePicker';
import './index.less';
import {
  adapterLabel,
  catalogScopeLabel,
  catalogStatusColor as statusColor,
  catalogStatusLabel as statusLabel,
} from './presentation';

const adapterOptions = [
  { label: 'MySQL', value: 'MYSQL' },
  { label: 'PostgreSQL', value: 'POSTGRESQL' },
  { label: 'Oracle', value: 'ORACLE' },
];

const responseMessage = (response: LakeApiResponse<unknown>) =>
  response.msg || response.message || '请求失败，请稍后重试';

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
  label: `${source.sourceDataSourceName || `数据源 #${source.sourceDataSourceId}`} · ${adapterLabel(source.dbType)} · ${
    names?.unitNameByCode(source.unitCode) || '未归属'
  }/${names?.systemNameByCode(source.systemCode) || '未归属'}`,
});

const adapterForDbType = (dbType?: string): string | undefined => {
  const normalized = String(dbType || '').toUpperCase();
  if (normalized === 'POSTGRE_SQL' || normalized.includes('POSTGRES')) return 'POSTGRESQL';
  if (normalized.includes('ORACLE')) return 'ORACLE';
  if (normalized.includes('MYSQL')) return 'MYSQL';
  return undefined;
};

const CreateCatalogDrawer: React.FC<{
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
  sources: LakePhysicalDataSource[];
  initialSourceId?: number;
  masterNames: MasterDataNames;
}> = ({ open, onClose, onCreated, sources, initialSourceId, masterNames }) => {
  const [form] = Form.useForm<CatalogFormValues>();
  const [loading, setLoading] = useState(false);
  const [capabilityLoading, setCapabilityLoading] = useState(false);
  const [capability, setCapability] = useState<LakeLogicalCapability>();
  const [capabilityError, setCapabilityError] = useState<string>();
  const [existingCatalog, setExistingCatalog] = useState<LakeCatalog>();
  const [checkingExisting, setCheckingExisting] = useState(false);
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
      scope: 'DATABASE',
      targetCatalogName: initialSourceId ? `ext_ds_${initialSourceId}` : '',
      databaseInclude: [],
      tableInclude: [],
    });
    setCapability(undefined);
    setCapabilityError(undefined);
  }, [form, initialSourceAdapter, initialSourceId, open, sources]);

  useEffect(() => {
    let active = true;
    setExistingCatalog(undefined);
    if (!open || !sourceDataSourceId) return;
    setCheckingExisting(true);
    void fetchCatalogs({ pageNo: 1, pageSize: 1, sourceDataSourceId })
      .then((response) => {
        if (active && response.code === 0) setExistingCatalog(normalizeLakePage(response.data).data[0]);
      })
      .catch(() => undefined)
      .finally(() => {
        if (active) setCheckingExisting(false);
      });
    return () => {
      active = false;
    };
  }, [open, sourceDataSourceId]);

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
    return () => {
      cancelled = true;
    };
  }, [adapter, open, scope, sourceDataSourceId]);

  const supported =
    capability?.logicalSupported === true || capability?.supported === true || capability?.enabled === true;
  const reasons = capability?.reasonCodes?.length ? capability.reasonCodes : capability?.disabledReasons || [];
  const sourceNetworkPending =
    !supported && reasons.length > 0 && reasons.every((reason) => reason.endsWith('_UNKNOWN'));
  const canAttempt = supported || sourceNetworkPending;
  const submit = async (values: CatalogFormValues) => {
    if (existingCatalog || checkingExisting) return;
    if (!canAttempt || !sourceDataSourceId || !adapter || !scope) {
      message.warning(sourceNetworkPending ? '源端网络将在创建时验证，请稍候重试' : '当前数据源不满足逻辑挂载条件');
      return;
    }
    setLoading(true);
    try {
      if (sourceNetworkPending) {
        const checked = await probeCatalogCapability(sourceDataSourceId, {
          adapter,
          scope,
        });
        if (checked.code !== 0 || !checked.data) throw new Error(responseMessage(checked));
        setCapability(checked.data);
        if (!(checked.data.logicalSupported || checked.data.supported || checked.data.enabled))
          throw new Error('连接验证未通过，请查看上方原因后重试');
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
      title="创建挂载"
      width={760}
      destroyOnHidden
      onClose={onClose}
      footer={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button
            type="primary"
            loading={loading}
            disabled={capabilityLoading || checkingExisting || Boolean(existingCatalog) || !canAttempt}
            onClick={() => form.submit()}
          >
            创建挂载
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        选择已有数据源，勾选要挂载的库表。数据保留在源端，可直接通过 Doris 查询。
      </Typography.Paragraph>
      {capabilityLoading ? <Alert type="info" showIcon message="正在检查数据源能力…" /> : null}
      {capabilityError ? <Alert type="error" showIcon message="能力检查失败" description={capabilityError} /> : null}
      {!capabilityLoading && !capabilityError && capability ? (
        <Alert
          type={supported ? 'success' : canAttempt ? 'info' : 'error'}
          showIcon
          message={
            supported
              ? '连接检查通过'
              : sourceNetworkPending
              ? '创建时将自动验证湖端到源端的连接'
              : '当前不可创建逻辑挂载'
          }
          description={canAttempt ? undefined : <CapabilityReason reasons={reasons} fallback="服务端未返回可用原因" />}
          action={
            !canAttempt && reasons.some((reason) => /DRIVER|ADAPTER_DISABLED|CONTROL_PLANE/.test(reason)) ? (
              <Button onClick={() => history.push('/lake/warehouse/config')}>配置数据湖</Button>
            ) : undefined
          }
        />
      ) : null}
      <Form
        name="lake-catalog-create"
        form={form}
        layout="vertical"
        onFinish={submit}
        onFinishFailed={({ errorFields }) => message.warning(errorFields[0]?.errors[0] || '请完善挂载配置')}
        initialValues={{ scope: 'DATABASE' }}
      >
        <Form.Item name="sourceDataSourceId" label="数据源" rules={[{ required: true, message: '请选择数据源' }]}>
          <Select
            showSearch
            options={sources
              .filter((source) => adapterForDbType(source.dbType))
              .map((source) => sourceOption(source, masterNames))}
            placeholder="选择已有数据源"
            optionFilterProp="label"
            onChange={(value) => {
              const nextSource = sources.find((source) => source.sourceDataSourceId === value);
              const nextAdapter = adapterForDbType(nextSource?.dbType);
              const currentAdapter = form.getFieldValue('adapter');
              form.setFieldsValue({
                sourceDataSourceId: value,
                scope: 'DATABASE',
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
        <Form.Item name="adapter" label="数据库类型" hidden>
          <Select options={adapterOptions} />
        </Form.Item>
        {existingCatalog ? (
          <Alert
            type="info"
            showIcon
            message="此数据源已有挂载，可直接查看数据或调整范围"
            action={
              <Button
                onClick={() => {
                  onClose();
                  history.push(`/lake/logical-access/${existingCatalog.id}`);
                }}
              >
                打开已有挂载
              </Button>
            }
          />
        ) : null}
        <CatalogScopePicker form={form} sourceId={sourceDataSourceId} adapter={adapter} />
        <Collapse
          ghost
          style={{ marginTop: 16 }}
          items={[
            {
              key: 'advanced',
              label: '高级设置（系统已自动配置）',
              forceRender: true,
              children: (
                <Form.Item
                  name="targetCatalogName"
                  label="挂载标识"
                  extra="系统自动生成，通常无需修改。创建后保持不变。"
                  rules={[
                    {
                      required: true,
                      pattern: /^[A-Za-z][A-Za-z0-9_-]{0,63}$/,
                      message: '请使用字母开头、64 位以内的字母、数字、下划线或短横线',
                    },
                  ]}
                >
                  <Input maxLength={64} />
                </Form.Item>
              ),
            },
          ]}
        />
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
    return () => {
      cancelled = true;
    };
  }, [sourcesReloadKey]);
  const columns: ProColumns<LakeCatalog>[] = [
    {
      title: '数据来源',
      dataIndex: 'sourceDataSourceId',
      width: 250,
      ellipsis: true,
      render: (_, row) => {
        const source = sources.find((item) => item.sourceDataSourceId === row.sourceDataSourceId);
        const name = source?.sourceDataSourceName || '数据源 #' + row.sourceDataSourceId;
        return (
          <div className="lake-source-cell">
            <Tooltip title={name}>
              <Button type="link" onClick={() => history.push('/lake/logical-access/' + row.id)}>
                {name}
              </Button>
            </Tooltip>
            <Typography.Text type="secondary">{adapterLabel(row.adapter)}</Typography.Text>
          </div>
        );
      },
    },
    {
      title: '挂载范围',
      dataIndex: 'scope',
      width: 200,
      ellipsis: true,
      render: (_, row) => <Tooltip title={catalogScopeLabel(row)}>{catalogScopeLabel(row)}</Tooltip>,
    },
    {
      title: '查询状态',
      dataIndex: 'resourceStatus',
      width: 110,
      align: 'center',
      render: (_, row) => <Tag color={statusColor(row.resourceStatus)}>{statusLabel(row.resourceStatus)}</Tag>,
    },
    {
      title: '配置检查',
      dataIndex: 'validationStatus',
      width: 120,
      align: 'center',
      render: (_, row) => <Tag color={statusColor(row.validationStatus)}>{statusLabel(row.validationStatus)}</Tag>,
    },
    {
      title: '最近检查',
      dataIndex: 'lastObservedAt',
      valueType: 'dateTime',
      width: 170,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 110,
      align: 'center',
      fixed: 'right',
      render: (_, row) =>
        row.id ? (
          <Button type="link" onClick={() => history.push('/lake/logical-access/' + row.id)}>
            查看详情
          </Button>
        ) : null,
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
          action={
            <Button type="link" onClick={() => setSourcesReloadKey((value) => value + 1)}>
              重试
            </Button>
          }
        />
      ) : null}
      {catalogError ? (
        <Alert
          className="lake-table-alert"
          type="error"
          showIcon
          message="逻辑入湖列表加载失败"
          description={catalogError}
          action={
            <Button type="link" onClick={() => actionRef.current?.reloadAndRest?.()}>
              重试
            </Button>
          }
        />
      ) : null}
      <div className="lake-logical-frame">
        <ProTable<LakeCatalog>
          className="lake-catalog-table"
          rowKey={(row) => String(row.id || `${row.sourceDataSourceId}-${row.targetCatalogName}`)}
          actionRef={actionRef}
          columns={columns}
          tableLayout="fixed"
          scroll={{ x: 960 }}
          search={false}
          // The default toolbar renders refresh/settings as non-focusable spans; the explicit
          // refresh button below is keyboard accessible.
          options={false}
          pagination={false}
          locale={{
            emptyText: (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={!sourcesLoading && !sources.length ? '暂无可用业务数据源' : '暂无逻辑挂载'} />
            ),
          }}
          params={{ current: pageNumber, pageSize }}
          toolBarRender={() => [
            <Button
              key="create"
              type="primary"
              icon={<PlusOutlined />}
              disabled={sourcesLoading || !sources.length}
              onClick={() => setCreateOpen(true)}
            >
              创建挂载
            </Button>,
            <Button key="refresh" icon={<ReloadOutlined />} onClick={() => actionRef.current?.reloadAndRest?.()}>
              刷新
            </Button>,
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
        <div className="lake-logical-pagination">
          <Pagination
            current={pageNumber}
            pageSize={pageSize}
            total={total}
            showSizeChanger
            showTotal={(count) => `共 ${count} 个挂载`}
            onChange={(next, size) => {
              setPageNumber(next);
              setPageSize(size);
            }}
          />
        </div>
      </div>
      <CreateCatalogDrawer
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => actionRef.current?.reloadAndRest?.()}
        sources={sources}
        initialSourceId={selectedSourceId}
        masterNames={masterNames}
      />
    </PageContainer>
  );
};

export default LogicalAccessPage;
