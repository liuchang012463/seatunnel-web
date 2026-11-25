import { SearchOutlined } from "@ant-design/icons";
import { DatePicker, Input, Segmented, Space } from "antd";
import React from "react";
import type { IntlShape } from "react-intl";
import type { CustomHistoryTimeRange, TimeRangeType } from "@/pages/common/utils/taskHistoryTime";

interface HistoryFilterBarProps {
  intl: IntlShape;
  keyword: string;
  onKeywordChange: (value: string) => void;
  timeRangeType: TimeRangeType;
  onTimeRangeTypeChange: (value: TimeRangeType) => void;
  customTimeRange: CustomHistoryTimeRange;
  onCustomTimeRangeChange: (
    value: CustomHistoryTimeRange
  ) => void;
}

const HistoryFilterBar: React.FC<HistoryFilterBarProps> = ({
  intl,
  keyword,
  onKeywordChange,
  timeRangeType,
  onTimeRangeTypeChange,
  customTimeRange,
  onCustomTimeRangeChange,
}) => {
  return (
    <Space direction="vertical" size={8} style={{ width: "100%" }}>
      <Input
        allowClear
        className="history-filter-input"
        prefix={<SearchOutlined style={{ color: "var(--st-color-accent)" }} />}
        placeholder={intl.formatMessage({
          id: "pages.job.history.search.placeholder",
          defaultMessage: "根据任务名称搜索",
        })}
        value={keyword}
        onChange={(e) => onKeywordChange(e.target.value)}
      />

      <Segmented
        value={timeRangeType}
        onChange={(value) => onTimeRangeTypeChange(value as TimeRangeType)}
        block
        className="time-range-segmented"
        options={[
          { label: "最近一天", value: "最近一天", title: "从昨天 00:00 至今（北京时间）" },
          { label: "最近三天", value: "最近三天", title: "从三天前 00:00 至今（北京时间）" },
          { label: "最近一周", value: "最近一周", title: "从七天前 00:00 至今（北京时间）" },
          { label: "自定义", value: "自定义", title: "按北京时间选择起止时间" },
        ]}
      />

      {timeRangeType === "自定义" && (
        <DatePicker.RangePicker
          showTime
          className="history-filter-picker"
          style={{ width: "100%" }}
          value={customTimeRange}
          onChange={(values) => {
            onCustomTimeRangeChange(
              values ? [values[0] ?? null, values[1] ?? null] : null
            );
          }}
          placeholder={["开始时间", "结束时间"]}
          format="YYYY-MM-DD HH:mm:ss"
        />
      )}
    </Space>
  );
};

export default HistoryFilterBar;
