import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Alert, Button, Card, Empty, Spin, Table, Tag, Tooltip, message } from "antd";
import { useIntl } from "@umijs/max";
import CountUp from "react-countup";
import {
  ArrowDownToLine,
  ArrowRight,
  ArrowUpFromLine,
  Database,
  Gauge,
  Layers3,
  RefreshCw,
  Table2,
} from "lucide-react";
import { batchJobInstanceApi } from "../api";
import { normalizeJobStatus } from "@/pages/common/utils/jobStatus";
import { parseEngineOperatorMetrics, type EngineOperatorMetric } from "../utils/engineOperatorMetrics";


interface MetricsTabProps {
  instanceItem: any;
}

interface MetricCardProps {
  title: string;
  value: number;
  unit: string;
  hint?: string;
  icon: React.ReactNode;
  accentClassName: string;
}

const toNumber = (value: any) => {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n : 0;
};

const formatNumber = (value: any) => {
  const n = toNumber(value);
  return n.toLocaleString();
};

const formatDecimal = (value: any) => {
  const n = toNumber(value);

  if (n === 0) {
    return "0";
  }

  if (n < 1) {
    return n.toFixed(4);
  }

  return n.toFixed(2).replace(/\.?0+$/, "");
};

const getShortTableName = (table?: string) => {
  if (!table) {
    return "-";
  }

  const parts = String(table).split(".");
  return parts[parts.length - 1] || table;
};

const getStatusMeta = (status?: string) => {
  const value = String(status || "UNKNOWN").toUpperCase();

  if (value === "NORMAL") {
    return {
      color: "success",
      text: "正常",
    };
  }

  if (value === "LAGGING") {
    return {
      color: "warning",
      text: "写入滞后",
    };
  }

  if (value === "IDLE") {
    return {
      color: "default",
      text: "暂无数据",
    };
  }

  if (value === "FAILED") {
    return {
      color: "error",
      text: "失败",
    };
  }

  return {
    color: "default",
    text: "未知",
  };
};

const getResponseData = (res: any) => {
  if (!res) {
    return [];
  }

  if (Array.isArray(res)) {
    return res;
  }

  if (Array.isArray(res.data)) {
    return res.data;
  }

  if (Array.isArray(res?.data?.data)) {
    return res.data.data;
  }

  return [];
};

const MetricCard: React.FC<MetricCardProps> = ({
  title,
  value,
  unit,
  hint,
  icon,
  accentClassName,
}) => {
  return (
    <div className="group rounded-2xl border border-slate-200 bg-white p-4 shadow-[0_1px_3px_rgba(15,23,42,0.04)] transition-all duration-200 hover:border-slate-300 hover:shadow-[0_10px_28px_rgba(15,23,42,0.07)]">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="text-xs font-medium uppercase tracking-[0.08em] text-slate-600">
            {title}
          </div>

          <div className="mt-3 flex items-end gap-1.5">
            <span className="text-[28px] font-semibold leading-none tracking-[-0.04em] text-slate-950">
              <CountUp end={value} duration={1.1} separator="," decimals={0} />
            </span>
            <span className="pb-0.5 text-xs font-medium text-slate-600">
              {unit}
            </span>
          </div>
        </div>

        <div
          className={[
            "flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border",
            accentClassName,
          ].join(" ")}
        >
          {icon}
        </div>
      </div>

      {hint ? (
        <div className="mt-4 flex justify-end">
          <div className="inline-flex items-center gap-1 rounded-full bg-slate-50 px-2 py-1 text-[11px] font-medium text-slate-500">
            {hint}
          </div>
        </div>
      ) : null}
    </div>
  );
};

const SectionHeader: React.FC<{
  title: string;
  description: string;
  icon: React.ReactNode;
  extra?: React.ReactNode;
}> = ({ title, description, icon, extra }) => {
  return (
    <div className="mb-3 flex items-center justify-between gap-4">
      <div className="flex items-center gap-2.5">
        <div className="flex h-8 w-8 items-center justify-center rounded-xl border border-slate-200 bg-slate-50 text-slate-500">
          {icon}
        </div>

        <div>
          <div className="text-sm font-semibold text-slate-900">{title}</div>
          <div className="mt-0.5 text-xs text-slate-500">{description}</div>
        </div>
      </div>

      {extra}
    </div>
  );
};

const MetricsTab: React.FC<MetricsTabProps> = ({ instanceItem }) => {
  const intl = useIntl();

  const [tableMetrics, setTableMetrics] = useState<any[]>([]);
  const [tableMetricsLoading, setTableMetricsLoading] = useState(false);
  const [operatorMetrics, setOperatorMetrics] = useState<EngineOperatorMetric[]>([]);
  const [operatorMetricsLoading, setOperatorMetricsLoading] = useState(false);
  const [operatorMetricsError, setOperatorMetricsError] = useState<string>();
  const operatorMetricsInFlight = useRef(false);
  const operatorMetricsRequest = useRef(0);

  const t = (id: string, defaultMessage: string) =>
    intl.formatMessage({ id, defaultMessage });

  const instanceId = instanceItem?.id;
  const normalizedStatus = normalizeJobStatus(instanceItem?.jobStatus);
  const isTerminal = normalizedStatus === "FINISHED" || normalizedStatus === "FAILED";
  // STARTING displays as RUNNING while the submit/recovery path settles the Engine result.
  // Operator metrics only exist after Engine has accepted the job.
  const isEngineActive = [
    "INITIALIZING",
    "CREATED",
    "PENDING",
    "SCHEDULED",
    "RUNNING",
    "FAILING",
    "DOING_SAVEPOINT",
    "CANCELING",
  ].includes(String(instanceItem?.jobStatus || "").toUpperCase());

  const readQps = toNumber(instanceItem?.readQps);
  const writeQps = toNumber(instanceItem?.writeQps);
  const readRows = toNumber(instanceItem?.readRowCount);
  const writeRows = toNumber(instanceItem?.writeRowCount);
  const readQpsTitle = isTerminal
    ? t("pages.job.detail.metrics.averageReadQps", "平均读取 QPS")
    : t("pages.job.detail.metrics.readQps", "读取 QPS");
  const writeQpsTitle = isTerminal
    ? t("pages.job.detail.metrics.averageWriteQps", "平均写入 QPS")
    : t("pages.job.detail.metrics.writeQps", "写入 QPS");

  useEffect(() => {
    let cancelled = false;

    const loadTableMetrics = async () => {
      if (!instanceId) {
        setTableMetrics([]);
        return;
      }

      setTableMetricsLoading(true);

      try {
        const res = await batchJobInstanceApi.tableMetrics(instanceId);
        const list = getResponseData(res);

        if (!cancelled) {
          setTableMetrics(list);
        }
      } catch (error) {
        if (!cancelled) {
          setTableMetrics([]);
          message.warning("表级指标加载失败，请稍后重试");
        }
      } finally {
        if (!cancelled) {
          setTableMetricsLoading(false);
        }
      }
    };

    loadTableMetrics();

    return () => {
      cancelled = true;
    };
  }, [instanceId]);

  const loadOperatorMetrics = useCallback(async (showLoading = false) => {
    if (!instanceId || operatorMetricsInFlight.current) return;

    operatorMetricsInFlight.current = true;
    const requestId = ++operatorMetricsRequest.current;
    if (showLoading) setOperatorMetricsLoading(true);
    setOperatorMetricsError(undefined);

    try {
      const response = await batchJobInstanceApi.engineInfo(instanceId);
      if (requestId !== operatorMetricsRequest.current) return;

      if (response?.code !== 0) {
        setOperatorMetricsError(response?.message || response?.msg || "读取算子指标失败");
        setOperatorMetrics([]);
        return;
      }

      if (response.data?.engineAvailable === false) {
        setOperatorMetricsError(
          response.data?.engineJobId
            ? "SeaTunnel Engine 暂时不可用，无法读取算子指标"
            : "任务尚未提交到 SeaTunnel Engine，暂无算子指标",
        );
        setOperatorMetrics([]);
        return;
      }

      setOperatorMetrics(parseEngineOperatorMetrics(response.data));
    } catch (error: any) {
      if (requestId === operatorMetricsRequest.current) {
        setOperatorMetricsError(
          error?.response?.data?.message || error?.message || "读取算子指标失败",
        );
        setOperatorMetrics([]);
      }
    } finally {
      operatorMetricsInFlight.current = false;
      if (showLoading) setOperatorMetricsLoading(false);
    }
  }, [instanceId]);

  useEffect(() => {
    let cancelled = false;
    let timer: number | undefined;
    let firstLoad = true;

    const refresh = async () => {
      await loadOperatorMetrics(firstLoad);
      firstLoad = false;
      if (!cancelled && isEngineActive) {
        timer = window.setTimeout(refresh, 5000);
      }
    };

    void refresh();

    return () => {
      cancelled = true;
      if (timer !== undefined) window.clearTimeout(timer);
      operatorMetricsRequest.current += 1;
    };
  }, [isEngineActive, loadOperatorMetrics]);

  const tableColumns = useMemo(
    () => [
      {
        title: "来源表",
        dataIndex: "sourceTable",
        key: "sourceTable",
        ellipsis: true,
        render: (value: string) => (
          <Tooltip title={value}>
            <div className="flex min-w-0 items-center gap-2">
              <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-blue-100 bg-blue-50 text-blue-600">
                <Table2 size={14} strokeWidth={1.9} />
              </span>

              <div className="min-w-0">
                <div className="truncate text-xs font-medium text-slate-800">
                  {getShortTableName(value)}
                </div>
                <div className="truncate text-[11px] text-slate-500">
                  {value || "-"}
                </div>
              </div>
            </div>
          </Tooltip>
        ),
      },
      {
        title: "",
        key: "arrow",
        width: 44,
        align: "center" as const,
        render: () => (
          <span className="inline-flex h-7 w-7 items-center justify-center rounded-full bg-slate-50 text-slate-400">
            <ArrowRight size={14} strokeWidth={1.9} />
          </span>
        ),
      },
      {
        title: "目标表",
        dataIndex: "sinkTable",
        key: "sinkTable",
        ellipsis: true,
        render: (value: string) => (
          <Tooltip title={value}>
            <div className="flex min-w-0 items-center gap-2">
              <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-emerald-100 bg-emerald-50 text-emerald-600">
                <Table2 size={14} strokeWidth={1.9} />
              </span>

              <div className="min-w-0">
                <div className="truncate text-xs font-medium text-slate-800">
                  {getShortTableName(value)}
                </div>
                <div className="truncate text-[11px] text-slate-500">
                  {value || "-"}
                </div>
              </div>
            </div>
          </Tooltip>
        ),
      },
      {
        title: "读取行数",
        dataIndex: "readRowCount",
        key: "readRowCount",
        width: 110,
        align: "right" as const,
        render: (value: any) => (
          <span className="font-medium text-slate-800">
            {formatNumber(value)}
          </span>
        ),
      },
      {
        title: "写入行数",
        dataIndex: "writeRowCount",
        key: "writeRowCount",
        width: 110,
        align: "right" as const,
        render: (value: any) => (
          <span className="font-medium text-slate-800">
            {formatNumber(value)}
          </span>
        ),
      },
      {
        title: readQpsTitle,
        dataIndex: "readQps",
        key: "readQps",
        width: 100,
        align: "right" as const,
        render: (value: any) => (
          <span className="text-slate-600">{formatDecimal(value)}</span>
        ),
      },
      {
        title: writeQpsTitle,
        dataIndex: "writeQps",
        key: "writeQps",
        width: 100,
        align: "right" as const,
        render: (value: any) => (
          <span className="text-slate-600">{formatDecimal(value)}</span>
        ),
      },
      {
        title: "状态",
        dataIndex: "status",
        key: "status",
        width: 96,
        render: (value: string) => {
          const meta = getStatusMeta(value);
          return <Tag color={meta.color}>{meta.text}</Tag>;
        },
      },
    ],
    [readQpsTitle, writeQpsTitle]
  );

  const operatorMetricColumns = useMemo(
    () => [
      {
        title: "名称",
        dataIndex: "name",
        key: "name",
        fixed: "left" as const,
        width: 260,
        render: (value: string) => (
          <Tooltip title={value}>
            <span className="block max-w-[250px] truncate font-medium text-slate-800">
              {value}
            </span>
          </Tooltip>
        ),
      },
      {
        title: "接收字节",
        dataIndex: "receivedBytes",
        key: "receivedBytes",
        width: 120,
        align: "right" as const,
        render: (value?: number) => value == null ? "-" : formatNumber(value),
      },
      {
        title: "写入字节",
        dataIndex: "writeBytes",
        key: "writeBytes",
        width: 120,
        align: "right" as const,
        render: (value?: number) => value == null ? "-" : formatNumber(value),
      },
      {
        title: "接收数量",
        dataIndex: "receivedCount",
        key: "receivedCount",
        width: 120,
        align: "right" as const,
        render: (value?: number) => value == null ? "-" : formatNumber(value),
      },
      {
        title: "写入数量",
        dataIndex: "writeCount",
        key: "writeCount",
        width: 120,
        align: "right" as const,
        render: (value?: number) => value == null ? "-" : formatNumber(value),
      },
      {
        title: "接收 QPS",
        dataIndex: "receivedQps",
        key: "receivedQps",
        width: 110,
        align: "right" as const,
        render: (value?: number) => value == null ? "-" : formatDecimal(value),
      },
      {
        title: "写入 QPS",
        dataIndex: "writeQps",
        key: "writeQps",
        width: 110,
        align: "right" as const,
        render: (value?: number) => value == null ? "-" : formatDecimal(value),
      },
      {
        title: "接收字节/秒",
        dataIndex: "receivedBytesPerSecond",
        key: "receivedBytesPerSecond",
        width: 140,
        align: "right" as const,
        render: (value?: number) => value == null ? "-" : formatDecimal(value),
      },
      {
        title: "写入字节/秒",
        dataIndex: "writeBytesPerSecond",
        key: "writeBytesPerSecond",
        width: 140,
        align: "right" as const,
        render: (value?: number) => value == null ? "-" : formatDecimal(value),
      },
      {
        title: "Flush Signal QPS",
        dataIndex: "flushSignalQps",
        key: "flushSignalQps",
        width: 150,
        align: "right" as const,
        render: (value?: number) => value == null ? "-" : formatDecimal(value),
      },
    ],
    [],
  );

  return (
    <Card
      size="small"
      className="mt-2 !rounded-2xl !border-slate-200 !shadow-[0_1px_3px_rgba(15,23,42,0.04)]"
      bodyStyle={{ padding: 16 }}
    >
      <div className="space-y-6" >
        <section>
          <SectionHeader
            icon={<Gauge size={16} strokeWidth={1.9} />}
            title={t("pages.job.detail.metrics.throughput", "吞吐速率")}
            description={isTerminal
              ? t(
                  "pages.job.detail.metrics.completedThroughputDesc",
                  "任务结束后按累计处理行数和运行时长计算的平均速度"
                )
              : t(
                  "pages.job.detail.metrics.throughputDesc",
                  "当前实例的读取与写入处理速度"
                )}
          />

          <div className="grid grid-cols-1 gap-3 xl:grid-cols-2">
            <MetricCard
              title={readQpsTitle}
              value={readQps}
              unit={t("pages.job.detail.metrics.unit.rowsPerSecond", "行/秒")}
              icon={<ArrowDownToLine size={17} strokeWidth={1.9} />}
              accentClassName="border-blue-100 bg-blue-50 text-blue-600"
            />

            <MetricCard
              title={writeQpsTitle}
              value={writeQps}
              unit={t("pages.job.detail.metrics.unit.rowsPerSecond", "行/秒")}
              icon={<ArrowUpFromLine size={17} strokeWidth={1.9} />}
              accentClassName="border-emerald-100 bg-emerald-50 text-emerald-600"
            />
          </div>
        </section>

        <section>
          <SectionHeader
            icon={<Database size={16} strokeWidth={1.9} />}
            title={t("pages.job.detail.metrics.totalVolume", "同步总量")}
            description={t(
              "pages.job.detail.metrics.totalVolumeDesc",
              "当前运行实例累计处理的数据量"
            )}
          />

          <div className="grid grid-cols-1 gap-3 xl:grid-cols-2">
            <MetricCard
              title={t("pages.job.detail.metrics.readRows", "读取行数")}
              value={readRows}
              unit={t("pages.job.detail.metrics.unit.records", "行")}
              hint={t("pages.job.detail.metrics.accumulated", "累计")}
              icon={<ArrowDownToLine size={17} strokeWidth={1.9} />}
              accentClassName="border-amber-100 bg-amber-50 text-amber-600"
            />

            <MetricCard
              title={t("pages.job.detail.metrics.writeRows", "写入行数")}
              value={writeRows}
              unit={t("pages.job.detail.metrics.unit.records", "行")}
              hint={t("pages.job.detail.metrics.accumulated", "累计")}
              icon={<ArrowUpFromLine size={17} strokeWidth={1.9} />}
              accentClassName="border-violet-100 bg-violet-50 text-violet-600"
            />
          </div>
        </section>

        <section>
          <SectionHeader
            icon={<Layers3 size={16} strokeWidth={1.9} />}
            title="算子指标"
            description="查看 Source 与 Sink 算子的处理量和吞吐速率"
            extra={
              <Button
                size="small"
                icon={<RefreshCw size={14} />}
                loading={operatorMetricsLoading}
                onClick={() => void loadOperatorMetrics(true)}
              >
                刷新
              </Button>
            }
          />

          {operatorMetricsError && (
            <Alert
              className="mb-3"
              type="warning"
              showIcon
              message="算子指标加载失败"
              description={operatorMetricsError}
            />
          )}

          <div className="rounded-2xl border border-slate-200 bg-white p-2 shadow-[0_1px_3px_rgba(15,23,42,0.04)]">
            <Spin spinning={operatorMetricsLoading}>
              <Table
                size="small"
                rowKey="key"
                columns={operatorMetricColumns}
                dataSource={operatorMetrics}
                pagination={false}
                scroll={{ x: 1320 }}
                locale={{
                  emptyText: (
                    <Empty
                      image={Empty.PRESENTED_IMAGE_SIMPLE}
                      description={instanceItem?.engineJobId ? "暂无算子指标" : "等待 Engine 作业指标"}
                    />
                  ),
                }}
              />
            </Spin>
          </div>
        </section>

        <section>
          <SectionHeader
            icon={<Layers3 size={16} strokeWidth={1.9} />}
            title="表级同步明细"
            description="查看当前运行实例的来源表与目标表同步情况"
            extra={
              <div className="rounded-full bg-slate-50 px-2.5 py-1 text-xs font-medium text-slate-500">
                {tableMetricsLoading ? "加载中" : `${tableMetrics.length} 张表`}
              </div>
            }
          />

          <div className="rounded-2xl border border-slate-200 bg-white p-2 shadow-[0_1px_3px_rgba(15,23,42,0.04)]">
            <Spin spinning={tableMetricsLoading}>
              <Table
                size="small"
                rowKey={(record: any, index) =>
                  record.id ||
                  `${record.sourceTable || "source"}-${
                    record.sinkTable || "sink"
                  }-${index}`
                }
                columns={tableColumns}
                dataSource={tableMetrics}
                pagination={false}
                scroll={{ x: 980 }}
                locale={{
                  emptyText: (
                    <Empty
                      image={Empty.PRESENTED_IMAGE_SIMPLE}
                      description="暂无表级指标"
                    />
                  ),
                }}
              />
            </Spin>
          </div>
        </section>
      </div>
    </Card>
  );
};

export default MetricsTab;
