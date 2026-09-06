import {
  ApartmentOutlined,
  ArrowLeftOutlined,
  CheckCircleOutlined,
  DatabaseOutlined,
  LinkOutlined,
  PlusOutlined,
  ReloadOutlined,
  SettingOutlined,
  TableOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import type { ProColumns } from '@ant-design/pro-components';
import { history, useParams } from '@umijs/max';
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
  Select,
  Space,
  Spin,
  Statistic,
  Tabs,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { TableColumnsType } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { fetchLakeOperations } from '@/services/lake';
import {
  bindUnmanagedTable,
  createOdsDatabase,
  fetchPhysicalInventory,
  fetchPhysicalSource,
  fetchSourceDatabases,
  fetchSourceSchemas,
  fetchSourceTables,
  reconcileOdsDatabase,
  unbindUnmanagedTable,
} from './service';
import type {
  ApiResponse,
  InventoryRelation,
  InventoryTable,
  OdsSourceTable,
  PhysicalDataSource,
  PhysicalInventory,
} from './types';
import {
  LakeErrorAlert,
  LakeResourceStatusTag,
  OperationTimeline,
  operationToStep,
  relationSchemaMode,
  relationSourceDataSource,
  relationTargetTable,
} from '../components/LakeStatus';
import './detail.less';

const { Paragraph, Text, Title } = Typography;

const responseMessage = <T,>(response: ApiResponse<T>, fallback: string) =>
  response.message || response.msg || fallback;

const formatTime = (value?: string) => (value ? value.replace('T', ' ').replace(/\.\d+Z$/, '') : '-');

const managementLabel: Record<string, string> = {
  MANAGED: '托管表',
  AUTO_CREATED: '自动表',
  UNMANAGED: '未纳管',
};

const relationColumns: ProColumns<InventoryRelation>[] = [
  { title: '任务', dataIndex: 'jobId', width: 110, render: (_, row) => row.jobId || '-' },
  { title: '运行类型', dataIndex: 'jobRuntimeType', width: 120 },
  { title: '范围', dataIndex: 'relationScope', width: 110 },
  { title: '版本', dataIndex: 'jobVersion', width: 90 },
  { title: '目标表', key: 'targetTable', width: 180, ellipsis: true, render: (_, row) => relationTargetTable(row) || <Text type="secondary">按任务动态生成</Text> },
  { title: 'Schema Mode', key: 'schemaSaveMode', width: 190, ellipsis: true, render: (_, row) => relationSchemaMode(row) || <Text type="secondary">未记录</Text> },
  { title: '源 DataSource', key: 'sourceDataSource', width: 130, render: (_, row) => relationSourceDataSource(row) || '-' },
  { title: '关系状态', dataIndex: 'relationStatus', width: 120, render: (_, row) => <Tag>{row.relationStatus || 'UNKNOWN'}</Tag> },
];

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
  const missingCode = !source?.unitCode || !source?.systemCode;

  useEffect(() => {
    if (open) form.resetFields();
  }, [form, open]);

  const submit = async ({ customName: value }: { customName: string }) => {
    if (!source?.sourceDataSourceId) return;
    setLoading(true);
    try {
      const response = await createOdsDatabase(source.sourceDataSourceId, value.trim());
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response, 'ODS 库创建失败'));
      message.success('ODS 库创建操作已提交');
      onSuccess();
      onClose();
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'ODS 库创建失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Drawer open={open} width={520} title="创建 ODS Database" destroyOnClose onClose={onClose}>
      <Alert
        type="info"
        showIcon
        message="先建立一个受控的 ODS 空间"
        description="数据库名由单位、系统编码和自定义名称组成。页面只提交名称，DDL 和状态由服务端负责。"
        className="lake-detail-alert"
      />
      <Descriptions bordered size="small" column={1}>
        <Descriptions.Item label="数据源">{source?.sourceDataSourceName || '-'}</Descriptions.Item>
        <Descriptions.Item label="单位 / 系统">{source?.unitCode || '-'} / {source?.systemCode || '-'}</Descriptions.Item>
        <Descriptions.Item label="固定前缀">ods_{unit}_{system}_</Descriptions.Item>
      </Descriptions>
      {missingCode ? <Alert type="warning" showIcon message="单位或系统编码缺失" description="请先在数据源主数据中补齐归属编码。" className="lake-detail-alert" /> : null}
      <Form form={form} layout="vertical" onFinish={submit} className="lake-drawer-form">
        <Form.Item
          name="customName"
          label="自定义名称"
          rules={[{ required: true, message: '请输入自定义名称' }, { max: 40, message: '最多 40 个字符' }, { pattern: /^[A-Za-z0-9_]+$/, message: '仅支持字母、数字和下划线' }]}
        >
          <Input placeholder="例如 orders" showCount maxLength={40} />
        </Form.Item>
        <Card size="small" title="完整名称预览" className="lake-name-card">
          <Text code>{fullName}</Text>
          <Text type="secondary">{fullName.length} / 64 字符</Text>
        </Card>
        <div className="lake-drawer-footer">
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" htmlType="submit" loading={loading} disabled={missingCode || !customName.trim()}>创建 ODS 库</Button>
        </div>
      </Form>
    </Drawer>
  );
};

const BindUnmanagedDrawer: React.FC<{
  open: boolean;
  source?: PhysicalDataSource;
  table?: InventoryTable;
  onClose: () => void;
  onSuccess: () => void;
}> = ({ open, source, table, onClose, onSuccess }) => {
  const [form] = Form.useForm<{ databaseFqn: string; schemaFqn: string; tableId: string }>();
  const [databases, setDatabases] = useState<Array<{ value: string; label: string }>>([]);
  const [schemas, setSchemas] = useState<Array<{ value: string; label: string }>>([]);
  const [tables, setTables] = useState<OdsSourceTable[]>([]);
  const [loading, setLoading] = useState(false);
  const databaseFqn = Form.useWatch('databaseFqn', form);
  const schemaFqn = Form.useWatch('schemaFqn', form);

  useEffect(() => {
    if (!open || !source?.sourceDataSourceId) return;
    form.resetFields();
    setSchemas([]);
    setTables([]);
    void fetchSourceDatabases(source.sourceDataSourceId).then((response) => {
      if (response.code === 0) {
        setDatabases((response.data || []).map((item) => ({ value: item.fullyQualifiedName || item.name, label: item.name || item.fullyQualifiedName })));
      }
    });
  }, [form, open, source?.sourceDataSourceId]);

  useEffect(() => {
    if (!databaseFqn || !source?.sourceDataSourceId) {
      setSchemas([]);
      return;
    }
    void fetchSourceSchemas(source.sourceDataSourceId, databaseFqn).then((response) => {
      if (response.code === 0) setSchemas((response.data || []).map((item) => ({ value: item.fullyQualifiedName, label: item.name || item.fullyQualifiedName })));
    });
  }, [databaseFqn, source?.sourceDataSourceId]);

  useEffect(() => {
    if (!databaseFqn || !schemaFqn || !source?.sourceDataSourceId) {
      setTables([]);
      return;
    }
    void fetchSourceTables(source.sourceDataSourceId, databaseFqn, schemaFqn).then((response) => {
      if (response.code === 0) setTables(response.data?.records || []);
    });
  }, [databaseFqn, schemaFqn, source?.sourceDataSourceId]);

  const submit = async (values: { databaseFqn: string; schemaFqn: string; tableId: string }) => {
    if (!source?.sourceDataSourceId || !source.odsDatabaseBindingId || !table?.targetTableName) return;
    setLoading(true);
    try {
      const response = await bindUnmanagedTable({
        odsDatabaseBindingId: source.odsDatabaseBindingId,
        targetTableName: table.targetTableName,
        sourceDataSourceId: source.sourceDataSourceId,
        omEntityId: values.tableId,
      });
      if (response.code !== 0) throw new Error(responseMessage(response, '未纳管表关联失败'));
      message.success('未纳管表已建立显式源表关联');
      onSuccess();
      onClose();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '未纳管表关联失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Drawer open={open} width={520} title="关联未纳管表" destroyOnClose onClose={onClose}>
      <Alert type="warning" showIcon message="显式关联仍保持 UNMANAGED" description="不会自动生成合同、生命周期或删除权限；这里只记录用户明确选择的源表。" className="lake-detail-alert" />
      <Descriptions bordered size="small" column={1}>
        <Descriptions.Item label="Doris 表">{table?.targetTableName || '-'}</Descriptions.Item>
        <Descriptions.Item label="数据源">{source?.sourceDataSourceName || '-'}</Descriptions.Item>
      </Descriptions>
      <Form form={form} layout="vertical" onFinish={submit} className="lake-drawer-form">
        <Form.Item name="databaseFqn" label="源数据库" rules={[{ required: true, message: '请选择数据库' }]}>
          <Select showSearch options={databases} placeholder="选择 OpenMetadata 数据库" optionFilterProp="label" />
        </Form.Item>
        <Form.Item name="schemaFqn" label="Schema" rules={[{ required: true, message: '请选择 Schema' }]}>
          <Select showSearch options={schemas} placeholder="选择 Schema" optionFilterProp="label" disabled={!databaseFqn} />
        </Form.Item>
        <Form.Item name="tableId" label="源表" rules={[{ required: true, message: '请选择源表' }]}>
          <Select showSearch options={tables.map((item) => ({ value: item.id, label: `${item.name} · ${item.fullyQualifiedName}` }))} placeholder="选择源表" optionFilterProp="label" disabled={!schemaFqn} />
        </Form.Item>
        <div className="lake-drawer-footer">
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" htmlType="submit" loading={loading}>确认关联</Button>
        </div>
      </Form>
    </Drawer>
  );
};

const PhysicalResourceDetailPage: React.FC = () => {
  const { sourceDataSourceId } = useParams<{ sourceDataSourceId?: string }>();
  const sourceId = Number(sourceDataSourceId);
  const [source, setSource] = useState<PhysicalDataSource>();
  const [inventory, setInventory] = useState<PhysicalInventory>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [odsOpen, setOdsOpen] = useState(false);
  const [bindTarget, setBindTarget] = useState<InventoryTable>();
  const [unbindLoadingId, setUnbindLoadingId] = useState<number>();
  const [reconcileLoading, setReconcileLoading] = useState(false);
  const [operations, setOperations] = useState<import('@/services/lake').LakeResourceOperation[]>([]);

  const load = useCallback(async () => {
    if (!Number.isInteger(sourceId) || sourceId <= 0) {
      setError('数据源标识无效');
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(undefined);
    try {
      const sourceResponse = await fetchPhysicalSource(sourceId);
      if (sourceResponse.code !== 0 || !sourceResponse.data) throw new Error(responseMessage(sourceResponse, '数据源详情加载失败'));
      setSource(sourceResponse.data);
      if (sourceResponse.data.odsDatabaseBindingId) {
        const inventoryResponse = await fetchPhysicalInventory(sourceResponse.data.odsDatabaseBindingId);
        if (inventoryResponse.code !== 0) throw new Error(responseMessage(inventoryResponse, 'ODS 库库存加载失败'));
        setInventory(inventoryResponse.data);
        void fetchLakeOperations('ODS_DATABASE_BINDING', sourceResponse.data.odsDatabaseBindingId)
          .then((operationResponse) => {
            if (operationResponse.code === 0) setOperations(operationResponse.data || []);
          })
          .catch(() => undefined);
      } else {
        setInventory(undefined);
        setOperations([]);
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '物理入湖详情加载失败');
    } finally {
      setLoading(false);
    }
  }, [sourceId]);

  useEffect(() => { void load(); }, [load]);

  const reconcile = async () => {
    const id = source?.odsDatabase?.id;
    if (!id) return;
    setReconcileLoading(true);
    try {
      const response = await reconcileOdsDatabase(id);
      if (response.code !== 0) throw new Error(responseMessage(response, '对账失败'));
      message.success('ODS 状态已重新读取');
      await load();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '对账失败');
    } finally {
      setReconcileLoading(false);
    }
  };

  const unbind = (row: InventoryTable) => {
    const mappingId = row.mappingId;
    if (!mappingId) return;
    Modal.confirm({
      title: '解除未纳管表关联？',
      content: `将移除“${row.targetTableName || '-'}”的显式源表关联。Doris 实际表不会被删除，表仍保持未纳管状态。`,
      okText: '确认解除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        setUnbindLoadingId(mappingId);
        try {
          const response = await unbindUnmanagedTable(mappingId);
          if (response.code !== 0) throw new Error(responseMessage(response, '解除关联失败'));
          message.success('未纳管表关联已解除');
          await load();
        } catch (reason) {
          message.error(reason instanceof Error ? reason.message : '解除关联失败');
          throw reason;
        } finally {
          setUnbindLoadingId(undefined);
        }
      },
    });
  };

  const registeredTables = inventory?.registeredTables || [];
  const discoveredTables = inventory?.discoveredTables || [];
  const tableColumns: TableColumnsType<InventoryTable> = useMemo(() => [
    {
      title: 'Doris 表', dataIndex: 'targetTableName', ellipsis: true,
      render: (_, row) => row.mappingId ? <Button type="link" className="lake-table-link" onClick={() => history.push(`/lake/resources/table/${row.mappingId}`)}>{row.targetTableName}<LinkOutlined /></Button> : row.targetTableName,
    },
    { title: '管理等级', dataIndex: 'managementLevel', width: 120, render: (_, row) => <Tag color={row.managementLevel === 'MANAGED' ? 'blue' : row.managementLevel === 'AUTO_CREATED' ? 'cyan' : 'default'}>{managementLabel[row.managementLevel || ''] || '未纳管'}</Tag> },
    { title: 'Resource', dataIndex: 'resourceStatus', width: 130, render: (_, row) => <LakeResourceStatusTag status={row.resourceStatus} /> },
    { title: '实际存在', dataIndex: 'actualExists', width: 100, render: (_, row) => row.actualExists ? <Tag color="success">存在</Tag> : <Tag color="error">缺失</Tag> },
    { title: '源表关联', dataIndex: 'sourceBound', width: 110, render: (_, row) => row.sourceBound ? <Tag color="success">已关联</Tag> : <Tag>未关联</Tag> },
    {
      title: '操作', key: 'action', width: 180,
      render: (_, row) => (
        <Space size={4} wrap>
          {row.mappingId ? <Button type="link" onClick={() => history.push(`/lake/resources/table/${row.mappingId}`)}>查看详情</Button> : null}
          {row.managementLevel === 'UNMANAGED' && !row.sourceBound ? <Button type="link" onClick={() => setBindTarget(row)}>关联源表</Button> : null}
          {row.managementLevel === 'UNMANAGED' && row.sourceBound && row.mappingId ? <Button type="link" danger loading={unbindLoadingId === row.mappingId} onClick={() => unbind(row)}>解除关联</Button> : null}
        </Space>
      ),
    },
  ], [load, unbindLoadingId]);

  if (loading) return <PageContainer title="物理入湖详情"><Spin /></PageContainer>;
  if (error || !source) return <PageContainer title="物理入湖详情"><Alert type="error" showIcon message={error || '未找到数据源'} action={<Button onClick={() => void load()}>重试</Button>} /></PageContainer>;

  const database = source.odsDatabase;
  const sourceLabel = source.sourceDataSourceName || `DataSource #${source.sourceDataSourceId}`;
  const operationItems = operations.map(operationToStep);

  return (
    <PageContainer
      title={sourceLabel}
      subTitle="物理入湖资源详情"
      onBack={() => history.push('/lake/resources')}
      breadcrumb={{ items: [{ title: '物理入湖', onClick: () => history.push('/lake/resources') }, { title: sourceLabel }] }}
      extra={[
        <Button key="back" icon={<ArrowLeftOutlined />} onClick={() => history.push('/lake/resources')}>返回资源列表</Button>,
        database ? <Button key="reconcile" icon={<ReloadOutlined />} loading={reconcileLoading} onClick={() => void reconcile()}>显式对账</Button> : null,
        database ? <Button key="create-table" type="primary" icon={<PlusOutlined />} onClick={() => history.push(`/lake/resources/table/create?sourceDataSourceId=${source.sourceDataSourceId}&odsDatabaseBindingId=${database.id}`)}>创建 MANAGED 表</Button> : <Button key="create-db" type="primary" icon={<DatabaseOutlined />} onClick={() => setOdsOpen(true)}>创建 ODS 库</Button>,
      ].filter(Boolean)}
    >
      <div className="lake-resource-detail">
        <Card className="lake-detail-hero" bordered={false}>
          <div className="lake-detail-hero-icon"><DatabaseOutlined /></div>
          <div className="lake-detail-hero-copy">
            <div className="lake-detail-kicker">业务数据源</div>
            <Title level={3}>{sourceLabel}</Title>
            <Paragraph type="secondary">{source.unitCode || '未归属单位'} / {source.systemCode || '未归属系统'} · 资源状态由 Doris 实际读取结果决定</Paragraph>
          </div>
          <div className="lake-detail-hero-status"><LakeResourceStatusTag status={database?.resourceStatus} /></div>
        </Card>
        <div className="lake-summary-grid">
          <Card><Statistic title="已登记表" value={registeredTables.length} prefix={<TableOutlined />} /></Card>
          <Card><Statistic title="未纳管发现" value={discoveredTables.length} prefix={<ApartmentOutlined />} /></Card>
          <Card><Statistic title="任务关系" value={(inventory?.tableRelations?.length || 0) + (inventory?.namespaceRelations?.length || 0)} prefix={<ThunderboltOutlined />} /></Card>
          <Card><Statistic title="最近对账" value={formatTime(database?.lastReconcileAt)} prefix={<ReloadOutlined />} /></Card>
        </div>
        <LakeErrorAlert code={database?.errorCode} message={database?.errorMessage} action={database?.id ? <Button type="link" onClick={() => void reconcile()}>重新读取 Doris 状态</Button> : undefined} />
        <Card className="lake-detail-card" bordered={false}>
          <Descriptions column={{ xs: 1, sm: 2, md: 3 }} size="small">
            <Descriptions.Item label="单位">{source.unitCode || '-'}</Descriptions.Item>
            <Descriptions.Item label="业务系统">{source.systemCode || '-'}</Descriptions.Item>
            <Descriptions.Item label="源类型">{source.dbType || '未知'}</Descriptions.Item>
            <Descriptions.Item label="源 DataSource ID">{source.sourceDataSourceId}</Descriptions.Item>
            <Descriptions.Item label="ODS Database">{database?.databaseName || '未创建'}</Descriptions.Item>
            <Descriptions.Item label="最近对账">{formatTime(database?.lastReconcileAt)}</Descriptions.Item>
            <Descriptions.Item label="数据库状态"><LakeResourceStatusTag status={database?.resourceStatus} /></Descriptions.Item>
          </Descriptions>
        </Card>
        <Tabs
          className="lake-detail-tabs"
          items={[
            {
              key: 'resources', label: 'ODS 资源', children: (
                <Space direction="vertical" size={18} className="lake-tab-content">
                  <Card title={<Space><TableOutlined />已登记资源</Space>} extra={<Text type="secondary">MANAGED / AUTO_CREATED / 已关联 UNMANAGED</Text>}>
                    <Table rowKey={(row) => String(row.mappingId || row.targetTableName)} columns={tableColumns} dataSource={registeredTables} pagination={false} scroll={{ x: 900 }} locale={{ emptyText: <Empty description="暂无已登记表" /> }} />
                  </Card>
                  <Card title={<Space><ApartmentOutlined />发现的未纳管表</Space>} extra={<Text type="secondary">仅观察，不自动猜测源表</Text>}>
                    <Table rowKey={(row) => String(row.targetTableName)} columns={tableColumns} dataSource={discoveredTables} pagination={false} scroll={{ x: 900 }} locale={{ emptyText: <Empty description="没有发现未纳管表" /> }} />
                  </Card>
                </Space>
              ),
            },
            {
              key: 'relations', label: '关联引接', children: (
                <Space direction="vertical" size={18} className="lake-tab-content">
                  <Card title="TABLE Relations"><ProTable search={false} options={false} pagination={false} rowKey={(row) => String(row.relationId)} columns={relationColumns} dataSource={inventory?.tableRelations || []} locale={{ emptyText: <Empty description="暂无表级任务关系" /> }} /></Card>
                  <Card title="NAMESPACE Relations"><Alert type="info" showIcon message="动态任务产生的表按未纳管发现" description="页面不会根据表名自动猜测源表；需要关联时请在未纳管表区域显式选择。" /><ProTable search={false} options={false} pagination={false} rowKey={(row) => String(row.relationId)} columns={relationColumns} dataSource={inventory?.namespaceRelations || []} locale={{ emptyText: <Empty description="暂无命名空间任务关系" /> }} /></Card>
                </Space>
              ),
            },
            {
              key: 'operations', label: '操作记录', children: (
                <Card title={<Space><SettingOutlined />资源操作时间线</Space>}><OperationTimeline items={operationItems} emptyText="ODS 库尚未建立操作记录" /></Card>
              ),
            },
          ]}
        />
      </div>
      <OdsDatabaseDrawer open={odsOpen} source={source} onClose={() => setOdsOpen(false)} onSuccess={() => void load()} />
      <BindUnmanagedDrawer open={!!bindTarget} source={source} table={bindTarget} onClose={() => setBindTarget(undefined)} onSuccess={() => void load()} />
    </PageContainer>
  );
};

export default PhysicalResourceDetailPage;
