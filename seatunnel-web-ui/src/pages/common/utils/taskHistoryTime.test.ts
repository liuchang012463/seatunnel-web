import dayjs from "dayjs";
import { getTaskHistoryTimeParams } from "./taskHistoryTime";

describe("引接任务运行历史时间范围", () => {
  const now = dayjs("2026-11-25T14:00:00Z");

  it.each([
    ["最近一天", "2026-11-24 00:00:00"],
    ["最近三天", "2026-11-22 00:00:00"],
    ["最近一周", "2026-11-18 00:00:00"],
  ] as const)("%s 按北京时间包含前面的完整自然日", (range, queryStartTime) => {
    expect(getTaskHistoryTimeParams(range, null, now)).toEqual({
      queryStartTime,
      queryEndTime: "2026-11-25 22:00:00",
    });
  });

  it("跨北京时间午夜时仍以北京时间的日期为准", () => {
    expect(getTaskHistoryTimeParams("最近一天", null, dayjs("2026-11-25T16:05:00Z"))).toEqual({
      queryStartTime: "2026-11-25 00:00:00",
      queryEndTime: "2026-11-26 00:05:00",
    });
  });

  it("自定义保留选中的时分秒，未选不限制范围", () => {
    expect(getTaskHistoryTimeParams("自定义", [
      dayjs("2026-11-24T21:19:53"),
      dayjs("2026-11-24T22:00:10"),
    ])).toEqual({
      queryStartTime: "2026-11-24 21:19:53",
      queryEndTime: "2026-11-24 22:00:10",
    });
    expect(getTaskHistoryTimeParams("自定义", null)).toEqual({
      queryStartTime: undefined,
      queryEndTime: undefined,
    });
  });
});
