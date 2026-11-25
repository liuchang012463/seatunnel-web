import dayjs, { type Dayjs } from "dayjs";
import utc from "dayjs/plugin/utc";

dayjs.extend(utc);

const FORMAT = "YYYY-MM-DD HH:mm:ss";
const PRESET_DAYS = { 最近一天: 1, 最近三天: 3, 最近一周: 7 } as const;
export type TimeRangeType = keyof typeof PRESET_DAYS | "自定义";
export type CustomHistoryTimeRange = [Dayjs | null, Dayjs | null] | null;

export function getTaskHistoryTimeParams(
  range: TimeRangeType,
  custom: CustomHistoryTimeRange,
  now = dayjs(),
) {
  if (range === "自定义") {
    return {
      queryStartTime: custom?.[0]?.format(FORMAT),
      queryEndTime: custom?.[1]?.format(FORMAT),
    };
  }

  // 实例查询接口按 GMT+8 解析无时区时间，快捷范围包含今天及之前的完整自然日。
  const beijingNow = now.utcOffset(8);
  return {
    queryStartTime: beijingNow.subtract(PRESET_DAYS[range], "day").startOf("day").format(FORMAT),
    queryEndTime: beijingNow.format(FORMAT),
  };
}
