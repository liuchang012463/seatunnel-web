import { Form, InputNumber } from "antd";
import React from "react";
import { formItemStyle, labelNodeStyle } from "../constants";
import type { MinuteModeValue } from "../types";

interface Props {
  minuteValue: MinuteModeValue;
  onChange: (patch: { minuteValue: MinuteModeValue }) => void;
}

const MinuteScheduleFields: React.FC<Props> = ({ minuteValue, onChange }) => (
  <Form.Item
    style={formItemStyle}
    label={<span style={labelNodeStyle}>每隔（分钟）</span>}
    required
  >
    <InputNumber
      min={1}
      max={59}
      precision={0}
      value={minuteValue?.intervalMinute ?? 5}
      onChange={(v) =>
        onChange({
          minuteValue: { intervalMinute: Number(v) || 5 },
        })
      }
    />
  </Form.Item>
);

export default MinuteScheduleFields;
