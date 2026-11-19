import { FileSearchOutlined } from "@ant-design/icons";
import { Empty, Modal, Tooltip } from "antd";
import React, { FC, ReactNode } from "react";

import TaskLogObservability from "@/pages/batch-link-up/components/TaskLogObservability";

import "../../../sumary.less";

type RunLogInstanceType = "BATCH" | "STREAMING" | string;

interface RunLogDrawerProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  subtitle?: string;
  footer?: ReactNode;
  children?: ReactNode;
  instanceId?: string | number;
  jobMode?: RunLogInstanceType;
  instanceStatus?: string;
  /** @deprecated 已改为与「查看历史」同款 Modal，保留以免调用方报错 */
  leftOffset?: number;
  /** @deprecated 已改为与「查看历史」同款 Modal，保留以免调用方报错 */
  defaultHeight?: number | string;
  /** @deprecated 已改为与「查看历史」同款 Modal，保留以免调用方报错 */
  minHeight?: number | string;
  /** @deprecated 已改为与「查看历史」同款 Modal，保留以免调用方报错 */
  maxHeight?: number | string;
}

const RunLogDrawer: FC<RunLogDrawerProps> = ({
  open,
  onClose,
  title = "运行日志",
  subtitle = "查看任务运行输出",
  footer,
  children,
  instanceId,
  jobMode = "BATCH",
  instanceStatus,
}) => {
  const modalTitle = (
    <div className="flex min-w-0 items-center justify-between gap-3 pr-8">
      <div className="flex min-w-0 items-center gap-3">
        <Tooltip title="当前任务实例的实时原始日志">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-[color:var(--st-color-bg-control)] text-[color:var(--st-color-accent)]">
            <FileSearchOutlined className="text-[15px]" />
          </div>
        </Tooltip>
        <div className="min-w-0">
          <div className="truncate text-[15px] font-semibold text-[color:var(--st-color-text-primary)]">
            {title}
          </div>
          <div className="truncate text-sm text-[color:var(--st-color-text-secondary)]">{subtitle}</div>
        </div>
      </div>
    </div>
  );

  return (
    <Modal
      title={modalTitle}
      open={open}
      onCancel={onClose}
      styles={{
        mask: { background: "var(--st-color-modal-mask)" },
        content: {
          display: "flex",
          flexDirection: "column",
          flex: "1 1 0%",
          minHeight: 0,
        },
        header: {
          marginBottom: 0,
          padding: "16px 20px",
          borderBottom: "1px solid var(--st-color-border)",
        },
        body: {
          display: "flex",
          flexDirection: "column",
          flex: "1 1 auto",
          minHeight: 0,
          overflow: "hidden",
          padding: 16,
        },
        footer: footer
          ? {
              marginTop: 0,
              padding: "12px 20px",
              borderTop: "1px solid var(--st-color-border)",
            }
          : undefined,
      }}
      destroyOnHidden
      className="custom-modal task-history-modal"
      maskClosable={false}
      style={{ top: 10, height: "calc(100vh - 20px)" }}
      width="96vw"
      footer={footer || null}
    >
      <div className="flex min-h-0 flex-1 flex-col">
        {children ? (
          children
        ) : instanceId ? (
          <TaskLogObservability
            instanceItem={{ id: instanceId, jobStatus: instanceStatus || "UNKNOWN" }}
            jobMode={jobMode === "STREAMING" ? "STREAMING" : "BATCH"}
            view="logs"
          />
        ) : (
          <div className="flex min-h-0 flex-1 items-center justify-center"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前任务暂无运行实例，无法查看日志" /></div>
        )}
      </div>
    </Modal>
  );
};

export default React.memo(RunLogDrawer);
