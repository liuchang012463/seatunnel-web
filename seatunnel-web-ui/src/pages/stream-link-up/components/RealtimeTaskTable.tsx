import { Empty, message, Table, Tooltip } from "antd";
import type { ColumnsType, TablePaginationConfig } from "antd/es/table";
import { useIntl } from "@umijs/max";
import React from "react";

import { CopyOutlined } from "@ant-design/icons";

import type { TaskSortField, TaskSortOrder } from "@/pages/common/components/TaskSortControls";

import ExecutionStatus from "./ExecutionStatus";
import RealtimeMetricsTrend from "./RealtimeMetricsTrend";
import RealtimeSyncPlan from "./RealtimeSyncPlan";
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
  pagination?: false | TablePaginationConfig;
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
  pagination,
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
      width: 260,
      ellipsis: true,
      sorter: true,
      sortOrder: sort?.field === "name" ? (sort.order === "asc" ? "ascend" : "descend") : null,
      render: (_content, record) => (
        <div className="stream-link-task-name-cell">
          <div className="sync-task-name-cell__title">
            <Tooltip title={record.jobName || record.id}>
              <span className="min-w-0 max-w-[240px] truncate text-[color:var(--st-color-text-primary)]">
                {record.jobName || "未命名实时任务"}
              </span>
            </Tooltip>
          </div>
          <div className="stream-link-task-name-cell__line">
            <Tooltip title={record.id}>
              <span className="sync-task-name-cell__id">{record.id}</span>
            </Tooltip>

            <Tooltip title="复制任务定义 ID">
              <button
                type="button"
                className="ml-1 inline-flex h-[18px] w-[18px] items-center justify-center rounded border-none bg-transparent text-[color:var(--st-color-text-muted)] transition hover:bg-[rgba(77,210,255,0.08)] hover:text-[color:var(--st-color-accent)]"
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
          <div className="stream-link-task-name-cell__line">
            <Tooltip title={record.engineJobId || "未启动"}>
              <span className="min-w-0 max-w-[150px] truncate text-[color:var(--st-color-text-muted)]">
                zetaId {record.engineJobId || "未启动"}
              </span>
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
      width: 230,
      ellipsis: true,
      render: (_content, record) => (
        <div className="min-w-[190px]">
          <RealtimeSyncPlan record={record} />
        </div>
      ),
    },
    {
      key: "metrics",
      title: intl.formatMessage({
        id: "pages.job.table.col.metrics",
        defaultMessage: "负载情况",
      }),
      dataIndex: "",
      width: 370,
      render: (_content, record) => (
        <div className="min-w-[350px]">
          <RealtimeMetricsTrend record={record} onView={onView} />
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
      width: 280,
      render: (_content, record) => (
        <div className="min-w-[240px]">
          <ExecutionStatus record={record} />
        </div>
      ),
    },
    {
      key: "updateTime",
      title: intl.formatMessage({
        id: "pages.job.table.col.updateTime",
        defaultMessage: "最近更新时间",
      }),
      dataIndex: "updateTime",
      sorter: true,
      sortOrder: sort?.field === "createTime" ? (sort.order === "asc" ? "ascend" : "descend") : null,
      width: 160,
      ellipsis: true,
      render: (value: string | undefined) => (
        <span className="stream-link-time-cell">{formatDateTime(value)}</span>
      ),
    },
    {
      key: "operate",
      title: intl.formatMessage({
        id: "pages.job.table.col.operate",
        defaultMessage: "操作",
      }),
      dataIndex: "",
      width: 250,
      fixed: "right" as const,
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
    <Table<StreamingJobDefinitionVO>
      rowKey="id"
      loading={loading}
      columns={columns}
      onChange={(_pagination, _filters, sorter) => {
        const active = Array.isArray(sorter) ? sorter[0] : sorter;
        if (!active?.order) return;
        onSortChange?.(
          active.field === "createTime" || active.field === "updateTime" ? "createTime" : "name",
          active.order === "ascend" ? "asc" : "desc",
        );
      }}
      dataSource={dataSource}
      pagination={false}
      rowSelection={{
        selectedRowKeys,
        onChange: onSelectedRowKeysChange,
        columnWidth: 42,
      }}
      size="middle"
      scroll={{ x: "max-content", y: "calc(100vh - 380px)" }}
      className="stream-link-task-table"
      locale={{
        emptyText: (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="暂无引接链路（实时）"
          />
        ),
      }}
    />
  );
};

export default RealtimeTaskTable;
