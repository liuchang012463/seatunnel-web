import type { StatusChipTone } from "@/components/StatusChip";

/**
 * 任务状态语义：批量与实时两个产品、列表与运行历史四个视图共用同一份映射，
 * 保证同一个后端状态在任何页面都呈现相同的严重程度与文案口径。
 */

/**
 * 状态筛选分类。口径必须与后端一致：
 * JobInstanceMapper.xml / JobDefinitionMapper.xml 把 CANCELED、CANCELLED、UNKNOWABLE
 * 计入失败桶，把 FAILING、CANCELING、DOING_SAVEPOINT 计入运行中桶。
 */
export type JobStatusCategory = "FINISHED" | "FAILED" | "RUNNING" | "UNSTARTED";

/** 文案口径：批量沿用 成功/失败/运行中/未执行，实时沿用 已完成/保存点完成/… */
export type JobStatusProduct = "batch" | "stream";

export interface JobStatusLabel {
  /** i18n 文案 id */
  id: string;
  /** 没有对应翻译时使用的默认文案 */
  defaultMessage: string;
}

export interface JobStatusView {
  /** 状态筛选分类 */
  category: JobStatusCategory;
  /** 状态语义色 */
  tone: StatusChipTone;
  /** 是否展示失败详情浮层：只有真正的失败态才展示 */
  errorDetail: boolean;
  /** 状态文案 */
  label: JobStatusLabel;
}

/** 没有任务实例，或任务实例尚未启动。 */
const UNSTARTED_STATUSES = new Set(["", "UNSTARTED", "NOT_EXECUTED"]);

/**
 * 后端 JobStatusHelper.runningLikeStatuses() 的镜像：提交中、运行中与各过渡态。
 * 批量操作栏与单行操作必须共用同一份集合，否则过渡态（STARTING/FAILING/…）任务会被
 * 当成"未运行"而放行二次启动或运行窗口内的下线/删除。
 */
const RUNNING_LIKE_STATUSES = new Set([
  "STARTING",
  "INITIALIZING",
  "CREATED",
  "PENDING",
  "SCHEDULED",
  "RUNNING",
  "FAILING",
  "DOING_SAVEPOINT",
  "CANCELING",
]);

/** 该状态是否仍处于提交/运行/过渡窗口（对应后端 runningLikeStatuses）。 */
export const isRunningLikeStatus = (status?: string) =>
  RUNNING_LIKE_STATUSES.has(String(status || "").toUpperCase());

/**
 * 原始状态 → 分类与语义色。取消态是中性色而不是错误色：取消是操作员主动触发的，
 * 不是失败。四个视图共用这一份，避免同一状态在不同页面出现不同的严重程度。
 */
const STATUS_TONES: Record<
  string,
  { category: JobStatusCategory; tone: StatusChipTone; errorDetail?: boolean }
> = {
  FINISHED: { category: "FINISHED", tone: "success" },
  SUCCESS: { category: "FINISHED", tone: "success" },
  SAVEPOINT_DONE: { category: "FINISHED", tone: "success" },
  FAILED: { category: "FAILED", tone: "error", errorDetail: true },
  UNKNOWABLE: { category: "FAILED", tone: "warning" },
  CANCELED: { category: "FAILED", tone: "neutral" },
  CANCELLED: { category: "FAILED", tone: "neutral" },
  RUNNING: { category: "RUNNING", tone: "processing" },
  STARTING: { category: "RUNNING", tone: "processing" },
  FAILING: { category: "RUNNING", tone: "error" },
  INITIALIZING: { category: "RUNNING", tone: "neutral" },
  CREATED: { category: "RUNNING", tone: "neutral" },
  PENDING: { category: "RUNNING", tone: "neutral" },
  SCHEDULED: { category: "RUNNING", tone: "neutral" },
  DOING_SAVEPOINT: { category: "RUNNING", tone: "warning" },
  CANCELING: { category: "RUNNING", tone: "neutral" },
  PAUSED: { category: "RUNNING", tone: "warning" },
};

/** 分类级别的通用文案。 */
const CATEGORY_LABELS: Record<
  JobStatusProduct,
  Record<JobStatusCategory, JobStatusLabel>
> = {
  batch: {
    FINISHED: { id: "pages.job.status.success", defaultMessage: "成功" },
    FAILED: { id: "pages.job.status.failed", defaultMessage: "失败" },
    RUNNING: { id: "pages.job.status.running", defaultMessage: "运行中" },
    UNSTARTED: { id: "pages.job.status.unstarted", defaultMessage: "未执行" },
  },
  stream: {
    FINISHED: { id: "pages.job.status.completed", defaultMessage: "已完成" },
    FAILED: { id: "pages.job.status.failed", defaultMessage: "失败" },
    RUNNING: { id: "pages.job.status.running", defaultMessage: "运行中" },
    UNSTARTED: { id: "pages.job.status.not_started", defaultMessage: "未开始" },
  },
};

/** 状态级别的具体文案：只有比通用文案更准确时才覆盖。 */
const STATUS_LABELS: Record<JobStatusProduct, Record<string, JobStatusLabel>> = {
  batch: {
    CANCELED: { id: "pages.job.status.canceled", defaultMessage: "已取消" },
    CANCELLED: { id: "pages.job.status.canceled", defaultMessage: "已取消" },
    UNKNOWABLE: { id: "pages.job.status.unknown", defaultMessage: "状态未知" },
    FAILING: { id: "pages.job.status.failing", defaultMessage: "失败中" },
  },
  stream: {
    SAVEPOINT_DONE: {
      id: "pages.job.status.savepoint_done",
      defaultMessage: "保存点完成",
    },
    CANCELED: { id: "pages.job.status.canceled", defaultMessage: "已取消" },
    CANCELLED: { id: "pages.job.status.canceled", defaultMessage: "已取消" },
    UNKNOWABLE: { id: "pages.job.status.unknown", defaultMessage: "状态未知" },
    FAILING: { id: "pages.job.status.failing", defaultMessage: "失败中" },
    STARTING: { id: "pages.job.status.starting", defaultMessage: "启动中" },
    INITIALIZING: {
      id: "pages.job.status.initializing",
      defaultMessage: "初始化中",
    },
    CREATED: { id: "pages.job.status.created", defaultMessage: "已创建" },
    PENDING: { id: "pages.job.status.pending", defaultMessage: "等待中" },
    SCHEDULED: { id: "pages.job.status.scheduled", defaultMessage: "已调度" },
    PAUSED: { id: "pages.job.status.paused", defaultMessage: "已暂停" },
    DOING_SAVEPOINT: {
      id: "pages.job.status.doing_savepoint",
      defaultMessage: "保存点中",
    },
    CANCELING: { id: "pages.job.status.canceling", defaultMessage: "取消中" },
  },
};

/** 前后端状态枚举之外的状态：不猜它是运行中还是结束。 */
const UNRECOGNIZED_LABEL: JobStatusLabel = {
  id: "pages.job.status.unrecognized",
  defaultMessage: "未识别",
};

const normalizeRawStatus = (status?: string | null) =>
  String(status || "")
    .trim()
    .toUpperCase();

/**
 * 状态筛选分类。与后端筛选口径一致，已取消仍然落在失败桶里，
 * 只有展示层才把已取消与失败区分开。
 */
export const normalizeJobStatus = (
  status?: string | null,
): JobStatusCategory => {
  const normalized = normalizeRawStatus(status);

  if (UNSTARTED_STATUSES.has(normalized)) return "UNSTARTED";

  return STATUS_TONES[normalized]?.category ?? "RUNNING";
};

/**
 * 状态的完整展示信息：筛选分类、语义色与文案。
 * 列表页直接使用文案，运行历史页通过 intl.formatMessage 使用。
 */
export const getJobStatusView = (
  status: string | undefined | null,
  product: JobStatusProduct,
): JobStatusView => {
  const normalized = normalizeRawStatus(status);
  const tone = STATUS_TONES[normalized];

  if (!tone) {
    return UNSTARTED_STATUSES.has(normalized)
      ? {
          category: "UNSTARTED",
          tone: "neutral",
          errorDetail: false,
          label: CATEGORY_LABELS[product].UNSTARTED,
        }
      : {
          category: "RUNNING",
          tone: "neutral",
          errorDetail: false,
          label: UNRECOGNIZED_LABEL,
        };
  }

  return {
    category: tone.category,
    tone: tone.tone,
    errorDetail: Boolean(tone.errorDetail),
    label:
      STATUS_LABELS[product][normalized] ?? CATEGORY_LABELS[product][tone.category],
  };
};
