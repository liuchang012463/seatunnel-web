import {
  CheckCircleFilled,
  CloseCircleFilled,
  ExclamationCircleFilled,
  LoadingOutlined,
  StopFilled,
} from "@ant-design/icons";
import React from "react";
import type { IntlShape } from "react-intl";
import type { StatusChipTone } from "@/components/StatusChip";
import { getJobStatusView } from "@/pages/common/utils/jobStatus";

/** 语义色对应的行内样式。取消态走中性色，不会被看成失败。 */
const toneMeta: Record<
  StatusChipTone,
  {
    color: string;
    tagColor: string;
    dotColor: string;
    lightBg: string;
    icon: React.ReactNode;
  }
> = {
  success: {
    color: "#16a34a",
    tagColor: "#16a34a",
    dotColor: "#22c55e",
    lightBg: "rgba(34, 197, 94, 0.12)",
    icon: <CheckCircleFilled style={{ color: "#22c55e" }} />,
  },
  processing: {
    color: "#2563eb",
    tagColor: "#2563eb",
    dotColor: "#3b82f6",
    lightBg: "rgba(59, 130, 246, 0.12)",
    icon: <LoadingOutlined style={{ color: "#3b82f6" }} />,
  },
  error: {
    color: "#e11d48",
    tagColor: "#e11d48",
    dotColor: "#f43f5e",
    lightBg: "rgba(244, 63, 94, 0.12)",
    icon: <CloseCircleFilled style={{ color: "#f43f5e" }} />,
  },
  warning: {
    color: "#d97706",
    tagColor: "#d97706",
    dotColor: "#f59e0b",
    lightBg: "rgba(245, 158, 11, 0.12)",
    icon: <ExclamationCircleFilled style={{ color: "#f59e0b" }} />,
  },
  neutral: {
    color: "var(--st-color-text-muted)",
    tagColor: "var(--st-color-text-muted)",
    dotColor: "var(--st-color-text-muted)",
    lightBg: "rgba(148, 163, 184, 0.16)",
    icon: <StopFilled style={{ color: "var(--st-color-text-muted)" }} />,
  },
};

export const getHistoryStatusMeta = (status: string, intl: IntlShape) => {
  const view = getJobStatusView(status, "batch");
  const meta = toneMeta[view.tone];

  return {
    text: intl.formatMessage({
      id: view.label.id,
      defaultMessage: view.label.defaultMessage,
    }),
    ...meta,
  };
};
