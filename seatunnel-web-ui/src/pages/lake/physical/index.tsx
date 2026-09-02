import {
  CheckCircleOutlined,
  DatabaseOutlined,
  ExclamationCircleOutlined,
  LinkOutlined,
  PlusOutlined,
  ReloadOutlined,
  RightOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { history, useLocation } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Radio,
  Select,
  Space,
  Statistic,
  Tag,
  Typography,
  message,
} from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  createOdsDatabase,
  fetchPhysicalSummary,
  fetchPhysicalSources,
  recommendLakeMode,
  reconcileOdsDatabase,
  retryOdsDatabase,
} from './service';
import type { LakeRecommendation } from './service';
import type { LakePage, LakeResourceStatus, OdsDatabase, PhysicalDataSource } from './types';
import './index.less';

const { Paragraph, Text, Title } = Typography;

const RESOURCE_STATUS: Array<{ label: string; value: LakeResourceStatus }> = [
  { label: '创建中', value: 'CREATING' },
  { label: '待创建', value: 'PENDING_CREATE' },
  { label: '已就绪', value: 'READY' },
  { label: '失败', value: 'CREATE_FAILED' },
  { label: '异常', value: 'ERROR' },
  { label: '缺失', value: 'MISSING' },
  { label: '未知', value: 'UNKNOWN' },
  { label: '删除中', value: 'DELETING' },
  { label: '已删除', value: 'DELETED' },
];

const statusLabel: Record<string, string> = Object.fromEntries(RESOURCE_STATUS.map((item) => [item.value, item.label]));
const statusColor: Record<string, string> = {
  CREATING: 'processing',
  PENDING_CREATE: 'processing',
  READY: 'success',
  CREATE_FAILED: 'error',
  ERROR: 'error',
  MISSING: 'error',
  UNKNOWN: 'warning',
  DELETING: 'processing',
  DELETED: 'default',
};

const adapterOptions = [
  { label: 'MySQL', value: 'MYSQL' },
  { label: 'PostgreSQL', value: 'POSTGRESQL' },
  { label: 'Oracle', value: 'ORACLE' },
];

const inferAdapter = (source?: PhysicalDataSource): string | undefined => {
  const type = String((source as PhysicalDataSource & { dbType?: string })?.dbType || '').toUpperCase();
  if (type.includes('POSTGRES')) return 'POSTGRESQL';
  if (type.includes('ORACLE')) return 'ORACLE';
  if (type.includes('MYSQL')) return 'MYSQL';
  return undefined;
};

const unwrapPage = (data?: LakePage<PhysicalDataSource>): { rows: PhysicalDataSource[]; total: number } => ({
  rows: Array.isArray(data?.bizData) ? data.bizData : [],
  // Some deployed MyBatis versions return total=0 with a usable page.
  total: Number(data?.pagination?.total || data?.bizData?.length || 0),
});

const formatTime = (value?: string) => (value ? value.replace('T', ' ').replace(/\.\d+Z$/, '') : '-');

const resourceTag = (value?: LakeResourceStatus) => (
  <Tag color={value ? statusColor[value] : undefined}>
    {value ? statusLabel[value] || value : '未绑定'}
  </Tag>
);

const recommendationModeLabel: Record<string, string> = {
  PHYSICAL: '物理入湖',
  LOGICAL: '逻辑入湖',
  UNSUPPORTED: '暂不支持',
};

interface RecommendationValues {
  moveData: boolean;
  physicalGovernance: boolean;
  joinOnly: boolean;
  targetScope: 'ALL' | 'DATABASE' | 'TABLE';
  adapter: string;
}

const initialRecommendationValues: RecommendationValues = {
  moveData: true,
  physicalGovernance: true,
  joinOnly: false,
  targetScope: 'ALL',
  adapter: 'MYSQL',
};

const RecommendationModal: React.FC<{
  open: boolean;
  source?: PhysicalDataSource;
  onClose: () => void;
  onContinue: (mode: string) => void;
}> = ({ open, source, onClose, onContinue }) => {
  const [form] = Form.useForm<RecommendationValues>();
  const [loading, setLoading] = useState(false);
  const [recommendation, setRecommendation] = useState<LakeRecommendation>();
  const [selectedAdapter, setSelectedAdapter] = useState<string>();

  const sourceAdapter = inferAdapter(source);
  useEffect(() => {
    const nextAdapter = sourceAdapter || initialRecommendationValues.adapter;
    setSelectedAdapter(nextAdapter);
    form.setFieldValue('adapter', nextAdapter);
  }, [form, open, sourceAdapter]);

  const capabilityText = (capability?: { supported?: boolean; disabledReasons?: string[] }) => {
    if (!capability) return '尚未检查';
    if (capability.supported) return '当前能力可用';
    return capability.disabledReasons?.join('、') || '服务端能力不可用';
  };

  const submit = async (values: RecommendationValues) => {
    if (!source?.sourceDataSourceId) return;
    setLoading(true);
    setRecommendation(undefined);
    try {
      const response = await recommendLakeMode({
        ...values,
        sourceDataSourceId: source.sourceDataSourceId,
      });
      if (response.code !== 0 || !response.data) throw new Error(response.message || response.msg || '推荐服务暂不可用');
      setRecommendation(response.data);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '推荐服务暂不可用');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      open={open}
      title="智能推荐入湖方式"
      width={720}
      forceRender
      destroyOnHidden
      onCancel={onClose}
      footer={null}
    >
      <Paragraph type="secondary">
        请回答四个问题。推荐仅用于本次导航，不会自动创建资源或写入绑定。
      </Paragraph>
      <Card size="small" className="lake-capability-card">
        <Descriptions size="small" column={2}>
          <Descriptions.Item label="数据源">{source?.sourceDataSourceName || '-'}</Descriptions.Item>
          <Descriptions.Item label="源端适配器">
            <Select
              size="small"
              value={selectedAdapter}
              placeholder="选择适配器"
              options={adapterOptions}
              onChange={(value) => {
                setSelectedAdapter(value);
                form.setFieldValue('adapter', value);
              }}
              style={{ width: 150 }}
            />
          </Descriptions.Item>
          <Descriptions.Item label="物理能力">
            {recommendation ? capabilityText(recommendation.physicalCapability) : '提交四问后检查'}
          </Descriptions.Item>
          <Descriptions.Item label="逻辑能力">
            {recommendation ? capabilityText(recommendation.logicalCapability) : '提交四问后检查'}
          </Descriptions.Item>
        </Descriptions>
      </Card>
      <Form
        form={form}
        layout="vertical"
        initialValues={{ ...initialRecommendationValues, adapter: sourceAdapter || initialRecommendationValues.adapter }}
        onFinish={submit}
        className="lake-recommendation-form"
      >
        <Form.Item name="moveData" label="1. 是否需要把数据移动到 Doris 管理的 ODS 存储？" rules={[{ required: true }]}>
          <Radio.Group optionType="button" buttonStyle="solid" options={[{ label: '是', value: true }, { label: '否', value: false }]} />
        </Form.Item>
        <Form.Item name="physicalGovernance" label="2. 是否需要 Web 管理 ODS 表结构和生命周期？" rules={[{ required: true }]}>
          <Radio.Group optionType="button" buttonStyle="solid" options={[{ label: '是', value: true }, { label: '否', value: false }]} />
        </Form.Item>
        <Form.Item name="joinOnly" label="3. 是否只需要跨 Catalog 联合查询，不落地数据？" rules={[{ required: true }]}>
          <Radio.Group optionType="button" buttonStyle="solid" options={[{ label: '是', value: true }, { label: '否', value: false }]} />
        </Form.Item>
        <Form.Item name="targetScope" label="4. 需要检查的逻辑元数据范围" rules={[{ required: true }]}>
          <Radio.Group options={[{ label: '全部', value: 'ALL' }, { label: '数据库', value: 'DATABASE' }, { label: '表', value: 'TABLE' }]} />
        </Form.Item>
        {recommendation ? (
          <Alert
            type={recommendation.mode === 'UNSUPPORTED' ? 'warning' : 'success'}
            showIcon
            icon={recommendation.mode === 'UNSUPPORTED' ? <ExclamationCircleOutlined /> : <CheckCircleOutlined />}
            message={`推荐：${recommendationModeLabel[recommendation.mode || recommendation.recommendation || 'UNSUPPORTED'] || '暂不支持'}`}
            description={
              <>
                <div>{recommendation.reason || recommendation.reasonCode || '服务端未返回推荐原因'}</div>
                {recommendation.disabledReasons?.length ? <div>限制：{recommendation.disabledReasons.join('、')}</div> : null}
              </>
            }
          />
        ) : null}
        <div className="lake-modal-footer">
          <Button onClick={onClose}>取消</Button>
          <Button htmlType="submit" loading={loading} type="default">
            检查并推荐
          </Button>
          <Button
            type="primary"
            disabled={!recommendation || (recommendation.mode || recommendation.recommendation) === 'UNSUPPORTED'}
            onClick={() => onContinue(recommendation?.mode || recommendation?.recommendation || 'UNSUPPORTED')}
          >
            按推荐方式继续
          </Button>
        </div>
      </Form>
    </Modal>
  );
};

const OdsDatabaseDrawer: React.FC<{
  open: boolean;
  source?: PhysicalDataSource;
  onClose: () => void;
  onSuccess: () => void;
}> = ({ open, source, onClose, onSuccess }) => {
  const [form] = Form.useForm<{ customName: string }>();
  const [loading, setLoading] = useState(false);
  const customName = Form.useWatch('customName', form) || '';
  const unit = source?.unitCode || 'unit';
  const system = source?.systemCode || 'system';
  const fullName = `ods_${unit}_${system}_${customName}`;
  const codeMissing = !source?.unitCode || !source?.systemCode;

  const submit = async ({ customName: value }: { customName: string }) => {
    if (!source?.sourceDataSourceId) return;
    setLoading(true);
    try {
      const response = await createOdsDatabase(source.sourceDataSourceId, value.trim());
      if (response.code !== 0 || !response.data) throw new Error(response.message || response.msg || 'ODS 库创建失败');
      message.success('ODS 库创建操作已提交');
      onSuccess();
      onClose();
      form.resetFields();
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'ODS 库创建失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Drawer
      open={open}
      width={520}
      title="创建 ODS Database"
      forceRender
      destroyOnHidden
      onClose={onClose}
      extra={<Tag color="blue">服务端创建</Tag>}
    >
      <Alert
        type="info"
        showIcon
        message="数据库命名由服务端校验"
        description="单位、系统编码只读；页面只提交 customName，不在浏览器执行 Doris DDL。"
        className="lake-drawer-alert"
      />
      <Descriptions bordered size="small" column={1}>
        <Descriptions.Item label="数据源">{source?.sourceDataSourceName || '-'}</Descriptions.Item>
        <Descriptions.Item label="单位编码">{source?.unitCode || '缺失'}</Descriptions.Item>
        <Descriptions.Item label="系统编码">{source?.systemCode || '缺失'}</Descriptions.Item>
        <Descriptions.Item label="固定前缀">ods_{unit}_{system}_</Descriptions.Item>
      </Descriptions>
      {codeMissing ? (
        <Alert
          type="warning"
          showIcon
          message="单位或系统编码缺失"
          description="请先前往主数据维护补齐编码，当前不会显示提交按钮。"
          className="lake-drawer-alert"
        />
      ) : null}
      <Form form={form} layout="vertical" onFinish={submit} className="lake-ods-form">
        <Form.Item
          name="customName"
          label="自定义名称"
          rules={[
            { required: true, message: '请输入自定义名称' },
            { max: 40, message: '自定义名称不能超过 40 个字符' },
            { pattern: /^[A-Za-z0-9_]+$/, message: '仅支持字母、数字和下划线' },
          ]}
        >
          <Input placeholder="例如 orders" showCount maxLength={40} />
        </Form.Item>
        <Card size="small" title="完整名称预览" className="lake-name-preview">
          <Text code>{fullName}</Text>
          <div className="lake-name-preview-count">{fullName.length} / 64 字符</div>
        </Card>
        <div className="lake-modal-footer">
          <Button onClick={onClose}>取消</Button>
          <Button htmlType="submit" type="primary" loading={loading} disabled={codeMissing || !customName.trim()}>
            创建 ODS 库
          </Button>
        </div>
      </Form>
    </Drawer>
  );
};

const PhysicalResourcesPage: React.FC = () => {
  const location = useLocation();
  const actionRef = useRef<ActionType>();
  const [createSource, setCreateSource] = useState<PhysicalDataSource>();
  const [recommendSource, setRecommendSource] = useState<PhysicalDataSource>();
  const [operationId, setOperationId] = useState<number>();
  const [operationLoading, setOperationLoading] = useState(false);
  const [recommendOpen, setRecommendOpen] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [resourceStatus, setResourceStatus] = useState<LakeResourceStatus>();
  const [loadedSources, setLoadedSources] = useState<PhysicalDataSource[]>([]);
  const [summary, setSummary] = useState<{ boundDataSourceCount?: number; odsTableCount?: number; pendingExceptionCount?: number }>();
  const recommendationSourceId = useMemo(() => {
    const value = new URLSearchParams(location.search).get('recommendSourceDataSourceId');
    return value ? Number(value) : undefined;
  }, [location.search]);

  const loadSummary = () => {
    void fetchPhysicalSummary().then((response) => {
      if (response.code === 0) setSummary(response.data);
    }).catch(() => undefined);
  };

  const reload = () => {
    actionRef.current?.reloadAndRest?.();
    loadSummary();
  };

  useEffect(() => {
    loadSummary();
  }, []);

  useEffect(() => {
    if (!recommendationSourceId || !loadedSources.length) return;
    const source = loadedSources.find((item) => item.sourceDataSourceId === recommendationSourceId);
    if (!source) return;
    setRecommendSource(source);
    setRecommendOpen(true);
    history.replace('/lake/resources');
  }, [loadedSources, recommendationSourceId]);

  const operate = async (type: 'retry' | 'reconcile', database?: OdsDatabase) => {
    if (!database?.id) return;
    setOperationId(database.id);
    setOperationLoading(true);
    try {
      const response = type === 'retry' ? await retryOdsDatabase(database.id) : await reconcileOdsDatabase(database.id);
      if (response.code !== 0) throw new Error(response.message || response.msg || '操作失败');
      message.success(type === 'retry' ? '重试操作已提交' : '对账已完成');
      reload();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '操作失败');
    } finally {
      setOperationLoading(false);
      setOperationId(undefined);
    }
  };

  const columns = useMemo<ProColumns<PhysicalDataSource>[]>(
    () => [
      {
        title: '单位 / 业务系统',
        key: 'owner',
        width: 190,
        render: (_, record) => (
          <div>
            <div>{record.unitCode || '待归属'}</div>
            <Text type="secondary">{record.systemCode || '待归属'}</Text>
          </div>
        ),
      },
      {
        title: '数据源',
        dataIndex: 'sourceDataSourceName',
        key: 'sourceDataSourceName',
        width: 220,
        render: (_, record) => (
          <Button type="link" className="lake-source-link" onClick={() => history.push(`/lake/resources/${record.sourceDataSourceId}`)}>
            {record.sourceDataSourceName || `DataSource #${record.sourceDataSourceId}`}
            <RightOutlined />
          </Button>
        ),
      },
      {
        title: 'ODS Database',
        key: 'databaseName',
        width: 230,
        render: (_, record) => record.odsDatabase?.databaseName || <Text type="secondary">未创建</Text>,
      },
      {
        title: 'Resource Status',
        key: 'resourceStatus',
        width: 130,
        render: (_, record) => resourceTag(record.odsDatabase?.resourceStatus),
        valueType: 'select',
        valueEnum: Object.fromEntries(RESOURCE_STATUS.map((item) => [item.value, { text: item.label }])),
      },
      {
        title: '最近对账',
        key: 'lastReconcileAt',
        width: 170,
        render: (_, record) => formatTime(record.odsDatabase?.lastReconcileAt),
      },
      {
        title: '操作',
        key: 'option',
        valueType: 'option',
        fixed: 'right',
        width: 280,
        render: (_, record) => {
          const database = record.odsDatabase;
          const canRetry = !!database && ['ERROR', 'CREATE_FAILED', 'MISSING', 'UNKNOWN', 'PENDING_CREATE'].includes(database.resourceStatus || '');
          const canReconcile = !!database && database.resourceStatus !== 'DELETED';
          return (
            <Space wrap size={4}>
              {!database ? (
                <Button type="link" icon={<PlusOutlined />} onClick={() => setCreateSource(record)}>
                  创建 ODS 库
                </Button>
              ) : null}
              {database ? (
                <Button type="link" icon={<ThunderboltOutlined />} onClick={() => { setRecommendSource(record); setRecommendOpen(true); }}>
                  智能推荐
                </Button>
              ) : null}
              {canRetry ? (
                <Button type="link" loading={operationLoading && operationId === database?.id} onClick={() => void operate('retry', database)}>
                  重试
                </Button>
              ) : null}
              {canReconcile ? (
                <Button type="link" icon={<ReloadOutlined />} loading={operationLoading && operationId === database?.id} onClick={() => void operate('reconcile', database)}>
                  对账
                </Button>
              ) : null}
            </Space>
          );
        },
      },
    ],
    [operationId, operationLoading],
  );

  return (
    <PageContainer
      title="物理入湖"
      subTitle="管理业务数据源对应的 Doris ODS 资源"
      extra={[
        <Button key="refresh" icon={<ReloadOutlined />} onClick={reload}>
          刷新列表
        </Button>,
      ]}
    >
      <div className="lake-physical-page">
        <Card className="lake-page-intro" size="small">
          <div className="lake-page-intro-icon"><DatabaseOutlined /></div>
          <div>
            <Title level={5}>ODS 资源工作台</Title>
            <Paragraph type="secondary">数据源列表只读加载；创建、重试和对账均由服务端控制面执行。</Paragraph>
          </div>
          <Button type="link" icon={<LinkOutlined />} onClick={() => history.push('/data-source')}>
            查看数据源
          </Button>
        </Card>
        <div className="lake-physical-summary">
          <Card size="small" className="lake-physical-stat"><Statistic title="已绑定数据源" value={summary?.boundDataSourceCount ?? 0} /></Card>
          <Card size="small" className="lake-physical-stat"><Statistic title="ODS 表总数" value={summary?.odsTableCount ?? 0} /></Card>
          <Card size="small" className="lake-physical-stat" data-warning={(summary?.pendingExceptionCount || 0) > 0}><Statistic title="待处理异常" value={summary?.pendingExceptionCount ?? 0} /></Card>
          <Text type="secondary" className="lake-physical-summary-note"><CheckCircleOutlined /> 页面 GET 只读取本地汇总；请使用行内“对账”按钮读取 Doris 实际状态。</Text>
        </div>
        <ProTable<PhysicalDataSource>
          actionRef={actionRef}
          rowKey="sourceDataSourceId"
          cardBordered
          columns={columns}
          scroll={{ x: 1180 }}
          search={false}
          options={false}
          pagination={{ showSizeChanger: true, showTotal: (total) => `共 ${total} 个数据源` }}
          request={async (params) => {
            try {
              const response = await fetchPhysicalSources({
                pageNo: params.current || 1,
                pageSize: params.pageSize || 10,
                keyword: keyword || undefined,
                resourceStatus: resourceStatus || undefined,
              });
              if (response.code !== 0) throw new Error(response.message || response.msg || '物理入湖列表加载失败');
              const page = unwrapPage(response.data);
              setLoadedSources(page.rows);
              return { data: page.rows, success: true, total: page.total };
            } catch (error) {
              message.error(error instanceof Error ? error.message : '物理入湖列表加载失败');
              return { data: [], success: false, total: 0 };
            }
          }}
          toolBarRender={() => [
            <Input.Search
              key="search"
              allowClear
              placeholder="搜索数据源名称"
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              onSearch={() => actionRef.current?.reload(true)}
              style={{ width: 'min(240px, 100%)' }}
            />,
            <Select
              key="status"
              allowClear
              placeholder="状态"
              value={resourceStatus}
              options={RESOURCE_STATUS.map((item) => ({ label: item.label, value: item.value }))}
              onChange={(value) => {
                setResourceStatus(value);
                window.setTimeout(() => actionRef.current?.reload(true), 0);
              }}
              style={{ width: 'min(130px, 100%)' }}
            />,
          ]}
          tableAlertRender={false}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无已接入的数据源" /> }}
        />
      </div>
      <OdsDatabaseDrawer
        open={!!createSource}
        source={createSource}
        onClose={() => setCreateSource(undefined)}
        onSuccess={reload}
      />
      <RecommendationModal
        open={recommendOpen}
        source={recommendSource}
        onClose={() => { setRecommendOpen(false); setRecommendSource(undefined); }}
        onContinue={(mode) => {
          const id = recommendSource?.sourceDataSourceId;
          setRecommendOpen(false);
          if (!id) return;
          history.push(mode === 'LOGICAL' ? `/lake/logical-access?sourceDataSourceId=${id}` : `/lake/resources/${id}`);
        }}
      />
    </PageContainer>
  );
};

export default PhysicalResourcesPage;
