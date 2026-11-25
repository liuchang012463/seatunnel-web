import { Empty, Modal } from "antd";
import React, { FC, ReactNode } from "react";

import TaskLogObservability from "@/pages/batch-link-up/components/TaskLogObservability";

import "../../../sumary.less";

type RunLogInstanceType = "BATCH" | "STREAMING" | string;

interface RunLogDrawerProps {
  open: boolean;
  onClose: () => void;
  jobName?: string;
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
  jobName,
  footer,
  children,
  instanceId,
  jobMode = "BATCH",
  instanceStatus,
}) => {
  const title = jobName ? `运行日志 · ${jobName}` : "运行日志";

  return (
    <Modal
      title={<span className="block truncate" title={title}>{title}</span>}
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
          // 日志区可以滚动，但不能挤压标题栏。
          flexShrink: 0,
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
            fillHeight
          />
        ) : (
          <div className="flex min-h-0 flex-1 items-center justify-center"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前任务暂无运行实例，无法查看日志" /></div>
        )}
      </div>
    </Modal>
  );
};

export default React.memo(RunLogDrawer);
