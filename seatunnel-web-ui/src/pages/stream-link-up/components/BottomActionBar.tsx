import {
  CloudDownloadOutlined,
  CloudUploadOutlined,
  CopyOutlined,
  DeleteOutlined,
  MoreOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  StopOutlined,
  SyncOutlined,
} from "@ant-design/icons";
import { Button, Dropdown, Pagination, Tooltip } from "antd";
import React from "react";

interface BottomActionBarProps {
  total: number;
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
  onClearSelection: () => void;
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
  current?: number;
  pageSize?: number;
  onPageChange?: (page: number, pageSize: number) => void;
}

/**
 * 底部批量栏（DESIGN.md §4.6）：空列表不渲染；未选中仅保留批量创建与分页；
 * 选中后「已选 n ｜ 启动 ｜ 终止 ｜ 更多▾ ｜ 取消」，低频与危险动作收进菜单。
 */
const BottomActionBar: React.FC<BottomActionBarProps> = ({
  total,
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
  onClearSelection,
  onlineDisabled = false,
  offlineDisabled = false,
  startDisabled = false,
  terminateDisabled = false,
  pauseDisabled = false,
  resumeDisabled = false,
  onlineTooltip,
  offlineTooltip,
  startTooltip,
  terminateTooltip,
  pauseTooltip,
  resumeTooltip,
  deleteDisabled = false,
  deleteTooltip,
  current = 1,
  pageSize = 10,
  onPageChange,
}) => {
  if (!total) {
    return null;
  }

  const hasSelection = selectedCount > 0;

  const moreItems = [
    {
      key: "online",
      label: "上线",
      disabled: disabled || onlineDisabled,
      title: onlineTooltip,
      onClick: onOnline,
    },
    {
      key: "offline",
      label: "下线",
      disabled: disabled || offlineDisabled,
      title: offlineTooltip,
      onClick: onOffline,
    },
    {
      key: "pause",
      label: "暂停并保存检查点",
      disabled: disabled || pauseDisabled,
      title: pauseTooltip,
      icon: <PauseCircleOutlined />,
      onClick: onPause,
    },
    {
      key: "resume",
      label: "从检查点恢复",
      disabled: disabled || resumeDisabled,
      title: resumeTooltip,
      icon: <SyncOutlined />,
      onClick: onResume,
    },
    ...(onDelete
      ? [
          { type: "divider" as const },
          {
            key: "delete",
            label: "删除",
            danger: true,
            disabled: disabled || deleteDisabled,
            title: deleteTooltip,
            onClick: onDelete,
          },
        ]
      : []),
  ];

  return (
    <div className="stream-link-bottom-bar fixed bottom-0 right-0 z-[99] flex min-h-16 items-center justify-between gap-4 px-6 py-3 backdrop-blur-xl left-[var(--pro-sider-current-width,0px)]">
      <div className="flex flex-wrap items-center gap-2">
        <Tooltip title={disabled ? "请先选择任务" : undefined}>
          <span>
            <Button size="small" disabled={disabled} onClick={onCreate} icon={<CopyOutlined />}>
              批量创建
            </Button>
          </span>
        </Tooltip>

        {hasSelection ? (
          <>
            <span className="text-sm text-[var(--st-color-text-secondary)]">
              已选 <span className="font-semibold text-[var(--st-color-text-primary)]">{selectedCount}</span> 条
            </span>

            <Tooltip title={startTooltip}>
              <span>
                <Button
                  type="primary"
                  size="small"
                  disabled={disabled || startDisabled}
                  onClick={onStart}
                  icon={<PlayCircleOutlined />}
                >
                  启动
                </Button>
              </span>
            </Tooltip>

            <Tooltip title={terminateTooltip}>
              <span>
                <Button
                  danger
                  size="small"
                  disabled={disabled || terminateDisabled}
                  onClick={onTerminate}
                  icon={<StopOutlined />}
                >
                  终止
                </Button>
              </span>
            </Tooltip>

            <Dropdown trigger={["click"]} menu={{ items: moreItems }} disabled={disabled}>
              <Button size="small" icon={<MoreOutlined />}>
                更多
              </Button>
            </Dropdown>

            <Button size="small" type="text" onClick={onClearSelection}>
              取消
            </Button>
          </>
        ) : null}
      </div>

      <div className="flex shrink-0 items-center gap-4 text-sm text-[var(--st-color-text-secondary)]">
        <span>总数 {total}</span>
        <Pagination
          size="small"
          total={total}
          current={current}
          pageSize={pageSize}
          showSizeChanger
          pageSizeOptions={[10, 20, 50]}
          onChange={onPageChange}
          onShowSizeChange={onPageChange}
        />
      </div>
    </div>
  );
};

export default BottomActionBar;
