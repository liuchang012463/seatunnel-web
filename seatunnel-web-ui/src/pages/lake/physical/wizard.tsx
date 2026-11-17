import { InfoCircleOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, useLocation } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Collapse,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Spin,
  Steps,
  Switch,
  Table,
  Typography,
  message,
} from 'antd';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { fetchApplicableLifecyclePolicies } from '@/services/lake';
import type { LakeLifecyclePolicy } from '@/services/lake';
import {
  createManagedTable,
  fetchPhysicalSource,
  fetchPhysicalInventory,
  fetchSourceDatabases,
  fetchSourceSchemas,
  fetchSourceTableDetail,
  fetchSourceTables,
  previewManagedTable,
} from './service';
import type { ApiResponse, OdsSourceTable, OdsSourceTableDetail, PhysicalDataSource } from './types';
import { primaryKeyNames, sourceNullable, suggestedDorisType, suggestedTableName } from './tableDefaults';
import './wizard.less';

const { Text } = Typography;

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

const responseMessage = <T,>(response: ApiResponse<T>, fallback: string) => {
  const reason = response.message || response.msg || fallback;
  if (reason.includes('Source metadata changed')) return '源表结构已变化，请返回重新检查。';
  if (reason.includes('planFingerprint is invalid or expired')) return '建表预览已过期，请返回重新检查。';
  if (reason.includes('target table already exists')) return '目标表已存在，请返回修改表名。';
  return reason;
};

const isDateType = (value?: string) => /^(DATE|DATETIME)(?:\b|\()/i.test(String(value || '').trim());

const isKeyForbiddenType = (value?: string) => {
  const type = String(value || '').toUpperCase();
  return !type || /STRING|TEXT|FLOAT|DOUBLE|ARRAY|MAP|STRUCT|JSON/.test(type);
};

const ManagedTableWizard: React.FC = () => {
  const location = useLocation();
  const query = useMemo(() => new URLSearchParams(location.search), [location.search]);
  const sourceId = Number(query.get('sourceDataSourceId'));
  const bindingId = Number(query.get('odsDatabaseBindingId'));
  const [form] = Form.useForm<WizardValues>();
  const [current, setCurrent] = useState(0);
  const [loadError, setLoadError] = useState('');
  const [advanced, setAdvanced] = useState<string[]>([]);
  const tableRequest = useRef(0);
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
  const [warningsAcknowledged, setWarningsAcknowledged] = useState(false);
  const databaseFqn = Form.useWatch('databaseFqn', { form, preserve: true });
  const schemaFqn = Form.useWatch('schemaFqn', { form, preserve: true });
  const partitionEnabled = Form.useWatch('partitionEnabled', { form, preserve: true });

  const load = useCallback(async () => {
    if (!sourceId || !bindingId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setLoadError('');
    try {
      const [sourceResponse, databaseResponse, policyResponse] = await Promise.all([
        fetchPhysicalSource(sourceId),
        fetchSourceDatabases(sourceId),
        fetchApplicableLifecyclePolicies(sourceId),
      ]);
      if (sourceResponse.code !== 0 || !sourceResponse.data) throw new Error(responseMessage(sourceResponse, '数据源加载失败'));
      setSource(sourceResponse.data);
      if (databaseResponse.code !== 0) throw new Error(responseMessage(databaseResponse, '源数据库读取失败'));
      const nextDatabases = (databaseResponse.data || []).map((item) => ({ value: item.fullyQualifiedName || item.name, label: item.name || item.fullyQualifiedName }));
      setDatabases(nextDatabases);
      if (nextDatabases.length === 1) form.setFieldValue('databaseFqn', nextDatabases[0].value);
      if (policyResponse.code === 0) setPolicies(policyResponse.data || []);
    } catch (reason) {
      setLoadError(reason instanceof Error ? reason.message : '建表向导初始化失败');
    } finally {
      setLoading(false);
    }
  }, [bindingId, sourceId, form]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    let cancelled = false;
    tableRequest.current += 1;
    setSelectedTable(undefined);
    setTableDetail(undefined);
    setColumns([]);
    setSchemas([]);
    setTables([]);
    form.setFieldsValue({ schemaFqn: undefined, tableId: undefined });
    if (databaseFqn && sourceId) void fetchSourceSchemas(sourceId, databaseFqn).then((response) => {
      if (cancelled) return;
      if (response.code !== 0) throw new Error(responseMessage(response, 'Schema 读取失败'));
      const next = (response.data || []).map((item) => ({ value: item.fullyQualifiedName, label: item.name || item.fullyQualifiedName }));
      setSchemas(next);
      if (next.length === 1) form.setFieldValue('schemaFqn', next[0].value);
    }).catch((error) => { if (!cancelled) message.error(error.message); });
    return () => { cancelled = true; };
  }, [databaseFqn, form, sourceId]);

  useEffect(() => {
    let cancelled = false;
    tableRequest.current += 1;
    setSelectedTable(undefined);
    setTableDetail(undefined);
    setColumns([]);
    setTables([]);
    form.setFieldValue('tableId', undefined);
    if (databaseFqn && schemaFqn && sourceId) void fetchSourceTables(sourceId, databaseFqn, schemaFqn).then((response) => {
      if (cancelled) return;
      if (response.code !== 0) throw new Error(responseMessage(response, '源表列表读取失败'));
      setTables(response.data?.records || []);
    }).catch((error) => { if (!cancelled) message.error(error.message); });
    return () => { cancelled = true; };
  }, [databaseFqn, form, schemaFqn, sourceId]);

  const selectTable = async (tableId: string) => {
    const table = tables.find((item) => item.id === tableId);
    if (!table || !sourceId) return;
    const request = ++tableRequest.current;
    setSelectedTable(table);
    setTableDetail(undefined);
    setColumns([]);
    setTableLoading(true);
    try {
      const response = await fetchSourceTableDetail(sourceId, table.id);
      if (request !== tableRequest.current) return;
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response, '源表结构读取失败'));
      setTableDetail(response.data);
      const keys = primaryKeyNames(response.data);
      const nextColumns = (response.data.columns || []).map((column) => {
        const key = keys.has(column.name.toLowerCase());
        return {
          sourceField: column.name,
          sourceType: column.dataTypeDisplay || column.dataType,
          sourceNullable: sourceNullable(column, keys),
          targetField: column.name,
          targetType: suggestedDorisType(column, key),
          key,
        };
      });
      if (!nextColumns.some((column) => column.key)) {
        const fallback = nextColumns.find((column) => !column.sourceNullable && !isKeyForbiddenType(column.targetType));
        if (fallback) fallback.key = true;
      }
      setColumns(nextColumns);
      form.setFieldsValue({
        targetTableName: suggestedTableName(databases.find((item) => item.value === databaseFqn)?.label || '', schemas.find((item) => item.value === schemaFqn)?.label || '', table.name),
        tableModel: 'DUPLICATE',
        partitionEnabled: false,
        partitionColumn: undefined,
        granularity: 'DAY',
        lifecyclePolicyId: undefined,
      });
    } catch (reason) {
      if (request !== tableRequest.current) return;
      setTableDetail(undefined);
      message.error(reason instanceof Error ? reason.message : '源表结构读取失败');
    } finally {
      if (request === tableRequest.current) setTableLoading(false);
    }
  };

  const updateColumn = (index: number, patch: Partial<WizardColumn>) => {
    setColumns((currentColumns) => currentColumns.map((column, columnIndex) => {
      if (columnIndex !== index) return column;
      const next = { ...column, ...patch };
      if (patch.key && isKeyForbiddenType(next.targetType)) next.targetType = 'VARCHAR(255)';
      if (patch.targetType !== undefined && next.key && isKeyForbiddenType(next.targetType)) {
        next.targetType = 'VARCHAR(255)';
      }
      return next;
    }));
  };

  const payload = () => {
    const values = form.getFieldsValue(true);
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
      distribution: values.tableModel === 'UNIQUE'
        ? { type: 'HASH', columns: columns.filter((column) => column.key).map((column) => column.targetField), buckets: 'AUTO' }
        : { type: 'RANDOM', columns: [], buckets: 'AUTO' },
    };
  };

  const runPreview = async () => {
    try {
      await form.validateFields(['databaseFqn', 'schemaFqn', 'tableId', 'targetTableName', 'tableModel']);
      if (!selectedTable || !tableDetail || !columns.length) throw new Error('请先选择并读取源表结构');
      const names = columns.map((column) => column.targetField.trim().toUpperCase());
      if (names.some((name) => !name) || new Set(names).size !== names.length) throw new Error('目标字段名不能为空或重复，请检查高级配置');
      if (!columns.some((column) => column.key)) throw new Error('请在高级配置中至少选择一个非空字段作为排序键');
      if (columns.some((column) => column.key && column.sourceNullable)) throw new Error('排序键必须是源端非空字段，请检查高级配置');
      if (form.getFieldValue('partitionEnabled')) {
        const partitionColumn = form.getFieldValue('partitionColumn');
        if (!columns.some((column) => column.targetField === partitionColumn && !column.sourceNullable && isDateType(column.targetType))) throw new Error('请选择非空的日期或时间字段作为分区字段');
        if (form.getFieldValue('tableModel') === 'UNIQUE' && !columns.some((column) => column.targetField === partitionColumn && column.key)) throw new Error('按键合并时，分区字段也必须是排序键');
      }
      setPreviewLoading(true);
      const response = await previewManagedTable(payload());
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response, '建表检查失败'));
      setPreview(response.data);
      setWarningsAcknowledged(false);
      setCurrent(1);
    } catch (reason) {
      setAdvanced(['structure', 'partition']);
      message.error(reason instanceof Error ? reason.message : '请完善当前步骤');
    } finally {
      setPreviewLoading(false);
    }
  };

  const create = async () => {
    if (!preview?.planFingerprint) return;
    if (preview.warnings?.length && !warningsAcknowledged) {
      message.warning('请先勾选“已了解写入转换风险”');
      return;
    }
    setCreateLoading(true);
    try {
      const response = await createManagedTable(preview.planFingerprint);
      if (response.code !== 0 || !response.data) throw new Error(responseMessage(response, '创建 ODS 表失败'));
      message.success('ODS 表已提交创建，正在准备全量任务');
      if (response.data.resourceStatus !== 'READY') {
        history.replace(`/lake/resources/table/${response.data.id}`);
        return;
      }
      const allocated = await import('../../batch-link-up/api').then(({ seatunnelJobDefinitionApi }) => seatunnelJobDefinitionApi.getUniqueId());
      if (allocated.code !== 0 || !allocated.data) {
        history.replace(`/lake/resources/table/${response.data.id}`);
        return;
      }
      const jobId = typeof allocated.data === 'object' ? (allocated.data as any).id : allocated.data;
      history.replace(`/sync/batch-link-up/${jobId}/detail?lakeMappingId=${response.data.id}`);
    } catch (reason) {
      // A failed external DDL may already have a tracked operation. Open its
      // recovery page instead of leaving the user with a consumed preview.
      try {
        const inventory = await fetchPhysicalInventory(bindingId);
        const mapping = inventory.data?.registeredTables?.find((item) => item.targetTableName === preview.targetTableName);
        if (mapping?.mappingId) {
          message.warning('创建尚未完成，已打开资源详情，请检查状态后重试。');
          history.replace(`/lake/resources/table/${mapping.mappingId}`);
          return;
        }
      } catch { /* The original error remains the useful result. */ }
      message.error(reason instanceof Error ? reason.message : '创建 ODS 表失败');
    } finally {
      setCreateLoading(false);
    }
  };

  const sourceColumns = useMemo(() => [
    { title: '排序键', dataIndex: 'key', width: 72, render: (_: unknown, row: WizardColumn, index: number) => <Checkbox aria-label={`${row.sourceField}设为排序键`} checked={row.key} onChange={(event) => updateColumn(index, { key: event.target.checked })} /> },
    { title: '源字段', dataIndex: 'sourceField', width: 180 },
    { title: '源类型', dataIndex: 'sourceType', width: 160, render: (value: string) => <Text type="secondary">{value || '-'}</Text> },
    { title: '允许空值', dataIndex: 'sourceNullable', width: 100, render: (value: boolean) => value ? '是' : '否' },
    { title: '目标字段', dataIndex: 'targetField', width: 190, render: (_: unknown, row: WizardColumn, index: number) => <Input aria-label={`${row.sourceField}的目标字段名`} value={row.targetField} onChange={(event) => updateColumn(index, { targetField: event.target.value })} /> },
    { title: '目标类型', dataIndex: 'targetType', width: 180, render: (_: unknown, row: WizardColumn, index: number) => <Select aria-label={`${row.sourceField}的目标类型`} value={row.targetType} onChange={(value) => updateColumn(index, { targetType: value })} options={Array.from(new Set([row.targetType, 'STRING', 'VARCHAR(255)', 'BIGINT', 'INT', 'DATE', 'DATETIME', 'DECIMAL(18,2)', 'DOUBLE'])).map((value) => ({ value, label: value, disabled: row.key && isKeyForbiddenType(value) }))} style={{ width: 150 }} /> },
    { title: '说明', key: 'hint', render: (_: unknown, row: WizardColumn) => row.key && row.targetType === 'VARCHAR(255)' ? <Text type="warning"><InfoCircleOutlined /> Key 不使用 STRING</Text> : null },
  ], [columns]);

  if (loading) return <PageContainer title={false}><Spin /></PageContainer>;
  if (loadError) return <PageContainer title={false}><Alert type="error" showIcon message={loadError} action={<Button onClick={load}>重新加载</Button>} /></PageContainer>;
  if (!source || !source.odsDatabaseBindingId) return <PageContainer title={false}><Alert type="warning" showIcon message="ODS 库尚未就绪" action={<Button onClick={() => history.push('/lake/resources')}>返回物理入湖</Button>} /></PageContainer>;

  return <PageContainer title={false}>
    <div className="lake-wizard-page">
      <Card className="lake-wizard-card">
        <Steps current={current} items={[{ title: '选表与配置' }, { title: '确认并创建任务' }]} />
        <div className="lake-wizard-body">
          <Form name="lake-managed-table" form={form} layout="vertical" preserve>
            {current === 0 && <div className="lake-step-panel">
              <Text type="secondary">数据源：{source.sourceDataSourceName}　目标库：{source.odsDatabase?.databaseName}</Text>
              <Space align="start" wrap className="lake-cascade-row">
                <Form.Item name="databaseFqn" label="来源数据库" rules={[{ required: true, message: '请选择数据库' }]}>
                  <Select showSearch options={databases} placeholder="选择数据库" optionFilterProp="label" style={{ width: 240 }} /></Form.Item>
                <Form.Item name="schemaFqn" label="Schema" rules={[{ required: true, message: '请选择 Schema' }]}>
                  <Select showSearch options={schemas} placeholder="选择 Schema" optionFilterProp="label" disabled={!databaseFqn} style={{ width: 220 }} /></Form.Item>
                <Form.Item name="tableId" label="来源表" rules={[{ required: true, message: '请选择来源表' }]}>
                  <Select showSearch options={tables.map((item) => ({ value: item.id, label: item.name }))} placeholder="选择来源表" optionFilterProp="label" disabled={!schemaFqn} loading={tableLoading} onChange={(value) => void selectTable(value)} style={{ width: 280 }} /></Form.Item>
              </Space>
              {!tables.length && schemaFqn && <Alert type="info" message="此范围暂无已采集的表结构" description="可在数据源管理中采集元数据后，返回此处刷新。" action={<Button onClick={() => history.push('/data-source')}>查看数据源</Button>} />}
              {tableDetail && !tableLoading && <>
                <Form.Item name="targetTableName" label="ODS 表名" extra={`已按源结构生成 ${columns.length} 个字段；默认自动分桶、长期保留。`} rules={[{ required: true, message: '请输入表名' }, { pattern: /^[A-Za-z0-9_]+$/, message: '仅支持字母、数字和下划线' }]}>
                  <Input style={{ maxWidth: 560 }} /></Form.Item>
                <Form.Item name="lifecyclePolicyId" label="生命周期策略" extra="只展示适用于当前数据源的启用策略。选定策略后自动开启时间分区。">
                  <Select allowClear style={{ maxWidth: 560 }} placeholder="长期保留（默认）"
                    options={policies.map((policy, index) => ({ value: policy.id, label: `${index === 0 ? '推荐 · ' : ''}${policy.policyName} · ${policy.retentionCount} 个历史${({ DAY: '日', MONTH: '月', YEAR: '年' })[policy.granularity || 'DAY']}分区` }))}
                    onChange={(id) => {
                      const policy = policies.find((item) => item.id === id);
                      form.setFieldsValue({ partitionEnabled: !!id, granularity: policy?.granularity || 'DAY', partitionColumn: id ? columns.find((column) => !column.sourceNullable && isDateType(column.targetType))?.targetField : undefined });
                      if (id) setAdvanced(['partition']);
                    }} /></Form.Item>
                <Collapse activeKey={advanced} onChange={(keys) => setAdvanced(Array.isArray(keys) ? keys : [keys])} items={[
                  { key: 'structure', forceRender: true, label: `字段映射与表模型（${columns.length} 个字段，自动分桶）`, children: <>
                    <Form.Item name="tableModel" label="表模型"><Select style={{ width: 320 }} options={[{ value: 'DUPLICATE', label: '保留明细（默认）' }, { value: 'UNIQUE', label: '按键合并（相同键保留最新记录）' }]} /></Form.Item>
                    <Table rowKey="sourceField" columns={sourceColumns} dataSource={columns} pagination={false} scroll={{ x: 1100 }} />
                  </> },
                  { key: 'partition', forceRender: true, label: '时间分区与存储设置', children: <>
                    <Space style={{ marginBottom: 16 }}><Switch aria-label="启用时间分区" checked={!!partitionEnabled} onChange={(enabled) => {
                      form.setFieldsValue({ partitionEnabled: enabled, ...(enabled ? {} : { partitionColumn: undefined, lifecyclePolicyId: undefined }) });
                    }} />启用时间分区</Space>
                    {partitionEnabled && <Space align="start" wrap>
                      <Form.Item name="partitionColumn" label="分区时间字段" rules={[{ required: true, message: '请选择分区字段' }]}>
                        <Select style={{ width: 280 }} placeholder="选择非空时间字段" options={columns.filter((column) => !column.sourceNullable && isDateType(column.targetType)).map((column) => ({ value: column.targetField, label: column.targetField }))} /></Form.Item>
                      <Form.Item name="granularity" label="分区粒度"><Select style={{ width: 160 }} options={[{ value: 'DAY', label: '按天' }, { value: 'MONTH', label: '按月' }, { value: 'YEAR', label: '按年' }]} onChange={() => form.setFieldValue('lifecyclePolicyId', undefined)} /></Form.Item>
                    </Space>}
                    {partitionEnabled && !columns.some((column) => !column.sourceNullable && isDateType(column.targetType)) && <Alert type="warning" message="没有可用的非空日期或时间字段；请保持长期保留，或先调整源表结构。" />}
                  </> },
                ]} />
              </>}
            </div>}
          </Form>
          {current === 1 && <div className="lake-step-panel">
            <Alert type={preview?.valid ? 'success' : 'error'} showIcon message={preview?.valid ? '检查通过，创建后将进入全量任务配置' : '检查未通过'} description={preview?.errors?.join('；')} />
            <Descriptions bordered column={1} style={{ marginTop: 20 }}>
              <Descriptions.Item label="来源表">{selectedTable?.fullyQualifiedName}</Descriptions.Item>
              <Descriptions.Item label="目标表">{source.odsDatabase?.databaseName}.{preview?.targetTableName}</Descriptions.Item>
              <Descriptions.Item label="字段数">{preview?.targetContract?.columns?.length}</Descriptions.Item>
              <Descriptions.Item label="任务写入">全量覆盖：每次先清空目标数据再重导，失败后需重新执行。</Descriptions.Item>
              <Descriptions.Item label="保留方式">{form.getFieldValue('lifecyclePolicyId') ? policies.find((policy) => policy.id === form.getFieldValue('lifecyclePolicyId'))?.policyName : '长期保留'}</Descriptions.Item>
            </Descriptions>
            {preview?.warnings?.length ? <Alert style={{ marginTop: 16 }} type="warning" showIcon message="请确认转换提醒" description={<><ul>{preview.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul><Checkbox checked={warningsAcknowledged} onChange={(event) => setWarningsAcknowledged(event.target.checked)}>我已了解转换提醒</Checkbox></>} /> : null}
            <Collapse className="lake-ddl-collapse" items={[{ key: 'ddl', label: '查看建表 SQL', children: <pre>{preview?.ddl}</pre> }]} />
          </div>}
        </div>
        <div className="lake-wizard-footer">
          <Button onClick={() => current ? setCurrent(0) : history.push('/lake/resources')}>{current ? '返回修改' : '取消'}</Button>
          {current === 0 ? <Button type="primary" loading={previewLoading} disabled={!tableDetail || tableLoading} onClick={runPreview}>检查并继续</Button>
            : <Button type="primary" loading={createLoading} disabled={!preview?.valid || !preview?.planFingerprint || (!!preview?.warnings?.length && !warningsAcknowledged)} onClick={create}>创建并配置全量任务</Button>}
        </div>
      </Card>
    </div>
  </PageContainer>;
};

export default ManagedTableWizard;
