import { normalizeBatchJobStatus } from "./normalizeJobStatus";

describe("批量任务四种状态", () => {
  it.each([undefined, null, "", "UNSTARTED", "NOT_EXECUTED"])(
    "%s 显示未执行", (status) => expect(normalizeBatchJobStatus(status)).toBe("UNSTARTED"),
  );
  it.each(["STARTING", "INITIALIZING", "CREATED", "PENDING", "SCHEDULED", "RUNNING", "FAILING", "CANCELING", "DOING_SAVEPOINT"])(
    "%s 显示运行中", (status) => expect(normalizeBatchJobStatus(status)).toBe("RUNNING"),
  );
  it.each(["FAILED", "UNKNOWABLE", "CANCELED", "CANCELLED", "SUBMISSION_UNKNOWN"])(
    "%s 显示失败", (status) => expect(normalizeBatchJobStatus(status)).toBe("FAILED"),
  );
  it.each(["FINISHED", "SUCCESS", "SAVEPOINT_DONE"])(
    "%s 显示成功", (status) => expect(normalizeBatchJobStatus(status)).toBe("FINISHED"),
  );
});
