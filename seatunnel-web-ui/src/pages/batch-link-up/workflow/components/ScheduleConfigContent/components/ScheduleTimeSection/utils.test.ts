import {
  buildCron,
  defaultDailyValue,
  defaultHourlyAppointValue,
  defaultHourlyRangeValue,
  defaultMinuteValue,
  defaultWeeklyValue,
} from "./utils";

describe("buildCron", () => {
  describe("minute schedule", () => {
    it("returns Quartz every-N-minute expression for default interval (5)", () => {
      expect(
        buildCron(
          "minute",
          "range",
          defaultHourlyRangeValue,
          defaultHourlyAppointValue,
          defaultDailyValue,
          defaultWeeklyValue,
        ),
      ).toBe("0 0/5 * * * ?");
    });

    it("supports explicit intervalMinute values", () => {
      expect(
        buildCron(
          "minute",
          "range",
          defaultHourlyRangeValue,
          defaultHourlyAppointValue,
          defaultDailyValue,
          defaultWeeklyValue,
          { intervalMinute: 1 },
        ),
      ).toBe("0 0/1 * * * ?");

      expect(
        buildCron(
          "minute",
          "range",
          defaultHourlyRangeValue,
          defaultHourlyAppointValue,
          defaultDailyValue,
          defaultWeeklyValue,
          { intervalMinute: 15 },
        ),
      ).toBe("0 0/15 * * * ?");

      expect(
        buildCron(
          "minute",
          "range",
          defaultHourlyRangeValue,
          defaultHourlyAppointValue,
          defaultDailyValue,
          defaultWeeklyValue,
          { intervalMinute: 59 },
        ),
      ).toBe("0 0/59 * * * ?");
    });

    it("clamps out-of-range values to [1, 59]", () => {
      expect(
        buildCron(
          "minute",
          "range",
          defaultHourlyRangeValue,
          defaultHourlyAppointValue,
          defaultDailyValue,
          defaultWeeklyValue,
          { intervalMinute: 0 },
        ),
      ).toBe("0 0/5 * * * ?");

      expect(
        buildCron(
          "minute",
          "range",
          defaultHourlyRangeValue,
          defaultHourlyAppointValue,
          defaultDailyValue,
          defaultWeeklyValue,
          { intervalMinute: 120 },
        ),
      ).toBe("0 0/59 * * * ?");
    });
  });

  describe("existing schedule types are unchanged", () => {
    it("hour range keeps the legacy template", () => {
      expect(
        buildCron(
          "hour",
          "range",
          { startTime: "01:30", intervalHour: 2, endTime: "23:30" },
          defaultHourlyAppointValue,
          defaultDailyValue,
          defaultWeeklyValue,
          defaultMinuteValue,
        ),
      ).toBe("0 30 1-23/2 * * ?");
    });

    it("day schedule keeps the legacy template", () => {
      expect(
        buildCron(
          "day",
          "range",
          defaultHourlyRangeValue,
          defaultHourlyAppointValue,
          { time: "02:45" },
          defaultWeeklyValue,
          defaultMinuteValue,
        ),
      ).toBe("0 45 2 * * ?");
    });

    it("week schedule keeps the legacy template", () => {
      expect(
        buildCron(
          "week",
          "range",
          defaultHourlyRangeValue,
          defaultHourlyAppointValue,
          defaultDailyValue,
          { weekdays: ["MON", "WED"], time: "03:15" },
          defaultMinuteValue,
        ),
      ).toBe("0 15 3 ? * 2,4");
    });
  });
});