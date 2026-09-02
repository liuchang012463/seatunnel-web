import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  EyeOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
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
  Empty,
  Input,
  Modal,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { seatunnelJobDefinitionApi } from '../../batch-link-up/api';
import { fetchLakeOperations, fetchLifecycleDetail } from '@/services/lake';
import type { LakeResourceOperation } from '@/services/lake';
import {
  deleteManagedTable,
  fetchManagedTable,
  fetchManagedTableDeleteImpact,
  fetchPhysicalInventory,
  reconcileManagedTable,
  retryManagedTable,
  unbindUnmanagedTable,
} from './service';
import type { ApiResponse, InventoryRelation, LakeConsistencyStatus, ManagedTable, PhysicalInventory } from './types';
import {
  LakeConsistencyTag,
  LakeErrorAlert,
  LakeResourceStatusTag,
  OperationTimeline,
  operationToStep,
  relationSchemaMode,
  relationSourceDataSource,
  relationTargetTable,
} from '../components/LakeStatus';
import './table-detail.less';

const { Paragraph, Text, Title } = Typography;

const responseMessage = <T,>(response: ApiResponse<T>, fallback: string) => response.message || response.msg || fallback;

const formatTime = (value?: string) => (value ? value.replace('T', ' ').replace(/\.\d+Z$/, '') : '-');

const formatTargetType = (value: unknown) => {
  if (typeof value === 'string') return value;
  if (!value || typeof value !== 'object') return '-';
  const type = value as Record<string, unknown>;
  const base = String(type.base || '').toUpperCase();
  if (!base) return '-';
  if ((base === 'VARCHAR' || base === 'CHAR') && type.length != null) {
    return `${base}(${type.length})`;
  }
  if (base === 'DECIMAL' && type.precision != null && type.scale != null) {
    return `${base}(${type.precision},${type.scale})`;
  }
  if (base === 'DATETIME' && type.scale != null) {
    return `${base}(${type.scale})`;
  }
  return base;
};

const targetTypeSignature = (value: unknown) => {
  if (typeof value === 'string') return value.trim().toUpperCase();
  if (!value || typeof value !== 'object') return '';
  const type = value as Record<string, unknown>;
  return [type.base, type.length, type.precision, type.scale]
    .map((part) => String(part ?? ''))
    .join(':')
    .toUpperCase();
};

const jdbcPlugin = (dbType?: string) => {
  const normalized = String(dbType || 'MYSQL').toUpperCase();
  const pluginType = normalized === 'POSTGRE_SQL' ? 'POSTGRESQL' : normalized;
  return {
    dbType: normalized,
    connectorType: 'Jdbc',
    pluginName: `JDBC-${pluginType}`,
  };
};

const buildLakeWorkflow = (table: ManagedTable) => {
  const sourceType = jdbcPlugin(table.sourceDbType);
  const sourceId = `lake-source-${Date.now()}`;
  const sinkId = `lake-sink-${Date.now()}`;
  const transformId = `lake-field-mapper-${Date.now()}`;
  const sourceTable = String(table.omFqn || '').split('.').pop() || table.targetTableName || '';
  const mappings = (table.fieldMappings || []).filter((item) => item.sourceField && item.targetField).map((item, index) => ({
    id: `lake-mapping-${index}`,
    sourceField: item.sourceField,
    targetField: item.targetField,
    targetType: item.targetType,
    enabled: true,
  }));
  const sourceSchema = mappings.map((item) => ({ name: item.sourceField, type: item.targetType || 'STRING', nullable: true }));
  const sourceNode = {
    id: sourceId,
    type: 'custom',
    position: { x: 80, y: 180 },
    data: {
      nodeType: 'source',
      title: sourceType.dbType,
      description: '读取已登记源表',
      ...sourceType,
      config: {
        dataSourceId: String(table.sourceDataSourceId || ''),
        ...sourceType,
        readMode: 'table',
        table: sourceTable,
        sql: '',
        extraParams: [],
        pluginOutput: sourceId,
      },
      meta: { outputSchema: sourceSchema, schemaStatus: mappings.length ? 'success' : 'idle', schemaError: '' },
    },
  };
  const sinkNode = {
    id: sinkId,
    type: 'custom',
    position: { x: mappings.length ? 700 : 460, y: 180 },
    data: {
      nodeType: 'sink',
      title: 'DORIS',
      description: '写入受控 ODS 表',
      dbType: 'DORIS',
      connectorType: 'Doris',
      pluginName: 'DORIS',
      config: {
        dataSourceId: String(table.lakeDataSourceId || ''),
        dbType: 'DORIS',
        connectorType: 'Doris',
        pluginName: 'DORIS',
        autoCreateTable: false,
        targetMode: 'table',
        table: table.targetTableName || '',
        targetTableName: table.targetTableName || '',
        schemaSaveMode: 'ERROR_WHEN_SCHEMA_NOT_EXIST',
        writeMode: 'append',
        odsDatabaseBindingId: table.odsDatabaseBindingId,
        pluginInput: mappings.length ? transformId : sourceId,
        extraParams: [],
      },
      meta: { inputSchema: sourceSchema, outputSchema: sourceSchema, schemaStatus: 'success', schemaError: '' },
    },
  };
  const nodes = [sourceNode as any];
  const edges: any[] = [];
  if (mappings.length) {
    nodes.push({
      id: transformId,
      type: 'custom',
      position: { x: 390, y: 180 },
      data: {
        nodeType: 'transform',
        componentType: 'FIELDMAPPER',
        label: '字段映射',
        title: '字段映射',
        description: '沿用 MANAGED 表已确认的字段关系',
        iconType: 'braces',
        config: { mappings, passThroughUnmapped: false },
        meta: { inputSchema: sourceSchema, outputSchema: mappings.map((item) => ({ name: item.targetField, type: item.targetType || 'STRING', nullable: true, originFieldName: item.sourceField })), schemaStatus: 'success', schemaError: '' },
      },
    });
    edges.push({ id: `${sourceId}-${transformId}`, source: sourceId, target: transformId, type: 'custom', data: {} });
    edges.push({ id: `${transformId}-${sinkId}`, source: transformId, target: sinkId, type: 'custom', data: {} });
  } else {
    edges.push({ id: `${sourceId}-${sinkId}`, source: sourceId, target: sinkId, type: 'custom', data: {} });
  }
  nodes.push(sinkNode as any);
  return { nodes, edges, sourceType, targetType: { dbType: 'DORIS', connectorType: 'Doris', pluginName: 'DORIS' } };
};

const relationColumns: ProColumns<InventoryRelation>[] = [
  { title: '任务', dataIndex: 'jobId', width: 110 },
  { title: '运行类型', dataIndex: 'jobRuntimeType', width: 120 },
  { title: '关系范围', dataIndex: 'relationScope', width: 120 },
  { title: '版本', dataIndex: 'jobVersion', width: 90 },
  { title: '目标表', key: 'targetTable', width: 180, ellipsis: true, render: (_, row) => relationTargetTable(row) || <Text type="secondary">按任务动态生成</Text> },
  { title: 'Schema Mode', key: 'schemaSaveMode', width: 190, ellipsis: true, render: (_, row) => relationSchemaMode(row) || <Text type="secondary">未记录</Text> },
  { title: '源 DataSource', key: 'sourceDataSource', width: 130, render: (_, row) => relationSourceDataSource(row) || '-' },
  { title: '状态', dataIndex: 'relationStatus', width: 120, render: (_, row) => <Tag>{row.relationStatus || 'UNKNOWN'}</Tag> },
];

const TableDeleteModal: React.FC<{
  open: boolean;
  table?: ManagedTable;
  onClose: () => void;
  onDeleted: () => void;
}> = ({ open, table, onClose, onDeleted }) => {
  const [impactLoading, setImpactLoading] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [impact, setImpact] = useState<Awaited<ReturnType<typeof fetchManagedTableDeleteImpact>>['data']>();
  const [confirmation, setConfirmation] = useState('');

  useEffect(() => {
    if (!open || !table?.id) return;
    setConfirmation('');
    setImpact(undefined);
    setImpactLoading(true);
    void fetchManagedTableDeleteImpact(table.id)
      .then((response) => {
        if (response.code !== 0) throw new Error(responseMessage(response, '删除影响读取失败'));
        setImpact(response.data);
      })
      .catch((error) => message.error(error instanceof Error ? error.message : '删除影响读取失败'))
      .finally(() => setImpactLoading(false));
  }, [open, table?.id]);

  const submit = async () => {
    if (!table?.id || !impact?.allowed || confirmation.trim() !== table.targetTableName) return;
    setDeleteLoading(true);
    try {
      const response = await deleteManagedTable(table.id, { targetTableName: confirmation.trim(), impactHash: impact.impactHash || '' });
      if (response.code !== 0) throw new Error(responseMessage(response, '删除表失败'));
      message.success('删除操作已提交');
      onDeleted();
      onClose();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '删除表失败');
    } finally {
      setDeleteLoading(false);
    }
  };

  return (
    <Modal open={open} title="删除 MANAGED 表" onCancel={onClose} destroyOnClose width={620} footer={[
      <Button key="cancel" onClick={onClose}>取消</Button>,
      <Button key="delete" danger type="primary" loading={deleteLoading} disabled={impactLoading || !impact?.allowed || confirmation.trim() !== table?.targetTableName} onClick={() => void submit()}>确认删除</Button>,
    ]}>
      <Alert type="warning" showIcon message="删除不可恢复" description="页面会先检查运行中的任务、生命周期和 Doris 实际表状态；这里只删除湖侧 MANAGED 资源。" />
      <Card size="small" className="lake-impact-card">
        {impactLoading ? <Spin /> : impact ? <Space direction="vertical" size={8}>
          <Descriptions size="small" column={2}>
            <Descriptions.Item label="Doris 表">{impact.targetTableName || table?.targetTableName || '-'}</Descriptions.Item>
            <Descriptions.Item label="实际存在">{impact.actualTableExists ? '存在' : '不存在'}</Descriptions.Item>
            <Descriptions.Item label="生命周期">{impact.lifecycleBound ? '已绑定' : '未绑定'}</Descriptions.Item>
            <Descriptions.Item label="结果">{impact.allowed ? <Tag color="success">允许删除</Tag> : <Tag color="error">被阻止</Tag>}</Descriptions.Item>
          </Descriptions>
          {impact.blockers?.length ? <Alert type="error" showIcon message="无法删除" description={<ul className="lake-impact-list">{impact.blockers.map((item) => <li key={item}>{item}</li>)}</ul>} /> : null}
        </Space> : <Empty description="无法读取删除影响" />}
      </Card>
      <Input placeholder={`请输入 ${table?.targetTableName || '目标表名'} 确认`} value={confirmation} onChange={(event) => setConfirmation(event.target.value)} disabled={!impact?.allowed} />
    </Modal>
  );
};

const ManagedTableDetailPage: React.FC = () => {
  const { mappingId } = useParams<{ mappingId?: string }>();
  const id = Number(mappingId);
  const [table, setTable] = useState<ManagedTable>();
  const [inventory, setInventory] = useState<PhysicalInventory>();
  const [lifecycle, setLifecycle] = useState<Record<string, unknown>>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [actionLoading, setActionLoading] = useState<string>();
  const [unbindLoading, setUnbindLoading] = useState(false);
  const [structureView, setStructureView] = useState<'desired' | 'actual'>('desired');
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [operations, setOperations] = useState<LakeResourceOperation[]>([]);

  const load = useCallback(async () => {
    if (!Number.isInteger(id) || id <= 0) {
      setError('表映射标识无效');
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(undefined);
    try {
      const response = await fetchManagedTable(id);
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response, '表详情加载失败'));
      setTable(response.data);
      const tableOperationsPromise = fetchLakeOperations('ODS_TABLE_MAPPING', response.data.id)
        .catch(() => undefined);
      if (response.data.odsDatabaseBindingId) {
        const inventoryResponse = await fetchPhysicalInventory(response.data.odsDatabaseBindingId);
        if (inventoryResponse.code === 0) setInventory(inventoryResponse.data);
      }
      const lifecycleResponse = await fetchLifecycleDetail(id);
      const tableOperationsResponse = await tableOperationsPromise;
      const timelineOperations: LakeResourceOperation[] =
        tableOperationsResponse?.code === 0 ? tableOperationsResponse.data || [] : [];
      if (lifecycleResponse.code === 0) {
        const lifecycleData = (lifecycleResponse.data || {}) as Record<string, any>;
        setLifecycle(lifecycleData);
        // Lifecycle mutations are journaled against the durable binding id,
        // not the table mapping id. Merge both streams so retention changes
        // are visible beside create/reconcile/delete operations.
        const bindingId = Number(lifecycleData.existingBinding?.id);
        if (Number.isInteger(bindingId) && bindingId > 0) {
          const lifecycleOperationsResponse = await fetchLakeOperations(
            'TABLE_LIFECYCLE',
            bindingId,
          ).catch(() => undefined);
          if (lifecycleOperationsResponse?.code === 0) {
            timelineOperations.push(...(lifecycleOperationsResponse.data || []));
          }
        }
      }
      setOperations(timelineOperations.sort((left, right) => {
        const leftTime = new Date(left.finishedAt || left.startedAt || 0).getTime();
        const rightTime = new Date(right.finishedAt || right.startedAt || 0).getTime();
        return rightTime - leftTime;
      }));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '表详情加载失败');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  const execute = async (name: string, action: (id: number) => Promise<ApiResponse<ManagedTable>>) => {
    setActionLoading(name);
    try {
      const response = await action(id);
      if (response.code !== 0) throw new Error(responseMessage(response, `${name}失败`));
      message.success(`${name}已完成`);
      await load();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : `${name}失败`);
    } finally {
      setActionLoading(undefined);
    }
  };

  const createIngestionTask = async () => {
    if (!table?.sourceDataSourceId || !table.lakeDataSourceId || !table.odsDatabaseBindingId) {
      message.warning('缺少源数据源、湖数据源或 ODS Database 绑定，暂不能预填任务');
      return;
    }
    try {
      const response = await seatunnelJobDefinitionApi.getUniqueId();
      if (response.code !== 0) throw new Error(response.message || '任务标识申请失败');
      const allocated = response.data as any;
      const id = typeof allocated === 'object' ? allocated?.id : allocated;
      if (!id) throw new Error('任务标识申请失败');
      const workflow = buildLakeWorkflow(table);
      const sourceLabel = table.sourceDbType || 'JDBC';
      sessionStorage.setItem(`batch-link-up-detail-${id}`, JSON.stringify({
        id,
        mode: 'GUIDE_SINGLE',
        jobName: `${sourceLabel} → DORIS · ${table.targetTableName || 'ODS 表'}`,
        jobDesc: '从物理入湖表详情预填的单表引接任务，请完成连通性测试后发布。',
        sourceType: workflow.sourceType,
        targetType: workflow.targetType,
        sourceDataSourceId: String(table.sourceDataSourceId),
        targetDataSourceId: String(table.lakeDataSourceId),
        odsDatabaseBindingId: table.odsDatabaseBindingId,
        workflow: workflow,
      }));
      history.push(`/sync/batch-link-up/${id}/detail`);
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '创建引接任务失败');
    }
  };

  const unbind = async () => {
    if (!table?.id || table.managementLevel !== 'UNMANAGED' || !table.sourceBound) return;
    Modal.confirm({
      title: '解除未纳管表关联？',
      content: `将移除“${table.targetTableName || '-'}”的显式源表关联。Doris 实际表不会被删除，表仍保持未纳管状态。`,
      okText: '确认解除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        setUnbindLoading(true);
        try {
          const response = await unbindUnmanagedTable(table.id);
          if (response.code !== 0) throw new Error(responseMessage(response, '解除关联失败'));
          message.success('未纳管表关联已解除');
          await load();
        } catch (reason) {
          message.error(reason instanceof Error ? reason.message : '解除关联失败');
          throw reason;
        } finally {
          setUnbindLoading(false);
        }
      },
    });
  };

  const columns = table?.targetContract?.columns || [];
  const actualColumns = table?.actualContract?.columns || [];
  const contractDiff = useMemo(() => {
    const desiredByName = new Map(columns.map((column) => [String(column.targetName || column.sourceName || '').toUpperCase(), column]));
    const actualByName = new Map(actualColumns.map((column) => [String(column.targetName || column.sourceName || '').toUpperCase(), column]));
    const missing = columns.filter((column) => !actualByName.has(String(column.targetName || column.sourceName || '').toUpperCase())).length;
    const extra = actualColumns.filter((column) => !desiredByName.has(String(column.targetName || column.sourceName || '').toUpperCase())).length;
    const changed = columns.filter((column) => {
      const actual = actualByName.get(String(column.targetName || column.sourceName || '').toUpperCase());
      if (!actual) return false;
      return targetTypeSignature(column.targetType) !== targetTypeSignature(actual.targetType)
        || Boolean(column.key) !== Boolean(actual.key)
        || Boolean(column.nullable) !== Boolean(actual.nullable);
    }).length;
    return { missing, extra, changed, hasActual: actualColumns.length > 0 };
  }, [actualColumns, columns]);
  const tableRelations = (inventory?.tableRelations || []).filter((relation) => relation.tableMappingId === id);
  const namespaceRelations = inventory?.namespaceRelations || [];
  const timelineItems = operations.map(operationToStep);
  const lifecycleData = lifecycle as Record<string, any> | undefined;
  const lifecycleBinding = lifecycleData?.existingBinding as Record<string, any> | undefined;
  const lifecyclePolicy = lifecycleData?.policySnapshot as Record<string, any> | undefined;
  const partitionSummary = lifecycleData?.partitionSummary as Record<string, any> | undefined;
  const lifecycleReasons = Array.isArray(lifecycleData?.reasons)
    ? lifecycleData.reasons.filter(Boolean).map(String)
    : [];

  if (loading) return <PageContainer title="表详情"><Spin /></PageContainer>;
  if (error || !table) return <PageContainer title="表详情"><Alert type="error" showIcon message={error || '未找到表映射'} action={<Button onClick={() => void load()}>重试</Button>} /></PageContainer>;

  return (
    <PageContainer
      title={table.targetTableName || 'ODS 表详情'}
      subTitle={`${table.omFqn || table.omEntityId || 'OpenMetadata 表'} → ${table.databaseName || '-'} / ${table.targetTableName || '-'}`}
      onBack={() => history.back()}
      breadcrumb={{ items: [{ title: '物理入湖', onClick: () => history.push('/lake/resources') }, { title: '表详情' }] }}
      extra={[
        <Button key="back" icon={<ArrowLeftOutlined />} onClick={() => history.back()}>返回</Button>,
        <Button key="create-task" type="primary" icon={<ThunderboltOutlined />} disabled={table.managementLevel === 'UNMANAGED' || !table.sourceDataSourceId || !table.lakeDataSourceId} onClick={() => void createIngestionTask()}>创建引接任务</Button>,
        table.managementLevel === 'UNMANAGED' && table.sourceBound ? <Button key="unbind" danger loading={unbindLoading} onClick={() => void unbind()}>解除关联</Button> : null,
        <Button key="reconcile" icon={<ReloadOutlined />} loading={actionLoading === '对账'} onClick={() => void execute('对账', reconcileManagedTable)}>对账</Button>,
        <Button key="retry" icon={<ThunderboltOutlined />} loading={actionLoading === '重试'} disabled={!['ERROR', 'CREATE_FAILED', 'MISSING', 'UNKNOWN'].includes(table.resourceStatus || '')} onClick={() => void execute('重试', retryManagedTable)}>重试</Button>,
        <Button key="delete" danger icon={<DeleteOutlined />} disabled={table.deleted} onClick={() => setDeleteOpen(true)}>删除</Button>,
      ]}
    >
      <div className="lake-table-detail">
        <Card className="lake-table-hero" bordered={false}>
          <div className="lake-table-hero-icon"><TableOutlined /></div>
          <div className="lake-table-hero-copy">
            <div className="lake-detail-kicker">MANAGED ODS TABLE</div>
            <Title level={3}>{table.targetTableName || '-'}</Title>
            <Paragraph type="secondary">{table.databaseName || '-'} · {table.tableModel || 'DUPLICATE'} · Source {table.omFqn || table.omEntityId || '-'}</Paragraph>
          </div>
          <LakeResourceStatusTag status={table.resourceStatus} />
        </Card>
        <LakeErrorAlert code={table.errorCode} message={table.errorMessage} action={<Button type="link" onClick={() => void execute('对账', reconcileManagedTable)}>重新对账</Button>} />
        <Card className="lake-table-status-card" bordered={false}>
          <Space size={24} wrap>
            <div><Text type="secondary">Source</Text><div><LakeConsistencyTag status={table.sourceConsistencyStatus as LakeConsistencyStatus} /></div></div>
            <div><Text type="secondary">Target</Text><div><LakeConsistencyTag status={table.targetConsistencyStatus as LakeConsistencyStatus} /></div></div>
            <div><Text type="secondary">Task</Text><div><LakeConsistencyTag status={table.taskConsistencyStatus as LakeConsistencyStatus} /></div></div>
            <div><Text type="secondary">实际表</Text><div>{table.actualTableExists ? <Tag color="success">存在</Tag> : <Tag color="error">不存在</Tag>}</div></div>
          </Space>
        </Card>
        <Tabs
          className="lake-table-tabs"
          items={[
            {
              key: 'basic', label: '基本信息', children: <Card><Descriptions bordered column={{ xs: 1, sm: 2, md: 3 }}><Descriptions.Item label="源表 FQN">{table.omFqn || '-'}</Descriptions.Item><Descriptions.Item label="目标表">{table.databaseName || '-'}.{table.targetTableName || '-'}</Descriptions.Item><Descriptions.Item label="管理等级">{table.managementLevel || '-'}</Descriptions.Item><Descriptions.Item label="Generation">{table.generation || '-'}</Descriptions.Item><Descriptions.Item label="Lock Version">{table.lockVersion || '-'}</Descriptions.Item><Descriptions.Item label="最近对账">{formatTime(table.lastReconcileAt)}</Descriptions.Item></Descriptions></Card>,
            },
            {
              key: 'structure', label: '表结构', children: <Card title={<Space><EyeOutlined />{structureView === 'actual' || !table.targetContract ? 'Actual Contract' : 'Desired Contract'}</Space>} extra={<Space size={8}><Button size="small" type={structureView === 'desired' ? 'primary' : 'default'} disabled={!table.targetContract} onClick={() => setStructureView('desired')}>Desired</Button><Button size="small" type={structureView === 'actual' ? 'primary' : 'default'} disabled={!table.actualContract} onClick={() => setStructureView('actual')}>Actual</Button></Space>}>
                {!table.actualContract ? <Alert type="info" showIcon message="尚未缓存 Doris 实际结构" description="点击“对账”后，服务端会读取 columns、Key、分区和分布并缓存结果；进入详情不会自动访问 Doris。" /> : contractDiff.missing || contractDiff.extra || contractDiff.changed ? <Alert type="warning" showIcon message="Desired 与 Actual 存在差异" description={`缺少 ${contractDiff.missing} 列，新增 ${contractDiff.extra} 列，属性变化 ${contractDiff.changed} 列。`} /> : <Alert type="success" showIcon message="Desired 与 Actual 一致" />}
                <Table rowKey={(row) => `${row.targetName || row.sourceName}-${row.physicalOrdinal || 0}`} dataSource={(structureView === 'actual' || !table.targetContract ? actualColumns : columns)} pagination={false} scroll={{ x: 920 }} columns={[
                { title: 'Key', dataIndex: 'key', width: 80, render: (value) => value ? <Tag color="blue">Key</Tag> : '-' },
                { title: '源字段', dataIndex: 'sourceName', width: 180 },
                { title: '目标字段', dataIndex: 'targetName', width: 180 },
                { title: '目标类型', dataIndex: 'targetType', width: 180, render: (value) => formatTargetType(value) },
                { title: 'Nullable', dataIndex: 'nullable', width: 100, render: (value) => value ? 'YES' : 'NO' },
                { title: '序号', dataIndex: 'physicalOrdinal', width: 90 },
                ]} locale={{ emptyText: <Empty description={structureView === 'actual' || !table.targetContract ? '暂无实际结构，请先对账' : '暂无目标合同'} /> }} />
              </Card>,
            },
            {
              key: 'lifecycle', label: '分区与生命周期', children: <Card title={<Space><SafetyCertificateOutlined />生命周期状态</Space>}>
                {!lifecycleBinding?.id ? <Empty description="未绑定生命周期策略" /> : <Space direction="vertical" size={12} style={{ width: '100%' }}>
                  {lifecycleData?.valid === true ? <Alert type="success" showIcon message="生命周期配置与 Doris 实际状态一致" /> : <Alert type="warning" showIcon message={lifecycleData?.code || '生命周期需要处理'} description={lifecycleReasons.length ? lifecycleReasons.join(' · ') : '请执行一次显式校验以获取最新状态'} />}
                  <Descriptions bordered column={{ xs: 1, sm: 2, md: 3 }}>
                    <Descriptions.Item label="策略">{lifecyclePolicy?.policyName || `Policy #${lifecycleBinding.policyId || lifecycleData?.policyId || '-'}`}</Descriptions.Item>
                    <Descriptions.Item label="策略版本">{lifecyclePolicy?.version || lifecycleBinding.policyVersion || '-'}</Descriptions.Item>
                    <Descriptions.Item label="分区字段">{lifecycleData?.partitionColumn || lifecycleBinding.partitionColumn || '-'}</Descriptions.Item>
                    <Descriptions.Item label="分区粒度">{lifecycleData?.granularity || lifecycleBinding.granularity || '-'}</Descriptions.Item>
                    <Descriptions.Item label="期望保留历史分区">{lifecycleData?.desiredRetentionCount ?? lifecycleBinding.retentionCount ?? '永久'}</Descriptions.Item>
                    <Descriptions.Item label="实际保留历史分区">{lifecycleData?.actualRetentionCount ?? lifecycleBinding.actualRetentionCount ?? '未观测'}</Descriptions.Item>
                    <Descriptions.Item label="结构一致性">{lifecycleData?.structuralMatch === true ? <Tag color="success">一致</Tag> : lifecycleData?.structuralMatch === false ? <Tag color="warning">漂移</Tag> : <Tag>未知</Tag>}</Descriptions.Item>
                    <Descriptions.Item label="最近观测">{formatTime(lifecycleData?.observedAt || lifecycleBinding.lastObservedAt)}</Descriptions.Item>
                    <Descriptions.Item label="历史分区数">{partitionSummary?.historical ?? '未观测'}</Descriptions.Item>
                    <Descriptions.Item label="当前分区数">{partitionSummary?.current ?? '未观测'}</Descriptions.Item>
                    <Descriptions.Item label="未来分区数">{partitionSummary?.future ?? '未观测'}</Descriptions.Item>
                    <Descriptions.Item label="未知分区数">{partitionSummary?.unknown ?? '未观测'}</Descriptions.Item>
                  </Descriptions>
                </Space>}
              </Card>,
            },
            {
              key: 'relations', label: '数据引接', children: <Space direction="vertical" size={16} className="lake-table-tab-content"><Card title={<Space><ThunderboltOutlined />TABLE Relations</Space>}><ProTable search={false} options={false} pagination={false} rowKey={(row) => String(row.relationId)} columns={relationColumns} dataSource={tableRelations} locale={{ emptyText: <Empty description="暂无表级任务关系" /> }} /></Card><Card title="NAMESPACE Relations"><Alert type="info" showIcon message="命名空间关系不会自动绑定到源表" /><ProTable search={false} options={false} pagination={false} rowKey={(row) => String(row.relationId)} columns={relationColumns} dataSource={namespaceRelations} locale={{ emptyText: <Empty description="暂无命名空间关系" /> }} /></Card></Space>,
            },
            {
              key: 'consistency', label: '一致性', children: <Card title={<Space><CheckCircleOutlined />三方一致性</Space>}><Descriptions bordered column={1}><Descriptions.Item label="Source">{table.sourceConsistencyStatus || 'UNKNOWN'} · 由 OpenMetadata snapshot 提供</Descriptions.Item><Descriptions.Item label="Target">{table.targetConsistencyStatus || 'UNKNOWN'} · 显式对账读取 Doris</Descriptions.Item><Descriptions.Item label="Task">{table.taskConsistencyStatus || 'UNBOUND'} · 由任务关联状态提供</Descriptions.Item></Descriptions></Card>,
            },
            {
              key: 'operations', label: '操作记录', children: <Card title={<Space><SettingOutlined />操作时间线</Space>}><OperationTimeline items={timelineItems} emptyText="暂无已记录的表操作" /></Card>,
            },
          ]}
        />
      </div>
      <TableDeleteModal open={deleteOpen} table={table} onClose={() => setDeleteOpen(false)} onDeleted={() => void load()} />
    </PageContainer>
  );
};

export default ManagedTableDetailPage;
