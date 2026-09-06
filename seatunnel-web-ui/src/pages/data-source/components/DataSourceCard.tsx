import {
  ApiOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PauseCircleFilled,
  PlayCircleFilled,
  PoweroffOutlined,
} from '@ant-design/icons';
import { Button, Card, Tag, Tooltip } from 'antd';
import React from 'react';
import DatabaseIcons from '../icon/DatabaseIcons';
import type { DataSourceLifecycleStatus, DataSourceRecord } from '../types';
import DataSourceLifecycleStatusTag from './DataSourceLifecycleStatus';
import DataSourceStatus from './DataSourceStatus';

interface DataSourceCardProps {
  record: DataSourceRecord;
  onEdit: (record: DataSourceRecord) => void;
  onDelete: (record: DataSourceRecord) => void;
  onTestConnection: (record: DataSourceRecord) => void;
  onViewExploration: (record: DataSourceRecord) => void;
  onStatusChange: (record: DataSourceRecord, status: DataSourceLifecycleStatus) => void;
  onLakePhysical: (record: DataSourceRecord) => void;
  onLakeLogical: (record: DataSourceRecord) => void;
  onLakeRecommend: (record: DataSourceRecord) => void;
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
  const unitName = record.unitName || record.dataSourceUnit || '待归属';
  const businessSystemName = record.businessSystemName || record.systemName || '待归属';
  const isSystemManaged = Boolean(record.systemManaged);
  const profileLabel = record.profileStatus === 'SUCCESS' ? '已探查' : '未探查';

  return (
    <Card bodyStyle={{ padding: 0 }} className="datasource-card">
      <div className="datasource-card-cover">
        <div className="datasource-card-logo">
          <DatabaseIcons dbType={record.dbType} width="34" height="34" />
        </div>
        <div className="datasource-card-cover-info">
          <div className="datasource-card-title" title={record.name}>
            {record.name || '-'}
          </div>
          <div className="datasource-card-jdbc-url" title={record.jdbcUrl}>
            {record.jdbcUrl || '-'}
          </div>
        </div>
        <div className="datasource-card-profile-ribbon">未探查数据</div>
      </div>

      <div className="datasource-card-content">
        <div className="datasource-card-status">
          <DataSourceStatus status={record.connStatus} />
          <DataSourceLifecycleStatusTag status={record.status} />
          <Tag className="datasource-profile-tag">{profileLabel}</Tag>
        </div>
        <div className="datasource-card-owner-grid">
          <span>单位：{unitName}</span>
          <span>系统：{businessSystemName}</span>
        </div>
        <div className="datasource-card-update-time">
          发布时间：{record.createTime || record.updateTime || '-'}
        </div>
      </div>

      <div className="datasource-card-actions">
        <Tooltip title="查看探查结果">
          <Button type="text" icon={<EyeOutlined />} disabled={isDeleting} onClick={() => onViewExploration(record)}>
            查看探查结果
          </Button>
        </Tooltip>
        {!isSystemManaged ? (
          <Tooltip title="编辑">
            <Button type="text" icon={<EditOutlined />} disabled={isDeleting} onClick={() => onEdit(record)}>
              编辑
            </Button>
          </Tooltip>
        ) : null}
        <Tooltip title="测试连接">
          <Button type="text" icon={<ApiOutlined />} disabled={isDeleting} onClick={() => onTestConnection(record)}>
            测试连接
          </Button>
        </Tooltip>
        {!isSystemManaged ? (
          <>
            <Tooltip title={statusActionLabel}>
              <Button
                type="text"
                icon={currentStatus === 'DISABLED' ? <PlayCircleFilled /> : <PauseCircleFilled />}
                disabled={isDeleting}
                onClick={() => onStatusChange(record, nextStatus)}
              >
                {statusActionLabel}
              </Button>
            </Tooltip>
            <Tooltip title={isRevoked ? '已注销' : '注销'}>
              <Button
                type="text"
                icon={<PoweroffOutlined />}
                disabled={isDeleting}
                onClick={() => onStatusChange(record, 'REVOKED')}
              >
                注销
              </Button>
            </Tooltip>
            <Tooltip title="删除">
              <Button type="text" danger icon={<DeleteOutlined />} disabled={isDeleting} onClick={() => onDelete(record)}>
                删除
              </Button>
            </Tooltip>
          </>
        ) : (
          <Button type="text" onClick={onOpenWarehouse}>数据湖管理</Button>
        )}
      </div>
    </Card>
  );
};

export default DataSourceCard;
