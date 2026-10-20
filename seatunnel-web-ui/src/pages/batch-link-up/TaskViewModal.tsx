import { Modal, Splitter } from "antd";
import React, { forwardRef, useImperativeHandle, useRef, useState } from "react";
import { useIntl } from "@umijs/max";

import "./sumary.less";
import TaskDetailPanel from "./TaskDetailPanel";
import TaskHistoryPanel from "./components/TaskHistoryPanel";


interface CreateModalProps {
  onCreate?: (values: any) => void;
}

const TaskViewModal = forwardRef(({}: CreateModalProps, ref) => {
  const intl = useIntl();

  const [visible, setVisible] = useState<boolean>(false);
  const [jobItem, setJobItem] = useState<any>(null);
  const [instanceItem, setInstanceItem] = useState<any>(null);
  const [statusFilter, setStatusFilter] = useState<string>("all");

  const callback = useRef<() => void>(() => {
    return;
  });

  const onClose = () => {
    setVisible(false);
    setInstanceItem(null);
    setJobItem(null);
    setStatusFilter("all");
  };

  const onOpen = (status: boolean, record: any, cbk: () => void) => {
    setVisible(status);
    setJobItem(record);
    setInstanceItem(null);
    callback.current = cbk;
  };

  useImperativeHandle(ref, () => ({
    onOpen,
  }));

  return (
    <Modal
      title={
        <span>
          运行历史
          {jobItem?.jobName ? ` · ${jobItem.jobName}` : ""}
        </span>
      }
      open={visible}
      onCancel={onClose}
      styles={{
        mask: { background: "var(--st-color-modal-mask)" },
        content: {
          display: "flex",
          flexDirection: "column",
          flex: "1 1 0%",
          minHeight: 0,
        },
        body: {
          display: "flex",
          flexDirection: "column",
          flex: "1 1 auto",
          minHeight: 0,
          overflow: "hidden",
        },
      }}
      destroyOnHidden
      className="custom-modal task-history-modal"
      maskClosable={false}
      style={{ top: 10, height: "calc(100vh - 20px)" }}
      width="96vw"
      footer={null}
    >
      <div
        className="flex min-h-0 flex-1 flex-col"
        style={{ padding: 16 }}
      >
        <div
          className="mb-4 shrink-0"
          style={{ fontSize: 18, fontWeight: 600 }}
        >
          {intl.formatMessage({
            id: "pages.job.history.title",
            defaultMessage: "Run History",
          })}
        </div>

        <div className="min-h-0 flex-1">
          <Splitter
            style={{
              height: "100%",
              boxShadow: "0 0 10px rgba(0, 0, 0, 0.1)",
            }}
          >
            <Splitter.Panel defaultSize="22%" min="22%" max="70%">
              <TaskHistoryPanel
                selectedItem={jobItem}
                statusFilter={statusFilter}
                onItemSelect={() => {}}
                onStatusFilterChange={setStatusFilter}
                setInstanceItem={setInstanceItem}
                instanceItem={instanceItem}
              />
            </Splitter.Panel>

            <Splitter.Panel>
              <TaskDetailPanel instanceItem={instanceItem} />
            </Splitter.Panel>
          </Splitter>
        </div>
      </div>
    </Modal>
  );
});

export default TaskViewModal;
