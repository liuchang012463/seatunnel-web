import { Button, Empty, message, Table, Tooltip } from "antd";
import type { ColumnsType } from "antd/es/table";
import { useIntl } from "@umijs/max";
import React from "react";

import { CopyOutlined } from "@ant-design/icons";

import type { TaskSortField, TaskSortOrder } from "@/pages/common/components/TaskSortControls";
import DataSourceSyncPlan from "@/pages/batch-link-up/components/SyncTaskList/components/DataSourceSyncPlan";
import ScheduleInfo from "@/pages/batch-link-up/components/SyncTaskList/components/ScheduleInfo";
import ExecutionStatus from "@/pages/batch-link-up/components/SyncTaskList/components/ExecutionStatus";
import "@/pages/batch-link-up/components/SyncTaskList/index.less";

import RealtimeTaskActionColumn, {
  StreamingJobDefinitionVO,
} from "./RealtimeTaskActionColumn";
import TaskStatus from "./TaskStatus";

interface RealtimeTaskTableProps {
  loading?: boolean;
  dataSource: StreamingJobDefinitionVO[];
  selectedRowKeys: React.Key[];
  onSelectedRowKeysChange: (keys: React.Key[]) => void;
  sort?: { field: TaskSortField; order: TaskSortOrder };
  onSortChange?: (field: TaskSortField, order: TaskSortOrder) => void;
  onDetail?: (record: StreamingJobDefinitionVO) => void;
  onView?: (record: StreamingJobDefinitionVO) => void;
  onEdit?: (record: StreamingJobDefinitionVO) => void;
  onRun?: (record: StreamingJobDefinitionVO) => Promise<void> | void;
  onStop?: (record: StreamingJobDefinitionVO) => Promise<void> | void;
  onStopWithSavepoint?: (
    record: StreamingJobDefinitionVO
  ) => Promise<void> | void;
  onResumeFromSavepoint?: (
    record: StreamingJobDefinitionVO
  ) => Promise<void> | void;
  onOnline?: (record: StreamingJobDefinitionVO) => Promise<void> | void;
  onOffline?: (record: StreamingJobDefinitionVO) => Promise<void> | void;
  onDelete?: (record: StreamingJobDefinitionVO) => Promise<void> | void;
  onLog?: (record: StreamingJobDefinitionVO) => void;
  onCheckpoint?: (record: StreamingJobDefinitionVO) => void;
  onCreate?: () => void;
}

const formatDateTime = (value?: string) => {
  if (!value) return "-";

  return String(value).replace("T", " ").slice(0, 19);
};

const RealtimeTaskTable: React.FC<RealtimeTaskTableProps> = ({
  loading,
  dataSource,
  selectedRowKeys,
  onSelectedRowKeysChange,
  onStopWithSavepoint,
  onResumeFromSavepoint,
  onView,
  onDetail,
  onEdit,
  onRun,
  onStop,
  onOnline,
  onOffline,
  onDelete,
  onLog,
  onCheckpoint,
  onCreate,
  sort,
  onSortChange,}) => {
  const intl = useIntl();
  const copyToClipboard = async (text: string | number) => {
    const value = String(text);

    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(value);
      } else {
        const textarea = document.createElement("textarea");
        textarea.value = value;
        textarea.style.position = "fixed";
        textarea.style.opacity = "0";
        document.body.appendChild(textarea);
        textarea.focus();
        textarea.select();
        document.execCommand("copy");
        document.body.removeChild(textarea);
      }

      message.success("ID 已复制");
    } catch {
      message.error("复制失败，请手动复制");
    }
  };

  const columns: ColumnsType<StreamingJobDefinitionVO> = [
    {
      key: "jobName",
      title: intl.formatMessage({
        id: "pages.job.table.col.name",
        defaultMessage: "链路名称/ID",
      }),
      dataIndex: "jobName",
      width: 208,
      ellipsis: true,
      sorter: true,
      sortOrder: sort?.field === "name" ? (sort.order === "asc" ? "ascend" : "descend") : null,
      render: (_content, record) => (
        <div className="sync-task-name-cell">
          <div className="sync-task-name-cell__title" title={record.jobName || String(record.id)}>
            {record.jobName || "未命名实时任务"}
          </div>
          <div className="sync-task-name-cell__id">
            <span>{record.id}</span>

            <Tooltip title="复制任务定义 ID">
              <button
                type="button"
                className="sync-task-copy-btn"
                aria-label="复制任务定义 ID"
                onClick={(e) => {
                  e.stopPropagation();
                  copyToClipboard(record.id);
                }}
              >
                <CopyOutlined className="text-[12px]" />
              </button>
            </Tooltip>
          </div>
        </div>
      ),
    },
    {
      key: "status",
      title: intl.formatMessage({
        id: "pages.job.table.col.status",
        defaultMessage: "健康状态",
      }),
      dataIndex: "taskParams",
      width: 110,
      render: (_content, record) => (
        <div className="stream-link-status-cell">
          <TaskStatus
            status={record?.lastJobStatus}
            errorMessage={record?.lastErrorMessage}
          />
        </div>
      ),
    },
    {
      key: "syncPlan",
      title: intl.formatMessage({
        id: "pages.job.table.col.syncPlan",
        defaultMessage: "数据源同步方案",
      }),
      dataIndex: "",
      width: 198,
      ellipsis: true,
      render: (_content, record) => (
        <div className="sync-task-plan-cell">
          <DataSourceSyncPlan record={record} />
        </div>
      ),
    },
    {
      key: "execution",
      title: intl.formatMessage({
        id: "pages.job.table.col.execution",
        defaultMessage: "执行概况",
      }),
      dataIndex: "",
      width: 156,
      render: (_content, record) => <ExecutionStatus record={record} />,
    },
    {
      key: "schedule",
      title: intl.formatMessage({
        id: "pages.job.table.col.schedule",
        defaultMessage: "链路动态调度",
      }),
      dataIndex: "",
      width: 154,
      render: (_content, record) => <ScheduleInfo record={record} />,
    },
    {
      key: "createTime",
      title: intl.formatMessage({
        id: "pages.job.table.col.createTime",
        defaultMessage: "创建时间",
      }),
      dataIndex: "createTime",
      sorter: true,
      sortOrder: sort?.field === "createTime" ? (sort.order === "asc" ? "ascend" : "descend") : null,
      width: 136,
      ellipsis: true,
      render: (value: string | undefined) => (
        <span className="sync-task-time">{formatDateTime(value)}</span>
      ),
    },
    {
      key: "operate",
      title: intl.formatMessage({
        id: "pages.job.table.col.operate",
        defaultMessage: "操作",
      }),
      dataIndex: "",
      width: 168,
      fixed: "right",
      render: (_content, record) => (
        <RealtimeTaskActionColumn
          record={record}
          onDetail={onDetail}
          onEdit={onEdit}
          onRun={onRun}
          onStop={onStop}
          onOnline={onOnline}
          onOffline={onOffline}
          onDelete={onDelete}
          onLog={onLog}
          onCheckpoint={onCheckpoint}
          onStopWithSavepoint={onStopWithSavepoint}
          onResumeFromSavepoint={onResumeFromSavepoint}
        />
      ),
    },
  ];

  return (
    <div className="sync-task-list task-table-shell stream-link-task-table">
      <Table<StreamingJobDefinitionVO>
      rowKey="id"
      loading={loading}
      columns={columns}
      onChange={(_pagination, _filters, sorter) => {
        const active = Array.isArray(sorter) ? sorter[0] : sorter;
        if (!active?.order) return;
        onSortChange?.(
          active.field === "createTime" ? "createTime" : "name",
          active.order === "ascend" ? "asc" : "desc",
        );
      }}
      dataSource={dataSource}
      pagination={false}
      tableLayout="fixed"
      rowSelection={{
        selectedRowKeys,
        onChange: onSelectedRowKeysChange,
        columnWidth: 42,
        getCheckboxProps: (record) => ({
          'aria-label': `选择任务 ${record?.jobName || record?.id || ''}`,
        } as any),
        getTitleCheckboxProps: () => ({
          'aria-label': '选择全部任务',
        }),
      }}
      size="middle"
      scroll={{
        x: "100%",
        ...(dataSource.length > 0 ? { y: "calc(100vh - 380px)" } : {}),
      }}
      className="task-table"
      locale={{
        emptyText: (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              <div className="stream-link-empty-state">
                <div>暂无实时数据引接任务</div>
                <span>创建后，实时任务会在这里显示运行状态和最近一次执行结果。</span>
                {onCreate ? (
                  <Button type="link" onClick={onCreate}>
                    创建实时任务
                  </Button>
                ) : null}
              </div>
            }
          />
        ),
      }}
      />
    </div>
  );
};

export default RealtimeTaskTable;
