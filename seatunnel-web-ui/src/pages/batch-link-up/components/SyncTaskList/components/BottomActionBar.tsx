import {
  CloudDownloadOutlined,
  CloudUploadOutlined,
  CopyOutlined,
  DeleteOutlined,
  MoreOutlined,
  PlayCircleOutlined,
  StopOutlined,
} from "@ant-design/icons";
import { Button, Dropdown, Tooltip } from "antd";
import React from "react";

interface BottomActionBarProps {
  onStart: () => void;
  onStop: () => void;
  onOnline: () => void;
  onOffline: () => void;
  onDelete: () => void;
  onCreate: () => void;
  selectedCount?: number;

  /**
   * 全局禁用，比如未选择任何任务时。
   */
  disabled?: boolean;

  /**
   * 单独控制启动按钮禁用。
   */
  startDisabled?: boolean;

  /**
   * 单独控制停止按钮禁用。
   */
  stopDisabled?: boolean;

  /**
   * 启动按钮提示。
   */
  startTooltip?: string;

  /**
   * 停止按钮提示。
   */
  stopTooltip?: string;
  onlineDisabled?: boolean;
  offlineDisabled?: boolean;
  onlineTooltip?: string;
  offlineTooltip?: string;
  deleteDisabled?: boolean;
  deleteTooltip?: string;
}

const BottomActionBar: React.FC<BottomActionBarProps> = ({
  onStart,
  onStop,
  onOnline,
  onOffline,
  onDelete,
  onCreate,
  selectedCount = 0,
  disabled = false,
  startDisabled = false,
  stopDisabled = false,
  startTooltip,
  stopTooltip,
  onlineDisabled = false,
  offlineDisabled = false,
  deleteDisabled = false,
}) => {
  const finalStartDisabled = disabled || startDisabled;
  const finalStopDisabled = disabled || stopDisabled;
  const finalOnlineDisabled = disabled || onlineDisabled;
  const finalOfflineDisabled = disabled || offlineDisabled;
  const finalDeleteDisabled = disabled || deleteDisabled;

  const defaultDisabledTooltip =
    selectedCount <= 0 ? "请先选择任务" : undefined;

  if (selectedCount <= 0) return null;

  const moreItems = [
    { key: "online", label: "上线", icon: <CloudUploadOutlined />, disabled: finalOnlineDisabled, onClick: onOnline },
    { key: "offline", label: "下线", icon: <CloudDownloadOutlined />, disabled: finalOfflineDisabled, onClick: onOffline },
    { type: "divider" as const },
    { key: "delete", label: "删除", icon: <DeleteOutlined />, danger: true, disabled: finalDeleteDisabled, onClick: onDelete },
  ];

  return (
    <div className="task-bottom-action-bar">
      <div className="task-bottom-action-bar__content">
        <div className="task-bottom-action-bar__actions">
          <Tooltip title={defaultDisabledTooltip}>
            <span style={{ display: "inline-flex" }}>
              <Button
                size="small"
                onClick={onCreate}
                disabled={disabled}
                className="h-8 min-w-[104px] rounded-full border-slate-200 font-bold"
                icon={<CopyOutlined />}
              >
                批量创建
              </Button>
            </span>
          </Tooltip>

          <Tooltip title={startTooltip || defaultDisabledTooltip}>
            <span style={{ display: "inline-flex" }}>
              <Button
                size="small"
                type="primary"
                onClick={onStart}
                disabled={finalStartDisabled}
                className="h-8 min-w-[88px] rounded-full border-none font-bold shadow-[0_12px_26px_rgba(53,84,209,0.23)]"
                icon={<PlayCircleOutlined />}
              >
                启动
              </Button>
            </span>
          </Tooltip>

          <Tooltip title={stopTooltip || defaultDisabledTooltip}>
            <span style={{ display: "inline-flex" }}>
              <Button
                size="small"
                onClick={onStop}
                danger
                type="primary"
                disabled={finalStopDisabled}
                className="h-8 min-w-[88px] rounded-full border-none font-bold shadow-[0_12px_26px_rgba(244,63,94,0.18)]"
                icon={<StopOutlined />}
              >
                终止
              </Button>
            </span>
          </Tooltip>

          <Tooltip title="更多批量操作">
            <Dropdown trigger={["click"]} menu={{ items: moreItems }}>
              <Button
                size="small"
                className="h-8 rounded-full border-slate-200 font-bold"
                icon={<MoreOutlined />}
              >
                更多操作
              </Button>
            </Dropdown>
          </Tooltip>
        </div>

        <div className="task-bottom-action-bar__selection">
          已选择 <strong>{selectedCount}</strong> 条
        </div>
      </div>
    </div>
  );
};

export default BottomActionBar;
