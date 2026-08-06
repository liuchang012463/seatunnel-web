import { useIntl } from '@umijs/max';
import {
  Button,
  Empty,
  Select,
  Table,
  Tooltip,
} from 'antd';
import type {
  ColumnsType,
  TablePaginationConfig,
} from 'antd/es/table';
import {
  BellRing,
  CheckCircle2,
  Clock3,
  MessageSquareText,
  RefreshCw,
  RotateCcw,
  XCircle,
} from 'lucide-react';
import React, {
  useCallback,
  useEffect,
  useMemo,
  useState,
} from 'react';
import {
  fetchAlarmRecords,
  fetchChannels,
} from '../service';
import type {
  AlarmChannelRecord,
  AlarmRecordRecord,
} from '../types';
import { formatTime } from '../utils';

const DEFAULT_PAGE_SIZE = 10;

interface RecordFilters {
  channelType?: string;
  severity?: string;
  success?: number;
}

interface DisplayStyle {
  label: string;
  className: string;
}

/**
 * 严重级别样式。
 */
const SEVERITY_STYLE_MAP: Record<
  string,
  DisplayStyle
> = {
  INFO: {
    label: '信息',
    className: 'alarm-record-badge--info',
  },
  WARN: {
    label: '警告',
    className: 'alarm-record-badge--warning',
  },
  CRITICAL: {
    label: '严重',
    className: 'alarm-record-badge--critical',
  },
};

/**
 * 任务状态样式。
 */
const STATUS_STYLE_MAP: Record<
  string,
  DisplayStyle
> = {
  CREATED: {
    label: '已创建',
    className: 'alarm-record-badge--neutral',
  },
  SCHEDULED: {
    label: '等待调度',
    className: 'alarm-record-badge--info',
  },
  PENDING: {
    label: '等待运行',
    className: 'alarm-record-badge--warning',
  },
  INITIALIZING: {
    label: '初始化中',
    className: 'alarm-record-badge--info',
  },
  RUNNING: {
    label: '运行中',
    className: 'alarm-record-badge--info',
  },
  FINISHED: {
    label: '已完成',
    className: 'alarm-record-badge--success',
  },
  FAILED: {
    label: '运行失败',
    className: 'alarm-record-badge--critical',
  },
  FAILING: {
    label: '失败处理中',
    className: 'alarm-record-badge--critical',
  },
  CANCELED: {
    label: '已取消',
    className: 'alarm-record-badge--neutral',
  },
  CANCELING: {
    label: '取消中',
    className: 'alarm-record-badge--warning',
  },
  DOING_SAVEPOINT: {
    label: '保存点处理中',
    className: 'alarm-record-badge--warning',
  },
  SAVEPOINT_DONE: {
    label: '保存点完成',
    className: 'alarm-record-badge--success',
  },
  UNKNOWABLE: {
    label: '未知状态',
    className: 'alarm-record-badge--neutral',
  },
};

const DEFAULT_STATUS_STYLE: DisplayStyle = {
  label: '未知状态',
  className: 'alarm-record-badge--neutral',
};

const RecordTab: React.FC = () => {
  const intl = useIntl();

  const [loading, setLoading] = useState(false);

  const [records, setRecords] = useState<
    AlarmRecordRecord[]
  >([]);

  const [pageNo, setPageNo] = useState(1);

  const [pageSize, setPageSize] = useState(
    DEFAULT_PAGE_SIZE,
  );

  const [total, setTotal] = useState(0);

  const [channelTypes, setChannelTypes] =
    useState<string[]>([]);

  /**
   * 页面当前选择的筛选条件。
   * 点击“筛选”后才提交给接口。
   */
  const [filterValues, setFilterValues] =
    useState<RecordFilters>({});

  /**
   * 已经提交给接口的筛选条件。
   * 翻页和刷新时继续使用该条件。
   */
  const [appliedFilters, setAppliedFilters] =
    useState<RecordFilters>({});

  /**
   * 查询告警记录。
   */
  const fetchRecords = useCallback(
    async (
      page: number,
      size: number,
      filters: RecordFilters,
    ) => {
      setLoading(true);

      try {
        const res = await fetchAlarmRecords({
          pageNo: page,
          pageSize: size,
          channelType: filters.channelType,
          severity: filters.severity,
          success: filters.success,
        });

        if (res?.code === 0 && res.data) {
          setRecords(
            Array.isArray(res.data.list)
              ? res.data.list
              : [],
          );

          setTotal(res.data.total || 0);
          return;
        }

        setRecords([]);
        setTotal(0);
      } catch {
        setRecords([]);
        setTotal(0);
      } finally {
        setLoading(false);
      }
    },
    [],
  );

  /**
   * 查询已有告警通道类型，作为筛选选项。
   */
  const fetchChannelTypes =
    useCallback(async () => {
      try {
        const res = await fetchChannels();

        if (
          res?.code !== 0 ||
          !Array.isArray(res.data)
        ) {
          setChannelTypes([]);
          return;
        }

        const types = Array.from(
          new Set(
            res.data
              .map(
                (
                  channel: AlarmChannelRecord,
                ) => channel.channelType,
              )
              .filter(
                (
                  channelType,
                ): channelType is string =>
                  Boolean(channelType),
              ),
          ),
        );

        setChannelTypes(types);
      } catch {
        setChannelTypes([]);
      }
    }, []);

  useEffect(() => {
    void fetchChannelTypes();

    void fetchRecords(
      1,
      DEFAULT_PAGE_SIZE,
      {},
    );
  }, [fetchChannelTypes, fetchRecords]);

  /**
   * 当前筛选控件是否存在值。
   */
  const hasFilterValues = useMemo(
    () =>
      filterValues.channelType != null ||
      filterValues.severity != null ||
      filterValues.success != null,
    [filterValues],
  );

  /**
   * 当前接口查询是否应用了筛选条件。
   */
  const hasAppliedFilters = useMemo(
    () =>
      appliedFilters.channelType != null ||
      appliedFilters.severity != null ||
      appliedFilters.success != null,
    [appliedFilters],
  );

  /**
   * 提交筛选。
   */
  const handleFilter = () => {
    const nextFilters: RecordFilters = {
      ...filterValues,
    };

    setAppliedFilters(nextFilters);
    setPageNo(1);

    void fetchRecords(
      1,
      pageSize,
      nextFilters,
    );
  };

  /**
   * 重置筛选条件。
   */
  const handleReset = () => {
    setFilterValues({});
    setAppliedFilters({});
    setPageNo(1);

    void fetchRecords(1, pageSize, {});
  };

  /**
   * 刷新当前页。
   */
  const handleRefresh = () => {
    void fetchRecords(
      pageNo,
      pageSize,
      appliedFilters,
    );
  };

  /**
   * 处理服务端分页。
   */
  const handleTableChange = (
    pagination: TablePaginationConfig,
  ) => {
    const nextPageSize =
      pagination.pageSize ||
      DEFAULT_PAGE_SIZE;

    const pageSizeChanged =
      nextPageSize !== pageSize;

    const nextPage = pageSizeChanged
      ? 1
      : pagination.current || 1;

    setPageNo(nextPage);
    setPageSize(nextPageSize);

    void fetchRecords(
      nextPage,
      nextPageSize,
      appliedFilters,
    );
  };

  /**
   * 表格列。
   */
  const columns = useMemo<
    ColumnsType<AlarmRecordRecord>
  >(
    () => [
      {
        title: '告警信息',
        key: 'alarmInfo',
        width: 350,
        render: (_, record) => {
          const jobTitle =
            record.jobName ||
            (record.jobInstanceId
              ? `任务实例 #${record.jobInstanceId}`
              : '未知任务');

          return (
            <div className="flex min-w-0 items-center gap-3">
              <div
                className={[
                  'flex h-9 w-9 shrink-0',
                  'items-center justify-center',
                  'rounded-lg',
                  'alarm-record-icon',
                  record.success === 1
                    ? 'alarm-record-icon--success'
                    : 'alarm-record-icon--failure',
                ].join(' ')}
              >
                {record.success === 1 ? (
                  <CheckCircle2 className="h-[18px] w-[18px]" />
                ) : (
                  <XCircle className="h-[18px] w-[18px]" />
                )}
              </div>

              <div className="min-w-0 flex-1">
                <Tooltip
                  title={jobTitle}
                  placement="topLeft"
                >
                  <p className="alarm-record-job-title m-0 truncate text-sm font-semibold">
                    {jobTitle}
                  </p>
                </Tooltip>

                <Tooltip
                  title={
                    record.content || undefined
                  }
                  placement="topLeft"
                >
                  <p className="alarm-record-job-content m-0 mt-1 truncate text-xs leading-5">
                    {record.content ||
                      '暂无告警内容'}
                  </p>
                </Tooltip>
              </div>
            </div>
          );
        },
      },
      {
        title: '任务状态',
        dataIndex: 'newStatus',
        key: 'newStatus',
        width: 130,
        render: (status?: string) => {
          if (!status) {
            return (
              <span className="alarm-record-empty text-xs">
                -
              </span>
            );
          }

          const statusStyle =
            STATUS_STYLE_MAP[status] || {
              ...DEFAULT_STATUS_STYLE,
              label: status,
            };

          return (
            <span
              className={[
                'inline-flex rounded-md',
                'alarm-record-badge',
                'px-2 py-1',
                'text-[11px] font-medium',
                statusStyle.className,
              ].join(' ')}
            >
              {statusStyle.label}
            </span>
          );
        },
      },
      {
        title: '严重级别',
        dataIndex: 'severity',
        key: 'severity',
        width: 110,
        render: (severity?: string) => {
          if (!severity) {
            return (
              <span className="alarm-record-empty text-xs">
                -
              </span>
            );
          }

          const severityStyle =
            SEVERITY_STYLE_MAP[severity];

          if (!severityStyle) {
            return (
              <span className="alarm-record-empty text-xs">
                {severity}
              </span>
            );
          }

          return (
            <span
              className={[
                'inline-flex rounded-full',
                'alarm-record-badge',
                'px-2 py-0.5',
                'text-[11px] font-medium',
                severityStyle.className,
              ].join(' ')}
            >
              {severityStyle.label}
            </span>
          );
        },
      },
      {
        title: '告警通道',
        dataIndex: 'channelType',
        key: 'channelType',
        width: 150,
        render: (channelType?: string) => (
          <span className="alarm-record-channel inline-flex max-w-full items-center gap-1.5 text-xs">
            <BellRing className="alarm-record-channel__icon h-3.5 w-3.5 shrink-0" />

            <Tooltip
              title={channelType || undefined}
            >
              <span className="truncate">
                {channelType || '-'}
              </span>
            </Tooltip>
          </span>
        ),
      },
      {
        title: '投递结果',
        key: 'deliveryResult',
        width: 160,
        render: (_, record) => {
          const delivered =
            record.success === 1;

          const tooltipContent = (
            <div
              className="min-w-0 max-w-full overflow-hidden py-1"
              style={{
                width: '100%',
              }}
            >
              <div>
                <p className="m-0 text-xs font-medium text-white/60">
                  告警内容
                </p>

                <p
                  className="m-0 mt-1 max-h-[180px] overflow-x-hidden overflow-y-auto whitespace-pre-wrap break-words text-xs leading-5 text-white"
                  style={{
                    overflowWrap: 'anywhere',
                    wordBreak: 'break-word',
                  }}
                >
                  {record.content ||
                    '暂无告警内容'}
                </p>
              </div>

              {record.errorMessage && (
                <div className="mt-3 border-t border-white/15 pt-3">
                  <p className="m-0 text-xs font-medium text-white/60">
                    错误信息
                  </p>

                  <p
                    className="m-0 mt-1 max-h-[180px] overflow-x-hidden overflow-y-auto whitespace-pre-wrap break-words text-xs leading-5 text-red-200"
                    style={{
                      overflowWrap: 'anywhere',
                      wordBreak: 'break-word',
                    }}
                  >
                    {record.errorMessage}
                  </p>
                </div>
              )}
            </div>
          );

          return (
            <div className="flex items-center gap-2">
              <span
                className={[
                  'inline-flex items-center gap-1.5',
                  'alarm-record-delivery whitespace-nowrap text-xs font-medium',
                  delivered
                    ? 'alarm-record-delivery--success'
                    : 'alarm-record-delivery--failure',
                ].join(' ')}
              >
                <span
                  className={[
                    'h-1.5 w-1.5 rounded-full',
                    'alarm-record-delivery__indicator',
                    delivered
                      ? 'alarm-record-delivery__indicator--success'
                      : 'alarm-record-delivery__indicator--failure',
                  ].join(' ')}
                />

                {delivered
                  ? '投递成功'
                  : '投递失败'}
              </span>

              <Tooltip
                title={tooltipContent}
                placement="topRight"
                color="var(--st-color-bg-panel)"
                overlayStyle={{
                  maxWidth: 'calc(100vw - 24px)',
                }}
                overlayInnerStyle={{
                  width: 'min(360px, calc(100vw - 48px))',
                  maxWidth: 'calc(100vw - 48px)',
                  boxSizing: 'border-box',
                  overflow: 'hidden',
                  padding: '10px 12px',
                  borderRadius: 10,
                  border: '1px solid rgba(33, 135, 168, 0.72)',
                  boxShadow: '0 12px 28px rgba(0, 25, 34, 0.3)',
                }}
              >
                <button
                  type="button"
                  aria-label="查看告警详情"
                  className={[
                    'inline-flex h-7 w-7 shrink-0',
                    'items-center justify-center',
                    'alarm-record-detail-button rounded-md',
                    'transition-colors duration-200',
                    'focus-visible:outline-none',
                    'focus-visible:ring-2',
                    'focus-visible:ring-slate-300',
                  ].join(' ')}
                >
                  <MessageSquareText className="h-3.5 w-3.5" />
                </button>
              </Tooltip>
            </div>
          );
        },
      },
      {
        title: '发送时间',
        dataIndex: 'sentTime',
        key: 'sentTime',
        width: 180,
        render: (sentTime?: string) => (
          <span className="alarm-record-time inline-flex items-center gap-1.5 whitespace-nowrap text-xs">
            <Clock3 className="alarm-record-time__icon h-3.5 w-3.5 shrink-0" />

            {sentTime
              ? formatTime(sentTime)
              : '-'}
          </span>
        ),
      },
    ],
    [],
  );

  return (
    <div>
      {/* 顶部说明 */}
      <div className="mb-5 flex items-center justify-between gap-4">
        <div>
          <p className="m-0 text-sm font-medium text-slate-700">
            共 {total} 条告警记录
          </p>

          <p className="m-0 mt-1 text-xs text-slate-500">
            查看告警消息的触发状态与投递结果
          </p>
        </div>

        <Button
          icon={
            <RefreshCw className="h-4 w-4" />
          }
          loading={loading}
          onClick={handleRefresh}
          className="rounded-full"
        >
          刷新
        </Button>
      </div>

      {/* 告警记录表格 */}
      <div
        className={[
          'overflow-hidden rounded-xl',
          'bg-white',
        ].join(' ')}
      >
        <Table<AlarmRecordRecord>
          rowKey={(record) =>
            String(
              record.id ??
                `${record.jobInstanceId}-${record.sentTime}`,
            )
          }
          size="middle"
          tableLayout="fixed"
          sticky
          loading={loading}
          columns={columns}
          dataSource={records}
          scroll={{
            x: 1080,
            y: 'calc(100vh - 430px)',
          }}
          pagination={{
            current: pageNo,
            pageSize,
            total,
            showSizeChanger: true,
            pageSizeOptions: [
              10,
              20,
              50,
              100,
            ],
            showQuickJumper:
              total > pageSize * 3,
            showTotal: (value) =>
              intl.formatMessage(
                {
                  id: 'pages.alarm.pagination.total',
                  defaultMessage:
                    '共 {total} 条',
                },
                {
                  total: value,
                },
              ),
          }}
          locale={{
            emptyText: (
              <div className="py-12">
                <Empty
                  image={
                    Empty.PRESENTED_IMAGE_SIMPLE
                  }
                  description={
                    hasAppliedFilters
                      ? '没有找到符合条件的告警记录'
                      : '暂时还没有告警记录'
                  }
                />
              </div>
            ),
          }}
          onChange={handleTableChange}
          className={[
            'alarm-record-table',
            '[&_.ant-table]:bg-transparent',
            '[&_.ant-table-container]:border-0',

            '[&_.ant-table-thead>tr>th]:border-[#2187a8]',
            '[&_.ant-table-thead>tr>th]:bg-slate-50/95',
            '[&_.ant-table-thead>tr>th]:py-3.5',
            '[&_.ant-table-thead>tr>th]:text-xs',
            '[&_.ant-table-thead>tr>th]:font-medium',
            '[&_.ant-table-thead>tr>th]:text-white',

            '[&_.ant-table-tbody>tr>td]:border-[#2187a8]',
            '[&_.ant-table-tbody>tr>td]:py-3.5',
            '[&_.ant-table-tbody>tr>td]:text-white',
            '[&_.ant-table-tbody>tr>td]:transition-colors',

            '[&_.ant-table-cell]:align-middle',

            '[&_.ant-pagination]:mx-4',
            '[&_.ant-pagination]:my-4',

            '[&_.ant-table-body::-webkit-scrollbar]:h-2',
            '[&_.ant-table-body::-webkit-scrollbar]:w-2',
            '[&_.ant-table-body::-webkit-scrollbar-track]:bg-transparent',
            '[&_.ant-table-body::-webkit-scrollbar-thumb]:rounded-full',
            '[&_.ant-table-body::-webkit-scrollbar-thumb]:bg-[#2187a8]',
            '[&_.ant-table-body::-webkit-scrollbar-thumb:hover]:bg-[#4dd2ff]',
          ].join(' ')}
        />
      </div>
    </div>
  );
};

export default RecordTab;
