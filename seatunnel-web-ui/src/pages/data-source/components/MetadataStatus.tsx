import {
  CheckCircleFilled,
  CloseCircleFilled,
  ClockCircleOutlined,
  LoadingOutlined,
  MinusCircleOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { Tag, Tooltip } from 'antd';
import React from 'react';

export interface MetadataStatusProps {
  syncStatus?: string;
  scanStatus?: string;
  scanLastRunTime?: string;
  profileStatus?: string;
  profileLastRunTime?: string;
}

type TagConfig = {
  color: 'success' | 'error' | 'processing' | 'default' | 'warning';
  icon: React.ReactNode;
  text: string;
};

export function metadataSyncTag(status?: string): TagConfig {
  const config: Record<string, TagConfig> = {
    READY: { color: 'success', icon: <CheckCircleFilled />, text: '已就绪' },
    PENDING: { color: 'warning', icon: <ClockCircleOutlined />, text: '待同步' },
    SYNCING: { color: 'processing', icon: <SyncOutlined spin />, text: '同步中' },
    WAITING: { color: 'warning', icon: <ClockCircleOutlined />, text: '等待同步' },
    ERROR: { color: 'error', icon: <CloseCircleFilled />, text: '同步异常' },
    DELETING: { color: 'default', icon: <LoadingOutlined spin />, text: '删除中' },
    NOT_INITIALIZED: { color: 'default', icon: <MinusCircleOutlined />, text: '未初始化' },
  };
  return config[status || 'NOT_INITIALIZED'] || config.NOT_INITIALIZED;
}

function runTag(status?: string): TagConfig {
  const config: Record<string, TagConfig> = {
    SUCCESS: { color: 'success', icon: <CheckCircleFilled />, text: '成功' },
    FAILED: { color: 'error', icon: <CloseCircleFilled />, text: '失败' },
    QUEUED: { color: 'processing', icon: <ClockCircleOutlined />, text: '排队中' },
    RUNNING: { color: 'processing', icon: <LoadingOutlined spin />, text: '运行中' },
    UNKNOWN: { color: 'warning', icon: <ClockCircleOutlined />, text: '状态未知' },
    NEVER: { color: 'default', icon: <MinusCircleOutlined />, text: '未运行' },
  };
  return config[status || 'NEVER'] || config.NEVER;
}

const compactTagStyle = { marginInlineEnd: 0, borderRadius: 999, paddingInline: 8, fontSize: 12 };

const MetadataStatus: React.FC<MetadataStatusProps> = ({
  syncStatus,
  scanStatus,
  scanLastRunTime,
  profileStatus,
  profileLastRunTime,
}) => {
  const sync = metadataSyncTag(syncStatus);
  const scan = runTag(scanStatus);
  const exploration = runTag(profileStatus);

  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <Tooltip title={`元数据同步：${sync.text}`}>
        <Tag color={sync.color} icon={sync.icon} style={compactTagStyle}>
          {sync.text}
        </Tag>
      </Tooltip>
      <Tooltip title={`自动扫描：${scan.text}${scanLastRunTime ? `，最近：${scanLastRunTime}` : ''}`}>
        <Tag color={scan.color} icon={scan.icon} style={compactTagStyle}>
          扫描 {scan.text}
        </Tag>
      </Tooltip>
      <Tooltip title={`数据源探查：${exploration.text}${profileLastRunTime ? `，最近：${profileLastRunTime}` : ''}`}>
        <Tag color={exploration.color} icon={exploration.icon} style={compactTagStyle}>
          探查 {exploration.text}
        </Tag>
      </Tooltip>
    </div>
  );
};

export default MetadataStatus;
