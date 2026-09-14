import {
  CloudDownloadOutlined,
  CloudUploadOutlined,
  DeleteOutlined,
  CopyOutlined,
  MoreOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  StopOutlined,
  SyncOutlined,
} from "@ant-design/icons";
import { Button, Dropdown, Tag, Tooltip } from "antd";
import React from "react";

interface BottomActionBarProps {
  selectedCount: number;
  disabled: boolean;
  onCreate: () => void;
  onOnline: () => void;
  onOffline: () => void;
  onStart: () => void;
  onTerminate: () => void;
  onPause: () => void;
  onResume: () => void;
  onDelete?: () => void;
  onlineDisabled?: boolean;
  offlineDisabled?: boolean;
  startDisabled?: boolean;
  terminateDisabled?: boolean;
  pauseDisabled?: boolean;
  resumeDisabled?: boolean;
  onlineTooltip?: string;
  offlineTooltip?: string;
  startTooltip?: string;
  terminateTooltip?: string;
  pauseTooltip?: string;
  resumeTooltip?: string;
  deleteDisabled?: boolean;
  deleteTooltip?: string;
}

const BottomActionBar: React.FC<BottomActionBarProps> = ({
  selectedCount,
  disabled,
  onCreate,
  onOnline,
  onOffline,
  onStart,
  onTerminate,
  onPause,
  onResume,
  onDelete,
  onlineDisabled = false,
  offlineDisabled = false,
  startDisabled = false,
  terminateDisabled = false,
  pauseDisabled = false,
  resumeDisabled = false,
  startTooltip,
  terminateTooltip,
  deleteDisabled = false,
}) => {
  const defaultDisabledTooltip = selectedCount <= 0 ? "请先选择任务" : undefined;
  const withDefault = (value?: string) => value || defaultDisabledTooltip;

  if (selectedCount <= 0) return null;

  const moreItems = [
    { key: "online", label: "上线", icon: <CloudUploadOutlined />, disabled: disabled || onlineDisabled, onClick: onOnline },
    { key: "offline", label: "下线", icon: <CloudDownloadOutlined />, disabled: disabled || offlineDisabled, onClick: onOffline },
    { key: "pause", label: "暂停并保存检查点", icon: <PauseCircleOutlined />, disabled: disabled || pauseDisabled, onClick: onPause },
    { key: "resume", label: "从检查点恢复", icon: <SyncOutlined />, disabled: disabled || resumeDisabled, onClick: onResume },
    ...(onDelete ? [{ type: "divider" as const }, { key: "delete", label: "删除", icon: <DeleteOutlined />, danger: true, disabled: disabled || deleteDisabled, onClick: onDelete }] : []),
  ];

  return (
    <div className="stream-link-bottom-bar">
      <div className="stream-link-bottom-bar__content">
        <div className="stream-link-bottom-bar__actions">
        <Tooltip title={defaultDisabledTooltip}>
          <span>
            <Button size="small" disabled={disabled} onClick={onCreate} icon={<CopyOutlined />}>
              批量创建
            </Button>
          </span>
        </Tooltip>

        <Tooltip title={withDefault(startTooltip)}>
          <span>
            <Button type="primary" size="small" disabled={disabled || startDisabled} onClick={onStart} icon={<PlayCircleOutlined />}>
              启动
            </Button>
          </span>
        </Tooltip>

        <Tooltip title={withDefault(terminateTooltip)}>
          <span>
            <Button danger size="small" disabled={disabled || terminateDisabled} onClick={onTerminate} icon={<StopOutlined />}>
              终止
            </Button>
          </span>
        </Tooltip>

        <Tooltip title="更多批量操作">
          <Dropdown trigger={["click"]} menu={{ items: moreItems }}>
            <Button size="small" icon={<MoreOutlined />}>更多操作</Button>
          </Dropdown>
        </Tooltip>

        <Tag color="blue" className="rounded-full px-3 py-0.5">已选择 {selectedCount}</Tag>
        </div>
      </div>
    </div>
  );
};

export default BottomActionBar;
