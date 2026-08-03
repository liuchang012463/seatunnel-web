import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import {
  Button,
  Empty,
  message,
  Modal,
  Spin,
  Switch,
  Tooltip,
} from 'antd';
import {
  ArrowRight,
  BellRing,
  Clock3,
  ShieldAlert,
  Target,
} from 'lucide-react';
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { SEVERITY_CONFIG } from '../constants';
import {
  deleteRule,
  fetchAllJobDefinitions,
  fetchAllRuleChannels,
  fetchChannels,
  fetchRuleChannels,
  fetchRules,
  saveRule,
} from '../service';
import {
  AlarmOperateType,
  type AlarmChannelRecord,
  type AlarmModalRef,
  type AlarmRuleRecord,
} from '../types';
import { formatTime } from '../utils';
import AddOrEditRuleModal from './AddOrEditRuleModal';

const { confirm } = Modal;

interface RuleTabProps {
  keyword?: string;
}

interface RuleChannelLink {
  ruleId: number;
  channelId: number;
}

/**
 * 将逗号分隔的字符串转换为字符串数组。
 */
function parseStringList(value?: string): string[] {
  if (!value) {
    return [];
  }

  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

/**
 * 将逗号分隔的 ID 转换为数字数组。
 */
function parseNumberList(value?: string): number[] {
  return parseStringList(value)
    .map(Number)
    .filter(Number.isFinite);
}

/**
 * 构建规则与通道的关联映射。
 */
function buildRuleChannelMap(
  links: RuleChannelLink[],
): Record<number, number[]> {
  const result: Record<number, number[]> = {};

  links.forEach((link) => {
    if (!result[link.ruleId]) {
      result[link.ruleId] = [];
    }

    result[link.ruleId].push(link.channelId);
  });

  return result;
}

const RuleTab: React.FC<RuleTabProps> = ({
  keyword = '',
}) => {
  const intl = useIntl();
  const drawerRef = useRef<AlarmModalRef>(null);

  const [loading, setLoading] = useState(false);
  const [togglingId, setTogglingId] = useState<
    number | null
  >(null);

  const [ruleList, setRuleList] = useState<
    AlarmRuleRecord[]
  >([]);

  const [channelList, setChannelList] = useState<
    AlarmChannelRecord[]
  >([]);

  const [jobNameMap, setJobNameMap] = useState<
    Record<number, string>
  >({});

  const [ruleChannelMap, setRuleChannelMap] =
    useState<Record<number, number[]>>({});

  /**
   * 通道 ID -> 通道信息。
   */
  const channelMap = useMemo(() => {
    const result: Record<
      number,
      AlarmChannelRecord
    > = {};

    channelList.forEach((channel) => {
      if (channel.id != null) {
        result[channel.id] = channel;
      }
    });

    return result;
  }, [channelList]);

  /**
   * 查询规则列表。
   */
  const fetchList = useCallback(async () => {
    setLoading(true);

    try {
      const res = await fetchRules();

      if (
        res?.code === 0 &&
        Array.isArray(res.data)
      ) {
        setRuleList(res.data);
      } else {
        setRuleList([]);
      }
    } catch {
      setRuleList([]);
    } finally {
      setLoading(false);
    }
  }, []);

  /**
   * 查询任务定义、告警通道和规则关联关系。
   */
  const fetchSupportData = useCallback(async () => {
    const [
      channelsRes,
      jobDefinitions,
      ruleChannelsRes,
    ] = await Promise.all([
      fetchChannels().catch(() => null),
      fetchAllJobDefinitions().catch(() => []),
      fetchAllRuleChannels().catch(() => null),
    ]);

    if (
      channelsRes?.code === 0 &&
      Array.isArray(channelsRes.data)
    ) {
      setChannelList(channelsRes.data);
    }

    const nextJobNameMap: Record<number, string> =
      {};

    jobDefinitions.forEach((job) => {
      nextJobNameMap[job.id] = job.jobName;
    });

    setJobNameMap(nextJobNameMap);

    if (
      ruleChannelsRes?.code === 0 &&
      Array.isArray(ruleChannelsRes.data)
    ) {
      setRuleChannelMap(
        buildRuleChannelMap(
          ruleChannelsRes.data,
        ),
      );
    }
  }, []);

  /**
   * 单独刷新规则和通道关联关系。
   */
  const fetchRuleChannelLinks =
    useCallback(async () => {
      try {
        const res = await fetchAllRuleChannels();

        if (
          res?.code === 0 &&
          Array.isArray(res.data)
        ) {
          setRuleChannelMap(
            buildRuleChannelMap(res.data),
          );
        }
      } catch {
        // 关联信息加载失败不影响规则列表展示
      }
    }, []);

  useEffect(() => {
    void fetchList();
    void fetchSupportData();
  }, [fetchList, fetchSupportData]);

  /**
   * 支持外部关键词过滤。
   */
  const filteredList = useMemo(() => {
    const normalizedKeyword = keyword
      .trim()
      .toLowerCase();

    if (!normalizedKeyword) {
      return ruleList;
    }

    return ruleList.filter((rule) => {
      const targetJobNames = parseNumberList(
        rule.targetJobs,
      ).map(
        (id) => jobNameMap[id] || String(id),
      );

      const channelNames =
        rule.id != null
          ? (
              ruleChannelMap[rule.id] || []
            ).map(
              (id) =>
                channelMap[id]?.name ||
                String(id),
            )
          : [];

      const searchableContent = [
        rule.name,
        rule.description,
        rule.severity,
        rule.triggerStatuses,
        ...targetJobNames,
        ...channelNames,
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();

      return searchableContent.includes(
        normalizedKeyword,
      );
    });
  }, [
    channelMap,
    jobNameMap,
    keyword,
    ruleChannelMap,
    ruleList,
  ]);

  /**
   * 新增或编辑成功后刷新规则和关联关系。
   */
  const handleRefresh = async () => {
    await Promise.all([
      fetchList(),
      fetchRuleChannelLinks(),
    ]);
  };

  const handleCreate = () => {
    drawerRef.current?.open({
      operateType: AlarmOperateType.Create,
      onSuccess: handleRefresh,
    });
  };

  const handleEdit = (
    record: AlarmRuleRecord,
  ) => {
    drawerRef.current?.open({
      operateType: AlarmOperateType.Edit,
      currentRecord: record,
      onSuccess: handleRefresh,
    });
  };

  const handleDelete = (
    record: AlarmRuleRecord,
  ) => {
    confirm({
      title: intl.formatMessage({
        id: 'pages.alarm.delete.confirmTitle',
        defaultMessage: '确认删除？',
      }),
      centered: true,
      content: intl.formatMessage(
        {
          id: 'pages.alarm.rule.delete.confirmContent',
          defaultMessage:
            '确认删除告警规则 [{name}] 吗？',
        },
        {
          name: record.name,
        },
      ),
      okText: intl.formatMessage({
        id: 'pages.alarm.delete.okText',
        defaultMessage: '删除',
      }),
      okType: 'primary',
      okButtonProps: {
        danger: true,
      },
      maskClosable: true,

      async onOk() {
        if (!record.id) {
          message.error('规则 ID 不存在');
          return;
        }

        const res = await deleteRule(record.id);

        if (res?.code === 0) {
          message.success(
            intl.formatMessage({
              id: 'pages.alarm.message.deleteSuccess',
              defaultMessage: '删除成功',
            }),
          );

          await handleRefresh();
        }
      },
    });
  };

  /**
   * 启用或禁用规则。
   *
   * 更新规则前需要保留原有通道关联，
   * 避免后端重新关联时将通道清空。
   */
  const handleToggleEnabled = async (
    record: AlarmRuleRecord,
    checked: boolean,
  ) => {
    if (!record.id) {
      return;
    }

    setTogglingId(record.id);

    try {
      const hasCachedChannelIds =
        Object.prototype.hasOwnProperty.call(
          ruleChannelMap,
          record.id,
        );

      let channelIds = hasCachedChannelIds
        ? ruleChannelMap[record.id]
        : [];

      if (!hasCachedChannelIds) {
        const linkRes =
          await fetchRuleChannels(record.id);

        if (
          linkRes?.code === 0 &&
          Array.isArray(linkRes.data)
        ) {
          channelIds = linkRes.data;

          setRuleChannelMap((prev) => ({
            ...prev,
            [record.id as number]: linkRes.data,
          }));
        }
      }

      const res = await saveRule({
        id: record.id,
        name: record.name,
        targetJobs: record.targetJobs,
        triggerStatuses:
          record.triggerStatuses,
        excludes: record.excludes,
        severity: record.severity,
        enabled: checked ? 1 : 0,
        description: record.description,
        channelIds,
      });

      if (res?.code === 0) {
        message.success(
          intl.formatMessage({
            id: checked
              ? 'pages.alarm.message.enabled'
              : 'pages.alarm.message.disabled',
            defaultMessage: checked
              ? '已启用'
              : '已禁用',
          }),
        );

        /*
         * 本地更新状态，避免整个列表重新闪烁。
         */
        setRuleList((prev) =>
          prev.map((item) =>
            item.id === record.id
              ? {
                  ...item,
                  enabled: checked ? 1 : 0,
                }
              : item,
          ),
        );
      }
    } catch {
      // 全局 errorHandler 已处理错误提示
    } finally {
      setTogglingId(null);
    }
  };

  return (
    <div className="alarm-page__tab-content">
      {/* 工具栏 */}
      <div className="alarm-page__tab-toolbar">
        <div>
          <p className="alarm-page__tab-count">
            共 {filteredList.length} 个告警规则
          </p>

          <p className="alarm-page__tab-description">
            配置任务状态变化时的告警触发条件
          </p>
        </div>

        <Button
          type="primary"
          onClick={handleCreate}
          className="alarm-page__primary-action"
        >
          {intl.formatMessage({
            id: 'pages.alarm.button.addRule',
            defaultMessage: '新建规则',
          })}
        </Button>
      </div>

      <Spin spinning={loading}>
        {filteredList.length > 0 ? (
          <div className="alarm-page__card-grid">
            {filteredList.map(
              (rule, index) => {
                const enabled =
                  rule.enabled === 1;

                const severityConfig =
                  SEVERITY_CONFIG[
                    rule.severity || ''
                  ];

                const statuses =
                  parseStringList(
                    rule.triggerStatuses,
                  );

                const targetJobIds =
                  parseNumberList(
                    rule.targetJobs,
                  );

                const channelIds =
                  rule.id != null
                    ? ruleChannelMap[
                        rule.id
                      ] || []
                    : [];

                const targetSummary =
                  targetJobIds.length === 0
                    ? intl.formatMessage({
                        id: 'pages.alarm.field.allJobs',
                        defaultMessage:
                          '全部任务',
                      })
                    : targetJobIds
                        .slice(0, 2)
                        .map(
                          (id) =>
                            jobNameMap[id] ||
                            `#${id}`,
                        )
                        .join('、');

                const channelSummary =
                  channelIds.length === 0
                    ? '未关联通道'
                    : channelIds
                        .slice(0, 2)
                        .map(
                          (id) =>
                            channelMap[id]
                              ?.name ||
                            `#${id}`,
                        )
                        .join('、');

                return (
                  <div
                    key={
                      rule.id ??
                      `${rule.name}-${index}`
                    }
                    className="alarm-card alarm-card--rule group"
                  >
                    {/* 图标 */}
                    <div
                      className={`alarm-card__icon ${enabled ? '' : 'alarm-card__icon--disabled'}`}
                    >
                      <ShieldAlert className="h-5 w-5" />
                    </div>

                    {/* 主体 */}
                    <div className="alarm-card__body">
                      <div className="alarm-card__header">
                        <div className="min-w-0">
                          <div className="alarm-card__title-row">
                            <h3 className="alarm-card__title">
                              {rule.name ||
                                '未命名规则'}
                            </h3>

                            <span
                              className={`alarm-card__indicator ${enabled ? '' : 'alarm-card__indicator--disabled'}`}
                            />

                            {severityConfig && (
                              <span
                                className="alarm-card__severity shrink-0 rounded-full"
                                style={{
                                  color:
                                    severityConfig.color,
                                  backgroundColor:
                                    severityConfig.backgroundColor,
                                }}
                              >
                                {
                                  severityConfig.text
                                }
                              </span>
                            )}
                          </div>

                          <p className="alarm-card__description">
                            {rule.description ||
                              '暂无规则说明'}
                          </p>
                        </div>

                        <Switch
                          size="small"
                          checked={enabled}
                          className="alarm-card__switch"
                          loading={
                            togglingId ===
                            rule.id
                          }
                          onChange={(checked) =>
                            handleToggleEnabled(
                              rule,
                              checked,
                            )
                          }
                        />
                      </div>

                      {/* 触发状态 */}
                      <div className="alarm-card__status-list">
                        {statuses
                          .slice(0, 3)
                          .map((status) => (
                            <span
                              key={status}
                              className="alarm-card__status"
                            >
                              {status}
                            </span>
                          ))}

                        {statuses.length > 3 && (
                          <span className="alarm-card__meta-value text-xs">
                            +
                            {statuses.length -
                              3}
                          </span>
                        )}

                        {statuses.length ===
                          0 && (
                          <span className="alarm-card__meta-value text-xs">
                            暂无触发状态
                          </span>
                        )}
                      </div>

                      {/* 元信息 */}
                      <div className="alarm-card__meta alarm-card__meta--stacked">
                        <div className="alarm-card__meta-item">
                          <Target className="h-3.5 w-3.5 shrink-0" />

                          <span className="alarm-card__meta-label">
                            目标任务
                          </span>

                          <span className="alarm-card__meta-value">
                            {targetSummary}
                          </span>

                          {targetJobIds.length >
                            2 && (
                            <span className="shrink-0">
                              +
                              {targetJobIds.length -
                                2}
                            </span>
                          )}
                        </div>

                        <div className="alarm-card__meta-item">
                          <BellRing className="h-3.5 w-3.5 shrink-0" />

                          <span className="alarm-card__meta-label">
                            告警通道
                          </span>

                          <span className="alarm-card__meta-value">
                            {channelSummary}
                          </span>

                          {channelIds.length >
                            2 && (
                            <span className="shrink-0">
                              +
                              {channelIds.length -
                                2}
                            </span>
                          )}
                        </div>
                      </div>

                      {/* 操作 */}
                      <div className="alarm-card__actions">
                        {rule.createTime && (
                          <span className="alarm-card__timestamp">
                            <Clock3 className="h-3.5 w-3.5 shrink-0" />

                            {formatTime(
                              rule.createTime,
                            )}
                          </span>
                        )}

                        <Button
                          type="text"
                          size="small"
                          icon={
                            <EditOutlined />
                          }
                          onClick={() =>
                            handleEdit(rule)
                          }
                          className="alarm-card__action"
                        >
                          {intl.formatMessage({
                            id: 'pages.alarm.button.edit',
                            defaultMessage:
                              '编辑',
                          })}
                        </Button>

                        <Button
                          type="text"
                          size="small"
                          danger
                          icon={
                            <DeleteOutlined />
                          }
                          onClick={() =>
                            handleDelete(rule)
                          }
                          className="alarm-card__action alarm-card__action--danger"
                        >
                          {intl.formatMessage({
                            id: 'pages.alarm.button.delete',
                            defaultMessage:
                              '删除',
                          })}
                        </Button>

                        <Tooltip title="查看详情">
                          <button
                            type="button"
                            onClick={() =>
                              handleEdit(rule)
                            }
                            className="alarm-card__detail ml-1"
                          >
                            <ArrowRight className="h-4 w-4" />
                          </button>
                        </Tooltip>
                      </div>
                    </div>
                  </div>
                );
              },
            )}
          </div>
        ) : (
          <div className="alarm-page__empty">
            <Empty
              image={
                Empty.PRESENTED_IMAGE_SIMPLE
              }
              description={
                keyword
                  ? '没有找到符合条件的告警规则'
                  : '暂时还没有告警规则'
              }
            >
              {!keyword && (
                <Button
                  type="primary"
                  className="alarm-page__primary-action"
                  onClick={handleCreate}
                >
                  新建规则
                </Button>
              )}
            </Empty>
          </div>
        )}
      </Spin>

      <AddOrEditRuleModal
        ref={drawerRef}
        channels={channelList}
      />
    </div>
  );
};

export default RuleTab;
