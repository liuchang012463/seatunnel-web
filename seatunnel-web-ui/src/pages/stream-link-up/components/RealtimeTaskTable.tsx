import { Empty, message, Table, Tooltip } from "antd";
import type { ColumnsType, TablePaginationConfig } from "antd/es/table";
import { useIntl } from "@umijs/max";
import React from "react";

import { CopyOutlined } from "@ant-design/icons";

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
      title: intl.formatMessage({
        id: "pages.job.table.col.name",
        defaultMessage: "链路名称/ID",
      }),
      dataIndex: "jobName",
      width: 220,
      render: (_, record) => (
        <div>
          <div className="flex items-center gap-1 text-xs leading-6">
            <em className="font-medium not-italic text-slate-700">
              {intl.formatMessage({
                id: "pages.job.table.label.jobName",
                defaultMessage: "任务名",
              })}
            </em>
            <span className="text-slate-400">:</span>

            <Tooltip title={record.jobName || record.id}>
              <span className="max-w-[150px] truncate text-slate-950">
                {record.jobName || "未命名实时任务"}
              </span>
            </Tooltip>
          </div>
          <div className="flex items-center gap-1 text-xs leading-6">
            <em className="font-medium not-italic text-slate-700">
              {intl.formatMessage({
                id: "pages.job.table.label.jobId",
                defaultMessage: "任务定义ID",
              })}
            </em>
            <span className="text-slate-400">:</span>
            <span className="text-slate-400">{record.id}</span>

            <Tooltip title="复制 ID">
              <button
                type="button"
                className="ml-1 inline-flex h-[18px] w-[18px] items-center justify-center rounded border-none bg-transparent text-slate-400 transition hover:bg-slate-100 hover:text-blue-600"
                onClick={(e) => {
                  e.stopPropagation();
                  copyToClipboard(record.id);
                }}
              >
                <CopyOutlined className="text-[12px]" />
              </button>
            </Tooltip>
          </div>
          <div className="flex items-center gap-1 text-xs leading-6">
            <em className="font-medium not-italic text-slate-700">zetaId</em>
            <span className="text-slate-400">:</span>

            <Tooltip title={record.engineJobId}>
              <span className="max-w-[150px] truncate text-slate-950">
                {record.engineJobId || "未启动"}
              </span>
            </Tooltip>
          </div>
        </div>
      ),
    },
    {
      title: intl.formatMessage({
        id: "pages.job.table.col.syncPlan",
        defaultMessage: "数据源同步方案",
      }),
      dataIndex: "syncPlan",
      width: 300,
      render: (_, record) => <RealtimeSyncPlan record={record} />,
    },
    {
      title: intl.formatMessage({
        id: "pages.job.table.col.status",
        defaultMessage: "健康状态",
      }),
      dataIndex: "taskParams",
      width: 106,
      render: (_content: any, record: any) => (
        <div className="flex w-full justify-center">
          <TaskStatus
            status={record?.lastJobStatus}
            errorMessage={record?.lastErrorMessage}
          />
        </div>
      ),
    },
    {
      title: "负载情况",
      dataIndex: "metricsTrend",
      width: 360,
      render: (_content: any, record: StreamingJobDefinitionVO) => (
        <RealtimeMetricsTrend record={record} onView={onView} />
      ),
    },
    {
      title: intl.formatMessage({
        id: "pages.job.table.col.execution",
        defaultMessage: "执行概况",
      }),
      dataIndex: "执行概况",
      render: (_content: any, record: any) => (
        <ExecutionStatus record={record} />
      ),
    },
    {
      title: "最近更新时间",
      dataIndex: "updateTime",
      width: 180,
      render: (value) => (
        <span className="text-sm text-slate-600">{formatDateTime(value)}</span>
      ),
    },
    {
      title: intl.formatMessage({
        id: "pages.job.table.col.operate",
        defaultMessage: "操作",
      }),
      dataIndex: "operate",
      width: 220,
      fixed: "right",
      render: (_, record) => (
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
    <Table
      rowKey="id"
      loading={loading}
      columns={columns as any}
      dataSource={dataSource}
      pagination={false}
      bordered
      rowSelection={{
        selectedRowKeys,
        onChange: onSelectedRowKeysChange,
        columnWidth: 42,
      }}
      size="middle"
      scroll={{ x: "max-content", y: "calc(100vh - 480px)" }}
      className={[
        "stream-link-task-table",
      ].join(" ")}
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
