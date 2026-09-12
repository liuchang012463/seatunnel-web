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
import CustomPagination from "../../../CustomPagination";

interface BottomActionBarProps {
  onStart: () => void;
  onStop: () => void;
  onOnline: () => void;
  onOffline: () => void;
  onDelete: () => void;
  onCreate: () => void;
  onClearSelection: () => void;
  pagination: {
    total: number;
    current?: number;
    pageSize?: number;
    onChange?: (page: number, pageSize: number) => void;
  };
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

/**
 * 底部批量栏（DESIGN.md §4.6）：空列表不渲染；未选中仅保留批量创建与分页；
 * 选中后「已选 n ｜ 启动 ｜ 终止 ｜ 更多▾ ｜ 取消」，低频与危险动作收进菜单。
 */
const BottomActionBar: React.FC<BottomActionBarProps> = ({
  onStart,
  onStop,
  onOnline,
  onOffline,
  onDelete,
  onCreate,
  onClearSelection,
  pagination,
  selectedCount = 0,
  disabled = false,
  startDisabled = false,
  stopDisabled = false,
  startTooltip,
  stopTooltip,
  onlineDisabled = false,
  offlineDisabled = false,
  onlineTooltip,
  offlineTooltip,
  deleteDisabled = false,
  deleteTooltip,
}) => {
  const finalStartDisabled = disabled || startDisabled;
  const finalStopDisabled = disabled || stopDisabled;
  const finalOnlineDisabled = disabled || onlineDisabled;
  const finalOfflineDisabled = disabled || offlineDisabled;
  const finalDeleteDisabled = disabled || deleteDisabled;

  const defaultDisabledTooltip =
    selectedCount <= 0 ? "请先选择任务" : undefined;

  if (!pagination.total) {
    return null;
  }

  const hasSelection = selectedCount > 0;

  const moreItems = [
    {
      key: "online",
      label: "上线",
      disabled: finalOnlineDisabled,
      title: onlineTooltip,
      onClick: onOnline,
    },
    {
      key: "offline",
      label: "下线",
      disabled: finalOfflineDisabled,
      title: offlineTooltip,
      onClick: onOffline,
    },
    { type: "divider" as const },
    {
      key: "delete",
      label: "删除",
      danger: true,
      disabled: finalDeleteDisabled,
      title: deleteTooltip,
      onClick: onDelete,
    },
  ];

  return (
    <div className="task-bottom-action-bar">
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          gap: 16,
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
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

          {hasSelection ? (
            <>
              <span className="text-xs text-slate-500">
                已选 <span className="font-semibold text-slate-900">{selectedCount}</span> 条
              </span>

              <Tooltip title={startTooltip}>
                <span style={{ display: "inline-flex" }}>
                  <Button
                    size="small"
                    type="primary"
                    onClick={onStart}
                    disabled={finalStartDisabled}
                    className="h-8 rounded-full border-none font-bold"
                    icon={<PlayCircleOutlined />}
                  >
                    启动
                  </Button>
                </span>
              </Tooltip>

              <Tooltip title={stopTooltip}>
                <span style={{ display: "inline-flex" }}>
                  <Button
                    size="small"
                    danger
                    onClick={onStop}
                    disabled={finalStopDisabled}
                    className="h-8 rounded-full font-bold"
                    icon={<StopOutlined />}
                  >
                    终止
                  </Button>
                </span>
              </Tooltip>

              <Dropdown
                trigger={["click"]}
                menu={{ items: moreItems }}
                disabled={disabled}
              >
                <Button size="small" className="h-8 rounded-full" icon={<MoreOutlined />}>
                  更多
                </Button>
              </Dropdown>

              <Button size="small" type="text" className="h-8" onClick={onClearSelection}>
                取消
              </Button>
            </>
          ) : null}
        </div>

        <div style={{ marginRight: 8 }}>
          <CustomPagination
            total={pagination.total}
            current={pagination.current}
            pageSize={pagination.pageSize}
            onChange={pagination.onChange}
          />
        </div>
      </div>
    </div>
  );
};

export default BottomActionBar;
