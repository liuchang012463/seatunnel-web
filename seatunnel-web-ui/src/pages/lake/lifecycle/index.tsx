import {
  CheckCircleOutlined,
  EditOutlined,
  ExclamationCircleOutlined,
  EyeOutlined,
  PauseCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { useLocation } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Divider,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  Select,
  Space,
  Spin,
  Steps,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { TableColumnsType } from 'antd';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  applyLifecycle,
  createLifecyclePolicy,
  disableLifecyclePolicy,
  fetchLifecycleDetail,
  fetchLifecyclePolicies,
  fetchPhysicalInventory,
  fetchPhysicalSources,
  fetchRetentionPreview,
  updateLifecyclePolicy,
  updateRetention,
  validateLifecycle,
} from './service';
import type {
  LifecycleMappingSnapshot,
  LifecyclePolicyFormValues,
  LifecycleTableCandidate,
  LifecycleValidationView,
  PartitionSummary,
  RetentionPreviewView,
} from './types';
import type { LakeLifecyclePolicy, LakePolicyStatus, LakeResourceStatus } from '@/services/lake';
import { normalizeLakePage } from '@/services/lake';
import './index.less';

const { Paragraph, Text, Title } = Typography;

const statusLabels: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '启用',
  DISABLED: '已停用',
  PENDING: '处理中',
  ERROR: '异常',
  READY: '已就绪',
  MISSING: '缺失',
  UNKNOWN: '未知',
};

const statusColors: Record<string, string> = {
  DRAFT: 'warning',
  ACTIVE: 'success',
  DISABLED: 'default',
  PENDING: 'processing',
  ERROR: 'error',
  READY: 'success',
  MISSING: 'error',
  UNKNOWN: 'warning',
};

const granularityLabels: Record<string, string> = { DAY: '天', MONTH: '月', YEAR: '年' };
const reasonLabels: Record<string, string> = {
  LAKE_LIFECYCLE_VALID: '校验通过',
  LAKE_LIFECYCLE_MAPPING_NOT_MANAGED: '仅 MANAGED 表允许绑定生命周期',
  LAKE_LIFECYCLE_MAPPING_NOT_READY: '目标表尚未就绪',
  LAKE_LIFECYCLE_POLICY_NOT_ACTIVE: '策略未启用',
  LAKE_LIFECYCLE_POLICY_DISABLED: '策略已停用',
  LAKE_LIFECYCLE_GRANULARITY_MISMATCH: '策略粒度与表分区粒度不一致',
  LAKE_LIFECYCLE_SOURCE_COLUMN_NULLABLE: '分区字段源端允许为空',
  LAKE_TARGET_TABLE_MISSING: 'Doris 目标表不存在',
  LAKE_LIFECYCLE_STRUCTURAL_DRIFT: '目标表结构已漂移',
  LAKE_LIFECYCLE_STRUCTURAL_UNKNOWN: '目标结构暂不可读取',
  LAKE_DORIS_UNAVAILABLE: 'Doris 当前不可用',
  LAKE_LIFECYCLE_PARTITION_OBSERVATION_UNKNOWN: '分区观察结果未知',
  LAKE_LIFECYCLE_RETENTION_IMPACT_UNKNOWN: '历史分区影响范围未知',
  LAKE_LIFECYCLE_NOT_BOUND: '表尚未绑定生命周期策略',
  LAKE_LIFECYCLE_CACHE_UNAVAILABLE: '缓存状态暂不可用',
};

const statusTag = (value?: string) => (
  <Tag color={value ? statusColors[value] : undefined}>{value ? statusLabels[value] || value : '-'}</Tag>
);

const formatTime = (value?: string) => (value ? value.replace('T', ' ').replace(/\.\d+Z$/, '') : '-');
const retentionText = (count?: number, granularity?: string) =>
  count == null ? '-' : `${count} ${granularityLabels[granularity || ''] || '个分区'}`;
const explainReason = (reason?: string) => (reason ? reasonLabels[reason] || reason : '未返回原因');

const responseError = (response: { msg?: string; message?: string }, fallback: string) =>
  response.message || response.msg || fallback;

const PolicyEditorDrawer: React.FC<{
  open: boolean;
  policy?: LakeLifecyclePolicy;
  onClose: () => void;
  onSuccess: () => void;
}> = ({ open, policy, onClose, onSuccess }) => {
  const [form] = Form.useForm<LifecyclePolicyFormValues>();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!open) return;
    form.setFieldsValue({
      policyName: policy?.policyName || '',
      granularity: policy?.granularity || 'DAY',
      retentionCount: policy?.retentionCount || 1,
      description: policy?.description || '',
      status: policy?.status === 'ACTIVE' ? 'ACTIVE' : 'DRAFT',
    });
  }, [form, open, policy]);

  const submit = async (values: LifecyclePolicyFormValues) => {
    setLoading(true);
    try {
      const payload = {
        policyName: values.policyName.trim(),
        granularity: values.granularity,
        retentionCount: values.retentionCount,
        description: values.description?.trim() || undefined,
        status: values.status,
      };
      const response = policy?.id
        ? await updateLifecyclePolicy(policy.id, { ...payload, expectedVersion: policy.version })
        : await createLifecyclePolicy(payload);
      if (response.code !== 0 || !response.data) throw new Error(responseError(response, '生命周期策略保存失败'));
      message.success(policy?.id ? '生命周期策略已更新' : '生命周期策略已创建');
      onSuccess();
      onClose();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '生命周期策略保存失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Drawer
      open={open}
      width={520}
      title={policy ? '编辑生命周期策略' : '新建生命周期策略'}
      destroyOnClose
      onClose={onClose}
    >
      <Alert
        type="info"
        showIcon
        message="模板变更不会批量改变已应用表"
        description="每张表保存自己的策略快照；更新模板后，已应用表需要在表详情中单独调整。"
        className="lake-lifecycle-alert"
      />
      <Form form={form} layout="vertical" onFinish={submit} className="lake-policy-form">
        <Form.Item
          name="policyName"
          label="策略名称"
          rules={[{ required: true, message: '请输入策略名称' }, { max: 128, message: '策略名称不能超过 128 个字符' }]}
        >
          <Input placeholder="例如 ods_orders_rolling" maxLength={128} showCount />
        </Form.Item>
        <Form.Item name="granularity" label="分区粒度" rules={[{ required: true, message: '请选择分区粒度' }]}>
          <Select options={[{ label: '按天', value: 'DAY' }, { label: '按月', value: 'MONTH' }, { label: '按年', value: 'YEAR' }]} />
        </Form.Item>
        <Form.Item
          name="retentionCount"
          label="历史分区保留数"
          extra="这里配置的是历史分区数量，不是精确的自然日 TTL。"
          rules={[{ required: true, message: '请输入保留数' }, { type: 'number', min: 1, max: 100000, message: '请输入 1 至 100000 的整数' }]}
        >
          <InputNumber min={1} max={100000} precision={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="status" label="状态">
          <Select disabled={policy?.status === 'ACTIVE'} options={[{ label: '草稿', value: 'DRAFT' }, { label: '启用', value: 'ACTIVE' }]} />
        </Form.Item>
        <Form.Item name="description" label="说明">
          <Input.TextArea maxLength={1024} showCount rows={4} placeholder="说明适用范围和分区约束" />
        </Form.Item>
        {policy?.version ? <Text type="secondary">当前版本：v{policy.version}。保存时使用版本校验，冲突后请刷新重试。</Text> : null}
        <div className="lake-drawer-footer">
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" htmlType="submit" loading={loading}>
            保存策略
          </Button>
        </div>
      </Form>
    </Drawer>
  );
};

const ImpactConfirmModal: React.FC<{
  open: boolean;
  preview?: RetentionPreviewView;
  loading?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}> = ({ open, preview, loading, onCancel, onConfirm }) => {
  const impacted = preview?.impactedHistoricalPartitionNames || [];
  return (
    <Modal
      open={open}
      title="确认减少历史分区保留数"
      onCancel={onCancel}
      onOk={onConfirm}
      okText="确认并应用"
      cancelText="取消"
      okButtonProps={{ danger: true, disabled: !preview?.confirmationToken }}
      confirmLoading={loading}
      width={620}
    >
      <Alert
        type="warning"
        showIcon
        message="该操作可能使历史分区超出新的保留范围"
        description="请确认影响清单。服务端会校验一次性确认令牌和最新分区观察结果，页面不展示令牌内容。"
      />
      <Descriptions size="small" column={2} className="lake-impact-summary">
        <Descriptions.Item label="当前历史分区数">{preview?.historicalPartitionCount ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="预计影响分区数">{preview?.impactedHistoricalPartitionCount ?? impacted.length}</Descriptions.Item>
        <Descriptions.Item label="当前保留数">{preview?.currentDesiredRetentionCount ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="目标保留数">{preview?.requestedRetentionCount ?? '-'}</Descriptions.Item>
      </Descriptions>
      <Divider orientation="left">预计将超出保留范围的历史分区</Divider>
      {impacted.length ? (
        <div className="lake-impact-list">
          {impacted.map((name) => <Tag key={name} color="warning">{name}</Tag>)}
        </div>
      ) : (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="服务端未返回可确认的影响分区" />
      )}
      {!preview?.confirmationToken ? <Alert type="error" showIcon className="lake-lifecycle-alert" message="当前影响观察不可确认，请重新预览" /> : null}
    </Modal>
  );
};

const ApplyPolicyDrawer: React.FC<{
  open: boolean;
  policy?: LakeLifecyclePolicy;
  onClose: () => void;
  onSuccess: () => void;
  onOpenTable: (mappingId: number) => void;
}> = ({ open, policy, onClose, onSuccess, onOpenTable }) => {
  const [candidates, setCandidates] = useState<LifecycleTableCandidate[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedId, setSelectedId] = useState<number>();
  const [applying, setApplying] = useState(false);
  const [validation, setValidation] = useState<LifecycleValidationView>();

  const loadCandidates = useCallback(async () => {
    if (!policy?.id) return;
    setLoading(true);
    setCandidates([]);
    setSelectedId(undefined);
    setValidation(undefined);
    try {
      const sourceResponse = await fetchPhysicalSources({ pageNo: 1, pageSize: 200 });
      if (sourceResponse.code !== 0) throw new Error(responseError(sourceResponse, '物理资源列表读取失败'));
      const sources = normalizeLakePage(sourceResponse.data).data;
      const inventoryResults = await Promise.allSettled(
        sources
          .filter((source) => source.odsDatabaseBindingId || source.odsDatabase?.id)
          .map(async (source) => {
            const bindingId = source.odsDatabaseBindingId || source.odsDatabase?.id;
            if (!bindingId) return [] as LifecycleTableCandidate[];
            const inventoryResponse = await fetchPhysicalInventory(bindingId);
            if (inventoryResponse.code !== 0) throw new Error(responseError(inventoryResponse, '物理库存读取失败'));
            const rows = inventoryResponse.data?.registeredTables || [];
            return rows
              .filter((row) => !!row.mappingId)
              .map((row) => ({
                mappingId: row.mappingId as number,
                targetTableName: row.targetTableName || '-',
                sourceDataSourceName: source.sourceDataSourceName || `DataSource #${source.sourceDataSourceId}`,
                databaseName: inventoryResponse.data?.databaseName || source.odsDatabase?.databaseName || '-',
                managementLevel: row.managementLevel,
                resourceStatus: row.resourceStatus,
                eligible: false,
              }));
          }),
      );
      const rows = inventoryResults.flatMap((result) => result.status === 'fulfilled' ? result.value : []);
      const checked = await Promise.all(rows.map(async (candidate) => {
        if (candidate.managementLevel !== 'MANAGED' || candidate.resourceStatus !== 'READY') {
          return { ...candidate, reason: candidate.managementLevel !== 'MANAGED' ? '仅 MANAGED 表可应用' : '目标表资源未就绪' };
        }
        try {
          const response = await validateLifecycle({ mappingId: candidate.mappingId, policyId: policy.id as number });
          const data = response.data as LifecycleValidationView;
          const partition = data.mappingSnapshot?.targetContract?.partition;
          const autoRange = Boolean(partition?.enabled || data.partitionColumn);
          const valid = response.code === 0 && Boolean(data.valid) && autoRange;
          return {
            ...candidate,
            eligible: valid,
            partitionColumn: data.partitionColumn || partition?.column,
            granularity: data.granularity || partition?.granularity,
            reason: valid ? undefined : (data.reasons?.map(explainReason).join('、') || (autoRange ? explainReason(data.code) : '表未配置自动时间分区')),
          };
        } catch (error) {
          return { ...candidate, reason: error instanceof Error ? error.message : '校验不可用' };
        }
      }));
      setCandidates(checked);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '可应用表列表读取失败');
    } finally {
      setLoading(false);
    }
  }, [policy]);

  useEffect(() => {
    if (open) void loadCandidates();
  }, [loadCandidates, open]);

  const selected = candidates.find((candidate) => candidate.mappingId === selectedId);

  const validateAndApply = async () => {
    if (!selected || !policy?.id) return;
    setApplying(true);
    try {
      const validateResponse = await validateLifecycle({ mappingId: selected.mappingId, policyId: policy.id });
      const validated = validateResponse.data as LifecycleValidationView;
      setValidation(validated);
      if (validateResponse.code !== 0 || !validated.valid) {
        message.warning('当前表未通过生命周期校验，请查看原因');
        return;
      }
      const applyResponse = await applyLifecycle({ mappingId: selected.mappingId, policyId: policy.id });
      if (applyResponse.code !== 0 || !applyResponse.data) throw new Error(responseError(applyResponse, '生命周期应用失败'));
      message.success('生命周期策略已应用');
      onSuccess();
      onClose();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '生命周期应用失败');
    } finally {
      setApplying(false);
    }
  };

  const eligible = candidates.filter((item) => item.eligible);
  const blocked = candidates.filter((item) => !item.eligible);
  const tableColumns: TableColumnsType<LifecycleTableCandidate> = [
    { title: '目标表', dataIndex: 'targetTableName', key: 'targetTableName', ellipsis: true },
    { title: '数据源', dataIndex: 'sourceDataSourceName', key: 'sourceDataSourceName', ellipsis: true },
    { title: '数据库', dataIndex: 'databaseName', key: 'databaseName', ellipsis: true },
    { title: '分区', key: 'partition', render: (_, row) => row.partitionColumn ? `${row.partitionColumn} / ${granularityLabels[row.granularity || ''] || row.granularity}` : '-' },
    { title: '操作', key: 'option', width: 110, render: (_, row) => <Button type="link" icon={<EyeOutlined />} onClick={() => onOpenTable(row.mappingId)}>查看详情</Button> },
  ];

  return (
    <Drawer
      open={open}
      width={860}
      title={`应用策略${policy?.policyName ? `：${policy.policyName}` : ''}`}
      onClose={onClose}
      destroyOnClose
      extra={policy ? <Tag color="blue">v{policy.version}</Tag> : null}
    >
      <Card size="small" className="lake-policy-snapshot" title="Policy Snapshot">
        <Descriptions size="small" column={3}>
          <Descriptions.Item label="策略">{policy?.policyName || '-'}</Descriptions.Item>
          <Descriptions.Item label="粒度">{granularityLabels[policy?.granularity || ''] || policy?.granularity || '-'}</Descriptions.Item>
          <Descriptions.Item label="历史分区保留数">{retentionText(policy?.retentionCount, policy?.granularity)}</Descriptions.Item>
        </Descriptions>
      </Card>
      <Alert
        type="info"
        showIcon
        className="lake-lifecycle-alert"
        message="只列出 MANAGED、READY 且已配置自动时间分区的表"
        description="页面会对候选表执行显式 validate；未通过的表不会提交 apply。"
      />
      {validation ? (
        <Alert
          type={validation.valid ? 'success' : 'warning'}
          showIcon
          className="lake-lifecycle-alert"
          message={validation.valid ? '校验通过，正在应用' : `校验未通过：${explainReason(validation.code || validation.reasonCode)}`}
          description={validation.reasons?.map(explainReason).join('、')}
        />
      ) : null}
      <Spin spinning={loading}>
        <Title level={5}>可应用表（{eligible.length}）</Title>
        <Table<LifecycleTableCandidate>
          rowKey="mappingId"
          size="small"
          columns={tableColumns}
          dataSource={eligible}
          rowSelection={{ type: 'radio', selectedRowKeys: selectedId ? [selectedId] : [], onChange: (keys) => setSelectedId(Number(keys[0])) }}
          pagination={{ pageSize: 8 }}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无符合条件的表" /> }}
          scroll={{ x: 680 }}
        />
        {blocked.length ? (
          <>
            <Divider orientation="left">不可应用项（{blocked.length}）</Divider>
            <List
              size="small"
              dataSource={blocked}
              renderItem={(item) => (
                <List.Item
                  actions={[<Button key="reason" type="link" onClick={() => onOpenTable(item.mappingId)}>查看原因</Button>]}
                >
                  <List.Item.Meta title={item.targetTableName} description={`${item.sourceDataSourceName} / ${item.databaseName}`} />
                  <Text type="secondary">{item.reason || '不满足应用条件'}</Text>
                </List.Item>
              )}
            />
          </>
        ) : null}
      </Spin>
      <div className="lake-drawer-footer">
        <Button icon={<ReloadOutlined />} onClick={() => void loadCandidates()} loading={loading}>重新检查</Button>
        <Button type="primary" disabled={!selected?.eligible} loading={applying} onClick={() => void validateAndApply()}>
          校验并应用
        </Button>
      </div>
    </Drawer>
  );
};

const LifecycleTableDetail: React.FC<{
  open: boolean;
  mappingId?: number;
  policies: LakeLifecyclePolicy[];
  onClose: () => void;
}> = ({ open, mappingId, policies, onClose }) => {
  const [detail, setDetail] = useState<LifecycleValidationView>();
  const [loading, setLoading] = useState(false);
  const [selectedPolicyId, setSelectedPolicyId] = useState<number>();
  const [preview, setPreview] = useState<RetentionPreviewView>();
  const [previewLoading, setPreviewLoading] = useState(false);
  const [impactOpen, setImpactOpen] = useState(false);
  const [updateLoading, setUpdateLoading] = useState(false);

  const loadDetail = useCallback(async () => {
    if (!mappingId) return;
    setLoading(true);
    try {
      const response = await fetchLifecycleDetail(mappingId);
      if (response.code !== 0 || !response.data) throw new Error(responseError(response, '表生命周期详情读取失败'));
      const data = response.data as LifecycleValidationView;
      setDetail(data);
      setSelectedPolicyId(data.policyId || data.existingBinding?.policyId);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '表生命周期详情读取失败');
    } finally {
      setLoading(false);
    }
  }, [mappingId]);

  useEffect(() => {
    if (open) void loadDetail();
  }, [loadDetail, open]);

  const mapping = detail?.mappingSnapshot;
  const binding = detail?.existingBinding;
  const selectedPolicy = policies.find((policy) => policy.id === selectedPolicyId);

  const previewRetention = async () => {
    if (!mappingId || !selectedPolicyId) return;
    setPreviewLoading(true);
    setPreview(undefined);
    try {
      const response = await fetchRetentionPreview(mappingId, selectedPolicyId);
      if (response.code !== 0 || !response.data) throw new Error(responseError(response, '保留数预览失败'));
      setPreview(response.data);
      if (!response.data.valid) message.warning(response.data.reasons?.map(explainReason).join('、') || '当前策略不可应用');
      else if (response.data.requiresConfirmation) setImpactOpen(true);
      else void submitRetention(response.data);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '保留数预览失败');
    } finally {
      setPreviewLoading(false);
    }
  };

  const submitRetention = async (previewResult: RetentionPreviewView) => {
    if (!mappingId || !selectedPolicyId || !previewResult.valid) return;
    setUpdateLoading(true);
    try {
      const response = await updateRetention(mappingId, {
        policyId: selectedPolicyId,
        confirmationToken: previewResult.confirmationToken,
      });
      if (response.code !== 0 || !response.data) throw new Error(responseError(response, '生命周期更新失败'));
      message.success('表生命周期已更新');
      setImpactOpen(false);
      await loadDetail();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '生命周期更新失败');
    } finally {
      setUpdateLoading(false);
    }
  };

  const explicitValidate = async () => {
    if (!mappingId || !selectedPolicyId) return;
    setLoading(true);
    try {
      const response = await validateLifecycle({ mappingId, policyId: selectedPolicyId });
      if (response.code !== 0 || !response.data) throw new Error(responseError(response, '生命周期校验失败'));
      setDetail(response.data as LifecycleValidationView);
      message.success(response.data.valid ? '校验通过，已刷新远端观察状态' : '校验完成，请查看异常原因');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '生命周期校验失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Drawer
      open={open}
      width={760}
      title="表生命周期详情"
      onClose={onClose}
      destroyOnClose
      extra={<Button icon={<ReloadOutlined />} onClick={() => void explicitValidate()} disabled={!selectedPolicyId}>重新校验</Button>}
    >
      <Spin spinning={loading}>
        <Card size="small" className="lake-detail-header">
          <Descriptions size="small" column={2}>
            <Descriptions.Item label="目标表">{mapping?.targetTableName || '-'}</Descriptions.Item>
            <Descriptions.Item label="数据库">{mapping?.databaseName || '-'}</Descriptions.Item>
            <Descriptions.Item label="资源状态">{statusTag(mapping?.resourceStatus)}</Descriptions.Item>
            <Descriptions.Item label="生命周期状态">{statusTag(binding?.status)}</Descriptions.Item>
            <Descriptions.Item label="分区字段">{detail?.partitionColumn || binding?.partitionColumn || '-'}</Descriptions.Item>
            <Descriptions.Item label="分区粒度">{granularityLabels[detail?.granularity || binding?.granularity || ''] || detail?.granularity || binding?.granularity || '-'}</Descriptions.Item>
            <Descriptions.Item label="期望历史分区数">{detail?.desiredRetentionCount ?? binding?.retentionCount ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="实际历史分区数">{detail?.actualRetentionCount ?? binding?.actualRetentionCount ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="最后观察">{formatTime(detail?.observedAt || binding?.lastObservedAt)}</Descriptions.Item>
            <Descriptions.Item label="结构一致性">{detail?.structuralMatch == null ? statusTag('UNKNOWN') : statusTag(detail.structuralMatch ? 'READY' : 'ERROR')}</Descriptions.Item>
          </Descriptions>
        </Card>
        {detail && !detail.valid ? (
          <Alert
            type="warning"
            showIcon
            className="lake-lifecycle-alert"
            message={`生命周期状态：${explainReason(detail.code || detail.reasonCode)}`}
            description={detail.reasons?.map(explainReason).join('、') || '远端观察未通过'}
          />
        ) : null}
        <Card size="small" title="分区观察" className="lake-detail-card">
          <Descriptions size="small" column={4}>
            <Descriptions.Item label="总数">{detail?.partitionSummary?.total ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="历史">{detail?.partitionSummary?.historical ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="当前">{detail?.partitionSummary?.current ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="未来">{detail?.partitionSummary?.future ?? '-'}</Descriptions.Item>
          </Descriptions>
          {detail?.partitionSummary?.unknown ? <Alert type="warning" showIcon message={`有 ${detail.partitionSummary.unknown} 个分区无法归类`} /> : null}
        </Card>
        <Card size="small" title="调整生命周期" className="lake-detail-card">
          <Paragraph type="secondary">retention 代表历史分区保留数。减少保留数前会先读取影响分区并要求一次性确认。</Paragraph>
          <Space.Compact block>
            <Select
              value={selectedPolicyId}
              placeholder="选择启用中的策略"
              onChange={setSelectedPolicyId}
              options={policies.filter((policy) => policy.status === 'ACTIVE').map((policy) => ({
                value: policy.id,
                label: `${policy.policyName} · ${retentionText(policy.retentionCount, policy.granularity)}`,
              }))}
              style={{ flex: 1 }}
            />
            <Button type="primary" loading={previewLoading} disabled={!selectedPolicy} onClick={() => void previewRetention()}>
              预览并应用
            </Button>
          </Space.Compact>
          {preview && !preview.requiresConfirmation && preview.valid ? <Alert type="success" showIcon className="lake-lifecycle-alert" message={`预览通过：目标保留 ${retentionText(preview.requestedRetentionCount, selectedPolicy?.granularity)}`} /> : null}
        </Card>
      </Spin>
      <ImpactConfirmModal open={impactOpen} preview={preview} loading={updateLoading} onCancel={() => setImpactOpen(false)} onConfirm={() => { if (preview) void submitRetention(preview); }} />
    </Drawer>
  );
};

const LifecyclePage: React.FC = () => {
  const actionRef = useRef<ActionType>();
  const location = useLocation();
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingPolicy, setEditingPolicy] = useState<LakeLifecyclePolicy>();
  const [applyOpen, setApplyOpen] = useState(false);
  const [applyPolicy, setApplyPolicy] = useState<LakeLifecyclePolicy>();
  const [detailMappingId, setDetailMappingId] = useState<number>();
  const [policies, setPolicies] = useState<LakeLifecyclePolicy[]>([]);

  const reload = () => {
    actionRef.current?.reload();
    void loadPolicyOptions();
  };

  const loadPolicyOptions = useCallback(async () => {
    try {
      const response = await fetchLifecyclePolicies({ pageNo: 1, pageSize: 200 });
      if (response.code === 0) setPolicies(normalizeLakePage(response.data).data);
    } catch (_) {
      // The table request still reports its own error; the drawer can be retried independently.
    }
  }, []);

  useEffect(() => {
    void loadPolicyOptions();
    const queryMappingId = new URLSearchParams(location.search).get('mappingId');
    if (queryMappingId && Number(queryMappingId) > 0) setDetailMappingId(Number(queryMappingId));
  }, [loadPolicyOptions, location.search]);

  const columns = useMemo<ProColumns<LakeLifecyclePolicy>[]>(
    () => [
      { title: '策略', dataIndex: 'policyName', key: 'policyName', ellipsis: true, width: 220 },
      {
        title: '粒度',
        dataIndex: 'granularity',
        key: 'granularity',
        width: 110,
        valueType: 'select',
        valueEnum: { DAY: { text: '按天' }, MONTH: { text: '按月' }, YEAR: { text: '按年' } },
        render: (_, row) => granularityLabels[row.granularity || ''] || row.granularity || '-',
      },
      {
        title: '历史分区保留数',
        dataIndex: 'retentionCount',
        key: 'retentionCount',
        width: 160,
        render: (_, row) => row.retentionCount ?? '-',
      },
      {
        title: '约等时间',
        key: 'approximateTime',
        width: 130,
        render: (_, row) => row.retentionCount == null ? '-' : `${row.retentionCount}${granularityLabels[row.granularity || ''] || '个分区'}`,
      },
      { title: 'Scope', key: 'scope', width: 150, render: () => <Text type="secondary">可复用模板</Text> },
      { title: '应用表', key: 'appliedTables', width: 100, render: () => <Text type="secondary">-</Text>, tooltip: '当前策略接口不返回应用表计数' },
      {
        title: '状态',
        dataIndex: 'status',
        key: 'status',
        width: 100,
        valueType: 'select',
        valueEnum: { DRAFT: { text: '草稿' }, ACTIVE: { text: '启用' }, DISABLED: { text: '已停用' } },
        render: (_, row) => statusTag(row.status),
      },
      {
        title: '操作',
        key: 'option',
        valueType: 'option',
        fixed: 'right',
        width: 260,
        render: (_, row) => (
          <Space size={4} wrap>
            <Button type="link" icon={<EditOutlined />} disabled={row.status === 'DISABLED'} onClick={() => { setEditingPolicy(row); setEditorOpen(true); }}>编辑</Button>
            {row.status === 'ACTIVE' ? <Button type="link" icon={<SafetyCertificateOutlined />} onClick={() => { setApplyPolicy(row); setApplyOpen(true); }}>应用</Button> : null}
            {row.status !== 'DISABLED' ? <Button type="link" danger icon={<PauseCircleOutlined />} onClick={() => {
              Modal.confirm({
                title: '停用生命周期策略？',
                icon: <ExclamationCircleOutlined />,
                content: '停用模板不会批量修改已应用表，但后续不能再用该策略应用新表。',
                okText: '停用',
                cancelText: '取消',
                okButtonProps: { danger: true },
                onOk: async () => {
                  if (!row.id || !row.version) return;
                  const response = await disableLifecyclePolicy(row.id, { expectedVersion: row.version });
                  if (response.code !== 0) throw new Error(responseError(response, '策略停用失败'));
                  message.success('策略已停用');
                  reload();
                },
              });
            }}>停用</Button> : null}
          </Space>
        ),
      },
    ],
    [],
  );

  return (
    <PageContainer
      title="生命周期管理"
      subTitle="按历史分区数维护 ODS 表生命周期策略"
      extra={[<Button key="refresh" icon={<ReloadOutlined />} onClick={reload}>刷新</Button>]}
    >
      <div className="lake-lifecycle-page">
        <Card size="small" className="lake-page-intro">
          <div className="lake-page-intro-icon"><SafetyCertificateOutlined /></div>
          <div>
            <Title level={5}>策略模板</Title>
            <Paragraph type="secondary">策略是可复用模板，已应用表保留独立快照；所有远端校验都通过显式操作触发。</Paragraph>
          </div>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditingPolicy(undefined); setEditorOpen(true); }}>新建策略</Button>
        </Card>
        <ProTable<LakeLifecyclePolicy>
          actionRef={actionRef}
          rowKey="id"
          cardBordered
          columns={columns}
          scroll={{ x: 1180 }}
          search={{ labelWidth: 'auto' }}
          options={false}
          pagination={{ showSizeChanger: true, showTotal: (total) => `共 ${total} 个策略` }}
          request={async (params) => {
            try {
              const response = await fetchLifecyclePolicies({
                pageNo: params.current || 1,
                pageSize: params.pageSize || 10,
                policyName: typeof params.policyName === 'string' ? params.policyName : undefined,
                status: typeof params.status === 'string' ? params.status : undefined,
                granularity: typeof params.granularity === 'string' ? params.granularity : undefined,
              });
              if (response.code !== 0) throw new Error(responseError(response, '生命周期策略列表加载失败'));
              const page = normalizeLakePage(response.data);
              setPolicies(page.data);
              return { data: page.data, success: true, total: page.total };
            } catch (error) {
              message.error(error instanceof Error ? error.message : '生命周期策略列表加载失败');
              return { data: [], success: false, total: 0 };
            }
          }}
          toolBarRender={() => [
            <Button key="create" type="primary" icon={<PlusOutlined />} onClick={() => { setEditingPolicy(undefined); setEditorOpen(true); }}>新建策略</Button>,
          ]}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无生命周期策略，请先新建模板" /> }}
        />
      </div>
      <PolicyEditorDrawer open={editorOpen} policy={editingPolicy} onClose={() => setEditorOpen(false)} onSuccess={reload} />
      <ApplyPolicyDrawer open={applyOpen} policy={applyPolicy} onClose={() => setApplyOpen(false)} onSuccess={reload} onOpenTable={(mappingId) => { setApplyOpen(false); setDetailMappingId(mappingId); }} />
      <LifecycleTableDetail open={!!detailMappingId} mappingId={detailMappingId} policies={policies} onClose={() => setDetailMappingId(undefined)} />
    </PageContainer>
  );
};

export default LifecyclePage;
