import {
  ApiOutlined,
  ApartmentOutlined,
  CloseCircleOutlined,
  DeleteOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons';
import { Button, Card, Tag, Tooltip } from 'antd';
import React from 'react';
import { environmentTagConfigMap } from '../constants';
import { getDataSourceCategory } from '../dataSourceRegistry';
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
}

const DataSourceCard: React.FC<DataSourceCardProps> = ({
  record,
  onEdit,
  onDelete,
  onTestConnection,
  onViewExploration,
  onStatusChange,
}) => {
  const environmentConfig = environmentTagConfigMap[record.environment || ''] || {
    text: record.environmentName || '-',
    color: 'var(--st-color-text-muted)',
    backgroundColor: 'rgba(102, 111, 117, 0.14)',
    icon: null,
  };
  const category = getDataSourceCategory(record.dbType);
  const currentStatus = record.status || 'ENABLED';
  const isRevoked = currentStatus === 'REVOKED';
  const isDeleting = isRevoked || record.metadataSyncStatus === 'DELETING';
  const nextStatus = currentStatus === 'DISABLED' ? 'ENABLED' : 'DISABLED';
  const statusActionLabel = currentStatus === 'DISABLED' ? '启用' : '停用';
  const unitName = record.unitName || record.dataSourceUnit || '待归属';
  const businessSystemName = record.businessSystemName || record.systemName || '待归属';

  return (
    <Card
      bodyStyle={{ padding: 0 }}
      className={[
        'datasource-card group relative',
        'transition-colors duration-200 ease-out',
        'hover:!translate-y-0 hover:!transform-none',
      ].join(' ')}
    >
      <div className="datasource-card-cover">
        <div className="datasource-card-logo">
          <DatabaseIcons dbType={record.dbType} width="28" height="28" />
        </div>

        <div className="datasource-card-env-tag">
          <span
            className="datasource-card-env-tag-inner"
            style={{
              background: environmentConfig.backgroundColor,
              color: environmentConfig.color,
            }}
          >
            {environmentConfig.icon}
            {record.environmentName || environmentConfig.text}
          </span>
        </div>

        <div
          className={[
            'datasource-card-hover-actions',
            'opacity-0 translate-y-[-6px] pointer-events-none',
            'transition-all duration-200 ease-out',
            'group-hover:opacity-100 group-hover:translate-y-0 group-hover:pointer-events-auto',
          ].join(' ')}
        >
          <Tooltip title="测试连接" placement="top">
            <button
              type="button"
              disabled={isDeleting}
              className="datasource-card-hover-action"
              onClick={(event) => {
                event.stopPropagation();
                onTestConnection(record);
              }}
            >
              <ApiOutlined />
            </button>
          </Tooltip>

          <Tooltip title="查看探查结果" placement="top">
            <button
              type="button"
              disabled={isDeleting}
              className="datasource-card-hover-action"
              onClick={(event) => {
                event.stopPropagation();
                onViewExploration(record);
              }}
            >
              <ApartmentOutlined />
            </button>
          </Tooltip>

          <Tooltip title={statusActionLabel} placement="top">
            <button
              type="button"
              disabled={isDeleting}
              className="datasource-card-hover-action"
              onClick={(event) => {
                event.stopPropagation();
                onStatusChange(record, nextStatus);
              }}
            >
              {currentStatus === 'DISABLED' ? <PlayCircleOutlined /> : <PauseCircleOutlined />}
            </button>
          </Tooltip>

          <Tooltip title={isRevoked ? '已注销' : '注销'} placement="top">
            <button
              type="button"
              disabled={isDeleting}
              className="datasource-card-hover-action datasource-card-hover-action--danger"
              onClick={(event) => {
                event.stopPropagation();
                onStatusChange(record, 'REVOKED');
              }}
            >
              <CloseCircleOutlined />
            </button>
          </Tooltip>

          <Tooltip title="删除" placement="top">
            <button
              type="button"
              disabled={isDeleting}
              className="datasource-card-hover-action datasource-card-hover-action--danger"
              onClick={(event) => {
                event.stopPropagation();
                onDelete(record);
              }}
            >
              <DeleteOutlined />
            </button>
          </Tooltip>
        </div>
      </div>

      <div className="datasource-card-content">
        <div className="datasource-card-title truncate" title={record.name}>
          {record.name || '-'}
        </div>

        <div className="datasource-card-jdbc-url" title={record.jdbcUrl}>
          {record.jdbcUrl || '-'}
        </div>

        <div className="datasource-card-status">
          <DataSourceStatus status={record.connStatus} />
          <DataSourceLifecycleStatusTag status={record.status} />
          <Tag color="blue" style={{ marginInlineEnd: 0, borderRadius: 999 }}>
            {category.label}
          </Tag>
        </div>

        <div className="datasource-card-exploration-row">
          <span className="datasource-card-label">探查状态</span>
          <Tag
            color={record.profileStatus === 'SUCCESS' ? 'success' : record.profileStatus === 'FAILED' ? 'error' : 'default'}
            style={{ marginInlineEnd: 0, borderRadius: 999 }}
          >
            {record.profileStatus === 'SUCCESS'
              ? '已完成'
              : record.profileStatus === 'FAILED'
                ? '异常'
                : record.profileStatus === 'RUNNING' || record.profileStatus === 'QUEUED'
                  ? '处理中'
                  : '未探查'}
          </Tag>
        </div>

        <div className="datasource-card-owner-grid">
          <div className="datasource-card-owner-row" title={unitName}>
            <span className="datasource-card-label">单位</span>
            <span className="datasource-card-value">{unitName}</span>
          </div>
          <div className="datasource-card-owner-row" title={businessSystemName}>
            <span className="datasource-card-label">业务系统</span>
            <span className="datasource-card-value">{businessSystemName}</span>
          </div>
        </div>

        <div className="datasource-card-update-time">
          <span className="datasource-card-label">最近更新</span>
          <span className="datasource-card-update-time-value">{record.updateTime || '-'}</span>
        </div>

        <Button
          block
          type="primary"
          disabled={isDeleting}
          className={[
            'datasource-card-detail-button group/detail relative overflow-hidden p-0',
            'transition-all duration-300 ease-out',
          ].join(' ')}
          onClick={() => onEdit(record)}
        >
          查看详情
        </Button>
      </div>
    </Card>
  );
};

export default DataSourceCard;
