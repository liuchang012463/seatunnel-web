import { PlayCircleOutlined, StopOutlined } from "@ant-design/icons";
import { Button, Pagination, Tag } from "antd";
import React from "react";

interface BottomActionBarProps {
  total: number;
  selectedCount: number;
  disabled: boolean;
  onStart: () => void;
  onStop: () => void;
  current?: number;
  pageSize?: number;
  onPageChange?: (page: number, pageSize: number) => void;
}

const BottomActionBar: React.FC<BottomActionBarProps> = ({
  total,
  selectedCount,
  disabled,
  onStart,
  onStop,
  current = 1,
  pageSize = 10,
  onPageChange,
}) => (
  <div className="stream-link-bottom-bar fixed bottom-0 right-0 z-[99] flex min-h-16 items-center justify-between px-6 py-3 backdrop-blur-xl left-[var(--pro-sider-current-width,0px)]">
    <div className="flex items-center gap-3">
      {selectedCount > 0 ? (
        <>
          <Button type="primary" icon={<PlayCircleOutlined />} disabled={disabled} onClick={onStart} className="h-8 rounded-md border-none font-semibold">
            Online
          </Button>
          <Button icon={<StopOutlined />} disabled={disabled} onClick={onStop} className="h-8 rounded-md font-semibold">
            Offline
          </Button>
          <Tag color="blue" className="rounded-full px-3 py-0.5">Selected {selectedCount}</Tag>
        </>
      ) : (
        <span className="text-sm text-[var(--st-color-text-muted)]">Select tasks for bulk actions</span>
      )}
    </div>
    <div className="flex items-center gap-4 text-sm text-[var(--st-color-text-secondary)]">
      <span>Total {total}</span>
      <Pagination size="small" total={total} current={current} pageSize={pageSize} showSizeChanger pageSizeOptions={[10, 20, 50]} onChange={onPageChange} onShowSizeChange={onPageChange} />
    </div>
  </div>
);

export default BottomActionBar;
