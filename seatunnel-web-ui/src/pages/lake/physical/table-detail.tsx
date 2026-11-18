import {
  ArrowLeftOutlined,
  MoreOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
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
  Dropdown,
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
import type { MenuProps, TableColumnsType } from 'antd';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import StatusChip from '@/components/StatusChip';
import { getJobStatusView } from '@/pages/common/utils/jobStatus';
import { seatunnelJobDefinitionApi } from '../../batch-link-up/api';
import { seatunnelStremJobDefinitionApi } from '../../stream-link-up/api';
import { fetchLifecycleDetail, validateLifecycle } from '@/services/lake';
import {
  deleteManagedTable,
  fetchManagedTable,
  fetchManagedTableDeleteImpact,
  fetchPhysicalInventory,
  fetchPhysicalSource,
  fetchTaskPrefill,
  reconcileManagedTable,
  retryManagedTable,
  unbindUnmanagedTable,
} from './service';
import type { ApiResponse, InventoryRelation, ManagedTable, PhysicalInventory, TargetColumn } from './types';
import {
  LakeConsistencyTag,
  LakeResourceStatusTag,
  relationSchemaMode,
  relationSourceDataSource,
  relationTargetTable,
} from '../components/LakeStatus';
import { lakeTaskForm } from './taskPrefill';
import { physicalResourcesPath, physicalSourcePath } from './paths';
import { TABLE_MODEL_LABELS } from './tableDefaults';
import './table-detail.less';

const { Text } = Typography;

const partitionLabel: Record<string, string> = { DAY: '日', MONTH: '月', YEAR: '年' };
const managementLabel = { MANAGED: '平台建表', AUTO_CREATED: '任务建表', UNMANAGED: '已有表' };

interface TaskSummary {
  jobName?: string;
  lastJobStatus?: string;
}

const responseMessage = <T,>(response: ApiResponse<T>, fallback: string) => response.message || response.msg || fallback;

const formatTime = (value?: string) => {
  if (!value) return '-';
  // Observations are UTC instants; convert to local time before hiding the
  // timezone suffix, otherwise a successful refresh appears eight hours old.
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
};

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
    <Modal open={open} title="删除入湖表" onCancel={onClose} destroyOnHidden width={620} footer={[
      <Button key="cancel" onClick={onClose}>取消</Button>,
      <Button key="delete" danger type="primary" loading={deleteLoading} disabled={impactLoading || !impact?.allowed || confirmation.trim() !== table?.targetTableName} onClick={() => void submit()}>确认删除</Button>,
    ]}>
      <Alert type="warning" showIcon message="删除将移除 Doris 目标表及其中的数据" description="系统会先检查引接任务、生命周期和目标表状态。" />
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

const ManagedTableDetail: React.FC<{ mappingId: string }> = ({ mappingId }) => {
  const id = Number(mappingId);
  const [table, setTable] = useState<ManagedTable>();
  const [inventory, setInventory] = useState<PhysicalInventory>();
  const [inventoryError, setInventoryError] = useState(false);
  const [sourceName, setSourceName] = useState<string>();
  const [taskSummaries, setTaskSummaries] = useState<Record<string, TaskSummary>>({});
  const [lifecycle, setLifecycle] = useState<Record<string, unknown>>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [actionLoading, setActionLoading] = useState<string>();
  const [unbindLoading, setUnbindLoading] = useState(false);
  const [lifecycleRefreshing, setLifecycleRefreshing] = useState(false);
  const [taskCreating, setTaskCreating] = useState(false);
  const [structureView, setStructureView] = useState<'desired' | 'actual'>('desired');
  const [deleteOpen, setDeleteOpen] = useState(false);
  const requestId = useRef(0);

  const load = useCallback(async () => {
    const request = ++requestId.current;
    if (!/^[1-9]\d*$/.test(mappingId) || !Number.isSafeInteger(id)) {
      setError('入湖表标识无效');
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(undefined);
    try {
      const response = await fetchManagedTable(id);
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response, '表详情加载失败'));
      if (request !== requestId.current) return;
      const current = response.data;
      if (String(current.id) !== mappingId) throw new Error('读取到的入湖表与当前地址不匹配，请重新加载。');
      setTable(current);
      // The table remains viewable while related metadata lookups fail independently.
      const [tasks, retention, source] = await Promise.allSettled([
        current.odsDatabaseBindingId ? fetchPhysicalInventory(current.odsDatabaseBindingId) : undefined,
        fetchLifecycleDetail(id),
        current.sourceDataSourceId ? fetchPhysicalSource(current.sourceDataSourceId) : undefined,
      ]);
      if (request !== requestId.current) return;
      const inventoryData = tasks.status === 'fulfilled' && tasks.value?.code === 0 ? tasks.value.data : undefined;
      const inventoryMatches = inventoryData && String(inventoryData.odsDatabaseBindingId) === String(current.odsDatabaseBindingId);
      setInventory(inventoryMatches ? inventoryData : undefined);
      setInventoryError(Boolean(current.odsDatabaseBindingId && !inventoryMatches));
      if (current.odsDatabaseBindingId && !inventoryMatches) {
        console.warn('[lake-physical-table] 引接关系读取失败或归属不匹配', { mappingId, bindingId: current.odsDatabaseBindingId });
      }
      setLifecycle(retention.status === 'fulfilled' && retention.value.code === 0 ? retention.value.data : undefined);
      const sourceData = source.status === 'fulfilled' && source.value?.code === 0 ? source.value.data : undefined;
      setSourceName(sourceData && String(sourceData.sourceDataSourceId) === String(current.sourceDataSourceId) ? sourceData.sourceDataSourceName : undefined);
    } catch (reason) {
      if (request !== requestId.current) return;
      console.warn('[lake-physical-table] 入湖表详情读取失败', { mappingId, reason });
      setError(reason instanceof Error ? reason.message : '表详情加载失败');
    } finally {
      if (request === requestId.current) setLoading(false);
    }
  }, [id, mappingId]);

  useEffect(() => {
    void load();
    return () => { requestId.current += 1; };
  }, [load]);

  const execute = async (name: string, action: (id: number) => Promise<ApiResponse<ManagedTable>>) => {
    const request = requestId.current;
    setActionLoading(name);
    try {
      const response = await action(id);
      if (request !== requestId.current) return;
      if (response.code !== 0) throw new Error(responseMessage(response, `${name}失败`));
      message.success(`${name}已完成`);
      await load();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : `${name}失败`);
    } finally {
      setActionLoading(undefined);
    }
  };

  const refreshLifecycle = async () => {
    const binding = lifecycle?.existingBinding as { policyId?: number } | undefined;
    if (!binding?.policyId) return;
    const request = requestId.current;
    setLifecycleRefreshing(true);
    try {
      // Explicit validation reads Doris and refreshes the observation cache;
      // it does not change the retention policy or delete any partitions.
      const response = await validateLifecycle({ mappingId: id, policyId: binding.policyId });
      if (request !== requestId.current) return;
      if (response.code !== 0 || !response.data) {
        throw new Error(responseMessage(response, '生命周期状态读取失败'));
      }
      setLifecycle(response.data as Record<string, unknown>);
      message.success('生命周期状态已更新');
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '生命周期状态读取失败');
    } finally {
      setLifecycleRefreshing(false);
    }
  };

  const createIngestionTask = async () => {
    if (!table?.sourceDataSourceId || !table.lakeDataSourceId || !table.odsDatabaseBindingId) {
      message.warning('入湖表尚未关联完整的来源和目标，暂不能配置引接。');
      return;
    }
    const request = requestId.current;
    setTaskCreating(true);
    try {
      const response = await seatunnelJobDefinitionApi.getUniqueId();
      if (response.code !== 0) throw new Error(response.message || '任务标识申请失败');
      const allocated = response.data as any;
      const id = typeof allocated === 'object' ? allocated?.id : allocated;
      if (!id) throw new Error('任务标识申请失败');
      const prefill = await fetchTaskPrefill(table.id);
      if (request !== requestId.current) return;
      if (prefill.code !== 0 || !prefill.data) throw new Error(responseMessage(prefill, '来源读取失败'));
      sessionStorage.setItem(`batch-link-up-detail-${id}`, JSON.stringify(lakeTaskForm(prefill.data, id)));
      history.push(`/sync/batch-link-up/${id}/detail?lakeMappingId=${table.id}`);
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '创建引接任务失败');
    } finally {
      setTaskCreating(false);
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
    return { missing, extra, changed };
  }, [actualColumns, columns]);
  const tableRelations = useMemo(() => (inventory?.tableRelations || [])
    .filter((relation) => String(relation.tableMappingId) === mappingId), [inventory, mappingId]);
  const activeRelations = useMemo(() => tableRelations.filter((relation) => relation.relationStatus === 'ACTIVE' && relation.jobId), [tableRelations]);
  const namespaceRelations = inventory?.namespaceRelations || [];
  const lifecycleData = lifecycle as Record<string, any> | undefined;
  const lifecycleBinding = lifecycleData?.existingBinding as Record<string, any> | undefined;
  const lifecyclePolicy = lifecycleData?.policySnapshot as Record<string, any> | undefined;
  const partitionSummary = lifecycleData?.partitionSummary as Record<string, any> | undefined;
  const lifecycleReasons = Array.isArray(lifecycleData?.reasons)
    ? lifecycleData.reasons.filter(Boolean).map(String)
    : [];

  useEffect(() => {
    let active = true;
    setTaskSummaries({});
    // Only fetch current tasks for the overview. Historical links remain in diagnostics.
    void Promise.allSettled(activeRelations.map(async (relation) => {
      const api = relation.jobRuntimeType === 'STREAMING' ? seatunnelStremJobDefinitionApi : seatunnelJobDefinitionApi;
      const result = await api.selectById(relation.jobId);
      return [String(relation.relationId), result.code === 0 ? result.data as TaskSummary : undefined] as const;
    })).then((results) => {
      if (!active) return;
      setTaskSummaries(Object.fromEntries(results.flatMap((result) => (
        result.status === 'fulfilled' && result.value[1] ? [[result.value[0], result.value[1]]] : []
      ))));
    });
    return () => { active = false; };
  }, [activeRelations]);

  if (loading) return <PageContainer title={false}><Spin /></PageContainer>;
  if (error || !table) return <PageContainer title={false}><Alert type="error" showIcon message={error || '未找到入湖表'}
    action={<Space><Button onClick={() => history.push(physicalResourcesPath)}>返回物理入湖</Button><Button onClick={() => void load()}>重试</Button></Space>} /></PageContainer>;

  const target = [table.databaseName, table.targetTableName].filter(Boolean).join('.');
  const sourceTable = [table.sourceDatabaseName, table.sourceSchemaName === table.sourceDatabaseName ? undefined : table.sourceSchemaName,
    table.sourceTableName].filter(Boolean).join('.') || table.omFqn;
  const backPath = table.sourceDataSourceId ? physicalSourcePath(table.sourceDataSourceId) : physicalResourcesPath;
  const model = table.targetContract?.tableModel ?? table.actualContract?.tableModel ?? table.tableModel;
  const retentionCount = lifecycleData?.desiredRetentionCount ?? lifecycleBinding?.retentionCount;
  const granularity = lifecycleData?.granularity ?? lifecycleBinding?.granularity;
  const policyName = lifecyclePolicy?.policyName || '保留策略已设置';
  const retentionLabel = lifecycleBinding ? `${policyName}${retentionCount == null ? '' : ` · 保留 ${retentionCount} 个历史${partitionLabel[granularity] ?? ''}分区`}`
    : lifecycle ? '未设置保留策略' : '保留策略暂不可用';
  const targetMissing = table.actualTableExists === false && table.resourceStatus === 'READY';
  const canRetry = !table.deleted && table.managementLevel === 'MANAGED'
    && ['ERROR', 'CREATE_FAILED', 'MISSING', 'UNKNOWN'].includes(table.resourceStatus || '');
  const canConfigure = !inventoryError && table.managementLevel !== 'UNMANAGED' && table.resourceStatus === 'READY'
    && table.actualTableExists && table.sourceDataSourceId && table.lakeDataSourceId && table.odsDatabaseBindingId;
  const issues = [
    table.sourceConsistencyStatus === 'DRIFT' && '来源表字段已变化，请核对字段映射。',
    table.sourceConsistencyStatus === 'MISSING' && '找不到来源表，请检查源端元数据。',
    targetMissing && 'Doris 目标表缺失。',
    table.targetConsistencyStatus === 'DRIFT' && 'Doris 表结构与建表配置有差异，请查看结构对照。',
    ['DRIFT', 'MISSING'].includes(table.taskConsistencyStatus || '') && '引接任务配置有变化，请核对当前任务。',
  ].filter(Boolean);
  const moreItems: MenuProps['items'] = [
    ...(table.managementLevel === 'UNMANAGED' && table.sourceBound ? [{ key: 'unbind', label: '解除来源关联', danger: true }] : []),
    ...(table.managementLevel === 'MANAGED' && !table.deleted ? [{ key: 'delete', label: '删除入湖表', danger: true }] : []),
  ];
  const refreshAction = !table.deleted && !['CREATING', 'DELETING', 'DELETED'].includes(table.resourceStatus || '') ? (
    <Button size="small" icon={<ReloadOutlined />} loading={actionLoading === '刷新表状态'}
      onClick={() => void execute('刷新表状态', reconcileManagedTable)}>刷新表状态</Button>
  ) : null;

  const useActual = structureView === 'actual' || !table.targetContract;
  const structureModel = useActual ? table.actualContract?.tableModel : table.targetContract?.tableModel;
  const fieldColumns = (actual: boolean, unique: boolean): TableColumnsType<TargetColumn> => [
    ...(!actual ? [{ title: '源字段', dataIndex: 'sourceName', width: 180 }] : []),
    { title: '目标字段', dataIndex: 'targetName', width: 180 },
    { title: '目标类型', dataIndex: 'targetType', width: 150, render: (value) => formatTargetType(value) },
    { title: '允许为空', dataIndex: 'nullable', width: 110, render: (value?: boolean) => value == null ? '—' : value ? '是' : '否' },
    { title: unique ? '用于合并' : '用于排序', dataIndex: 'key', width: 110, render: (value) => value ? '是' : '—' },
  ];
  const fieldKey = (row: TargetColumn) => `${row.targetName || row.sourceName}-${row.physicalOrdinal || 0}`;

  return (
    <PageContainer
      title={false}
      header={{ style: { display: 'none' } }}
      className="lake-table-detail-page"
    >
      <div className="lake-table-detail">
        <div className="lake-table-toolbar">
          <Space wrap>
            <Button icon={<ArrowLeftOutlined />} onClick={() => history.push(backPath)}>{table.sourceDataSourceId ? '返回数据源' : '返回物理入湖'}</Button>
            <Text strong className="lake-table-name">{table.targetTableName || '入湖表'}</Text>
            {targetMissing ? <Tag color="error">目标表缺失</Tag> : <LakeResourceStatusTag status={table.resourceStatus} />}
          </Space>
          {moreItems.length > 0 && <Dropdown trigger={['click']} menu={{ items: moreItems, onClick: ({ key }) => {
            if (key === 'unbind') void unbind();
            if (key === 'delete') setDeleteOpen(true);
          } }}><Button icon={<MoreOutlined />} loading={!!actionLoading || unbindLoading}>更多操作</Button></Dropdown>}
        </div>
        {(canRetry || !!issues.length || table.errorCode || table.errorMessage) && <Alert type="warning" showIcon message="入湖表需要处理"
          description={issues.length ? issues.join(' ') : '可在诊断详情中查看原因。'}
          action={canRetry ? <Button size="small" loading={actionLoading === '重试建表'} onClick={() => void execute('重试建表', retryManagedTable)}>重试建表</Button>
            : refreshAction} />}
        <Card size="small">
          <Descriptions size="small" column={{ md: 2, xl: 2 }}>
            <Descriptions.Item label="数据源">{table.sourceDataSourceId ? <Button type="link" className="lake-table-source-link" onClick={() => history.push(backPath)}>{sourceName || table.sourceDbType || '查看来源数据源'}</Button> : '未关联'}</Descriptions.Item>
            <Descriptions.Item label="来源表">{sourceTable || '未关联'}</Descriptions.Item>
            <Descriptions.Item label="入湖表" span="filled"><Text copyable={target ? { text: target } : false}>{target || '—'}</Text></Descriptions.Item>
            <Descriptions.Item label="表模型">{model ? TABLE_MODEL_LABELS[model] : '待检查'}</Descriptions.Item>
            <Descriptions.Item label="保留规则">{retentionLabel}</Descriptions.Item>
          </Descriptions>
        </Card>
        <Card title="引接任务" size="small" extra={!activeRelations.length && canConfigure ? (
          <Button type="primary" icon={<ThunderboltOutlined />} loading={taskCreating} onClick={() => void createIngestionTask()}>配置引接</Button>
        ) : null}>
          {inventoryError ? <Alert type="warning" showIcon message="暂时无法读取引接任务" action={<Button onClick={() => void load()}>重新加载</Button>} />
            : activeRelations.length ? <Space direction="vertical" className="lake-table-tab-content">
              {activeRelations.map((relation) => {
                const streaming = relation.jobRuntimeType === 'STREAMING';
                const summary = taskSummaries[String(relation.relationId)];
                const name = summary?.jobName || (streaming ? '实时引接任务' : '批量引接任务');
                const run = summary?.lastJobStatus ? getJobStatusView(summary.lastJobStatus, streaming ? 'stream' : 'batch') : undefined;
                const path = `/sync/${streaming ? 'stream' : 'batch'}-link-up/${relation.jobId}/detail`;
                return <div className="lake-table-task" key={String(relation.relationId ?? relation.jobId)}>
                  <Space wrap><Text>{name}</Text>{run ? <StatusChip tone={run.tone} label={run.label.defaultMessage} /> : <Text type="secondary">已配置</Text>}</Space>
                  <Button type="link" aria-label={`查看任务 ${name}`} onClick={() => history.push(path)}>查看任务</Button>
                </div>;
              })}
            </Space> : <Text type="secondary">{canConfigure ? '还没有配置引接任务' : '暂无关联的引接任务'}</Text>}
        </Card>
        <Collapse items={[
          { key: 'fields', label: '查看字段', children: <Table rowKey={fieldKey}
            dataSource={table.targetContract ? columns : actualColumns} columns={fieldColumns(!table.targetContract, model === 'UNIQUE')}
            pagination={false} scroll={{ x: 740 }} locale={{ emptyText: <Empty description="暂无表结构" /> }} /> },
          { key: 'diagnostics', label: '诊断详情', children: <>
            <Descriptions size="small" column={{ md: 2, xl: 3 }}>
              <Descriptions.Item label="管理方式">{table.managementLevel ? managementLabel[table.managementLevel] : '—'}</Descriptions.Item>
              <Descriptions.Item label="最近检查">{formatTime(table.lastReconcileAt)}</Descriptions.Item>
              <Descriptions.Item label="实际存在">{table.actualTableExists == null ? '待检查' : table.actualTableExists ? '存在' : '缺失'}</Descriptions.Item>
              <Descriptions.Item label="表映射 ID">{table.id}</Descriptions.Item>
              <Descriptions.Item label="资源代次">{table.generation ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="并发版本">{table.lockVersion ?? '—'}</Descriptions.Item>
              {table.omFqn && <Descriptions.Item label="来源标识" span="filled">{table.omFqn}</Descriptions.Item>}
              {(table.errorCode || table.errorMessage) && <Descriptions.Item label="错误信息" span="filled">{table.errorCode} {table.errorMessage}</Descriptions.Item>}
              {refreshAction ? <Descriptions.Item>{refreshAction}</Descriptions.Item> : null}
            </Descriptions>
            <Tabs items={[
            {
              key: 'structure', label: '结构对照', children: <Card size="small" title={useActual ? 'Doris 实际结构' : '建表配置'} extra={<Space size={8}>
                <Button size="small" type={!useActual ? 'primary' : 'default'} disabled={!table.targetContract} onClick={() => setStructureView('desired')}>建表配置</Button>
                <Button size="small" type={useActual ? 'primary' : 'default'} disabled={!table.actualContract} onClick={() => setStructureView('actual')}>实际结构</Button>
              </Space>}>
                {!table.actualContract ? <Alert type="info" showIcon message="尚未读取 Doris 实际结构" />
                  : table.targetContract && (contractDiff.missing || contractDiff.extra || contractDiff.changed
                    ? <Alert type="warning" showIcon message="字段与建表配置有差异" description={`缺少 ${contractDiff.missing} 列，新增 ${contractDiff.extra} 列，属性变化 ${contractDiff.changed} 列。`} />
                    : <Alert type="success" showIcon message="字段与建表配置一致" />)}
                <Table rowKey={fieldKey} dataSource={useActual ? actualColumns : columns} pagination={false} scroll={{ x: 820 }} columns={[
                  ...fieldColumns(useActual, structureModel === 'UNIQUE'),
                  { title: 'Doris 列顺序', dataIndex: 'physicalOrdinal', width: 120 },
                ]} />
              </Card>,
            },
            {
              key: 'lifecycle', label: '分区与保留', children: <Card size="small" title="保留状态" extra={lifecycleBinding?.id ? <Button icon={<ReloadOutlined />} loading={lifecycleRefreshing} onClick={() => void refreshLifecycle()}>检查保留状态</Button> : null}>
                {!lifecycleBinding?.id ? <Empty description={retentionLabel} /> : <Space direction="vertical" size={12} style={{ width: '100%' }}>
                  {lifecycleData?.valid === true ? <Alert type="success" showIcon message="Doris 保留配置已核对" /> : lifecycleData?.code === 'LAKE_LIFECYCLE_CACHE_UNAVAILABLE' ? <Alert type="info" showIcon message="尚未读取最新分区状态" /> : <Alert type="warning" showIcon message="请检查保留状态" description={lifecycleReasons.join(' · ')} />}
                  <Descriptions bordered column={{ xs: 1, sm: 2, md: 3 }}>
                    <Descriptions.Item label="策略">{policyName}</Descriptions.Item>
                    <Descriptions.Item label="策略版本">{lifecyclePolicy?.version || lifecycleBinding.policyVersion || '-'}</Descriptions.Item>
                    <Descriptions.Item label="分区字段">{lifecycleData?.partitionColumn || lifecycleBinding.partitionColumn || '-'}</Descriptions.Item>
                    <Descriptions.Item label="分区粒度">{lifecycleData?.granularity || lifecycleBinding.granularity || '-'}</Descriptions.Item>
                    <Descriptions.Item label="策略保留分区数">{retentionCount ?? '未设置'}</Descriptions.Item>
                    <Descriptions.Item label="Doris 配置保留数">{lifecycleData?.actualRetentionCount ?? lifecycleBinding.actualRetentionCount ?? '未读取'}</Descriptions.Item>
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
              key: 'relations', label: '任务关联', children: <Space direction="vertical" size={16} className="lake-table-tab-content"><Card title="本表引接任务"><ProTable search={false} options={false} pagination={false} rowKey={(row) => String(row.relationId)} columns={relationColumns} dataSource={tableRelations} locale={{ emptyText: <Empty description="暂无表级任务关系" /> }} /></Card><Card title="整库引接任务"><ProTable search={false} options={false} pagination={false} rowKey={(row) => String(row.relationId)} columns={relationColumns} dataSource={namespaceRelations} locale={{ emptyText: <Empty description="暂无整库任务关系" /> }} /></Card></Space>,
            },
            {
              key: 'consistency', label: '检查结果', children: <Descriptions bordered column={1}>
                <Descriptions.Item label="来源结构"><LakeConsistencyTag status={table.sourceConsistencyStatus} /></Descriptions.Item>
                <Descriptions.Item label="湖表结构"><LakeConsistencyTag status={table.targetConsistencyStatus} /></Descriptions.Item>
                <Descriptions.Item label="引接配置"><LakeConsistencyTag status={table.taskConsistencyStatus} /></Descriptions.Item>
              </Descriptions>,
            },
          ]} />
          </> },
        ]} />
      </div>
      <TableDeleteModal open={deleteOpen} table={table} onClose={() => setDeleteOpen(false)} onDeleted={() => void load()} />
    </PageContainer>
  );
};

const ManagedTableDetailPage: React.FC = () => {
  const { mappingId = '' } = useParams<{ mappingId?: string }>();
  return <ManagedTableDetail key={mappingId} mappingId={mappingId} />;
};

export default ManagedTableDetailPage;
