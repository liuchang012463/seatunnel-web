import { CheckCircleFilled, CloseCircleFilled, StopOutlined } from "@ant-design/icons";
import { Tag, Tooltip } from "antd";
import React from "react";
import type { DataSourceLifecycleStatus as LifecycleStatus } from "../types";

interface DataSourceLifecycleStatusProps {
  status?: LifecycleStatus;
}

const statusConfig: Record<
  LifecycleStatus,
  { color: "success" | "warning" | "default"; icon: React.ReactNode; text: string; tooltip: string }
> = {
  ENABLED: {
    color: "success",
    icon: <CheckCircleFilled />,
    text: "已启用",
    tooltip: "数据源可用于新任务配置",
  },
  DISABLED: {
    color: "warning",
    icon: <StopOutlined />,
    text: "已停用",
    tooltip: "数据源暂不可用于新任务配置",
  },
  REVOKED: {
    color: "default",
    icon: <CloseCircleFilled />,
    text: "已注销",
    tooltip: "数据源已退出使用，不可恢复启用",
  },
};

const DataSourceLifecycleStatus: React.FC<DataSourceLifecycleStatusProps> = ({ status }) => {
  const config = statusConfig[status || "ENABLED"];

  return (
    <Tooltip title={config.tooltip}>
      <Tag
        color={config.color}
        icon={config.icon}
        style={{ marginInlineEnd: 0, borderRadius: 999, paddingInline: 10, fontSize: 12 }}
      >
        {config.text}
      </Tag>
    </Tooltip>
  );
};

export default DataSourceLifecycleStatus;
