import {
  buildSchedulePayload,
  getScheduleValidationMessage,
  resolveExecutionMode,
} from "./types";

describe("schedule execution mode", () => {
  it("defaults a new schedule to manual execution", () => {
    expect(resolveExecutionMode()).toBe("MANUAL");
    expect(buildSchedulePayload({ paramsList: [] })).toEqual({
      paramsList: [],
      executionMode: "MANUAL",
      cronExpression: null,
    });
  });

  it("keeps legacy schedules with Cron automatic", () => {
    expect(resolveExecutionMode({ cronExpression: "0 0 2 * * ?" })).toBe("AUTO");
    expect(
      getScheduleValidationMessage({ executionMode: "AUTO" }),
    ).toBe("自动调度必须配置调度周期和时间");
  });

  it("clears stale Cron for explicit manual mode and forces incremental auto mode", () => {
    expect(
      buildSchedulePayload({
        executionMode: "MANUAL",
        cronExpression: "0 0 2 * * ?",
      }),
    ).toMatchObject({ executionMode: "MANUAL", cronExpression: null });

    expect(resolveExecutionMode({ executionMode: "MANUAL" }, true)).toBe("AUTO");
    expect(
      getScheduleValidationMessage({ executionMode: "MANUAL" }, true),
    ).toBe("自动调度必须配置调度周期和时间");
  });
});
