import {
  CloudDownloadOutlined,
  CloudUploadOutlined,
  DeleteOutlined,
  CopyOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  StopOutlined,
  SyncOutlined,
} from "@ant-design/icons";
import { Button, Pagination, Tag, Tooltip } from "antd";
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
  const defaultDisabledTooltip = selectedCount <= 0 ? "请先选择任务" : undefined;
  const withDefault = (value?: string) => value || defaultDisabledTooltip;

  return (
    <div className="stream-link-bottom-bar fixed bottom-0 right-0 z-[99] flex min-h-16 items-center justify-between gap-4 px-6 py-3 backdrop-blur-xl left-[var(--pro-sider-current-width,0px)]">
      <div className="flex flex-wrap items-center gap-2">
        <Tooltip title={defaultDisabledTooltip}>
          <span>
            <Button size="small" disabled={disabled} onClick={onCreate} icon={<CopyOutlined />}>
              批量创建
            </Button>
          </span>
        </Tooltip>

        <Tooltip title={withDefault(onlineTooltip)}>
          <span>
            <Button size="small" disabled={disabled || onlineDisabled} onClick={onOnline} icon={<CloudUploadOutlined />}>
              上线
            </Button>
          </span>
        </Tooltip>

        <Tooltip title={withDefault(offlineTooltip)}>
          <span>
            <Button size="small" disabled={disabled || offlineDisabled} onClick={onOffline} icon={<CloudDownloadOutlined />}>
              下线
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

        <Tooltip title={withDefault(pauseTooltip)}>
          <span>
            <Button size="small" disabled={disabled || pauseDisabled} onClick={onPause} icon={<PauseCircleOutlined />}>
              暂停并保存检查点
            </Button>
          </span>
        </Tooltip>

        <Tooltip title={withDefault(resumeTooltip)}>
          <span>
            <Button size="small" disabled={disabled || resumeDisabled} onClick={onResume} icon={<SyncOutlined />}>
              从检查点恢复
            </Button>
          </span>
        </Tooltip>

        {onDelete ? (
          <Tooltip title={withDefault(deleteTooltip)}>
            <span>
              <Button
                danger
                size="small"
                disabled={disabled || deleteDisabled}
                onClick={onDelete}
                icon={<DeleteOutlined />}
              >
                删除
              </Button>
            </span>
          </Tooltip>
        ) : null}

        {selectedCount > 0 ? <Tag color="blue" className="rounded-full px-3 py-0.5">已选择 {selectedCount}</Tag> : null}
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
