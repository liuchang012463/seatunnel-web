import { getJobStatusView, normalizeJobStatus } from "./jobStatus";

describe("任务状态筛选分类", () => {
  it.each([undefined, null, "", "UNSTARTED", "NOT_EXECUTED"])(
    "%s 归入未执行",
    (status) => expect(normalizeJobStatus(status)).toBe("UNSTARTED"),
  );

  it.each([
    "STARTING",
    "INITIALIZING",
    "CREATED",
    "PENDING",
    "SCHEDULED",
    "RUNNING",
    "FAILING",
    "CANCELING",
    "DOING_SAVEPOINT",
  ])("%s 归入运行中", (status) =>
    expect(normalizeJobStatus(status)).toBe("RUNNING"),
  );

  it.each(["FAILED", "UNKNOWABLE", "CANCELED", "CANCELLED"])(
    "%s 归入失败桶",
    (status) => expect(normalizeJobStatus(status)).toBe("FAILED"),
  );

  it.each(["FINISHED", "SUCCESS", "SAVEPOINT_DONE"])(
    "%s 归入成功桶",
    (status) => expect(normalizeJobStatus(status)).toBe("FINISHED"),
  );

  it("不再把后端已删除的状态当成失败", () => {
    expect(normalizeJobStatus("SUBMISSION_UNKNOWN")).not.toBe("FAILED");
  });
});

describe("任务状态展示", () => {
  it.each(["CANCELED", "CANCELLED"])(
    "%s 展示已取消，且不是错误色",
    (status) => {
      const batch = getJobStatusView(status, "batch");
      const stream = getJobStatusView(status, "stream");

      expect(batch.label.defaultMessage).toBe("已取消");
      expect(stream.label.defaultMessage).toBe("已取消");
      expect(batch.tone).not.toBe("error");
      expect(stream.tone).not.toBe("error");
      // 已取消仍然落在失败筛选桶里，与后端筛选口径一致。
      expect(batch.category).toBe("FAILED");
      expect(stream.category).toBe("FAILED");
      // 已取消的任务没有失败原因，不展示失败详情浮层。
      expect(batch.errorDetail).toBe(false);
      expect(stream.errorDetail).toBe(false);
    },
  );

  it("未知状态是警告色，不是错误色", () => {
    const view = getJobStatusView("UNKNOWABLE", "batch");

    expect(view.category).toBe("FAILED");
    expect(view.tone).toBe("warning");
    expect(view.label.defaultMessage).toBe("状态未知");
  });

  it("失败中是运行中分类，但用错误色提示", () => {
    const batch = getJobStatusView("FAILING", "batch");
    const stream = getJobStatusView("FAILING", "stream");

    expect(batch.category).toBe("RUNNING");
    expect(stream.category).toBe("RUNNING");
    expect(batch.tone).toBe("error");
    expect(stream.tone).toBe("error");
  });

  it("保存点完成归入成功桶", () => {
    expect(getJobStatusView("SAVEPOINT_DONE", "batch").category).toBe("FINISHED");
    expect(getJobStatusView("SAVEPOINT_DONE", "stream").category).toBe("FINISHED");
  });

  it("只有真正的失败态展示失败详情", () => {
    expect(getJobStatusView("FAILED", "batch").errorDetail).toBe(true);
    expect(getJobStatusView("FAILED", "stream").errorDetail).toBe(true);
    expect(getJobStatusView("CANCELED", "batch").errorDetail).toBe(false);
  });

  it("两个产品沿用各自既有的文案", () => {
    expect(getJobStatusView("FINISHED", "batch").label.defaultMessage).toBe("成功");
    expect(getJobStatusView("FINISHED", "stream").label.defaultMessage).toBe("已完成");
    expect(getJobStatusView("SAVEPOINT_DONE", "batch").label.defaultMessage).toBe(
      "成功",
    );
    expect(getJobStatusView("SAVEPOINT_DONE", "stream").label.defaultMessage).toBe(
      "保存点完成",
    );
    expect(getJobStatusView(undefined, "batch").label.defaultMessage).toBe("未执行");
    expect(getJobStatusView(undefined, "stream").label.defaultMessage).toBe("未开始");
  });

  it("同一状态在两个产品的严重程度一致", () => {
    const statuses = [
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
      "PAUSED",
    ];

    statuses.forEach((status) => {
      expect(getJobStatusView(status, "stream").tone).toBe(
        getJobStatusView(status, "batch").tone,
      );
      expect(getJobStatusView(status, "stream").category).toBe(
        getJobStatusView(status, "batch").category,
      );
    });
  });
});
