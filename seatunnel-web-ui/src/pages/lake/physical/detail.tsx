import {
  ArrowLeftOutlined,
  DatabaseOutlined,
  LinkOutlined,
  MoreOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import type { ProColumns } from '@ant-design/pro-components';
import { history, useParams } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Collapse,
  Descriptions,
  Drawer,
  Dropdown,
  Empty,
  Form,
  Modal,
  Select,
  Space,
  Spin,
  Tabs,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { TableColumnsType } from 'antd';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { fetchLakeOperations } from '@/services/lake';
import type { LakeResourceOperation } from '@/services/lake';
import {
  bindUnmanagedTable,
  createOdsDatabase,
  fetchPhysicalInventory,
  fetchPhysicalSource,
  fetchSourceDatabases,
  fetchSourceSchemas,
  fetchSourceTables,
  reconcileOdsDatabase,
  retryOdsDatabase,
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
  LakeResourceStatusTag,
  OperationTimeline,
  operationToStep,
  relationSchemaMode,
  relationSourceDataSource,
  relationTargetTable,
} from '../components/LakeStatus';
import { useMasterDataNames } from '../useMasterDataNames';
import { physicalResourcesPath, physicalTableCreatePath } from './paths';
import './detail.less';

const { Text } = Typography;

const responseMessage = <T,>(response: ApiResponse<T>, fallback: string) =>
  response.message || response.msg || fallback;

const formatTime = (value?: string) => (value ? value.replace('T', ' ').replace(/\.\d+Z$/, '') : '-');

const managementLabel: Record<string, string> = {
  MANAGED: '平台建表',
  AUTO_CREATED: '任务建表',
  UNMANAGED: '已有表',
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
    <Drawer open={open} width={520} title="关联已有表" destroyOnHidden onClose={onClose}>
      <Alert type="info" showIcon message="此操作只登记已有表的来源" className="lake-detail-alert" />
      <Descriptions bordered size="small" column={1}>
        <Descriptions.Item label="Doris 表">{table?.targetTableName || '-'}</Descriptions.Item>
        <Descriptions.Item label="数据源">{source?.sourceDataSourceName || '-'}</Descriptions.Item>
      </Descriptions>
      <Form name="lake-source-table-picker" form={form} layout="vertical" onFinish={submit} className="lake-drawer-form">
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

const PhysicalResourceDetail: React.FC<{ sourceId: string }> = ({ sourceId }) => {
  const { unitNameByCode, systemNameByCode } = useMasterDataNames();
  const [source, setSource] = useState<PhysicalDataSource>();
  const [inventory, setInventory] = useState<PhysicalInventory>();
  const [inventoryError, setInventoryError] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [creatingDatabase, setCreatingDatabase] = useState(false);
  const [bindTarget, setBindTarget] = useState<InventoryTable>();
  const [unbindLoadingId, setUnbindLoadingId] = useState<number>();
  const [reconcileLoading, setReconcileLoading] = useState(false);
  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false);
  const [diagnosticTab, setDiagnosticTab] = useState('resources');
  const [operations, setOperations] = useState<LakeResourceOperation[]>([]);
  const requestId = useRef(0);

  const load = useCallback(async () => {
    const request = ++requestId.current;
    if (!/^[1-9]\d*$/.test(sourceId)) {
      setError('数据源标识无效');
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(undefined);
    setInventoryError(undefined);
    try {
      const sourceResponse = await fetchPhysicalSource(sourceId);
      if (sourceResponse.code !== 0 || !sourceResponse.data) throw new Error(responseMessage(sourceResponse, '数据源详情加载失败'));
      if (request !== requestId.current) return;
      const current = sourceResponse.data;
      const database = current.odsDatabase;
      const bindingId = current.odsDatabaseBindingId ?? database?.id;
      if (String(current.sourceDataSourceId) !== sourceId
        || (database?.sourceDataSourceId != null && String(database.sourceDataSourceId) !== sourceId)
        || (database && String(database.id) !== String(bindingId))) {
        console.warn('[lake-physical-detail] 详情资源归属不匹配', { sourceId, returnedSourceId: current.sourceDataSourceId, bindingId });
        throw new Error('返回的入湖库与当前数据源不匹配，请重新加载。');
      }
      setSource(current);
      setInventory(undefined);
      if (bindingId && database?.resourceStatus === 'READY') {
        try {
          const response = await fetchPhysicalInventory(bindingId);
          if (request !== requestId.current) return;
          if (response.code !== 0 || !response.data) throw new Error(responseMessage(response, '入湖表列表读取失败'));
          if (String(response.data.odsDatabaseBindingId) !== String(bindingId)) throw new Error('返回的表列表与当前入湖库不匹配');
          setInventory(response.data);
        } catch (reason) {
          if (request !== requestId.current) return;
          console.warn('[lake-physical-detail] 入湖表列表读取失败', { sourceId, bindingId, reason });
          setInventoryError('暂时无法读取入湖表，请重新加载。');
        }
      }
    } catch (reason) {
      if (request !== requestId.current) return;
      console.error('[lake-physical-detail] 加载资源详情失败', { sourceId, reason });
      setError(reason instanceof Error ? reason.message : '物理入湖详情加载失败');
    } finally {
      if (request === requestId.current) setLoading(false);
    }
  }, [sourceId]);

  useEffect(() => {
    void load();
    // Route changes unmount the detail. Ignore requests from that previous page even if they finish late.
    return () => { requestId.current += 1; };
  }, [load]);

  const database = source?.odsDatabase;
  const databaseId = database?.id;
  const lastCheck = database?.lastReconcileAt;
  useEffect(() => {
    if (!diagnosticsOpen || !databaseId) return;
    let active = true;
    void fetchLakeOperations('ODS_DATABASE_BINDING', databaseId).then((response) => {
      if (active && response.code === 0) setOperations(response.data || []);
    }).catch((reason) => {
      if (active) console.warn('[lake-physical-detail] 入湖库操作记录读取失败', { databaseId, reason });
    });
    return () => { active = false; };
  }, [databaseId, diagnosticsOpen, lastCheck]);

  const startPhysical = async () => {
    if (!source?.sourceDataSourceId) return;
    const request = requestId.current;
    setCreatingDatabase(true);
    try {
      // Use the same deterministic name as the list entry; no manual database setup is needed.
      const response = await createOdsDatabase(source.sourceDataSourceId, `ds_${source.sourceDataSourceId}`);
      if (request !== requestId.current) return;
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response, 'ODS 库准备失败'));
      if (response.data.resourceStatus === 'READY') {
        history.push(physicalTableCreatePath(sourceId, response.data.id));
      } else {
        message.info('ODS 库尚未就绪，请查看状态或重试');
        await load();
      }
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '准备入湖失败');
    } finally {
      setCreatingDatabase(false);
    }
  };

  const checkDatabase = async (retry = false) => {
    const id = source?.odsDatabase?.id;
    if (!id) return;
    const request = requestId.current;
    setReconcileLoading(true);
    try {
      const response = retry ? await retryOdsDatabase(id) : await reconcileOdsDatabase(id);
      if (request !== requestId.current) return;
      if (response.code !== 0) throw new Error(responseMessage(response, '入湖库操作失败'));
      message.success(retry ? '已重新准备入湖库' : '入湖库检查完成');
      await load();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '入湖库操作失败');
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
  const tablePath = (row: InventoryTable) => `${physicalResourcesPath}/table/${row.mappingId}`;
  const tableColumns: TableColumnsType<InventoryTable> = [
    {
      title: '入湖表', dataIndex: 'targetTableName',
      render: (_, row) => row.mappingId ? <Button type="link" className="lake-table-link" onClick={() => history.push(tablePath(row))}>{row.targetTableName}<LinkOutlined /></Button> : row.targetTableName,
    },
    { title: '管理等级', dataIndex: 'managementLevel', width: 120, render: (_, row) => <Tag color={row.managementLevel === 'MANAGED' ? 'blue' : row.managementLevel === 'AUTO_CREATED' ? 'cyan' : 'default'}>{managementLabel[row.managementLevel || ''] || '未纳管'}</Tag> },
    { title: '表状态', dataIndex: 'resourceStatus', width: 130, render: (_, row) => <LakeResourceStatusTag status={row.resourceStatus} /> },
    { title: '实际存在', dataIndex: 'actualExists', width: 100, render: (_, row) => row.actualExists === undefined ? '待检查' : row.actualExists ? '存在' : '缺失' },
    { title: '源表关联', dataIndex: 'sourceBound', width: 110, render: (_, row) => row.sourceBound ? <Tag color="success">已关联</Tag> : <Tag>未关联</Tag> },
    {
      title: '操作', key: 'action', width: 180,
      render: (_, row) => (
        <Space size={4} wrap>
          {row.mappingId ? <Button type="link" onClick={() => history.push(tablePath(row))}>查看详情</Button> : null}
          {row.managementLevel === 'UNMANAGED' && !row.sourceBound ? <Button type="link" onClick={() => setBindTarget(row)}>关联源表</Button> : null}
          {row.managementLevel === 'UNMANAGED' && row.sourceBound && row.mappingId ? <Button type="link" danger loading={unbindLoadingId === row.mappingId} onClick={() => unbind(row)}>解除关联</Button> : null}
        </Space>
      ),
    },
  ];

  const linkedTableIds = new Set((inventory?.tableRelations || [])
    .filter((relation) => relation.relationStatus === 'ACTIVE').map((relation) => relation.tableMappingId));
  const overviewColumns: TableColumnsType<InventoryTable> = [
    tableColumns[0],
    { title: '表状态', key: 'status', width: 160, render: (_, row) => row.actualExists === false && row.resourceStatus === 'READY'
      ? <Tag color="error">目标表缺失</Tag> : <LakeResourceStatusTag status={row.resourceStatus} /> },
    { title: '引接任务', key: 'task', width: 160, render: (_, row) => linkedTableIds.has(row.mappingId)
      ? '已配置' : row.managementLevel === 'UNMANAGED' ? '—' : '待配置' },
  ];

  const backToResources = () => history.push(physicalResourcesPath);
  const backButton = <Button key="back" icon={<ArrowLeftOutlined />} onClick={backToResources}>返回物理入湖</Button>;
  // 加载和失败分支同样保留导航，避免 iframe 内出错后没有返回入口。
  if (loading || error || !source) {
    return (
      <PageContainer title={false} extra={[backButton]}>
        {loading ? <Spin /> : <Alert type="error" showIcon message={error || '未找到数据源'} action={<Button onClick={() => void load()}>重试</Button>} />}
      </PageContainer>
    );
  }

  const sourceLabel = source.sourceDataSourceName || '数据源';
  const operationItems = operations.map(operationToStep);
  const canRetry = database && ['CREATE_FAILED', 'ERROR', 'MISSING', 'UNKNOWN', 'PENDING_CREATE'].includes(database.resourceStatus || '');
  const ready = database?.resourceStatus === 'READY';
  const canStart = source.sourceEnabled !== false && ['MYSQL', 'POSTGRE_SQL', 'ORACLE', 'JDBC', 'DORIS', 'DAMENG', 'KINGBASE', 'VASTBASE'].includes(source.dbType || '');
  const emptyTables = canStart
    ? (ready ? '还没有入湖表。点击“新增入湖表”选择来源表。' : '入湖库就绪后可新增入湖表')
    : (source.sourceEnabled === false ? '请在数据源管理中开启来源用途' : '当前数据源类型暂不支持创建入湖表');

  return (
    <PageContainer
      title={sourceLabel}
      breadcrumb={{ items: [{ title: '物理入湖', onClick: () => history.push('/lake/resources') }, { title: sourceLabel }] }}
      extra={[
        backButton,
        database && database.resourceStatus !== 'DELETED' ? <Dropdown key="more" trigger={['click']} menu={{
          items: [
            { key: 'check', label: '检查入湖库' },
            ...(discoveredTables.length ? [{ key: 'associate', label: '关联已有表' }] : []),
          ],
          onClick: ({ key }) => {
            if (key === 'check') void checkDatabase();
            else { setDiagnosticTab('resources'); setDiagnosticsOpen(true); }
          },
        }}><Button icon={<MoreOutlined />} loading={reconcileLoading}>更多操作</Button></Dropdown> : null,
        ready ? <Button key="create-table" type="primary" icon={<PlusOutlined />} disabled={!canStart}
          onClick={() => history.push(physicalTableCreatePath(sourceId, database.id))}>新增入湖表</Button>
          : !database || database.resourceStatus === 'DELETED'
            ? <Button key="create-db" type="primary" icon={<DatabaseOutlined />} loading={creatingDatabase} disabled={!canStart} onClick={() => void startPhysical()}>开始入湖</Button>
            : null,
      ].filter(Boolean)}
    >
      <div className="lake-resource-detail">
        <Card size="small">
          <Descriptions size="small" column={{ md: 2, xl: 3 }}>
            <Descriptions.Item label="数据源类型">{source.dbType || '未知'}</Descriptions.Item>
            <Descriptions.Item label="单位 / 业务系统">{unitNameByCode(source.unitCode) || '待归属'} / {systemNameByCode(source.systemCode) || '待归属'}</Descriptions.Item>
            <Descriptions.Item label="入湖库状态"><LakeResourceStatusTag status={database?.resourceStatus} /></Descriptions.Item>
            <Descriptions.Item label="入湖库" span="filled"><Text className="lake-resource-identifier" copyable={database?.databaseName ? { text: database.databaseName } : false}>{database?.databaseName || '未创建'}</Text></Descriptions.Item>
          </Descriptions>
        </Card>
        {canRetry && <Alert type="warning" showIcon message="入湖库尚未就绪"
          action={<Button size="small" loading={reconcileLoading} onClick={() => void checkDatabase(true)}>重试准备入湖库</Button>} />}
        <Card title="入湖表" className="lake-resource-tables">
          {inventoryError ? <Alert type="warning" showIcon message={inventoryError} action={<Button onClick={() => void load()}>重新加载</Button>} />
            : <Table rowKey={(row) => String(row.mappingId || row.targetTableName)} columns={overviewColumns}
              dataSource={registeredTables} pagination={false} scroll={{ x: 560 }}
              locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyTables} /> }} />}
        </Card>
        <Collapse activeKey={diagnosticsOpen ? ['diagnostics'] : []} onChange={(keys) => setDiagnosticsOpen(keys.includes('diagnostics'))}
          items={[{ key: 'diagnostics', label: '诊断详情', children: <>
            <Descriptions size="small" column={{ md: 2, xl: 3 }}>
              <Descriptions.Item label="数据源 ID">{source.sourceDataSourceId}</Descriptions.Item>
              <Descriptions.Item label="入湖库绑定 ID">{databaseId || '—'}</Descriptions.Item>
              <Descriptions.Item label="最近检查">{formatTime(lastCheck)}</Descriptions.Item>
              {database?.errorMessage && <Descriptions.Item label="检查结果" span="filled">{database.errorCode} {database.errorMessage}</Descriptions.Item>}
            </Descriptions>
            <Tabs activeKey={diagnosticTab} onChange={setDiagnosticTab} items={[
            {
              key: 'resources', label: '库表登记', children: (
                <Space direction="vertical" size={18} className="lake-tab-content">
                  <Card title="已登记表">
                    <Table rowKey={(row) => String(row.mappingId || row.targetTableName)} columns={tableColumns} dataSource={registeredTables} pagination={false} scroll={{ x: 900 }} locale={{ emptyText: <Empty description="暂无已登记表" /> }} />
                  </Card>
                  <Card title="发现的已有表">
                    <Table rowKey={(row) => String(row.targetTableName)} columns={tableColumns} dataSource={discoveredTables} pagination={false} scroll={{ x: 900 }} locale={{ emptyText: <Empty description="没有发现未登记的已有表" /> }} />
                  </Card>
                </Space>
              ),
            },
            {
              key: 'relations', label: '关联引接', children: (
                <Space direction="vertical" size={18} className="lake-tab-content">
                  <Card title="表级任务"><ProTable search={false} options={false} pagination={false} rowKey={(row) => String(row.relationId)} columns={relationColumns} dataSource={inventory?.tableRelations || []} locale={{ emptyText: <Empty description="暂无表级任务关系" /> }} /></Card>
                  <Card title="整库任务"><ProTable search={false} options={false} pagination={false} rowKey={(row) => String(row.relationId)} columns={relationColumns} dataSource={inventory?.namespaceRelations || []} locale={{ emptyText: <Empty description="暂无整库任务关系" /> }} /></Card>
                </Space>
              ),
            },
            {
              key: 'operations', label: '操作记录', children: (
                <OperationTimeline items={operationItems} emptyText="暂无入湖库操作记录" />
              ),
            },
          ]} />
          </> }]} />
      </div>
      <BindUnmanagedDrawer open={!!bindTarget} source={source} table={bindTarget} onClose={() => setBindTarget(undefined)} onSuccess={() => void load()} />
    </PageContainer>
  );
};

const PhysicalResourceDetailPage: React.FC = () => {
  const { sourceDataSourceId = '' } = useParams<{ sourceDataSourceId?: string }>();
  return <PhysicalResourceDetail key={sourceDataSourceId} sourceId={sourceDataSourceId} />;
};

export default PhysicalResourceDetailPage;
