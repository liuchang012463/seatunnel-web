import { CopyOutlined } from "@ant-design/icons";
import { history, useIntl } from "@umijs/max";
import { Divider, Empty, Modal, Table, Tooltip, message } from "antd";
import { TableRowSelection } from "antd/es/table/interface";
import moment from "moment";
import { useEffect, useState } from "react";
import TaskSortControls, {
  type TaskSortField,
  type TaskSortOrder,
} from "@/pages/common/components/TaskSortControls";
import { seatunnelJobDefinitionApi } from "../../api";
import BatchCreateJobModal, {
  BatchCreateValues,
} from "@/pages/common/components/BatchCreateJobModal";
import { batchJobExecutorApi } from "../../type";
import ActionColumn from "./components/ActionColumn";
import AdvancedSearchForm from "./components/AdvancedSearchForm";
import BottomActionBar from "./components/BottomActionBar";
import DataSourceSyncPlan from "./components/DataSourceSyncPlan";
import ExecutionStatus from "./components/ExecutionStatus";
import Footer from "./components/Footer";
import ScheduleInfo from "./components/ScheduleInfo";
import TaskStatus from "./components/TaskStatus";
import './index.less';

interface Props {
  goDetail: (value: any, item?: any) => void;
  mode?: string;
  excludeMode?: string;
  emptyDescription?: string;
}

const DEFAULT_TIME_RANGE: any[] = [];
const DEFAULT_SORT_FIELD: TaskSortField = "createTime";
const DEFAULT_SORT_ORDER: TaskSortOrder = "desc";

const RUNNING_STATUS_SET = new Set([
  "INITIALIZING",
  "CREATED",
  "PENDING",
  "SCHEDULED",
  "RUNNING",
  "FAILING",
  "DOING_SAVEPOINT",
  "CANCELING",
]);

const parseSearchParamsFromUrl = () => {
  const params = new URLSearchParams(window.location.search);

  const createTimeStart = params.get("createTimeStart");
  const createTimeEnd = params.get("createTimeEnd");

  return {
    jobName: params.get("jobName") || undefined,
    id: params.get("id") || undefined,
    status: params.get("status") || undefined,
    sourceType: params.get("sourceType") || undefined,
    sinkType: params.get("sinkType") || undefined,
    sourceTable: params.get("sourceTable") || undefined,
    sinkTable: params.get("sinkTable") || undefined,
    createTime:
      createTimeStart && createTimeEnd
        ? [
            moment(createTimeStart, "YYYY-MM-DD HH:mm:ss"),
            moment(createTimeEnd, "YYYY-MM-DD HH:mm:ss"),
          ]
        : DEFAULT_TIME_RANGE,
  };
};

const parsePaginationFromUrl = () => {
  const params = new URLSearchParams(window.location.search);

  return {
    current: Number(params.get("current") || 1),
    pageSize: Number(params.get("pageSize") || 10),
    total: 0,
  };
};

const parseSortFromUrl = () => {
  const params = new URLSearchParams(window.location.search);
  const field = params.get("sortField");
  const order = params.get("sortOrder");

  return {
    field: field === "name" || field === "createTime" ? field : DEFAULT_SORT_FIELD,
    order: order === "asc" || order === "desc" ? order : DEFAULT_SORT_ORDER,
  } as { field: TaskSortField; order: TaskSortOrder };
};

const App: React.FC<Props> = ({
  goDetail,
  mode,
  excludeMode,
  emptyDescription = "暂无引接链路（离线）",
}) => {
  const intl = useIntl();

  const [taskList, setTaskList] = useState<any[]>([]);
  const [searchParams, setSearchParams] = useState<any>(() =>
    parseSearchParamsFromUrl()
  );
  const [pagination, setPagination] = useState(() => parsePaginationFromUrl());
  const [sort, setSort] = useState(() => parseSortFromUrl());
  const [loading, setLoading] = useState(false);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [batchCreateOpen, setBatchCreateOpen] = useState(false);
  const [batchCreateLoading, setBatchCreateLoading] = useState(false);

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

      message.success("任务定义ID已复制");
    } catch {
      message.error("复制失败，请手动复制");
    }
  };

  const syncUrlParams = (
    params: any,
    pageInfo: { current: number; pageSize: number },
    sortInfo: { field: TaskSortField; order: TaskSortOrder },
  ) => {
    const query = new URLSearchParams();

    if (params?.jobName) query.set("jobName", params.jobName);
    if (params?.id) query.set("id", params.id);
    if (params?.status) query.set("status", params.status);
    if (params?.sourceType) query.set("sourceType", params.sourceType);
    if (params?.sinkType) query.set("sinkType", params.sinkType);
    if (params?.sourceTable) query.set("sourceTable", params.sourceTable);
    if (params?.sinkTable) query.set("sinkTable", params.sinkTable);

    if (params?.createTime?.length === 2) {
      query.set(
        "createTimeStart",
        moment(params.createTime[0]).format("YYYY-MM-DD HH:mm:ss")
      );
      query.set(
        "createTimeEnd",
        moment(params.createTime[1]).format("YYYY-MM-DD HH:mm:ss")
      );
    }

    query.set("current", String(pageInfo.current || 1));
    query.set("pageSize", String(pageInfo.pageSize || 10));
    query.set("sortField", sortInfo.field);
    query.set("sortOrder", sortInfo.order);

    history.replace({
      search: `?${query.toString()}`,
    });
  };

  const fetchTaskList = async () => {
    setLoading(true);

    const transformedParams = { ...searchParams };

    if (transformedParams?.createTime?.length === 2) {
      transformedParams.createTimeStart = moment(
        transformedParams.createTime[0]
      ).format("YYYY-MM-DD HH:mm:ss");
      transformedParams.createTimeEnd = moment(
        transformedParams.createTime[1]
      ).format("YYYY-MM-DD HH:mm:ss");
      delete transformedParams.createTime;
    }

    try {
      const data = await seatunnelJobDefinitionApi.page({
        ...transformedParams,
        mode,
        excludeMode,
        sortField: sort.field,
        sortOrder: sort.order,
        pageNo: pagination.current,
        pageSize: pagination.pageSize,
      });

      const nextTaskList = data?.data?.bizData || [];
      setTaskList(nextTaskList);
      setSelectedRowKeys((previousKeys) =>
        previousKeys.filter((key) =>
          nextTaskList.some((record: any) => String(record?.id) === String(key)),
        ),
      );
      setPagination((prev) => ({
        ...prev,
        total: data?.data?.pagination?.total || 0,
      }));
    } catch (error) {
      message.error("查询任务列表失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    syncUrlParams(searchParams, pagination, sort);
  }, [searchParams, pagination.current, pagination.pageSize, sort]);

  useEffect(() => {
    fetchTaskList();
  }, [searchParams, pagination.current, pagination.pageSize, sort]);

  const baseColumns = [
    {
      title: intl.formatMessage({
        id: "pages.job.table.col.name",
        defaultMessage: "Name",
      }),
      dataIndex: "jobName",
      width: "12%",
      ellipsis: true,
      render: (_content: any, record: any) => (
        <div className="sync-task-name-cell">
          <div className="sync-task-name-cell__title">
            <em>
              {intl.formatMessage({
                id: "pages.job.table.label.jobName",
                defaultMessage: "JobName",
              })}
            </em>
            : {record?.jobName}
          </div>
          <div className="sync-task-name-cell__id">
            <em>
              {intl.formatMessage({
                id: "pages.job.table.label.jobId",
                defaultMessage: "Job Definition ID",
              })}
            </em>
            :{" "}
            <span>{record?.id}</span>{" "}
            <Tooltip title="复制任务定义ID">
              <button
                type="button"
                className="sync-task-copy-btn"
                onClick={(e) => {
                  e.stopPropagation();
                  copyToClipboard(record?.id);
                }}
              >
                <CopyOutlined style={{ fontSize: 12 }} />
              </button>
            </Tooltip>
          </div>
        </div>
      ),
    },
    {
      title: intl.formatMessage({
        id: "pages.job.table.col.syncPlan",
        defaultMessage: "Sync Plan",
      }),
      dataIndex: "",
      width: "21%",
      render: (_content: any, record: any) => (
        <div className="sync-task-plan-cell">
          <DataSourceSyncPlan record={record} />
        </div>
      ),
    },
    {
      title: intl.formatMessage({
        id: "pages.job.table.col.status",
        defaultMessage: "Status",
      }),
      dataIndex: "taskParams",
      width: "7%",
      render: (_content: any, record: any) => (
        <div className="sync-task-status-cell flex w-full justify-center">
          <TaskStatus
            status={record?.lastJobStatus}
            errorMessage={record?.lastErrorMessage}
          />
        </div>
      ),
    },
    {
      title: intl.formatMessage({
        id: "pages.job.table.col.execution",
        defaultMessage: "Execution",
      }),
      dataIndex: "执行概况",
      width: "15%",
      render: (_content: any, record: any) => (
        <div className="sync-task-info-list">
          <ExecutionStatus record={record} />
        </div>
      ),
    },
    {
      title: intl.formatMessage({
        id: "pages.job.table.col.schedule",
        defaultMessage: "Schedule",
      }),
      dataIndex: "taskName",
      width: "20%",
      render: (_content: any, record: any) => (
        <div className="sync-task-info-list sync-task-schedule-list">
          <ScheduleInfo record={record} />
        </div>
      ),
    },
    {
      title: intl.formatMessage({
        id: "pages.job.table.col.createTime",
        defaultMessage: "CreateTime",
      }),
      dataIndex: "createTime",
      width: "10%",
      render: (createTime: string) => (
        <span className="sync-task-time">{createTime || "-"}</span>
      ),
    },
    {
      title: intl.formatMessage({
        id: "pages.job.table.col.operate",
        defaultMessage: "Operate",
      }),
      dataIndex: "",
      width: "14%",
      fixed: "right" as const,
      render: (record: any) => (
        <ActionColumn record={record} cbk={fetchTaskList} goDetail={goDetail} />
      ),
    },
  ];

  const onSelectChange = (newSelectedRowKeys: React.Key[]) => {
    setSelectedRowKeys(newSelectedRowKeys);
  };

  const rowSelection: TableRowSelection<any> = {
    selectedRowKeys,
    onChange: onSelectChange,
  };

  const handleSearch = (values: any) => {
    setSelectedRowKeys([]);
    setSearchParams(values);
    setPagination((prev) => ({ ...prev, current: 1 }));
  };

  const handleReset = () => {
    setSelectedRowKeys([]);
    setSort({ field: DEFAULT_SORT_FIELD, order: DEFAULT_SORT_ORDER });
    setSearchParams({
      createTime: DEFAULT_TIME_RANGE,
    });
    setPagination((prev) => ({
      ...prev,
      current: 1,
    }));
  };

  const handlePaginationChange = (page: number, pageSize: number) => {
    setSelectedRowKeys([]);
    setPagination((prev) => ({
      ...prev,
      current: page,
      pageSize,
    }));
  };

  const handleSortChange = (field: TaskSortField, order: TaskSortOrder) => {
    setSort({ field, order });
    setSelectedRowKeys([]);
    setPagination((prev) => ({ ...prev, current: 1 }));
  };

  const hasSelected = selectedRowKeys.length > 0;

  const getSelectedRows = () => {
    const selectedKeySet = new Set(selectedRowKeys.map(String));
    return taskList.filter((item) => selectedKeySet.has(String(item?.id)));
  };

  const isOnline = (record: any) => {
    return String(record?.releaseState || "").toUpperCase() === "ONLINE";
  };

  const isRunning = (record: any) => {
    return RUNNING_STATUS_SET.has(
      String(record?.lastJobStatus || "").toUpperCase()
    );
  };

  const buildJobLabel = (record: any) => {
    return `${record?.jobName || "-"}(${record?.id || "-"})`;
  };

  const buildLimitedJobLabels = (records: any[]) => {
    const labels = records.slice(0, 3).map(buildJobLabel).join("、");
    if (records.length <= 3) {
      return labels;
    }
    return `${labels} 等 ${records.length} 个任务`;
  };

  const getBatchActionState = () => {
    const selectedRows = getSelectedRows();

    if (selectedRows.length === 0) {
      return {
        startDisabled: true,
        stopDisabled: true,
        onlineDisabled: true,
        offlineDisabled: true,
        deleteDisabled: true,
        startTooltip: "请先选择任务",
        stopTooltip: "请先选择要终止的任务",
        onlineTooltip: "请先选择任务",
        offlineTooltip: "请先选择任务",
        deleteTooltip: "请先选择要删除的任务",
      };
    }

    const offlineRows = selectedRows.filter((item) => !isOnline(item));
    const onlineRows = selectedRows.filter(isOnline);
    const runningRows = selectedRows.filter(isRunning);
    const notRunningRows = selectedRows.filter((item) => !isRunning(item));
    const onlineOrRunningRows = selectedRows.filter(
      (item) => isOnline(item) || isRunning(item),
    );

    const startDisabled = offlineRows.length > 0 || runningRows.length > 0;
    const stopDisabled = notRunningRows.length > 0;
    const onlineDisabled = offlineRows.length === 0;
    const offlineDisabled = onlineRows.length === 0 || runningRows.length > 0;
    const deleteDisabled = onlineOrRunningRows.length > 0;

    let startTooltip: string | undefined;
    let stopTooltip: string | undefined;
    let onlineTooltip: string | undefined;
    let offlineTooltip: string | undefined;
    let deleteTooltip: string | undefined;

    if (offlineRows.length > 0) {
      startTooltip = `存在未上线任务，请先上线后再启动：${buildLimitedJobLabels(
        offlineRows
      )}`;
    } else if (runningRows.length > 0) {
      startTooltip = `存在运行中的任务，请只选择未运行任务进行启动：${buildLimitedJobLabels(
        runningRows
      )}`;
    }

    if (notRunningRows.length > 0) {
      stopTooltip = `存在未运行任务，请只选择运行中的任务进行终止：${buildLimitedJobLabels(
        notRunningRows
      )}`;
    }

    if (onlineDisabled) {
      onlineTooltip = "所选任务已经全部上线";
    }

    if (runningRows.length > 0) {
      offlineTooltip = `存在运行中的任务，请先终止后再下线：${buildLimitedJobLabels(
        runningRows
      )}`;
    } else if (!offlineDisabled) {
      offlineTooltip = undefined;
    } else {
      offlineTooltip = "所选任务已经全部下线";
    }

    if (onlineOrRunningRows.length > 0) {
      const onlineRows = onlineOrRunningRows.filter((item) => isOnline(item));
      const runningRows = onlineOrRunningRows.filter((item) => isRunning(item));
      const reasons = [
        onlineRows.length > 0
          ? `已上线任务：${buildLimitedJobLabels(onlineRows)}`
          : "",
        runningRows.length > 0
          ? `运行中任务：${buildLimitedJobLabels(runningRows)}`
          : "",
      ].filter(Boolean);
      deleteTooltip = `请先下线并终止不可删除任务：${reasons.join("；")}`;
    }

    return {
      startDisabled,
      stopDisabled,
      onlineDisabled,
      offlineDisabled,
      deleteDisabled,
      startTooltip,
      stopTooltip,
      onlineTooltip,
      offlineTooltip,
      deleteTooltip,
    };
  };

  const batchActionState = getBatchActionState();

  const validateBatchStart = () => {
    const selectedRows = getSelectedRows();

    if (selectedRows.length === 0) {
      message.warning("请先选择要启动的任务");
      return false;
    }

    const offlineRows = selectedRows.filter((item) => !isOnline(item));
    if (offlineRows.length > 0) {
      message.warning(
        `存在未上线任务，请先上线后再启动：${buildLimitedJobLabels(
          offlineRows
        )}`
      );
      return false;
    }

    const runningRows = selectedRows.filter(isRunning);
    if (runningRows.length > 0) {
      message.warning(
        `存在运行中的任务，请只选择未运行任务进行批量启动：${buildLimitedJobLabels(
          runningRows
        )}`
      );
      return false;
    }

    return true;
  };

  const validateBatchStop = () => {
    const selectedRows = getSelectedRows();

    if (selectedRows.length === 0) {
      message.warning("请先选择要终止的任务");
      return false;
    }

    const notRunningRows = selectedRows.filter((item) => !isRunning(item));
    if (notRunningRows.length > 0) {
      message.warning(
        `存在未运行的任务，请只选择运行中的任务进行批量终止：${buildLimitedJobLabels(
          notRunningRows
        )}`
      );
      return false;
    }

    return true;
  };

  const getErrorMessage = (error: any, fallback: string) => {
    return (
      error?.response?.data?.message ||
      error?.response?.data?.msg ||
      error?.data?.message ||
      error?.data?.msg ||
      error?.message ||
      fallback
    );
  };

  const onStartAll = async () => {
    if (!validateBatchStart()) {
      return;
    }

    try {
      const data = await batchJobExecutorApi.batchExecute(selectedRowKeys);

      if (data?.code === 0) {
        const result = data?.data;

        message.success(
          `批量启动完成：成功 ${result?.successCount || 0} 个，失败 ${
            result?.failedCount || 0
          } 个`
        );

        setSelectedRowKeys([]);
        fetchTaskList();
      } else {
        message.error(data?.message || data?.msg || "批量启动失败");
      }
    } catch (error: any) {
      message.error(getErrorMessage(error, "批量启动失败"));
    }
  };

  const onTerminateAll = async () => {
    if (!validateBatchStop()) {
      return;
    }

    try {
      const data = await batchJobExecutorApi.batchTerminate(selectedRowKeys);

      if (data?.code === 0) {
        const result = data?.data;

        message.success(
          `批量终止完成：成功 ${result?.successCount || 0} 个，失败 ${
            result?.failedCount || 0
          } 个`
        );

        setSelectedRowKeys([]);
        fetchTaskList();
      } else {
        message.error(data?.message || data?.msg || "批量终止失败");
      }
    } catch (error: any) {
      message.error(getErrorMessage(error, "批量终止失败"));
    }
  };

  const onOnlineAll = async () => {
    const selectedRows = getSelectedRows();
    const records = selectedRows.filter((item) => !isOnline(item));

    if (records.length === 0) {
      message.warning("所选任务已经全部上线");
      return;
    }

    const responses = await Promise.allSettled(
      records.map((record) => seatunnelJobDefinitionApi.online(record.id))
    );
    const successCount = responses.filter(
      (item) => item.status === "fulfilled" && item.value?.code === 0
    ).length;
    const failedCount = responses.length - successCount;

    if (successCount > 0) {
      message.success(`批量上线完成：成功 ${successCount} 个`);
      fetchTaskList();
    }
    if (failedCount > 0) {
      message.error(`批量上线失败：${failedCount} 个`);
    }
    setSelectedRowKeys([]);
  };

  const onOfflineAll = async () => {
    const selectedRows = getSelectedRows();
    const runningRows = selectedRows.filter(isRunning);
    if (runningRows.length > 0) {
      message.warning(
        `存在运行中的任务，请先终止后再下线：${buildLimitedJobLabels(runningRows)}`
      );
      return;
    }

    const records = selectedRows.filter(isOnline);
    if (records.length === 0) {
      message.warning("所选任务已经全部下线");
      return;
    }

    const responses = await Promise.allSettled(
      records.map((record) => seatunnelJobDefinitionApi.offline(record.id))
    );
    const successCount = responses.filter(
      (item) => item.status === "fulfilled" && item.value?.code === 0
    ).length;
    const failedCount = responses.length - successCount;

    if (successCount > 0) {
      message.success(`批量下线完成：成功 ${successCount} 个`);
      fetchTaskList();
    }
    if (failedCount > 0) {
      message.error(`批量下线失败：${failedCount} 个`);
    }
    setSelectedRowKeys([]);
  };

  const onDeleteAll = () => {
    const selectedRows = getSelectedRows();
    if (selectedRows.length === 0) {
      message.warning("请先选择要删除的任务");
      return;
    }

    const blockedRows = selectedRows.filter(
      (record) => isOnline(record) || isRunning(record),
    );
    if (blockedRows.length > 0) {
      message.warning(batchActionState.deleteTooltip || "所选任务当前不可删除");
      return;
    }

    Modal.confirm({
      title: "确认批量删除任务？",
      centered: true,
      content: `将删除 ${selectedRows.length} 个离线任务定义及其历史记录，删除后不可恢复。`,
      okText: "删除",
      cancelText: "取消",
      okButtonProps: { danger: true },
      async onOk() {
        const responses = await Promise.allSettled(
          selectedRows.map((record) => seatunnelJobDefinitionApi.delete(String(record.id))),
        );
        const successCount = responses.filter(
          (item) => item.status === "fulfilled" && item.value?.code === 0,
        ).length;
        const failedCount = responses.length - successCount;

        if (successCount > 0) {
          message.success(`批量删除完成：成功 ${successCount} 个`);
          setSelectedRowKeys([]);
          const shouldTurnPage =
            successCount === taskList.length && pagination.current > 1;
          if (shouldTurnPage) {
            setPagination((previous) => ({
              ...previous,
              current: previous.current - 1,
            }));
          } else {
            fetchTaskList();
          }
        }
        if (failedCount > 0) {
          message.error(`批量删除失败：${failedCount} 个`);
        }
      },
    });
  };

  const openBatchCreate = () => {
    if (getSelectedRows().length === 0) {
      message.warning("请先选择一个或多个模板任务");
      return;
    }
    setBatchCreateOpen(true);
  };

  const handleBatchCreate = async (values: BatchCreateValues) => {
    const templates = getSelectedRows();
    if (templates.length === 0) {
      message.warning("请先选择一个或多个模板任务");
      return;
    }

    try {
      setBatchCreateLoading(true);
      const response: any = await seatunnelJobDefinitionApi.batchCreate({
        templateJobDefinitionIds: templates.map((item) => item.id),
        copiesPerTemplate: values.copiesPerTemplate,
        jobNamePrefix: values.jobNamePrefix,
      });

      if (response?.code !== 0) {
        message.error(response?.message || response?.msg || "批量创建失败");
        return;
      }

      const createdCount = response?.data?.createdCount || 0;
      message.success(`批量创建完成：成功创建 ${createdCount} 个任务`);
      setBatchCreateOpen(false);
      setSelectedRowKeys([]);
      fetchTaskList();
    } catch (error: any) {
      message.error(getErrorMessage(error, "批量创建失败"));
    } finally {
      setBatchCreateLoading(false);
    }
  };

  return (
    <>
      <div className="batch-link-up-page sync-task-list">
        <div className="config-manage-page">
          <div className="operate-bar task-search-wrap">
            <div className="left">
              <AdvancedSearchForm
                onSearch={handleSearch}
                onReset={handleReset}
                initialValues={searchParams}
                fileMode={mode === "FILE_SYNC"}
                sortControls={
                  <TaskSortControls
                    field={sort.field}
                    order={sort.order}
                    onChange={handleSortChange}
                  />
                }
              />
            </div>
          </div>

          <Divider style={{ margin: "16px 0" }} />

          <div className="task-table-shell">
          <Table
            columns={baseColumns as any}
            dataSource={taskList}
            rowKey="id"
            pagination={false}
            loading={loading}
            rowSelection={{ type: "checkbox", ...rowSelection }}
            scroll={{ x: "max-content", y: "calc(100vh - 380px)" }}
            className="task-table"
            locale={{
              emptyText: (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={emptyDescription}
                />
              ),
            }}
          />
          </div>
        </div>
      </div>

      <BottomActionBar
        onStart={onStartAll}
        onStop={onTerminateAll}
        onOnline={onOnlineAll}
        onOffline={onOfflineAll}
        onDelete={onDeleteAll}
        onCreate={openBatchCreate}
        pagination={{
          ...pagination,
          onChange: handlePaginationChange,
        }}
        selectedCount={selectedRowKeys.length}
        disabled={!hasSelected}
        startDisabled={batchActionState.startDisabled}
        stopDisabled={batchActionState.stopDisabled}
        startTooltip={batchActionState.startTooltip}
        stopTooltip={batchActionState.stopTooltip}
        onlineDisabled={batchActionState.onlineDisabled}
        offlineDisabled={batchActionState.offlineDisabled}
        onlineTooltip={batchActionState.onlineTooltip}
        offlineTooltip={batchActionState.offlineTooltip}
        deleteDisabled={batchActionState.deleteDisabled}
        deleteTooltip={batchActionState.deleteTooltip}
      />

      <BatchCreateJobModal
        open={batchCreateOpen}
        loading={batchCreateLoading}
        templates={getSelectedRows()}
        onCancel={() => setBatchCreateOpen(false)}
        onSubmit={handleBatchCreate}
      />
    </>
  );
};

export default App;
