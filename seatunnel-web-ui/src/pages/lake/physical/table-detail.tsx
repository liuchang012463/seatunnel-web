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
import { fetchLifecycleDetail } from '@/services/lake';
import {
  deleteManagedTable,
  fetchManagedTable,
  fetchManagedTableDeleteImpact,
  fetchPhysicalInventory,
  reconcileManagedTable,
  retryManagedTable,
} from './service';
import type { ApiResponse, InventoryRelation, LakeConsistencyStatus, ManagedTable, PhysicalInventory } from './types';
import { LakeConsistencyTag, LakeErrorAlert, LakeResourceStatusTag, OperationTimeline } from '../components/LakeStatus';
import './table-detail.less';

const { Paragraph, Text, Title } = Typography;

const responseMessage = <T,>(response: ApiResponse<T>, fallback: string) => response.message || response.msg || fallback;

const formatTime = (value?: string) => (value ? value.replace('T', ' ').replace(/\.\d+Z$/, '') : '-');

const relationColumns: ProColumns<InventoryRelation>[] = [
  { title: '任务', dataIndex: 'jobId', width: 110 },
  { title: '运行类型', dataIndex: 'jobRuntimeType', width: 120 },
  { title: '关系范围', dataIndex: 'relationScope', width: 120 },
  { title: '版本', dataIndex: 'jobVersion', width: 90 },
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
  const [deleteOpen, setDeleteOpen] = useState(false);

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
      if (response.data.odsDatabaseBindingId) {
        const inventoryResponse = await fetchPhysicalInventory(response.data.odsDatabaseBindingId);
        if (inventoryResponse.code === 0) setInventory(inventoryResponse.data);
      }
      const lifecycleResponse = await fetchLifecycleDetail(id);
      if (lifecycleResponse.code === 0) setLifecycle((lifecycleResponse.data || {}) as Record<string, unknown>);
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

  const columns = table?.targetContract?.columns || [];
  const tableRelations = (inventory?.tableRelations || []).filter((relation) => relation.tableMappingId === id);
  const namespaceRelations = inventory?.namespaceRelations || [];
  const mappingStatus = table?.resourceStatus;
  const timelineItems = table ? [
    { title: `目标表：${table.targetTableName || '-'}`, description: `管理等级 ${table.managementLevel || '-'} · ${formatTime(table.createTime)}`, status: mappingStatus === 'ERROR' || mappingStatus === 'CREATE_FAILED' ? 'error' as const : 'finish' as const },
    { title: '最近一次对账', description: table.lastReconcileAt ? formatTime(table.lastReconcileAt) : '尚未执行显式对账', status: table.lastReconcileAt ? 'finish' as const : 'wait' as const },
  ] : [];

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
              key: 'structure', label: '表结构', children: <Card title={<Space><EyeOutlined />Desired Contract</Space>} extra={<Text type="secondary">默认类型和 Key 约束由服务端归一化</Text>}><Table rowKey={(row) => `${row.targetName || row.sourceName}-${row.physicalOrdinal || 0}`} dataSource={columns} pagination={false} scroll={{ x: 920 }} columns={[
                { title: 'Key', dataIndex: 'key', width: 80, render: (value) => value ? <Tag color="blue">Key</Tag> : '-' },
                { title: '源字段', dataIndex: 'sourceName', width: 180 },
                { title: '目标字段', dataIndex: 'targetName', width: 180 },
                { title: '目标类型', dataIndex: 'targetType', width: 180, render: (value) => typeof value === 'string' ? value : value?.base || '-' },
                { title: 'Nullable', dataIndex: 'nullable', width: 100, render: (value) => value ? 'YES' : 'NO' },
                { title: '序号', dataIndex: 'physicalOrdinal', width: 90 },
              ]} locale={{ emptyText: <Empty description="暂无目标合同" /> }} /></Card>,
            },
            {
              key: 'lifecycle', label: '分区与生命周期', children: <Card title={<Space><SafetyCertificateOutlined />生命周期状态</Space>}>{lifecycle ? <Descriptions bordered column={{ xs: 1, sm: 2, md: 3 }}>{Object.entries(lifecycle).filter(([key]) => !['warnings', 'errors'].includes(key)).map(([key, value]) => <Descriptions.Item key={key} label={key}>{typeof value === 'object' ? JSON.stringify(value) : String(value ?? '-')}</Descriptions.Item>)}</Descriptions> : <Empty description="未绑定生命周期策略" />}</Card>,
            },
            {
              key: 'relations', label: '数据引接', children: <Space direction="vertical" size={16} className="lake-table-tab-content"><Card title={<Space><ThunderboltOutlined />TABLE Relations</Space>}><ProTable search={false} options={false} pagination={false} rowKey={(row) => String(row.relationId)} columns={relationColumns} dataSource={tableRelations} locale={{ emptyText: <Empty description="暂无表级任务关系" /> }} /></Card><Card title="NAMESPACE Relations"><Alert type="info" showIcon message="命名空间关系不会自动绑定到源表" /><ProTable search={false} options={false} pagination={false} rowKey={(row) => String(row.relationId)} columns={relationColumns} dataSource={namespaceRelations} locale={{ emptyText: <Empty description="暂无命名空间关系" /> }} /></Card></Space>,
            },
            {
              key: 'consistency', label: '一致性', children: <Card title={<Space><CheckCircleOutlined />三方一致性</Space>}><Descriptions bordered column={1}><Descriptions.Item label="Source">{table.sourceConsistencyStatus || 'UNKNOWN'} · 由 OpenMetadata snapshot 提供</Descriptions.Item><Descriptions.Item label="Target">{table.targetConsistencyStatus || 'UNKNOWN'} · 显式对账读取 Doris</Descriptions.Item><Descriptions.Item label="Task">{table.taskConsistencyStatus || 'UNBOUND'} · 由任务关联状态提供</Descriptions.Item></Descriptions></Card>,
            },
            {
              key: 'operations', label: '操作记录', children: <Card title={<Space><SettingOutlined />操作时间线</Space>}><OperationTimeline items={timelineItems} /></Card>,
            },
          ]}
        />
      </div>
      <TableDeleteModal open={deleteOpen} table={table} onClose={() => setDeleteOpen(false)} onDeleted={() => void load()} />
    </PageContainer>
  );
};

export default ManagedTableDetailPage;
