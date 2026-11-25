import { message, Table } from "antd";
import type { ColumnsType } from "antd/es/table";
import { useIntl } from "@umijs/max";
import React from "react";

import CatalogText from "@/pages/data-source/components/CatalogText";
import TaskNameCell from "@/pages/batch-link-up/components/SyncTaskList/components/TaskNameCell";

import type { TaskSortField, TaskSortOrder } from "@/pages/common/components/TaskSortControls";
import { TASK_TABLE_COLUMN_WIDTHS } from "@/pages/common/components/taskTableLayout";
import DataSourceSyncPlan from "@/pages/batch-link-up/components/SyncTaskList/components/DataSourceSyncPlan";
import ExecutionStatus from "@/pages/batch-link-up/components/SyncTaskList/components/ExecutionStatus";
import "@/pages/batch-link-up/components/SyncTaskList/index.less";

import RealtimeTaskActionColumn, {
  StreamingJobDefinitionVO,
} from "./RealtimeTaskActionColumn";
import RealtimeMetricsTrend from "./RealtimeMetricsTrend";
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
  emptyText: React.ReactNode;
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
  emptyText,
  sort,
  onSortChange,
}) => {
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
      width: TASK_TABLE_COLUMN_WIDTHS.name,
      fixed: "left",
      ellipsis: { showTitle: false },
      sorter: true,
      sortOrder: sort?.field === "name" ? (sort.order === "asc" ? "ascend" : "descend") : null,
      render: (_content, record) => (
        <TaskNameCell
          name={record.jobName || "未命名实时任务"}
          id={record.id}
          engineJobId={record.engineJobId || "-"}
          onCopy={() => void copyToClipboard(record.id)}
        />
      ),
    },
    {
      key: "status",
      title: intl.formatMessage({
        id: "pages.job.table.col.status",
        defaultMessage: "健康状态",
      }),
      dataIndex: "taskParams",
      width: TASK_TABLE_COLUMN_WIDTHS.status,
      fixed: "left",
      render: (_content, record) => (
        <div className="stream-link-status-cell">
          <TaskStatus
            status={record?.lastJobStatus}
            errorMessage={record?.lastErrorMessage}
            showErrorDetailsLink={false}
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
      width: TASK_TABLE_COLUMN_WIDTHS.plan,
      ellipsis: true,
      render: (_content, record) => (
        <div className="sync-task-plan-cell">
          <DataSourceSyncPlan record={record} />
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
      width: TASK_TABLE_COLUMN_WIDTHS.execution,
      render: (_content, record) => <ExecutionStatus record={record} />,
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
      width: TASK_TABLE_COLUMN_WIDTHS.createTime,
      ellipsis: { showTitle: false },
      render: (value: string | undefined) => (
        <CatalogText className="sync-task-time" text={formatDateTime(value)} />
      ),
    },
    {
      key: "updateTime",
      title: intl.formatMessage({
        id: "pages.job.table.col.updateTime",
        defaultMessage: "最近更新时间",
      }),
      dataIndex: "updateTime",
      width: TASK_TABLE_COLUMN_WIDTHS.updateTime,
      ellipsis: { showTitle: false },
      render: (value: string | undefined) => (
        <CatalogText className="sync-task-time" text={formatDateTime(value)} />
      ),
    },
    {
      key: "operate",
      title: intl.formatMessage({
        id: "pages.job.table.col.operate",
        defaultMessage: "操作",
      }),
      dataIndex: "",
      width: TASK_TABLE_COLUMN_WIDTHS.action,
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
        fixed: true,
        selectedRowKeys,
        onChange: onSelectedRowKeysChange,
        columnWidth: TASK_TABLE_COLUMN_WIDTHS.selection,
        getCheckboxProps: (record) => ({
          'aria-label': `选择任务 ${record?.jobName || record?.id || ''}`,
        } as any),
        getTitleCheckboxProps: () => ({
          'aria-label': '选择全部任务',
        }),
      }}
      size="middle"
      scroll={{ x: "100%" }}
      className="task-table"
      locale={{ emptyText }}
    />
  );
};

export default RealtimeTaskTable;
