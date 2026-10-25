import { render, screen } from "@testing-library/react";
import React from "react";
import type { IntlShape } from "react-intl";
import BatchTaskStatus from "@/pages/batch-link-up/components/SyncTaskList/components/TaskStatus";
import { getHistoryStatusMeta as getBatchHistoryStatusMeta } from "@/pages/batch-link-up/components/TaskHistoryPanel/utils/historyStatus";
import StreamTaskStatus from "@/pages/stream-link-up/components/TaskStatus";
import { getHistoryStatusMeta as getStreamHistoryStatusMeta } from "@/pages/stream-link-up/components/TaskHistoryPanel/utils/historyStatus";

const intl = {
  formatMessage: ({ defaultMessage }: { defaultMessage: string }) =>
    defaultMessage,
} as unknown as IntlShape;

const STATUSES = [
  "FINISHED",
  "SUCCESS",
  "SAVEPOINT_DONE",
  "FAILED",
  "UNKNOWABLE",
  "CANCELED",
  "CANCELLED",
  "RUNNING",
  "STARTING",
  "FAILING",
  "INITIALIZING",
  "CREATED",
  "PENDING",
  "SCHEDULED",
  "DOING_SAVEPOINT",
  "CANCELING",
];

const chipColorOf = (container: HTMLElement) =>
  (container.querySelector(".st-status-chip") as HTMLElement)?.style.color;

describe("批量任务的列表状态与运行历史状态一致", () => {
  it.each(STATUSES)("%s 的列表与运行历史文案一致", (status) => {
    const historyText = getBatchHistoryStatusMeta(status, intl).text;

    render(<BatchTaskStatus status={status} />);

    expect(screen.getByText(historyText)).toBeTruthy();
  });
});

describe("实时任务的列表状态与运行历史状态一致", () => {
  it.each(STATUSES)("%s 的列表与运行历史文案一致", (status) => {
    const historyText = getStreamHistoryStatusMeta(status, intl).text;

    render(<StreamTaskStatus status={status} />);

    expect(screen.getByText(historyText)).toBeTruthy();
  });
});

describe("已取消的任务不是失败", () => {
  it.each([
    [
      "批量",
      <BatchTaskStatus key="batch" status="CANCELED" errorMessage="boom" />,
    ],
    [
      "实时",
      <StreamTaskStatus key="stream" status="CANCELED" errorMessage="boom" />,
    ],
  ])("%s 列表展示已取消且不展示失败原因", (_name, element) => {
    render(element);

    expect(screen.getByText("已取消")).toBeTruthy();
    expect(screen.queryByText("失败原因")).toBeNull();
    expect(screen.queryByText("任务错误详情")).toBeNull();
  });

  it.each([
    ["批量", getBatchHistoryStatusMeta],
    ["实时", getStreamHistoryStatusMeta],
  ])("%s 运行历史展示已取消，且不是失败色", (_name, getMeta) => {
    const meta = getMeta("CANCELED", intl);
    const failedMeta = getMeta("FAILED", intl);

    expect(meta.text).toBe("已取消");
    expect(meta.tagColor).not.toBe(failedMeta.tagColor);
  });

  it("批量列表的已取消状态色与失败态不同", () => {
    const canceled = render(<BatchTaskStatus status="CANCELED" />);
    const canceledColor = chipColorOf(canceled.container);
    canceled.unmount();

    const failed = render(<BatchTaskStatus status="FAILED" />);
    const failedColor = chipColorOf(failed.container);
    failed.unmount();

    expect(canceledColor).toBeTruthy();
    expect(canceledColor).not.toBe(failedColor);
  });

  it("实时列表的已取消状态色与失败态不同", () => {
    const canceled = render(<StreamTaskStatus status="CANCELED" />);
    const canceledColor = chipColorOf(canceled.container);
    canceled.unmount();

    const failed = render(<StreamTaskStatus status="FAILED" />);
    const failedColor = chipColorOf(failed.container);
    failed.unmount();

    expect(canceledColor).toBeTruthy();
    expect(canceledColor).not.toBe(failedColor);
  });
});
