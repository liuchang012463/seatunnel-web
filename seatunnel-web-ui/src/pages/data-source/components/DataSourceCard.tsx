import { Dropdown } from 'antd';
import React from 'react';
import StatusChip from '@/components/StatusChip';
import DatabaseIcons from '../icon/DatabaseIcons';
import type { DataSourceLifecycleStatus, DataSourceRecord } from '../types';
import DataSourceLifecycleStatusTag from './DataSourceLifecycleStatus';
import DataSourceStatus from './DataSourceStatus';
import { profileStatusConfig } from './profileStatus';

interface DataSourceCardProps {
  record: DataSourceRecord;
  onEdit: (record: DataSourceRecord) => void;
  onDelete: (record: DataSourceRecord) => void;
  onTestConnection: (record: DataSourceRecord) => void;
  onViewExploration: (record: DataSourceRecord) => void;
  onStatusChange: (record: DataSourceRecord, status: DataSourceLifecycleStatus) => void;
  onOpenWarehouse: () => void;
}

const DataSourceCard: React.FC<DataSourceCardProps> = ({
  record,
  onEdit,
  onDelete,
  onTestConnection,
  onViewExploration,
  onStatusChange,
  onOpenWarehouse,
}) => {
  const currentStatus = record.status || 'ENABLED';
  const isRevoked = currentStatus === 'REVOKED';
  const isDeleting = isRevoked || record.metadataSyncStatus === 'DELETING';
  const nextStatus = currentStatus === 'DISABLED' ? 'ENABLED' : 'DISABLED';
  const statusActionLabel = currentStatus === 'DISABLED' ? '启用' : '停用';
  const isSystemManaged = Boolean(record.systemManaged);
  const profileStatus = profileStatusConfig(record.profileStatus);

  const handleDetail = () => {
    if (isSystemManaged) {
      onOpenWarehouse();
      return;
    }
    onEdit(record);
  };

  return (
    <div className="datasource-card">
      <div className="datasource-card-logo" aria-hidden>
        <DatabaseIcons dbType={record.dbType} width="28" height="28" />
      </div>

      <div className="datasource-card-body">
        <div className="datasource-card-header">
          <div className="datasource-card-title" title={record.name}>
            {record.name || '-'}
          </div>
          {isSystemManaged ? (
            <span
              style={{
                flexShrink: 0,
                fontSize: 12,
                lineHeight: '20px',
                color: 'var(--st-color-text-muted)',
              }}
            >
              系统内置 · 只读
            </span>
          ) : null}
          <button
            type="button"
            className="datasource-card-detail-link"
            disabled={isDeleting && !isSystemManaged}
            onClick={handleDetail}
          >
            {isSystemManaged ? '数据湖管理 >' : '查看详情 >'}
          </button>
        </div>

        <div className="datasource-card-jdbc-url" title={record.jdbcUrl}>
          {record.jdbcUrl || '-'}
        </div>

        <div className="datasource-card-status">
          <DataSourceStatus status={record.connStatus} />
          <DataSourceLifecycleStatusTag status={record.status} />
          <StatusChip tone={profileStatus.tone} label={profileStatus.text} detail={profileStatus.detail} />
        </div>

        <div className="datasource-card-actions">
          <button
            type="button"
            className="datasource-card-action datasource-card-action--test"
            disabled={isDeleting}
            onClick={() => onTestConnection(record)}
          >
            测试连接
          </button>
          <button
            type="button"
            className="datasource-card-action datasource-card-action--primary"
            disabled={isDeleting}
            onClick={() => onViewExploration(record)}
          >
            探查结果
          </button>

          {isSystemManaged ? (
            <button
              type="button"
              className="datasource-card-action datasource-card-action--neutral"
              onClick={onOpenWarehouse}
            >
              数据湖管理
            </button>
          ) : (
            <Dropdown
              trigger={['click']}
              menu={{
                items: [
                  {
                    key: 'lifecycle',
                    label: statusActionLabel,
                    disabled: isDeleting,
                    onClick: () => onStatusChange(record, nextStatus),
                  },
                  {
                    key: 'revoke',
                    label: '注销',
                    disabled: isDeleting || isRevoked,
                    onClick: () => onStatusChange(record, 'REVOKED'),
                  },
                  { type: 'divider' },
                  {
                    key: 'delete',
                    label: '删除',
                    danger: true,
                    disabled: isDeleting,
                    onClick: () => onDelete(record),
                  },
                ],
              }}
            >
              <button
                type="button"
                className="datasource-card-action datasource-card-action--neutral"
                disabled={isDeleting}
              >
                更多
              </button>
            </Dropdown>
          )}
        </div>
      </div>
    </div>
  );
};

export default DataSourceCard;
