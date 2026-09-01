import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  FileSearchOutlined,
  InfoCircleOutlined,
  LeftOutlined,
  RightOutlined,
  SafetyCertificateOutlined,
  TableOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, useLocation } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Collapse,
  Descriptions,
  Empty,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Spin,
  Steps,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { fetchLifecyclePolicies, normalizeLakePage } from '@/services/lake';
import type { LakeLifecyclePolicy, LakePage } from '@/services/lake';
import {
  createManagedTable,
  fetchPhysicalSource,
  fetchSourceDatabases,
  fetchSourceSchemas,
  fetchSourceTableDetail,
  fetchSourceTables,
  previewManagedTable,
} from './service';
import type { ApiResponse, OdsSourceTable, OdsSourceTableDetail, PhysicalDataSource } from './types';
import './wizard.less';

const { Paragraph, Text, Title } = Typography;

interface WizardColumn {
  sourceField: string;
  sourceType?: string;
  sourceNullable?: boolean;
  targetField: string;
  targetType: string;
  key: boolean;
}

interface WizardValues {
  databaseFqn?: string;
  schemaFqn?: string;
  tableId?: string;
  targetTableName?: string;
  tableModel?: 'DUPLICATE' | 'UNIQUE';
  partitionEnabled?: boolean;
  partitionColumn?: string;
  granularity?: 'DAY' | 'MONTH' | 'YEAR';
  lifecyclePolicyId?: number;
}

const responseMessage = <T,>(response: ApiResponse<T>, fallback: string) => response.message || response.msg || fallback;

const normalizeType = (value?: string, key = false) => {
  const type = String(value || '').toUpperCase();
  if (key) return type.includes('CHAR') || type.includes('STRING') || !type ? 'VARCHAR(255)' : type;
  return type || 'STRING';
};

const isDateType = (value?: string) => /DATE|DATETIME|TIMESTAMP/i.test(value || '');

const sourceKeyColumns = (detail: OdsSourceTableDetail) => {
  const primary = (detail.tableConstraints || []).find((constraint) => /PRIMARY|UNIQUE/i.test(constraint.constraintType || ''));
  const constrained = new Set(primary?.columns || []);
  return new Set((detail.columns || []).filter((column) => constrained.has(column.name) || /PRIMARY/i.test(column.constraint || '')).map((column) => column.name));
};

const ManagedTableWizard: React.FC = () => {
  const location = useLocation();
  const query = useMemo(() => new URLSearchParams(location.search), [location.search]);
  const sourceId = Number(query.get('sourceDataSourceId'));
  const bindingId = Number(query.get('odsDatabaseBindingId'));
  const [form] = Form.useForm<WizardValues>();
  const [current, setCurrent] = useState(0);
  const [source, setSource] = useState<PhysicalDataSource>();
  const [databases, setDatabases] = useState<Array<{ value: string; label: string }>>([]);
  const [schemas, setSchemas] = useState<Array<{ value: string; label: string }>>([]);
  const [tables, setTables] = useState<OdsSourceTable[]>([]);
  const [selectedTable, setSelectedTable] = useState<OdsSourceTable>();
  const [tableDetail, setTableDetail] = useState<OdsSourceTableDetail>();
  const [columns, setColumns] = useState<WizardColumn[]>([]);
  const [policies, setPolicies] = useState<LakeLifecyclePolicy[]>([]);
  const [loading, setLoading] = useState(true);
  const [tableLoading, setTableLoading] = useState(false);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [createLoading, setCreateLoading] = useState(false);
  const [preview, setPreview] = useState<Awaited<ReturnType<typeof previewManagedTable>>['data']>();
  const databaseFqn = Form.useWatch('databaseFqn', form);
  const schemaFqn = Form.useWatch('schemaFqn', form);
  const partitionEnabled = Form.useWatch('partitionEnabled', form);

  const load = useCallback(async () => {
    if (!sourceId || !bindingId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const [sourceResponse, databaseResponse, policyResponse] = await Promise.all([
        fetchPhysicalSource(sourceId),
        fetchSourceDatabases(sourceId),
        fetchLifecyclePolicies({ pageNo: 1, pageSize: 100 }),
      ]);
      if (sourceResponse.code !== 0 || !sourceResponse.data) throw new Error(responseMessage(sourceResponse, '数据源加载失败'));
      setSource(sourceResponse.data);
      if (databaseResponse.code === 0) setDatabases((databaseResponse.data || []).map((item) => ({ value: item.fullyQualifiedName || item.name, label: item.name || item.fullyQualifiedName })));
      if (policyResponse.code === 0) setPolicies(normalizeLakePage(policyResponse.data as LakePage<LakeLifecyclePolicy>).data);
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '建表向导初始化失败');
    } finally {
      setLoading(false);
    }
  }, [bindingId, sourceId]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    if (!databaseFqn || !sourceId) {
      setSchemas([]);
      setTables([]);
      return;
    }
    form.setFieldValue('schemaFqn', undefined);
    form.setFieldValue('tableId', undefined);
    void fetchSourceSchemas(sourceId, databaseFqn).then((response) => {
      if (response.code === 0) setSchemas((response.data || []).map((item) => ({ value: item.fullyQualifiedName, label: item.name || item.fullyQualifiedName })));
    });
  }, [databaseFqn, form, sourceId]);

  useEffect(() => {
    if (!databaseFqn || !schemaFqn || !sourceId) {
      setTables([]);
      return;
    }
    form.setFieldValue('tableId', undefined);
    void fetchSourceTables(sourceId, databaseFqn, schemaFqn).then((response) => {
      if (response.code === 0) setTables(response.data?.records || []);
    });
  }, [databaseFqn, form, schemaFqn, sourceId]);

  const selectTable = async (tableId: string) => {
    const table = tables.find((item) => item.id === tableId);
    if (!table || !sourceId) return;
    setSelectedTable(table);
    setTableLoading(true);
    try {
      const response = await fetchSourceTableDetail(sourceId, table.id);
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response, '源表结构读取失败'));
      setTableDetail(response.data);
      const keys = sourceKeyColumns(response.data);
      const nextColumns = (response.data.columns || []).map((column) => {
        const key = keys.has(column.name);
        return {
          sourceField: column.name,
          sourceType: column.dataTypeDisplay || column.dataType,
          sourceNullable: column.constraint ? !/NOT_NULL|NOT NULL/i.test(column.constraint) : true,
          targetField: column.name,
          targetType: normalizeType(column.dataTypeDisplay || column.dataType, key),
          key,
        };
      });
      setColumns(nextColumns);
      form.setFieldsValue({
        targetTableName: table.name,
        tableModel: 'DUPLICATE',
        partitionEnabled: false,
        partitionColumn: undefined,
        granularity: 'DAY',
      });
    } catch (reason) {
      setTableDetail(undefined);
      message.error(reason instanceof Error ? reason.message : '源表结构读取失败');
    } finally {
      setTableLoading(false);
    }
  };

  const updateColumn = (index: number, patch: Partial<WizardColumn>) => {
    setColumns((currentColumns) => currentColumns.map((column, columnIndex) => {
      if (columnIndex !== index) return column;
      const next = { ...column, ...patch };
      if (patch.key !== undefined && patch.key) next.targetType = normalizeType(next.targetType, true);
      return next;
    }));
  };

  const payload = () => {
    const values = form.getFieldsValue();
    return {
      sourceDataSourceId: sourceId,
      omEntityId: selectedTable?.id,
      odsDatabaseBindingId: bindingId,
      lifecyclePolicyId: values.lifecyclePolicyId,
      targetTableName: values.targetTableName,
      tableModel: values.tableModel || 'DUPLICATE',
      columns: columns.map((column) => ({ sourceField: column.sourceField, targetField: column.targetField, targetType: column.targetType, key: column.key })),
      keyColumns: columns.filter((column) => column.key).map((column) => column.targetField),
      partition: { enabled: Boolean(values.partitionEnabled), column: values.partitionEnabled ? values.partitionColumn : undefined, granularity: values.partitionEnabled ? values.granularity : undefined },
      distribution: { type: 'RANDOM', columns: [], buckets: 'AUTO' },
    };
  };

  const runPreview = async () => {
    try {
      await form.validateFields(['databaseFqn', 'schemaFqn', 'tableId', 'targetTableName', 'tableModel']);
      if (!selectedTable || !tableDetail || !columns.length) throw new Error('请先选择并读取源表结构');
      if (form.getFieldValue('partitionEnabled') && !form.getFieldValue('partitionColumn')) throw new Error('启用分区后请选择分区字段');
      setPreviewLoading(true);
      const response = await previewManagedTable(payload());
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response, 'Preview 失败'));
      setPreview(response.data);
      setCurrent(3);
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '请完善当前步骤');
    } finally {
      setPreviewLoading(false);
    }
  };

  const create = async () => {
    if (!preview?.previewToken) return;
    setCreateLoading(true);
    try {
      const response = await createManagedTable(preview.previewToken);
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response, '创建 MANAGED 表失败'));
      message.success('MANAGED 表创建操作已提交');
      history.replace(`/lake/resources/table/${response.data.id}`);
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '创建 MANAGED 表失败');
    } finally {
      setCreateLoading(false);
    }
  };

  const sourceColumns = useMemo(() => [
    { title: 'Key', dataIndex: 'key', width: 72, render: (_: unknown, row: WizardColumn, index: number) => <Checkbox checked={row.key} onChange={(event) => updateColumn(index, { key: event.target.checked })} /> },
    { title: '源字段', dataIndex: 'sourceField', width: 180 },
    { title: '源类型', dataIndex: 'sourceType', width: 160, render: (value: string) => <Text type="secondary">{value || '-'}</Text> },
    { title: 'Nullable', dataIndex: 'sourceNullable', width: 100, render: (value: boolean) => value ? 'YES' : 'NO' },
    { title: '目标字段', dataIndex: 'targetField', width: 190, render: (_: unknown, row: WizardColumn, index: number) => <Input value={row.targetField} onChange={(event) => updateColumn(index, { targetField: event.target.value })} /> },
    { title: '目标类型', dataIndex: 'targetType', width: 180, render: (_: unknown, row: WizardColumn, index: number) => <Select value={row.targetType} onChange={(value) => updateColumn(index, { targetType: value })} options={['STRING', 'VARCHAR(255)', 'BIGINT', 'INT', 'DATE', 'DATETIME', 'DECIMAL(18,2)', 'DOUBLE'].map((value) => ({ value, label: value }))} style={{ width: 150 }} /> },
    { title: '说明', key: 'hint', render: (_: unknown, row: WizardColumn) => row.key && row.targetType === 'VARCHAR(255)' ? <Text type="warning"><InfoCircleOutlined /> Key 不使用 STRING</Text> : null },
  ], [columns]);

  if (loading) return <PageContainer title="创建 MANAGED 表"><Spin /></PageContainer>;
  if (!source || !source.odsDatabaseBindingId) return <PageContainer title="创建 MANAGED 表"><Alert type="warning" showIcon message="尚未准备 ODS Database" description="请先返回物理资源详情创建并等待 ODS Database READY。" action={<Button onClick={() => history.back()}>返回</Button>} /></PageContainer>;

  return (
    <PageContainer title="创建 MANAGED 表" subTitle="从 OpenMetadata 源表生成受控的 Doris ODS 表" onBack={() => history.back()} extra={<Button icon={<ArrowLeftOutlined />} onClick={() => history.back()}>返回资源详情</Button>}>
      <div className="lake-wizard-page">
        <Card className="lake-wizard-intro" bordered={false}>
          <div className="lake-wizard-intro-icon"><SafetyCertificateOutlined /></div>
          <div><div className="lake-detail-kicker">SAFE TABLE CREATION</div><Title level={3}>创建一张可追溯的 MANAGED 表</Title><Paragraph type="secondary">源表、字段映射、分区和生命周期会先在服务端校验，再生成一次性 Preview Token。页面不会执行 SQL。</Paragraph></div>
        </Card>
        <Card className="lake-wizard-card">
          <Steps current={current} items={[{ title: '选择源表', description: 'OpenMetadata 结构' }, { title: '编辑表结构', description: '字段与 Key' }, { title: '分区与生命周期', description: 'Auto Range' }, { title: 'Preview 并创建', description: '确认后提交' }]} />
          <div className="lake-wizard-body">
            {current === 0 ? <div className="lake-step-panel"><Alert type="info" showIcon message={`当前数据源：${source.sourceDataSourceName || source.sourceDataSourceId}`} description="列表来自现有 OpenMetadata 探查结果；如果没有数据库，请先在数据源页完成 Metadata 探查。" /><Form form={form} layout="vertical" className="lake-step-form"><Space align="start" wrap className="lake-cascade-row"><Form.Item name="databaseFqn" label="数据库" rules={[{ required: true, message: '请选择数据库' }]}><Select showSearch options={databases} placeholder="选择数据库" optionFilterProp="label" style={{ minWidth: 240 }} /></Form.Item><Form.Item name="schemaFqn" label="Schema" rules={[{ required: true, message: '请选择 Schema' }]}><Select showSearch options={schemas} placeholder="选择 Schema" optionFilterProp="label" disabled={!databaseFqn} style={{ minWidth: 240 }} /></Form.Item><Form.Item name="tableId" label="源表" rules={[{ required: true, message: '请选择源表' }]}><Select showSearch options={tables.map((item) => ({ value: item.id, label: `${item.name} · ${item.fullyQualifiedName}` }))} placeholder="选择源表" optionFilterProp="label" disabled={!schemaFqn} onChange={(value) => void selectTable(value)} style={{ minWidth: 320 }} /></Form.Item></Space></Form>{tableLoading ? <Spin /> : selectedTable && tableDetail ? <Card size="small" className="lake-source-summary" title={<Space><TableOutlined />{selectedTable.fullyQualifiedName}</Space>}><Descriptions size="small" column={{ xs: 1, sm: 2, md: 4 }}><Descriptions.Item label="字段数">{tableDetail.columns?.length || 0}</Descriptions.Item><Descriptions.Item label="约束">{tableDetail.tableConstraints?.length || 0}</Descriptions.Item><Descriptions.Item label="探查状态">已读取源端最新结构</Descriptions.Item><Descriptions.Item label="表类型">TABLE</Descriptions.Item></Descriptions></Card> : <Empty description="选择源表后读取结构" />}</div> : null}
            {current === 1 ? <div className="lake-step-panel"><Form form={form} layout="vertical" className="lake-step-form"><Space align="start" wrap><Form.Item name="targetTableName" label="目标表名" rules={[{ required: true, message: '请输入目标表名' }, { pattern: /^[A-Za-z0-9_]+$/, message: '仅支持字母、数字和下划线' }]}><Input prefix={<TableOutlined />} placeholder="例如 orders" style={{ width: 300 }} /></Form.Item><Form.Item name="tableModel" label="表模型" rules={[{ required: true }]}><Select options={[{ value: 'DUPLICATE', label: 'Duplicate（排序 Key）' }, { value: 'UNIQUE', label: 'Unique（唯一 Key）' }]} style={{ width: 240 }} /></Form.Item></Space></Form><Card size="small" title="字段映射" extra={<Text type="secondary">Key 字段会自动避开 STRING</Text>}><Table rowKey="sourceField" columns={sourceColumns} dataSource={columns} pagination={false} scroll={{ x: 1100 }} locale={{ emptyText: <Empty description="请返回上一步选择源表" /> }} /></Card></div> : null}
            {current === 2 ? <div className="lake-step-panel"><Form form={form} layout="vertical" className="lake-step-form"><Card size="small" title="时间分区" extra={<Switch checked={partitionEnabled} onChange={(value) => { form.setFieldValue('partitionEnabled', value); if (!value) { form.setFieldValue('partitionColumn', undefined); form.setFieldValue('lifecyclePolicyId', undefined); } }} />}><Paragraph type="secondary">开启后由 Doris Auto Range 管理历史分区；分区字段必须是源端和目标端均非空的 DATE/DATETIME。</Paragraph>{partitionEnabled ? <Space align="start" wrap><Form.Item name="partitionColumn" label="分区字段" rules={[{ required: true, message: '请选择分区字段' }]}><Select options={columns.filter((column) => !column.sourceNullable && isDateType(column.targetType)).map((column) => ({ value: column.targetField, label: `${column.targetField} · ${column.targetType}` }))} placeholder="选择时间字段" style={{ width: 280 }} /></Form.Item><Form.Item name="granularity" label="粒度" rules={[{ required: true }]}><Select options={[{ value: 'DAY', label: '按天' }, { value: 'MONTH', label: '按月' }, { value: 'YEAR', label: '按年' }]} style={{ width: 160 }} /></Form.Item><Form.Item name="lifecyclePolicyId" label="生命周期策略"><Select allowClear options={policies.filter((policy) => policy.status === 'ACTIVE').map((policy) => ({ value: policy.id, label: `${policy.policyName} · 保留 ${policy.retentionCount} 个历史分区` }))} placeholder="可选策略" style={{ width: 300 }} /></Form.Item></Space> : <Text type="secondary">未开启分区时，生命周期只能保持永久。</Text>}</Card></Form></div> : null}
            {current === 3 ? <div className="lake-step-panel"><Alert type={preview?.errors?.length ? 'error' : 'success'} showIcon icon={preview?.errors?.length ? undefined : <CheckCircleOutlined />} message={preview?.errors?.length ? 'Preview 未通过' : 'Preview 已生成，可提交创建'} description={preview?.errors?.length ? preview.errors.join('；') : '服务端已校验源快照、合同、字段映射和目标命名。'} /><div className="lake-preview-grid"><Card size="small" title="Source Summary"><Descriptions size="small" column={1}><Descriptions.Item label="源表">{selectedTable?.fullyQualifiedName || '-'}</Descriptions.Item><Descriptions.Item label="Schema Hash">{preview?.sourceSchemaHash || '-'}</Descriptions.Item></Descriptions></Card><Card size="small" title="Target Contract"><Descriptions size="small" column={1}><Descriptions.Item label="目标表">{preview?.targetTableName || '-'}</Descriptions.Item><Descriptions.Item label="字段数">{preview?.targetContract?.columns?.length || 0}</Descriptions.Item><Descriptions.Item label="Key">{preview?.targetContract?.keyColumns?.join(', ') || '无'}</Descriptions.Item><Descriptions.Item label="分区">{preview?.targetContract?.partition?.enabled ? `${preview.targetContract.partition.column} · ${preview.targetContract.partition.granularity}` : '永久'}</Descriptions.Item></Descriptions></Card></div>{preview?.warnings?.length ? <Alert type="warning" showIcon message="请确认以下提醒" description={<ul>{preview.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul>} /> : null}<Collapse className="lake-ddl-collapse" items={[{ key: 'ddl', label: '查看脱敏 DDL Preview', children: <pre>{preview?.ddl || '服务端未返回 DDL'}</pre> }]} /><div className="lake-create-footer"><Button onClick={() => setCurrent(2)}>返回修改</Button><Tooltip title={preview?.errors?.length ? '请先解决 Preview errors' : undefined}><Button type="primary" loading={createLoading} disabled={!preview?.previewToken || Boolean(preview?.errors?.length)} onClick={() => void create()}>确认创建 MANAGED 表</Button></Tooltip></div></div> : null}
          </div>
          {current < 3 ? <div className="lake-wizard-footer"><Button icon={<LeftOutlined />} disabled={current === 0} onClick={() => setCurrent((step) => step - 1)}>上一步</Button><Button type="primary" icon={current === 2 ? <FileSearchOutlined /> : <RightOutlined />} loading={previewLoading} onClick={() => { if (current === 2) void runPreview(); else setCurrent((step) => step + 1); }}>{current === 2 ? '生成 Preview' : '下一步'}</Button></div> : null}
        </Card>
      </div>
    </PageContainer>
  );
};

export default ManagedTableWizard;
