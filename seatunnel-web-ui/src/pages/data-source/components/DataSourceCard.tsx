import {
  ApiOutlined,
  CloseCircleOutlined,
  DeleteOutlined,
  HistoryOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  RadarChartOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { Button, Card, Tag, Tooltip } from 'antd';
import React from 'react';
import { environmentTagConfigMap } from '../constants';
import { getDataSourceCategory } from '../dataSourceRegistry';
import DatabaseIcons from '../icon/DatabaseIcons';
import type { DataSourceLifecycleStatus, DataSourceRecord } from '../types';
import DataSourceLifecycleStatusTag from './DataSourceLifecycleStatus';
import DataSourceStatus from './DataSourceStatus';
import MetadataStatus from './MetadataStatus';

interface DataSourceCardProps {
  record: DataSourceRecord;
  onEdit: (record: DataSourceRecord) => void;
  onDelete: (record: DataSourceRecord) => void;
  onTestConnection: (record: DataSourceRecord) => void;
  onScan: (record: DataSourceRecord) => void;
  onExplore: (record: DataSourceRecord) => void;
  onRuns: (record: DataSourceRecord) => void;
  onStatusChange: (record: DataSourceRecord, status: DataSourceLifecycleStatus) => void;
}

const DataSourceCard: React.FC<DataSourceCardProps> = ({
  record,
  onEdit,
  onDelete,
  onTestConnection,
  onScan,
  onExplore,
  onRuns,
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
  const metadataReady = record.metadataSyncStatus === 'READY';
  const metadataBusy = record.scanStatus === 'QUEUED'
    || record.scanStatus === 'RUNNING'
    || record.profileStatus === 'QUEUED'
    || record.profileStatus === 'RUNNING';
  const canOperateMetadata = !isDeleting && metadataReady && !metadataBusy;
  const nextStatus = currentStatus === 'DISABLED' ? 'ENABLED' : 'DISABLED';
  const statusActionLabel = currentStatus === 'DISABLED' ? '启用' : '停用';
  const isUnassigned = record.businessSystemId === undefined || record.businessSystemId === null;
  const unitName = isUnassigned ? '待归属' : record.unitName || record.dataSourceUnit || '待归属';
  const businessSystemName = isUnassigned ? '待归属' : record.businessSystemName || record.systemName || '待归属';

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

          <Tooltip title={canOperateMetadata ? '重新扫描' : '自动扫描暂不可触发'} placement="top">
            <button
              type="button"
              disabled={!canOperateMetadata}
              className="datasource-card-hover-action"
              onClick={(event) => {
                event.stopPropagation();
                onScan(record);
              }}
            >
              <SyncOutlined />
            </button>
          </Tooltip>

          <Tooltip title={canOperateMetadata ? '数据源探查' : '数据源探查暂不可触发'} placement="top">
            <button
              type="button"
              disabled={!canOperateMetadata}
              className="datasource-card-hover-action"
              onClick={(event) => {
                event.stopPropagation();
                onExplore(record);
              }}
            >
              <RadarChartOutlined />
            </button>
          </Tooltip>

          <Tooltip title={metadataReady && !isDeleting ? '查看运行记录' : '运行记录暂不可查看'} placement="top">
            <button
              type="button"
              disabled={!metadataReady || isDeleting}
              className="datasource-card-hover-action"
              onClick={(event) => {
                event.stopPropagation();
                onRuns(record);
              }}
            >
              <HistoryOutlined />
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

        <div className="datasource-card-status flex items-center gap-2">
          <DataSourceStatus status={record.connStatus} />
          <DataSourceLifecycleStatusTag status={record.status} />
          <Tag color="blue" style={{ marginInlineEnd: 0, borderRadius: 999 }}>
            {category.label}
          </Tag>
        </div>

        <div className="mt-2">
          <MetadataStatus
            syncStatus={record.metadataSyncStatus}
            scanStatus={record.scanStatus}
            scanLastRunTime={record.scanLastRunTime}
            profileStatus={record.profileStatus}
            profileLastRunTime={record.profileLastRunTime}
          />
        </div>

        <div className="mt-2 space-y-0.5 text-xs text-[var(--st-color-text-muted)]">
          <div>最近扫描：{record.scanLastSuccessTime || record.scanLastRunTime || '-'}</div>
          <div>最近探查：{record.profileLastSuccessTime || record.profileLastRunTime || '-'}</div>
        </div>

        <div className="datasource-card-unit" title={unitName}>
          单位：{unitName}
        </div>

        <div className="datasource-card-system" title={businessSystemName}>
          业务系统：{businessSystemName}
        </div>

        <div className="datasource-card-update-time">
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
