import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
  QuestionCircleOutlined,
} from '@ant-design/icons';
import { Alert, Space, Tag, Timeline, Typography } from 'antd';
import React from 'react';
import type { LakeConsistencyStatus, LakeOperationStatus, LakeResourceOperation, LakeResourceStatus } from '@/services/lake';
import './index.less';

const RESOURCE_STATUS: Record<string, { label: string; color: string; icon: React.ReactNode }> = {
  PENDING_CREATE: { label: '待创建', color: 'processing', icon: <ClockCircleOutlined /> },
  CREATING: { label: '创建中', color: 'processing', icon: <ClockCircleOutlined /> },
  READY: { label: '已就绪', color: 'success', icon: <CheckCircleOutlined /> },
  CREATE_FAILED: { label: '创建失败', color: 'error', icon: <ExclamationCircleOutlined /> },
  ERROR: { label: '异常', color: 'error', icon: <ExclamationCircleOutlined /> },
  MISSING: { label: '远端缺失', color: 'error', icon: <ExclamationCircleOutlined /> },
  UNKNOWN: { label: '状态未知', color: 'warning', icon: <QuestionCircleOutlined /> },
  DELETING: { label: '删除中', color: 'processing', icon: <ClockCircleOutlined /> },
  DELETED: { label: '已删除', color: 'default', icon: <InfoCircleOutlined /> },
};

const CONSISTENCY_STATUS: Record<string, { label: string; color: string }> = {
  CONSISTENT: { label: '一致', color: 'success' },
  DRIFT: { label: '存在漂移', color: 'warning' },
  MISSING: { label: '远端缺失', color: 'error' },
  UNKNOWN: { label: '无法判断', color: 'default' },
  UNBOUND: { label: '未关联', color: 'default' },
};

const REASON_LABELS: Record<string, string> = {
  ADAPTER_MISSING: '此数据源类型暂不支持逻辑挂载。',
  ADAPTER_DISABLED: '对应适配器尚未启用，请在数据湖配置中完成驱动验证。',
  DRIVER_CONFIG_MISSING: '尚未配置对应 JDBC 驱动，请在数据湖配置中注册。',
  DRIVER_CHECKSUM_MISSING: '驱动校验值缺失，请重新校验驱动文件。',
  DRIVER_CHECKSUM_INVALID: '驱动校验值不正确，请重新校验驱动文件。',
  DRIVER_REGISTRY_REVISION_MISSING: '驱动登记版本缺失，请重新登记驱动。',
  DRIVER_UNAVAILABLE: '驱动文件不可用，请检查湖端驱动配置。',
  DRIVER_NOT_VERIFIED: '驱动尚未验证，请先完成驱动验证。',
  SOURCE_CONFIG_INCOMPLETE: '数据源连接信息不完整，请先完善数据源。',
  SOURCE_NOT_FOUND: '数据源不存在，请重新选择。',
  SOURCE_DISABLED: '数据源已停用，请先在数据源管理中启用。',
  SOURCE_TYPE_MISMATCH: '数据源类型与挂载适配器不匹配，请重新选择数据源。',
  LAKE_CONTROL_PLANE_DISABLED: '请先配置并测试数据湖连接。',
  SCOPE_UNSUPPORTED: '当前适配器不支持此挂载范围。',
  UPDATE_UNSUPPORTED: '当前适配器不支持更新挂载范围。',
  SOURCE_NETWORK_UNKNOWN: '源端网络尚未探查，创建时会再次验证。',
  SOURCE_NETWORK_UNREACHABLE: '当前无法连接源端，请检查网络或数据源连接状态。',
  LAKE_DORIS_UNREACHABLE: '湖 Doris 当前不可达，请稍后重试。',
  DRIVER_MISSING: '缺少对应 JDBC Driver，请先完成服务端 Driver 配置。',
  DORIS_DRIVER_MD5_INVALID: 'Doris Driver 的 MD5 格式不正确，请配置 32 位十六进制值。',
  CAPABILITY_UNKNOWN: '能力信息暂不可用，请先完成元数据探查。',
  LOGICAL_CAPABILITY_UNKNOWN: '尚需检查源端连接，继续挂载时验证。',
};

export const capabilityReasonLabel = (reason: string) => REASON_LABELS[reason] || reason;

export const resourceStatusLabel = (status?: string) =>
  (status && RESOURCE_STATUS[status]?.label) || status || '未绑定';

export const LakeResourceStatusTag: React.FC<{ status?: LakeResourceStatus | string; showIcon?: boolean }> = ({
  status,
  showIcon = true,
}) => {
  const config = status ? RESOURCE_STATUS[status] : undefined;
  return (
    <Tag color={config?.color} icon={showIcon ? config?.icon : undefined} className="lake-status-tag">
      {config?.label || status || '未绑定'}
    </Tag>
  );
};

export const LakeConsistencyTag: React.FC<{ status?: LakeConsistencyStatus | string }> = ({ status }) => {
  const config = status ? CONSISTENCY_STATUS[status] : undefined;
  return <Tag color={config?.color}>{config?.label || status || '未设置'}</Tag>;
};

export const CapabilityReason: React.FC<{ reasons?: string[]; fallback?: string }> = ({ reasons, fallback }) => {
  const values = reasons?.filter(Boolean) || [];
  if (!values.length) return <Typography.Text type="secondary">{fallback || '暂无能力限制'}</Typography.Text>;
  return (
    <Space direction="vertical" size={2} className="lake-reason-list">
      {values.map((reason) => (
        <Typography.Text type="secondary" key={reason}>
          <InfoCircleOutlined /> {REASON_LABELS[reason] || reason}
        </Typography.Text>
      ))}
    </Space>
  );
};

export const LakeErrorAlert: React.FC<{
  code?: string;
  message?: string;
  action?: React.ReactNode;
  title?: string;
}> = ({ code, message, action, title = '需要处理的问题' }) => {
  if (!code && !message) return null;
  return (
    <Alert
      type="error"
      showIcon
      icon={<ExclamationCircleOutlined />}
      message={title}
      description={
        <Space direction="vertical" size={4}>
          <Typography.Text>{[code, message].filter(Boolean).join('：')}</Typography.Text>
          {action}
        </Space>
      }
      className="lake-error-alert"
    />
  );
};

export interface LakeOperationStep {
  title: string;
  description?: string;
  status?: 'wait' | 'process' | 'finish' | 'error';
}

/**
 * Relation snapshots are deliberately parsed only for a small, display-safe
 * set of fields.  They are server-generated JSON and must never be rendered
 * wholesale because that would make an internal connector snapshot look like
 * a user-facing configuration (and could expose future sensitive fields).
 */
export const snapshotField = (snapshot: unknown, keys: string[]): string | undefined => {
  if (typeof snapshot !== 'string' || !snapshot.trim()) return undefined;
  try {
    const parsed = JSON.parse(snapshot) as Record<string, unknown>;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return undefined;
    for (const key of keys) {
      const value = parsed[key];
      if (typeof value === 'string' && value.trim()) return value.trim();
      if (typeof value === 'number' || typeof value === 'boolean') return String(value);
      if (Array.isArray(value) && value.length) return value.map((item) => String(item)).join(', ');
    }
  } catch {
    return undefined;
  }
  return undefined;
};

export const relationTargetTable = (relation: { sinkEndpointSnapshot?: string }) =>
  snapshotField(relation.sinkEndpointSnapshot, ['targetTableName', 'target_table_name', 'tableName', 'table']);

export const relationSchemaMode = (relation: { schemaSaveModeSnapshot?: string; sinkEndpointSnapshot?: string }) =>
  relation.schemaSaveModeSnapshot || snapshotField(relation.sinkEndpointSnapshot, ['schemaSaveMode', 'schema_save_mode']);

export const relationSourceDataSource = (relation: { sourceEndpointSnapshot?: string }) =>
  snapshotField(relation.sourceEndpointSnapshot, ['dataSourceId', 'sourceDataSourceId']);

const OPERATION_LABELS: Record<string, string> = {
  CREATE_DATABASE: '创建 ODS Database',
  CREATE_TABLE: '创建 ODS 表',
  DROP_DATABASE: '删除 ODS Database',
  DROP_TABLE: '删除 ODS 表',
  CREATE_CATALOG: '创建逻辑 Catalog',
  UPDATE_CATALOG: '更新逻辑 Catalog',
  VALIDATE_CATALOG: '验证逻辑 Catalog',
  VALIDATE: '验证资源',
  REFRESH_CATALOG: '刷新逻辑 Catalog',
  DROP_CATALOG: '删除逻辑 Catalog',
  RECONCILE: '资源对账',
  DELETE: '删除湖侧资源',
  READONLY_QUERY: '结构化只读查询',
  ALTER_RETENTION: '变更生命周期',
  RETRY: '重试资源操作',
};

const OPERATION_STATUS: Record<string, LakeOperationStep['status']> = {
  PENDING: 'wait',
  RUNNING: 'process',
  SUCCEEDED: 'finish',
  FAILED: 'error',
  IGNORED: 'wait',
};

const operationTime = (value?: string) => {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value.replace('T', ' ').replace(/\.\d+Z$/, '') : date.toLocaleString();
};

/** Maps the durable journal projection to the same compact timeline shape used by all pages. */
export const operationToStep = (operation: LakeResourceOperation): LakeOperationStep => {
  const status = (operation.status || 'UNKNOWN').toUpperCase() as LakeOperationStatus | 'UNKNOWN';
  const title = OPERATION_LABELS[operation.operationType || ''] || operation.operationType || '资源操作';
  const when = operation.finishedAt || operation.startedAt;
  const details = [
    status === 'FAILED' && operation.errorCode ? operation.errorCode : undefined,
    status === 'FAILED' && operation.errorSummary ? operation.errorSummary : undefined,
    operation.generation ? `Generation ${operation.generation}` : undefined,
    operation.operatorId ? `操作者 #${operation.operatorId}` : undefined,
    operationTime(when),
  ].filter(Boolean).join(' · ');
  return { title, description: details || status, status: OPERATION_STATUS[status] || 'wait' };
};

export const OperationTimeline: React.FC<{ items: LakeOperationStep[]; emptyText?: string }> = ({ items, emptyText }) => {
  if (!items.length) return <Typography.Text type="secondary">{emptyText || '暂无操作记录'}</Typography.Text>;
  return (
    <Timeline
      items={items.map((item) => ({
        color: item.status === 'error' ? 'red' : item.status === 'finish' ? 'green' : undefined,
        children: (
          <div className="lake-operation-item">
            <Typography.Text strong>{item.title}</Typography.Text>
            {item.description ? <Typography.Paragraph type="secondary">{item.description}</Typography.Paragraph> : null}
          </div>
        ),
      }))}
    />
  );
};
