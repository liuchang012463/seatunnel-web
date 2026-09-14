import React from 'react';
import StatusChip, { type StatusChipTone } from '@/components/StatusChip';

interface DataSourceStatusProps {
  status?: string;
}

interface StatusConfigItem {
  tone: StatusChipTone;
  text: string;
  tooltip: string;
}

const statusConfigMap: Record<string, StatusConfigItem> = {
  CONNECTED_SUCCESS: {
    tone: 'success',
    text: '连通正常',
    tooltip: '最近一次连通检测成功',
  },
  CONNECTED_FAILED: {
    tone: 'error',
    text: '连通异常',
    tooltip: '最近一次连通检测失败',
  },
  CONNECTING: {
    tone: 'processing',
    text: '检测中',
    tooltip: '正在进行连通检测',
  },
  CONNECTED_NONE: {
    tone: 'neutral',
    text: '未检测',
    tooltip: '尚未进行连通检测',
  },
};

const DataSourceStatus: React.FC<DataSourceStatusProps> = ({ status }) => {
  const currentConfig = statusConfigMap[status || 'CONNECTED_NONE'] || statusConfigMap.CONNECTED_NONE;

  return <StatusChip tone={currentConfig.tone} label={currentConfig.text} detail={currentConfig.tooltip} />;
};

export default DataSourceStatus;
