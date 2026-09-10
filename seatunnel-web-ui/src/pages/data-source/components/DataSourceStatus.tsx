import React from 'react';
import { Tag, Tooltip } from 'antd';
import {
  CheckCircleFilled,
  CloseCircleFilled,
  LoadingOutlined,
  MinusCircleOutlined,
} from '@ant-design/icons';

interface DataSourceStatusProps {
  status?: string;
}

interface StatusConfigItem {
  color: 'success' | 'error' | 'processing' | 'default' | 'warning';
  icon: React.ReactNode;
  text: string;
  tooltip?: string;
}

const statusConfigMap: Record<string, StatusConfigItem> = {
  CONNECTED_SUCCESS: {
    color: 'success',
    icon: <CheckCircleFilled />,
    text: '连通正常',
    tooltip: '最近一次连通检测成功',
  },
  CONNECTED_FAILED: {
    color: 'error',
    icon: <CloseCircleFilled />,
    text: '连通异常',
    tooltip: '最近一次连通检测失败',
  },
  CONNECTING: {
    color: 'processing',
    icon: <LoadingOutlined spin />,
    text: '检测中',
    tooltip: '正在进行连通检测',
  },
  CONNECTED_NONE: {
    color: 'default',
    icon: <MinusCircleOutlined />,
    text: '未检测',
    tooltip: '尚未进行连通检测',
  },
};

const DataSourceStatus: React.FC<DataSourceStatusProps> = ({ status }) => {
  const currentConfig = statusConfigMap[status || 'CONNECTED_NONE'] || statusConfigMap.CONNECTED_NONE;

  return (
    <Tooltip title={currentConfig.tooltip}>
      <Tag
        color={currentConfig.color}
        icon={currentConfig.icon}
        style={{
          marginInlineEnd: 0,
          borderRadius: 999,
          paddingInline: 10,
          fontSize: 12,
          lineHeight: '20px',
        }}
      >
        {currentConfig.text}
      </Tag>
    </Tooltip>
  );
};

export default DataSourceStatus;