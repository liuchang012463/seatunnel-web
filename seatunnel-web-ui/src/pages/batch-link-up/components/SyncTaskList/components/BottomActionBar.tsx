import {
  CloudDownloadOutlined,
  CloudUploadOutlined,
  CopyOutlined,
  DeleteOutlined,
  PlayCircleOutlined,
  StopOutlined,
} from "@ant-design/icons";
import { Button, Divider, Tooltip } from "antd";
import React from "react";
import CustomPagination from "../../../CustomPagination";

interface BottomActionBarProps {
  onStart: () => void;
  onStop: () => void;
  onOnline: () => void;
  onOffline: () => void;
  onDelete: () => void;
  onCreate: () => void;
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

const BottomActionBar: React.FC<BottomActionBarProps> = ({
  onStart,
  onStop,
  onOnline,
  onOffline,
  onDelete,
  onCreate,
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
        <div style={{ display: "flex", alignItems: "center" }}>
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

          <Divider type="vertical" />

          <Tooltip title={onlineTooltip || defaultDisabledTooltip}>
            <span style={{ display: "inline-flex" }}>
              <Button
                size="small"
                onClick={onOnline}
                disabled={finalOnlineDisabled}
                className="h-8 min-w-[82px] rounded-full border-slate-200 font-bold"
                icon={<CloudUploadOutlined />}
              >
                上线
              </Button>
            </span>
          </Tooltip>

          <Divider type="vertical" />

          <Tooltip title={offlineTooltip || defaultDisabledTooltip}>
            <span style={{ display: "inline-flex" }}>
              <Button
                size="small"
                onClick={onOffline}
                disabled={finalOfflineDisabled}
                className="h-8 min-w-[82px] rounded-full border-slate-200 font-bold"
                icon={<CloudDownloadOutlined />}
              >
                下线
              </Button>
            </span>
          </Tooltip>

          <Divider type="vertical" />

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

          <Divider type="vertical" />

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

          {selectedCount > 0 ? (
            <>
              <Divider type="vertical" />

              <span className="text-xs text-slate-500">
                已选择{" "}
                <span className="font-semibold text-slate-900">
                  {selectedCount}
                </span>{" "}
                条
              </span>
            </>
          ) : null}

          <Divider type="vertical" />

          <Tooltip title={deleteTooltip || defaultDisabledTooltip}>
            <span style={{ display: "inline-flex" }}>
              <Button
                size="small"
                danger
                onClick={onDelete}
                disabled={finalDeleteDisabled}
                className="h-8 min-w-[82px] rounded-full font-bold"
                icon={<DeleteOutlined />}
              >
                删除
              </Button>
            </span>
          </Tooltip>
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
