import React from "react";
import StatusChip, { type StatusChipTone } from "@/components/StatusChip";
import type { DataSourceLifecycleStatus as LifecycleStatus } from "../types";

interface DataSourceLifecycleStatusProps {
  status?: LifecycleStatus;
}

const statusConfig: Record<LifecycleStatus, { tone: StatusChipTone; text: string; tooltip: string }> = {
  ENABLED: {
    tone: "success",
    text: "已启用",
    tooltip: "数据源可用于新任务配置",
  },
  DISABLED: {
    tone: "warning",
    text: "已停用",
    tooltip: "数据源暂不可用于新任务配置",
  },
  REVOKED: {
    tone: "neutral",
    text: "已注销",
    tooltip: "数据源已退出使用，不可恢复启用",
  },
};

const DataSourceLifecycleStatus: React.FC<DataSourceLifecycleStatusProps> = ({ status }) => {
  const config = statusConfig[status || "ENABLED"];

  return <StatusChip tone={config.tone} label={config.text} detail={config.tooltip} />;
};

export default DataSourceLifecycleStatus;
