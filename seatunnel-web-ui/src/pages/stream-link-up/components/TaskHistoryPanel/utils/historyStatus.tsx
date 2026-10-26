import {
  CheckCircleFilled,
  ClockCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  SyncOutlined,
} from "@ant-design/icons";
import React from "react";
import type { IntlShape } from "react-intl";
import { HistoryItem } from "@/pages/batch-link-up/type";
import type { StatusChipTone } from "@/components/StatusChip";
import { getJobStatusView } from "@/pages/common/utils/jobStatus";

/** 语义色对应的行内样式。取消态走中性色，不会被看成失败；背景用低透明度色，暗色主题下同样成立。 */
const toneMeta: Record<
  StatusChipTone,
  { tagColor: string; dotColor: string; lightBg: string; icon: React.ReactNode }
> = {
  success: {
    tagColor: "success",
    dotColor: "#52c41a",
    lightBg: "rgba(82, 196, 26, 0.12)",
    icon: <CheckCircleFilled style={{ color: "#52c41a" }} />,
  },
  processing: {
    tagColor: "processing",
    dotColor: "#1677ff",
    lightBg: "rgba(22, 119, 255, 0.12)",
    icon: <SyncOutlined spin style={{ color: "#1677ff" }} />,
  },
  error: {
    tagColor: "error",
    dotColor: "#ff4d4f",
    lightBg: "rgba(255, 77, 79, 0.12)",
    icon: <CloseCircleOutlined style={{ color: "#ff4d4f" }} />,
  },
  warning: {
    tagColor: "warning",
    dotColor: "#faad14",
    lightBg: "rgba(250, 173, 20, 0.12)",
    icon: <ExclamationCircleOutlined style={{ color: "#faad14" }} />,
  },
  neutral: {
    tagColor: "default",
    dotColor: "var(--st-color-text-muted)",
    lightBg: "rgba(148, 163, 184, 0.16)",
    icon: <ClockCircleOutlined style={{ color: "var(--st-color-text-muted)" }} />,
  },
};

export const getHistoryStatusMeta = (
  status: HistoryItem["jobStatus"],
  intl: IntlShape
) => {
  const view = getJobStatusView(status, "stream");
  const meta = toneMeta[view.tone];

  return {
    icon: meta.icon,
    text: intl.formatMessage({
      id: view.label.id,
      defaultMessage: view.label.defaultMessage,
    }),
    tagColor: meta.tagColor,
    dotColor: meta.dotColor,
    lightBg: meta.lightBg,
  };
};
