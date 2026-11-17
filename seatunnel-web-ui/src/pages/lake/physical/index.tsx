import {
  CheckCircleOutlined,
  LinkOutlined,
  ReloadOutlined,
  RightOutlined,
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
  Tooltip,
  Pagination,
  Dropdown,
  Tag,
  Typography,
  message,
} from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  createOdsDatabase,
  fetchPhysicalSummary,
  fetchPhysicalSource,
  fetchPhysicalSources,
  recommendLakeMode,
  reconcileOdsDatabase,
  retryOdsDatabase,
} from './service';
import type { LakeRecommendation } from './service';
import type { LakePage, LakeResourceStatus, OdsDatabase, PhysicalDataSource } from './types';
import { useMasterDataNames } from '../useMasterDataNames';
import { withTimeout } from '@/utils/withTimeout';
import { capabilityReasonLabel } from '../components/LakeStatus';
import './index.less';

const { Paragraph, Text } = Typography;

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

const inferAdapter = (source?: PhysicalDataSource): string | undefined => {
  const type = String((source as PhysicalDataSource & { dbType?: string })?.dbType || '').toUpperCase();
  if (type === 'POSTGRE_SQL' || type.includes('POSTGRES')) return 'POSTGRESQL';
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

const RecommendationModal: React.FC<{
  open: boolean;
  source?: PhysicalDataSource;
  onClose: () => void;
  onContinue: (mode: string) => void;
}> = ({ open, source, onClose, onContinue }) => {
  const [intent, setIntent] = useState('PHYSICAL');
  const [loading, setLoading] = useState(false);
  const [recommendation, setRecommendation] = useState<LakeRecommendation>();
  useEffect(() => { setRecommendation(undefined); setIntent('PHYSICAL'); }, [open, source?.sourceDataSourceId]);
  const submit = async () => {
    if (!source?.sourceDataSourceId) return;
    setLoading(true);
    setRecommendation(undefined);
    try {
      const physical = intent === 'PHYSICAL';
      const response = await recommendLakeMode({
        sourceDataSourceId: source.sourceDataSourceId,
        adapter: inferAdapter(source) || 'MYSQL', targetScope: 'ALL',
        moveData: physical, physicalGovernance: physical, joinOnly: !physical,
      });
      if (response.code !== 0 || !response.data) throw new Error(response.message || response.msg || '推荐服务暂不可用');
      setRecommendation(response.data);
    } catch (error) { message.error(error instanceof Error ? error.message : '推荐服务暂不可用'); }
    finally { setLoading(false); }
  };
  return <Modal open={open} title="选择入湖方式" width={600} onCancel={onClose} footer={<Space>
    <Button onClick={onClose}>取消</Button>
    <Button onClick={submit} loading={loading}>检查可用性</Button>
    <Button type="primary" disabled={!recommendation || recommendation.mode === 'UNSUPPORTED'} onClick={() => onContinue(recommendation?.mode || 'UNSUPPORTED')}>继续</Button>
  </Space>}>
    <Paragraph>数据源：{source?.sourceDataSourceName}</Paragraph>
    <Radio.Group value={intent} onChange={(event) => { setIntent(event.target.value); setRecommendation(undefined); }}>
      <Space direction="vertical" size="middle">
        <Radio value="PHYSICAL">复制到湖内，集中存储和管理</Radio>
        <Radio value="LOGICAL" disabled={!inferAdapter(source)}>保留在原数据源，通过外部挂载查询</Radio>
      </Space>
    </Radio.Group>
    {!inferAdapter(source) && <Paragraph type="secondary" style={{ marginTop: 12 }}>当前逻辑入湖支持 MySQL、PostgreSQL 和 Oracle。</Paragraph>}
    {recommendation && <Alert style={{ marginTop: 20 }} type={recommendation.disabledReasons?.length || recommendation.mode === 'UNSUPPORTED' ? 'warning' : 'success'} showIcon
      message={`建议：${recommendationModeLabel[recommendation.mode || 'UNSUPPORTED']}`}
      description={recommendation.disabledReasons?.map(capabilityReasonLabel).join('、')} />}
  </Modal>;
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
      <Form name="lake-ods-custom" form={form} layout="vertical" onFinish={submit} className="lake-ods-form">
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
  const { unitNameByCode, systemNameByCode } = useMasterDataNames();
  const [createSource, setCreateSource] = useState<PhysicalDataSource>();
  const [recommendSource, setRecommendSource] = useState<PhysicalDataSource>();
  const [operationId, setOperationId] = useState<number>();
  const [operationLoading, setOperationLoading] = useState(false);
  const [recommendOpen, setRecommendOpen] = useState(false);
  const [startingId, setStartingId] = useState<number>();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [resourceStatus, setResourceStatus] = useState<LakeResourceStatus>();
  const [loadedSources, setLoadedSources] = useState<PhysicalDataSource[]>([]);
  const [listError, setListError] = useState<string>();
  const [summaryError, setSummaryError] = useState<string>();
  const [summary, setSummary] = useState<{ boundDataSourceCount?: number; odsTableCount?: number; pendingExceptionCount?: number }>();
  const recommendationSourceId = useMemo(() => {
    const value = new URLSearchParams(location.search).get('recommendSourceDataSourceId');
    return value ? Number(value) : undefined;
  }, [location.search]);

  const loadSummary = () => {
    setSummaryError(undefined);
    setSummary(undefined);
    void withTimeout(fetchPhysicalSummary(), 10000, '物理入湖汇总请求超时，请稍后重试')
      .then((response) => {
        if (response.code !== 0) throw new Error(response.message || response.msg || '物理入湖汇总加载失败');
        setSummary(response.data);
      })
      .catch((error) => {
        setSummaryError(error instanceof Error ? error.message : '物理入湖汇总加载失败');
      });
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

  const physicalTypes = ['MYSQL', 'POSTGRE_SQL', 'ORACLE', 'JDBC', 'DORIS', 'DAMENG', 'KINGBASE', 'VASTBASE'];
  const startPhysical = async (record: PhysicalDataSource) => {
    setStartingId(record.sourceDataSourceId);
    try {
      // Re-read before creating so a stale list cannot produce a second binding.
      const latest = await fetchPhysicalSource(record.sourceDataSourceId);
      if (latest.code !== 0 || !latest.data) throw new Error(latest.message || latest.msg || '数据源读取失败');
      if (latest.data.sourceEnabled === false) {
        message.warning('请先在数据源管理中为该连接开启来源用途');
        history.push('/data-source');
        return;
      }
      let database = latest.data.odsDatabase;
      if (!database || database.resourceStatus === 'DELETED') {
        const created = await createOdsDatabase(record.sourceDataSourceId, `ds_${record.sourceDataSourceId}`);
        if (created.code !== 0 || !created.data) throw new Error(created.message || created.msg || 'ODS 库准备失败，请检查数据源的单位和业务系统归属');
        database = created.data;
      }
      if (database.resourceStatus !== 'READY') {
        message.info('ODS 库尚未就绪，请在资源详情中查看状态或重试');
        history.push(`/lake/resources/${record.sourceDataSourceId}`);
        return;
      }
      history.push(`/lake/resources/table/create?sourceDataSourceId=${record.sourceDataSourceId}&odsDatabaseBindingId=${database.id}`);
    } catch (error) { message.error(error instanceof Error ? error.message : '准备入湖失败'); }
    finally { setStartingId(undefined); reload(); }
  };

  const columns = useMemo<ProColumns<PhysicalDataSource>[]>(
    () => [
      {
        title: '单位 / 业务系统',
        key: 'owner',
        width: 190,
        render: (_, record) => (
          <div>
            <div>{unitNameByCode(record.unitCode) || '待归属'}</div>
            <Text type="secondary">{systemNameByCode(record.systemCode) || '待归属'}</Text>
          </div>
        ),
      },
      {
        title: '数据源',
        dataIndex: 'sourceDataSourceName',
        key: 'sourceDataSourceName',
        width: 210,
        ellipsis: true,
        render: (_, record) => (
          <Button type="link" className="lake-source-link" onClick={() => history.push(`/lake/resources/${record.sourceDataSourceId}`)}>
            {record.sourceDataSourceName || `DataSource #${record.sourceDataSourceId}`}
            <RightOutlined />
          </Button>
        ),
      },
      {
        title: 'ODS 库',
        key: 'databaseName',
        width: 230,
        ellipsis: true,
        render: (_, record) => record.odsDatabase?.databaseName || <Text type="secondary">未创建</Text>,
      },
      {
        title: '资源状态',
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
        responsive: ['xxl'],
        render: (_, record) => formatTime(record.odsDatabase?.lastReconcileAt),
      },
      {
        title: '操作',
        key: 'option',
        valueType: 'option',
        width: 220,
        render: (_, record) => {
          const database = record.odsDatabase;
          const canRetry = !!database && ['ERROR', 'CREATE_FAILED', 'MISSING', 'UNKNOWN', 'PENDING_CREATE'].includes(database.resourceStatus || '');
          const canReconcile = !!database && database.resourceStatus !== 'DELETED';
          const supported = physicalTypes.includes(String(record.dbType || ''));
          const busy = !!database && ['CREATING', 'DELETING'].includes(database.resourceStatus || '');
          return <Space wrap size={4}>
            <Tooltip title={record.sourceEnabled === false ? '该连接未开启来源用途，请先到数据源管理中开启' : !supported ? '此数据类型尚不支持可视化创建 ODS 表' : busy ? '资源正在处理，可查看详情' : undefined}>
              <Button type="link" disabled={!supported || busy || record.sourceEnabled === false} loading={startingId === record.sourceDataSourceId}
                onClick={() => void startPhysical(record)}>{database?.resourceStatus === 'READY' ? '继续建表' : '开始入湖'}</Button>
            </Tooltip>
            <Button type="link" onClick={() => { setRecommendSource(record); setRecommendOpen(true); }}>选择方式</Button>
            <Dropdown menu={{ items: [
              { key: 'detail', label: '查看资源详情', onClick: () => history.push(`/lake/resources/${record.sourceDataSourceId}`) },
              ...(!database && record.sourceEnabled !== false ? [{ key: 'custom', label: '自定义 ODS 库名', onClick: () => setCreateSource(record) }] : []),
              ...(record.sourceEnabled === false ? [{ key: 'usage', label: '配置数据源用途', onClick: () => history.push('/data-source') }] : []),
              ...(canRetry ? [{ key: 'retry', label: '重试', onClick: () => void operate('retry', database) }] : []),
              ...(canReconcile ? [{ key: 'reconcile', label: '刷新实际状态', onClick: () => void operate('reconcile', database) }] : []),
            ] }}><Button type="link" loading={operationLoading && operationId === database?.id}>更多</Button></Dropdown>
          </Space>;
        },
      },
    ],
    [operationId, operationLoading, startingId, unitNameByCode, systemNameByCode],
  );

  return (
    <PageContainer
      title={false}
      extra={[
        <Button key="datasource" type="link" icon={<LinkOutlined />} onClick={() => history.push('/data-source')}>
          查看数据源
        </Button>,
        <Button key="refresh" icon={<ReloadOutlined />} onClick={reload}>
          刷新列表
        </Button>,
      ]}
    >
      <div className="lake-physical-page">
        <div className="lake-physical-summary">
          <Card size="small" className="lake-physical-stat"><Statistic title="已建立 ODS 关联" value={summary?.boundDataSourceCount ?? '—'} /></Card>
          <Card size="small" className="lake-physical-stat"><Statistic title="平台纳管 ODS 表" value={summary?.odsTableCount ?? '—'} /></Card>
          <Card size="small" className="lake-physical-stat" data-warning={(summary?.pendingExceptionCount || 0) > 0}><Statistic title="待处理异常" value={summary?.pendingExceptionCount ?? '—'} /></Card>
        </div>
        {summaryError ? (
          <Alert
            className="lake-table-alert"
            type="warning"
            showIcon
            message="物理入湖汇总加载失败"
            description={summaryError}
            action={<Button type="link" onClick={loadSummary}>重试</Button>}
          />
        ) : null}
        {listError ? (
          <Alert
            className="lake-table-alert"
            type="error"
            showIcon
            message="物理入湖列表加载失败"
            description={listError}
            action={<Button type="link" onClick={reload}>重试</Button>}
          />
        ) : null}
        <ProTable<PhysicalDataSource>
          actionRef={actionRef}
          rowKey="sourceDataSourceId"
          cardBordered
          columns={columns}
          scroll={{ x: '100%' }}
          tableLayout="fixed"
          search={false}
          options={false}
          pagination={false}
          params={{ current: page, pageSize }}
          request={async (params) => {
            setListError(undefined);
            try {
              const response = await withTimeout(
                fetchPhysicalSources({
                  pageNo: params.current || 1,
                  pageSize: params.pageSize || 10,
                  keyword: keyword || undefined,
                  resourceStatus: resourceStatus || undefined,
                }),
                10000,
                '物理入湖列表请求超时，请稍后重试',
              );
              if (response.code !== 0) throw new Error(response.message || response.msg || '物理入湖列表加载失败');
              const page = unwrapPage(response.data);
              setLoadedSources(page.rows);
              setTotal(page.total);
              return { data: page.rows, success: true, total: page.total };
            } catch (error) {
              setTotal(0);
              setListError(error instanceof Error ? error.message : '物理入湖列表加载失败');
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
              onSearch={() => { setPage(1); actionRef.current?.reload(); }}
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
                setPage(1);
                window.setTimeout(() => actionRef.current?.reload(true), 0);
              }}
              style={{ width: 'min(130px, 100%)' }}
            />,
          ]}
          tableAlertRender={false}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无已接入的数据源" /> }}
        />
        <div className="lake-fixed-pagination"><Pagination current={page} pageSize={pageSize} total={total} showSizeChanger showTotal={(count) => `共 ${count} 个数据源`} onChange={(next, size) => { setPage(next); setPageSize(size); }} /></div>
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
          if (mode === 'LOGICAL') history.push(`/lake/logical-access?sourceDataSourceId=${id}`);
          else if (recommendSource) void startPhysical(recommendSource);
        }}
      />
    </PageContainer>
  );
};

export default PhysicalResourcesPage;
